"""Gets a long voice recording into a shape Claude can work with.

The Claude app will not take an attachment over 50 MB, and Claude reads speech
as text, so a long phone recording needs one of:

    shrink      re-encode to speech-quality mono 16 kHz AAC (.m4a) under the limit
    split       cut into numbered parts under the limit, without re-encoding
    transcribe  write a timestamped transcript (.txt and .srt) on this computer;
                the .txt is what to attach to Claude

Usage:

    python tools/audio/prepare_audio.py shrink "회의 녹음.m4a"       # 회의 녹음_small.m4a
    python tools/audio/prepare_audio.py split "회의 녹음.m4a"        # 회의 녹음_part01.m4a, ...
    pip install sherpa-onnx numpy
    python tools/audio/prepare_audio.py transcribe "회의 녹음.m4a"   # 회의 녹음.txt, 회의 녹음.srt

All three need ffmpeg and ffprobe on the PATH. transcribe runs offline with
sherpa-onnx: ffmpeg streams the audio in, silero VAD cuts it into utterances,
and Whisper large-v3-turbo (default) or SenseVoice (--model sense-voice, about
six times faster) turns each one into text. The recording never leaves the
computer. The model is downloaded once from the k2-fsa/sherpa-onnx GitHub
releases into ~/.cache/prepare_audio (--model-dir or the PREPARE_AUDIO_MODELS
environment variable put it elsewhere).

On 4 CPU cores a 53-minute recording took 8.5 minutes with whisper-turbo
(about 2 GB of memory) and 72 seconds with sense-voice (about 0.4 GB).
"""

import argparse
import base64
import contextlib
import importlib.util
import itertools
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import textwrap
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

# MB is 10^6 bytes here, the smaller reading of "50 MB", so a file that fits
# here fits however the limit is counted.
MB = 1000 * 1000

SAMPLE_RATE = 16000
MAX_KBPS, MIN_KBPS = 32, 16

# ffmpeg's AAC encoder lands up to about 4% over the requested bitrate and the
# m4a index adds about 60 bytes per second, so sizes are planned with room for
# both. Every output is still measured afterwards.
BITRATE_SLACK = 1.04
INDEX_BYTES_PER_SECOND = 80
HEADER_BYTES = 200 * 1000

RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
VAD_FILE = "silero_vad.onnx"

WHISPER_LANGUAGES = set("""
    en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro
    da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu
    is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo
    uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su""".split())

# Only the int8 files are unpacked: they are what was measured, and the fp32
# copies some archives also carry would double the disk use for no gain.
# SenseVoice is pinned to the 2024-07-17 release on purpose: the 2025-09-09 one
# is a Cantonese fine-tune that writes Chinese characters for Korean speech.
MODELS = {
    "whisper-turbo": {
        "kind": "whisper",
        "archive": "sherpa-onnx-whisper-turbo",
        "files": ["turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt"],
        "download_mb": 564, "disk_mb": 1040,
        "languages": WHISPER_LANGUAGES | {"yue"},
        "about": "Default. Best Korean accuracy measured. About 10 minutes per hour of "
                 "audio on 4 CPU cores, 2 GB of memory, 564 MB download.",
    },
    "sense-voice": {
        "kind": "sense-voice",
        "archive": "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        "files": ["model.int8.onnx", "tokens.txt"],
        "download_mb": 163, "disk_mb": 240,
        "languages": {"ko", "en", "zh", "ja", "yue"},
        "about": "Fast: about 1.5 minutes per hour of audio, 0.4 GB of memory, 163 MB "
                 "download. Korean, English, Chinese, Japanese and Cantonese only, and "
                 "Korean words sometimes get a stray space inside them.",
    },
    "whisper-small": {
        "kind": "whisper",
        "archive": "sherpa-onnx-whisper-small",
        "files": ["small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt"],
        "download_mb": 639, "disk_mb": 380,
        "languages": WHISPER_LANGUAGES,
        "about": "About as accurate as whisper-turbo on the test clips and no faster. "
                 "639 MB download.",
    },
    "whisper-large-v3": {
        "kind": "whisper",
        "archive": "sherpa-onnx-whisper-large-v3",
        "files": ["large-v3-encoder.int8.onnx", "large-v3-decoder.int8.onnx",
                  "large-v3-tokens.txt"],
        "download_mb": 1069, "disk_mb": 1780,
        "languages": WHISPER_LANGUAGES | {"yue"},
        "about": "Slightly more accurate, but very slow: about 2 hours per hour of audio "
                 "on 4 CPU cores. 1069 MB download.",
    },
    "zipformer-korean": {
        "kind": "transducer",
        "archive": "sherpa-onnx-zipformer-korean-2024-06-24",
        "files": ["encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
                  "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"],
        "download_mb": 330, "disk_mb": 80,
        "languages": {"ko"},
        "about": "Korean only and fast, but writes no spaces and turns English into "
                 "gibberish. 330 MB download.",
    },
}
DEFAULT_MODEL = "whisper-turbo"

FFMPEG = FFPROBE = None


def require_ffmpeg():
    found = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if all(found):
        return found
    if sys.platform == "win32":
        hint = "winget install ffmpeg   (then open a new terminal)"
    elif sys.platform == "darwin":
        hint = "brew install ffmpeg"
    else:
        hint = "sudo apt install ffmpeg"
    sys.exit(f"ffmpeg and ffprobe are needed but not on the PATH. Install them with: {hint}")


def clock(seconds):
    seconds = int(seconds)
    return f"{seconds // 3600}:{seconds % 3600 // 60:02d}:{seconds % 60:02d}"


def stamp(seconds):
    seconds = int(seconds)
    return f"{seconds // 3600:02d}:{seconds % 3600 // 60:02d}:{seconds % 60:02d}"


def srt_time(seconds):
    ms = int(round(seconds * 1000))
    return f"{ms // 3600000:02d}:{ms % 3600000 // 60000:02d}:{ms % 60000 // 1000:02d},{ms % 1000:03d}"


def megabytes(size):
    return f"{size / MB:.1f} MB"


class Status:
    """A progress line on stderr: redrawn in place on a terminal, else printed every 30 s."""

    def __init__(self):
        try:
            self.tty = sys.stderr.isatty()
        except (AttributeError, ValueError):
            self.tty = False
        self.last = -1e9
        self.width = 0

    def show(self, text, force=False):
        now = time.monotonic()
        if not force and now - self.last < (0.5 if self.tty else 30):
            return
        self.last = now
        if self.tty:
            sys.stderr.write("\r" + text.ljust(self.width))
            self.width = len(text)
        else:
            sys.stderr.write(text + "\n")
        sys.stderr.flush()

    def clear(self):
        if self.tty and self.width:
            sys.stderr.write("\r" + " " * self.width + "\r")
            sys.stderr.flush()
            self.width = 0


def existing_file(name):
    path = Path(name).expanduser()
    if not path.is_file():
        sys.exit(f"No such file: {name}")
    # An absolute path also keeps ffmpeg from reading a name like "-x.m4a" as an option.
    return path.absolute()


def refuse_to_overwrite(source, *outputs):
    for output in outputs:
        if output.exists() and os.path.samefile(source, output):
            sys.exit(f"Refusing to overwrite the input {source}; choose another output name.")


@contextlib.contextmanager
def work_dir(parent):
    # Next to the outputs rather than in the system temp folder, so finished
    # files can be renamed into place (a rename cannot cross drives).
    parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".prepare_audio-", dir=parent) as tmp:
        yield Path(tmp)


def run_ffmpeg(args, duration, label):
    """Runs ffmpeg with a progress line and returns how many seconds of media it wrote."""
    cmd = [FFMPEG, "-hide_banner", "-nostdin", "-v", "error", "-y",
           "-progress", "pipe:1", "-nostats"] + args
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    # stderr is drained on its own thread so a flood of decoder warnings cannot
    # fill the pipe and stall ffmpeg while we wait on stdout.
    errors = []
    reader = threading.Thread(target=lambda: errors.append(proc.stderr.read()), daemon=True)
    reader.start()
    status = Status()
    done = 0.0
    for line in proc.stdout:
        key, _, value = line.decode("ascii", "replace").strip().partition("=")
        if key == "out_time_us" and value.isdigit():
            done = int(value) / 1e6
            total = f" / {clock(duration)} ({done / duration:.0%})" if duration else ""
            status.show(f"  {label}: {clock(done)}{total}")
    proc.wait()
    reader.join()
    status.clear()
    if proc.returncode != 0:
        message = errors[0].decode("utf-8", "replace").strip() if errors and errors[0] else ""
        sys.exit("ffmpeg failed: "
                 + (message.splitlines()[-1] if message else f"exit code {proc.returncode}"))
    return done


def probe(path):
    """Returns (duration in seconds, first audio stream, all streams) of a media file."""
    result = subprocess.run([FFPROBE, "-v", "error", "-print_format", "json",
                             "-show_format", "-show_streams", str(path)], capture_output=True)
    if result.returncode != 0:
        sys.exit(f"ffprobe cannot read {path}: "
                 + result.stderr.decode("utf-8", "replace").strip())
    info = json.loads(result.stdout.decode("utf-8", "replace"))
    streams = info.get("streams", [])
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    if audio is None:
        sys.exit(f"{path} has no audio stream")
    duration = number(info.get("format", {}).get("duration")) or number(audio.get("duration"))
    if not duration:
        # Raw streams such as .aac carry no duration; decoding is the only sure way.
        duration = run_ffmpeg(["-i", str(path), "-map", "0:a:0", "-f", "null", "-"],
                              None, "measuring length")
    if not duration:
        sys.exit(f"Cannot tell how long {path} is")
    return duration, audio, streams


def number(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def report(paths, source_duration):
    total = 0.0
    for path in paths:
        duration = probe(path)[0]
        total += duration
        print(f"  {path}  {megabytes(path.stat().st_size)}  {clock(duration)}")
    if abs(total - source_duration) > 1:
        print(f"Warning: the output lasts {total:.1f} s but the input lasted {source_duration:.1f} s")


def numbered(folder, stem, suffix, count):
    return [folder / f"{stem}_part{i:02d}{suffix}" for i in range(1, count + 1)]


def warn_about_stale_parts(folder, stem, suffix, count):
    stale = [p for p in numbered(folder, stem, suffix, count + 20)[count:] if p.exists()]
    if stale:
        print(f"Note: {', '.join(p.name for p in stale)} are left over from an earlier run "
              "and are not part of this result.")


# --- shrink ----------------------------------------------------------------


def plan_shrink(duration, max_bytes, min_parts=1):
    """Fewest parts that fit at MIN_KBPS or more, then the highest bitrate those parts allow."""
    for parts in itertools.count(min_parts):
        seconds = duration / parts + 1
        per_second = (max_bytes - HEADER_BYTES) / seconds - INDEX_BYTES_PER_SECOND
        kbps = min(MAX_KBPS, int(per_second * 8 / 1000 / BITRATE_SLACK))
        if kbps >= MIN_KBPS:
            return parts, kbps


def encode(source, tmp, duration, kbps, parts):
    args = ["-i", str(source), "-map", "0:a:0", "-map_chapters", "-1",
            "-c:a", "aac", "-b:a", f"{kbps}k", "-ac", "1", "-ar", str(SAMPLE_RATE)]
    if parts == 1:
        args.append(str(tmp / "out.m4a"))
    else:
        # The extra second keeps rounding from leaving a sliver of a last part.
        args += ["-f", "segment", "-segment_time", f"{duration / parts + 1:.3f}",
                 "-reset_timestamps", "1", str(tmp / "part%03d.m4a")]
    run_ffmpeg(args, duration, "encoding")
    return sorted(tmp.glob("*.m4a"))


def shrink(args):
    source = existing_file(args.input)
    max_bytes = int(args.max_mb * MB)
    out = Path(args.output).expanduser().absolute() if args.output else \
        source.with_name(source.stem + "_small.m4a")
    if out.suffix.lower() != ".m4a":
        sys.exit("shrink writes AAC audio, so the output name must end in .m4a")
    refuse_to_overwrite(source, out)
    size = source.stat().st_size
    duration = probe(source)[0]
    if size <= max_bytes and not args.force:
        print(f"{source.name} is already {megabytes(size)}, within {args.max_mb:g} MB. "
              "Nothing to do (--force re-encodes it anyway).")
        return

    parts, kbps = plan_shrink(duration, max_bytes)
    for _ in range(8):
        print(f"Re-encoding {clock(duration)} of audio ({megabytes(size)}) to mono 16 kHz AAC "
              f"at {kbps} kbps" + (f" in {parts} parts" if parts > 1 else "") + " ...")
        with work_dir(out.parent) as tmp:
            pieces = encode(source, tmp, duration, kbps, parts)
            biggest = max(p.stat().st_size for p in pieces)
            if biggest <= max_bytes:
                if len(pieces) == 1:
                    finals = [out]
                else:
                    finals = numbered(out.parent, out.stem, out.suffix, len(pieces))
                    refuse_to_overwrite(source, *finals)
                for piece, final in zip(pieces, finals):
                    os.replace(piece, final)
                break
        print(f"  came out at {megabytes(biggest)}, over the limit; trying again smaller")
        kbps = int(kbps * max_bytes / biggest * 0.97)
        if kbps < MIN_KBPS:
            parts, kbps = plan_shrink(duration, max_bytes, parts + 1)
    else:
        sys.exit(f"Could not get the output under {args.max_mb:g} MB")

    if len(finals) > 1:
        print(f"Even at {MIN_KBPS} kbps one file would be over {args.max_mb:g} MB, "
              f"so it was written as {len(finals)} parts:")
        warn_about_stale_parts(out.parent, out.stem, out.suffix, len(finals))
    else:
        print("Wrote:")
    report(finals, duration)


# --- split -----------------------------------------------------------------


def copy_extension(audio):
    """A container that takes the audio codec as is, so nothing is re-encoded."""
    codec = audio.get("codec_name", "")
    if codec in ("pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_u8",
                 "pcm_alaw", "pcm_mulaw"):
        return ".wav"
    return {"aac": ".m4a", "alac": ".m4a", "mp3": ".mp3", "flac": ".flac", "opus": ".opus",
            "vorbis": ".ogg", "amr_nb": ".amr", "amr_wb": ".amr"}.get(codec, ".mka")


def split(args):
    source = existing_file(args.input)
    max_bytes = int(args.max_mb * MB)
    size = source.stat().st_size
    if size <= max_bytes:
        print(f"{source.name} is already {megabytes(size)}, within {args.max_mb:g} MB. "
              "Nothing to split.")
        return
    duration, audio, streams = probe(source)
    suffix = copy_extension(audio)
    out_dir = Path(args.output_dir).expanduser().absolute() if args.output_dir else source.parent

    # Only the first audio stream is kept, so video or cover art does not count.
    if len(streams) == 1:
        bytes_per_second = size / duration
    else:
        bits = number(audio.get("bit_rate")) or size * 8 / duration
        bytes_per_second = bits / 8 * 1.02 + INDEX_BYTES_PER_SECOND
    target = max_bytes * 0.95
    parts = max(1, math.ceil(duration * bytes_per_second / target))

    for _ in range(8):
        print(f"Splitting {clock(duration)} of audio into {parts} part"
              f"{'s' if parts > 1 else ''} of about {clock(duration / parts)} without re-encoding ...")
        with work_dir(out_dir) as tmp:
            run_ffmpeg(["-i", str(source), "-map", "0:a:0", "-map_chapters", "-1", "-c", "copy",
                        "-f", "segment", "-segment_time", f"{duration / parts + 1:.3f}",
                        "-reset_timestamps", "1", str(tmp / f"part%03d{suffix}")],
                       duration, "splitting")
            pieces = sorted(tmp.glob("part*"))
            biggest = max(p.stat().st_size for p in pieces)
            if biggest <= max_bytes:
                finals = numbered(out_dir, source.stem, suffix, len(pieces))
                refuse_to_overwrite(source, *finals)
                for piece, final in zip(pieces, finals):
                    os.replace(piece, final)
                break
        # Variable-bitrate audio is denser in some stretches than others.
        print(f"  one part came out at {megabytes(biggest)}, over the limit; "
              "splitting into more parts")
        parts = max(parts + 1, math.ceil(parts * biggest / target))
    else:
        sys.exit(f"Could not get every part under {args.max_mb:g} MB")

    print("Wrote:")
    warn_about_stale_parts(out_dir, source.stem, suffix, len(finals))
    report(finals, duration)
    if len(streams) > 1:
        print("Only the audio was kept.")
    if len(finals) > 3:
        print("Tip: shrink re-encodes to speech quality, which usually fits in one file.")


# --- transcribe ------------------------------------------------------------


def download(url, dest):
    """Downloads to DEST.part, resuming a part left by an earlier run, and renames when whole."""
    part = dest.with_name(dest.name + ".part")
    have = part.stat().st_size if part.exists() else 0
    headers = {"User-Agent": "prepare_audio"}
    if have:
        headers["Range"] = f"bytes={have}-"
    status = Status()
    try:
        try:
            response = urllib.request.urlopen(urllib.request.Request(url, headers=headers),
                                              timeout=60)
        except urllib.error.HTTPError as error:
            # 416: the part file already holds the whole thing.
            if error.code == 416 and (error.headers.get("Content-Range") or "").endswith(f"/{have}"):
                os.replace(part, dest)
                return
            raise
        with response:
            if response.status == 206:
                total = int(response.headers["Content-Range"].rsplit("/", 1)[1])
                mode = "ab"
            else:
                total, have, mode = int(response.headers.get("Content-Length") or 0), 0, "wb"
            with open(part, mode) as out:
                while True:
                    chunk = response.read(1 << 20)
                    if not chunk:
                        break
                    out.write(chunk)
                    have += len(chunk)
                    if total:
                        status.show(f"  {have / MB:.0f} / {total / MB:.0f} MB ({have / total:.0%})")
    except (OSError, urllib.error.URLError) as error:
        status.clear()
        hint = "Run the same command again to resume."
        if "CERTIFICATE_VERIFY_FAILED" in str(error) and sys.platform == "darwin":
            # The python.org installer for macOS leaves Python without trusted certificates.
            hint = ("Run 'Install Certificates.command' in the Python folder under "
                    "Applications, then try again.")
        sys.exit(f"Download of {url} failed ({error}). {hint}")
    status.clear()
    if total and have != total:
        sys.exit(f"Download of {url} stopped at {have} of {total} bytes. "
                 "Run the same command again to resume.")
    os.replace(part, dest)


def unpack(archive, names, folder):
    """Takes just NAMES out of the archive, by file name only, and checks each one's size."""
    sizes = {}
    try:
        # Stream mode reads the .bz2 once, front to back, instead of seeking in it.
        with tarfile.open(archive, "r|bz2") as tar:
            for member in tar:
                name = member.name.rsplit("/", 1)[-1]
                if not member.isfile() or name not in names or name in sizes:
                    continue
                part = folder / (name + ".part")
                with tar.extractfile(member) as src, open(part, "wb") as out:
                    shutil.copyfileobj(src, out, 1 << 20)
                if part.stat().st_size != member.size:
                    raise tarfile.ReadError(f"{name} came out short")
                os.replace(part, folder / name)
                sizes[name] = member.size
    except (tarfile.TarError, EOFError, OSError) as error:
        archive.unlink()
        sys.exit(f"{archive.name} is damaged ({error}); it was deleted. "
                 "Run the same command again to download it afresh.")
    missing = [n for n in names if n not in sizes]
    if missing:
        sys.exit(f"{archive.name} does not contain {', '.join(missing)}")
    return sizes


def model_is_ready(folder):
    try:
        sizes = json.loads((folder / "complete.json").read_text(encoding="utf-8"))
        return all((folder / name).stat().st_size == size for name, size in sizes.items())
    except (OSError, ValueError):
        return False


def ensure_model(name, cache):
    model = MODELS[name]
    folder = cache / name
    if model_is_ready(folder):
        return folder
    folder.mkdir(parents=True, exist_ok=True)
    archive = folder / (model["archive"] + ".tar.bz2")
    need_mb = model["disk_mb"] + (0 if archive.exists() else model["download_mb"])
    free_mb = shutil.disk_usage(folder).free / MB
    if free_mb < need_mb:
        sys.exit(f"{name} needs about {need_mb} MB of free disk in {folder} but only "
                 f"{free_mb:.0f} MB is free; point --model-dir at a bigger disk.")
    if not archive.exists():
        part = archive.with_name(archive.name + ".part")
        if part.exists():
            print(f"Resuming the download of {name} ({part.stat().st_size / MB:.0f} of "
                  f"{model['download_mb']} MB already here) into {folder}")
        else:
            print(f"First use of {name}: downloading {model['download_mb']} MB from GitHub "
                  f"(only this once) into {folder}")
        download(RELEASES + archive.name, archive)
    print(f"Unpacking {archive.name} ...")
    sizes = unpack(archive, model["files"], folder)
    (folder / "complete.json").write_text(json.dumps(sizes, indent=1), encoding="utf-8")
    archive.unlink()
    return folder


def ensure_vad(cache):
    path = cache / VAD_FILE
    if not path.exists():
        cache.mkdir(parents=True, exist_ok=True)
        print(f"Downloading the voice activity detector (0.6 MB) into {cache}")
        download(RELEASES + VAD_FILE, path)
    return path


def native_path(path):
    """A path sherpa-onnx can open. On Windows it may open files through the ANSI code
    page, which can mangle a Korean user name in the path; the 8.3 short name avoids it."""
    text = str(path)
    if os.name != "nt" or text.isascii():
        return text
    import ctypes
    buffer = ctypes.create_unicode_buffer(32768)
    if ctypes.windll.kernel32.GetShortPathNameW(text, buffer, len(buffer)) and buffer.value.isascii():
        return buffer.value
    print(f"Note: if loading the model fails, the non-English letters in {text} may be the "
          "reason; try --model-dir C:\\prepare_audio", file=sys.stderr)
    return text


class WhisperTokens:
    """sherpa-onnx (1.13.8 at least) turns each Whisper token into text on its own and
    drops any token that is not whole UTF-8 by itself. A Korean syllable is often split
    over two or three tokens, so syllables vanished ("괜찮은 척하려고" came out as
    "괜찮은  하고"). The recognizer is given a tokens file in which every token reads
    as a \\x01<id>\\x02 marker, and the text is rebuilt here from the real token bytes."""

    def __init__(self, tokens_path):
        self.bytes = {}
        for line in tokens_path.read_bytes().splitlines():
            fields = line.split()
            if len(fields) == 2:
                self.bytes[int(fields[1])] = base64.b64decode(fields[0])
        self.path = tokens_path.with_name("id-tokens.txt")
        if not self.path.exists():
            part = self.path.with_name(self.path.name + ".part")
            with open(part, "w", encoding="ascii", newline="\n") as out:
                for i in sorted(self.bytes):
                    out.write(base64.b64encode(f"\x01{i}\x02".encode()).decode() + f" {i}\n")
            os.replace(part, self.path)

    def __call__(self, text):
        ids = re.findall(r"\x01(\d+)\x02", text)
        # A segment cut off mid-syllable can end in part of a character.
        return b"".join(self.bytes.get(int(i), b"") for i in ids).decode("utf-8", "ignore")


def load_recognizer(name, folder, language, threads):
    """Returns the recognizer and the function that turns its raw text into the transcript."""
    import sherpa_onnx

    model = MODELS[name]
    files = [native_path(folder / f) for f in model["files"]]
    if model["kind"] == "whisper":
        tokens = WhisperTokens(folder / model["files"][2])
        recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=files[0], decoder=files[1], tokens=native_path(tokens.path),
            language=language, task="transcribe", num_threads=threads)
        return recognizer, tokens
    if model["kind"] == "sense-voice":
        recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=files[0], tokens=files[1], language=language, use_itn=True,
            num_threads=threads)
        return recognizer, str
    recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=files[0], decoder=files[1], joiner=files[2], tokens=files[3],
        decoding_method="greedy_search", num_threads=threads)
    return recognizer, str


class DecodeError(Exception):
    pass


def speech_segments(source, vad_path, progress):
    """Yields (start seconds, samples) for each utterance silero VAD finds, decoding through
    ffmpeg a little at a time so hours of audio never sit in memory at once."""
    import numpy as np
    import sherpa_onnx

    config = sherpa_onnx.VadModelConfig()
    config.silero_vad.model = native_path(vad_path)
    config.silero_vad.threshold = 0.5
    config.silero_vad.min_silence_duration = 0.5
    config.silero_vad.min_speech_duration = 0.25
    # Whisper takes at most 30 s at a time; 20 s also keeps transcript lines readable.
    config.silero_vad.max_speech_duration = 20
    config.sample_rate = SAMPLE_RATE
    config.num_threads = 1
    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=120)
    window = config.silero_vad.window_size

    def ready():
        while not vad.empty():
            segment = vad.front
            start, samples = segment.start / SAMPLE_RATE, np.array(segment.samples, np.float32)
            vad.pop()
            yield start, samples

    decoder = subprocess.Popen(
        [FFMPEG, "-nostdin", "-v", "error", "-i", str(source), "-map", "0:a:0",
         "-f", "f32le", "-acodec", "pcm_f32le", "-ac", "1", "-ar", str(SAMPLE_RATE), "-"],
        stdout=subprocess.PIPE)
    try:
        pending, leftover, read = np.zeros(0, np.float32), b"", 0
        while True:
            raw = decoder.stdout.read(SAMPLE_RATE * 4 // 2)
            if not raw:
                break
            raw = leftover + raw
            whole = len(raw) // 4 * 4
            leftover = raw[whole:]
            pending = np.concatenate([pending, np.frombuffer(raw[:whole], np.float32)])
            read += whole // 4
            while len(pending) >= window:
                vad.accept_waveform(pending[:window])
                pending = pending[window:]
            progress(read / SAMPLE_RATE)
            yield from ready()
        if decoder.wait() != 0:
            raise DecodeError(f"ffmpeg could not decode the rest of {source.name}")
        vad.flush()
        yield from ready()
    finally:
        if decoder.poll() is None:
            decoder.kill()
            decoder.wait()


def transcribe_file(source, duration, out_dir, recognizer, to_text, vad_path):
    out_dir = out_dir or source.parent
    out_dir.mkdir(parents=True, exist_ok=True)
    txt_path, srt_path = out_dir / (source.stem + ".txt"), out_dir / (source.stem + ".srt")
    refuse_to_overwrite(source, txt_path, srt_path)
    print(f"Transcribing {source.name} ({clock(duration)}) ...")

    status = Status()
    started = time.monotonic()
    position = [0.0]

    def progress(seconds):
        position[0] = seconds
        elapsed = time.monotonic() - started
        eta = f"  about {clock(elapsed / seconds * (duration - seconds))} left" if seconds > 30 else ""
        status.show(f"  {clock(seconds)} / {clock(duration)} ({min(seconds / duration, 1):.0%})"
                    f"  elapsed {clock(elapsed)}{eta}")

    lines = 0
    try:
        with open(txt_path, "w", encoding="utf-8") as txt, \
                open(srt_path, "w", encoding="utf-8") as srt, \
                contextlib.closing(speech_segments(source, vad_path, progress)) as segments:
            for start, samples in segments:
                stream = recognizer.create_stream()
                stream.accept_waveform(SAMPLE_RATE, samples)
                recognizer.decode_stream(stream)
                text = to_text(stream.result.text).strip()
                if not text:
                    continue
                lines += 1
                end = start + len(samples) / SAMPLE_RATE
                line = f"[{stamp(start)}] {text}"
                txt.write(line + "\n")
                srt.write(f"{lines}\n{srt_time(start)} --> {srt_time(end)}\n{text}\n\n")
                txt.flush()
                srt.flush()
                status.clear()
                print(line, flush=True)
    except (KeyboardInterrupt, DecodeError) as error:
        # Ctrl+C can reach ffmpeg before Python, which then shows up as a decode error.
        status.clear()
        reason = f" ({error})" if isinstance(error, DecodeError) else ""
        sys.exit(f"\nStopped at {clock(position[0])}{reason}. "
                 f"{txt_path.name} holds the transcript up to there.")
    except OSError as error:
        status.clear()
        sys.exit(f"Cannot write the transcript ({error}); choose a folder with --out-dir.")
    status.clear()

    elapsed = time.monotonic() - started
    if not lines:
        print(f"No speech was found in {source.name}.")
    print(f"Done: {lines} line{'' if lines == 1 else 's'} from {clock(duration)} of audio "
          f"in {clock(elapsed)} ({elapsed / duration:.2f}x real time).")
    print(f"  {txt_path}\n  {srt_path}")
    return txt_path


def transcribe(args):
    if any(importlib.util.find_spec(name) is None for name in ("numpy", "sherpa_onnx")):
        sys.exit("transcribe needs sherpa-onnx and numpy. Install them with: "
                 "python -m pip install sherpa-onnx numpy")
    model = MODELS[args.model]
    language = "" if args.lang.lower() == "auto" else args.lang.lower()
    if model["kind"] == "transducer":
        language = ""
    if language and language not in model["languages"]:
        if model["kind"] == "whisper":
            sys.exit(f"Whisper does not know the language code '{args.lang}'. "
                     "Use a two-letter code such as ko, en, tl, ja or zh, or auto.")
        sys.exit(f"{args.model} cannot do '{args.lang}'; it knows only "
                 f"{', '.join(sorted(model['languages']))}. The whisper models know about 100.")
    # Every input is checked before the model, which can be a long download, is loaded.
    sources = [(path, probe(path)[0]) for path in map(existing_file, args.input)]
    out_dir = Path(args.out_dir).expanduser().absolute() if args.out_dir else None
    cache = Path(args.model_dir or os.environ.get("PREPARE_AUDIO_MODELS")
                 or Path.home() / ".cache" / "prepare_audio").expanduser().absolute()

    folder = ensure_model(args.model, cache)
    vad_path = ensure_vad(cache)
    shown = "ko only" if model["kind"] == "transducer" else language or "auto"
    print(f"Loading {args.model} (language: {shown}, {args.threads} threads) ...")
    recognizer, to_text = load_recognizer(args.model, folder, language, args.threads)
    written = [transcribe_file(source, duration, out_dir, recognizer, to_text, vad_path)
               for source, duration in sources]
    print("Attach " + ", ".join(p.name for p in written) + " to Claude.")


# --- command line ----------------------------------------------------------


def size_limit(text):
    value = float(text)
    if value < 1:
        raise argparse.ArgumentTypeError("must be at least 1 MB")
    return value


def models_help():
    lines = ["models (--model):"]
    for name, model in MODELS.items():
        wrapped = textwrap.wrap(model["about"], 60)
        lines.append(f"  {name:18s}{wrapped[0]}")
        lines += [" " * 20 + more for more in wrapped[1:]]
    return "\n".join(lines)


def main():
    for stream in (sys.stdout, sys.stderr):
        # A console that cannot show Korean gets "?" instead of a crash, and lines
        # reach a log file in the same order as the progress on stderr.
        with contextlib.suppress(AttributeError, ValueError):
            stream.reconfigure(errors="replace", line_buffering=True)

    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    limit_help = "size limit per file in MB (default 45, which leaves room under Claude's 50 MB)"

    shrink_parser = commands.add_parser(
        "shrink", help="re-encode to small speech-quality .m4a under the size limit",
        description="Re-encodes to mono 16 kHz AAC at the highest bitrate from 32 down to "
                    "16 kbps that fits under --max-mb, dropping video and cover art. If even "
                    "16 kbps does not fit, writes numbered parts that each fit.")
    shrink_parser.add_argument("input")
    shrink_parser.add_argument("-o", "--output",
                               help="output .m4a (default: INPUT_small.m4a next to the input)")
    shrink_parser.add_argument("--max-mb", type=size_limit, default=45, help=limit_help)
    shrink_parser.add_argument("--force", action="store_true",
                               help="re-encode even if the input already fits")

    split_parser = commands.add_parser(
        "split", help="cut into numbered parts under the size limit, without re-encoding",
        description="Copies the audio stream unchanged into numbered parts "
                    "(INPUT_part01, ...) that each fit under --max-mb. Video is dropped.")
    split_parser.add_argument("input")
    split_parser.add_argument("--max-mb", type=size_limit, default=45, help=limit_help)
    split_parser.add_argument("-o", "--output-dir",
                              help="folder for the parts (default: next to the input)")

    transcribe_parser = commands.add_parser(
        "transcribe", help="write a timestamped transcript (.txt and .srt) to attach to Claude",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description="Writes INPUT.txt (one '[hh:mm:ss] text' line per utterance) and\n"
                    "INPUT.srt next to the input. Runs on this computer; the first run\n"
                    "downloads the model and the voice activity detector.",
        epilog=models_help())
    transcribe_parser.add_argument("input", nargs="+",
                                   help="audio or video files (m4a, mp3, wav, mp4, ...)")
    transcribe_parser.add_argument("--model", choices=list(MODELS), default=DEFAULT_MODEL,
                                   help=f"default {DEFAULT_MODEL}; see the list below")
    transcribe_parser.add_argument(
        "--lang", default="auto",
        help="auto (default) detects the language of every utterance, which was right on "
             "every Korean and English test clip. Forcing ko makes Whisper turn English "
             "speech into Korean, so use it only for recordings that are entirely Korean. "
             "Other codes: en, tl (Tagalog, whisper models only, untested), ja, zh, ...")
    transcribe_parser.add_argument("--out-dir", help="folder for the .txt and .srt "
                                                     "(default: next to the input)")
    transcribe_parser.add_argument("--threads", type=int,
                                   default=max(1, min(os.cpu_count() or 4, 8)),
                                   help="CPU threads for recognition (default: %(default)s)")
    transcribe_parser.add_argument("--model-dir",
                                   help="where models are kept (default: $PREPARE_AUDIO_MODELS "
                                        "or ~/.cache/prepare_audio)")

    args = parser.parse_args()
    global FFMPEG, FFPROBE
    FFMPEG, FFPROBE = require_ffmpeg()
    try:
        {"shrink": shrink, "split": split, "transcribe": transcribe}[args.command](args)
    except KeyboardInterrupt:
        sys.exit("\nStopped.")


if __name__ == "__main__":
    main()
