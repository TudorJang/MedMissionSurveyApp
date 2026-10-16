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

All three need ffmpeg and ffprobe on the PATH, and none of them replaces an
existing file unless --overwrite is given. A recording with several audio
tracks (a screen recording with the microphone in its own track) is mixed into
one by shrink and transcribe; split keeps every track.

transcribe runs offline with sherpa-onnx: ffmpeg streams the audio in, silero
VAD cuts it into utterances, and Whisper large-v3-turbo (default) or SenseVoice
(--model sense-voice, about eight times faster) turns each one into text. The
recording never leaves the computer. The model is downloaded once from the
k2-fsa/sherpa-onnx GitHub releases into ~/.cache/prepare_audio (--model-dir or
the PREPARE_AUDIO_MODELS environment variable put it elsewhere).

On 4 CPU cores a 53-minute recording took 11 minutes with whisper-turbo
(about 2.3 GB of memory) and 76 seconds with sense-voice (about 0.4 GB).
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
import signal
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

# Utterances are cut into pieces of at most 10 s. Whisper writes each piece in one
# language and, where two people talk quickly, can follow one voice and leave out
# the other; with 28 s pieces whole sentences went missing.
MAX_PIECE_SECONDS = 10
# Whisper sometimes repeats the end of a piece into the silence after it
# ("부모. 부모. 부모. ..."); three or more repeats in a row are written once.
REPEATS = re.compile(r"(\w.{2,}?)(?:\s*\1){2,}")
# Audio kept on either side of what the voice detector marks as speech, which
# it trims so closely that word beginnings and endings were lost.
CONTEXT_SECONDS = 0.25

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
        "about": "Default. Best Korean accuracy measured. About 12 minutes per hour of "
                 "audio on 4 CPU cores, 2.3 GB of memory, 564 MB download.",
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


def check_outputs(source, paths, overwrite):
    """Exits if writing PATHS would replace the input, or any other file without --overwrite."""
    taken = [path for path in paths if path.exists()]
    for path in taken:
        if path.is_dir():
            sys.exit(f"{path} is a folder; choose another output name.")
        if os.path.samefile(source, path):
            sys.exit(f"Refusing to overwrite the input {source}; choose another output name.")
    if taken and not overwrite:
        one = len(taken) == 1
        sys.exit(f"{', '.join(p.name for p in taken)} already exist{'s' if one else ''} in "
                 f"{taken[0].parent}. Add --overwrite to replace {'it' if one else 'them'}, "
                 "or write somewhere else.")


def writable(folder, option):
    """Creates FOLDER if needed and exits with a hint at OPTION if nothing can be written there."""
    try:
        folder.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryFile(dir=folder):
            pass
    except OSError as error:
        sys.exit(f"Cannot write in {folder} ({error.strerror or error}); "
                 f"choose another place with {option}.")


@contextlib.contextmanager
def work_dir(parent, option):
    # Next to the outputs rather than in the system temp folder, so finished
    # files can be renamed into place (a rename cannot cross drives).
    writable(parent, option)
    with tempfile.TemporaryDirectory(prefix=".prepare_audio-", dir=parent) as tmp:
        yield Path(tmp)


def segment_pattern(tmp, suffix):
    # The segment muxer reads the whole path as a printf template, so a '%' in
    # a folder name ("50%", "Recording%201") has to be doubled.
    return str(tmp).replace("%", "%%") + os.sep + "part%03d" + suffix


def pieces_in(tmp):
    # By number: past part999 the names grow a digit and sort out of order as text.
    return sorted(tmp.glob("part*"), key=lambda p: int(p.stem[4:]))


def move_into_place(pieces, finals):
    try:
        for piece, final in zip(pieces, finals):
            os.replace(piece, final)
    except OSError as error:
        sys.exit(f"Cannot write {error.filename or finals[0]} ({error.strerror or error}).")


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
    try:
        for line in proc.stdout:
            key, _, value = line.decode("ascii", "replace").strip().partition("=")
            if key == "out_time_us" and value.isdigit():
                done = int(value) / 1e6
                total = f" / {clock(duration)} ({done / duration:.0%})" if duration else ""
                status.show(f"  {label}: {clock(done)}{total}")
    finally:
        # Stopped part-way: ffmpeg must not keep writing into a folder being removed.
        if proc.poll() is None:
            proc.kill()
        proc.wait()
        reader.join()
        status.clear()
    if proc.returncode != 0:
        message = errors[0].decode("utf-8", "replace").strip() if errors and errors[0] else ""
        sys.exit("ffmpeg failed: "
                 + (message.splitlines()[-1] if message else f"exit code {proc.returncode}"))
    return done


class Media:
    """What ffprobe says about a media file, and the ffmpeg options to read all of its audio."""

    def __init__(self, path):
        result = subprocess.run([FFPROBE, "-v", "warning", "-print_format", "json",
                                 "-show_format", "-show_streams", str(path)], capture_output=True)
        if result.returncode != 0:
            sys.exit(f"ffprobe cannot read {path}: "
                     + result.stderr.decode("utf-8", "replace").strip())
        info = json.loads(result.stdout.decode("utf-8", "replace"))
        self.path = path
        self.streams = info.get("streams", [])
        self.audio = [s for s in self.streams if s.get("codec_type") == "audio"]
        if not self.audio:
            sys.exit(f"{path} has no audio stream")
        container = info.get("format", {})
        self.duration = number(container.get("duration")) or number(self.audio[0].get("duration"))
        self.options = []
        rate = number(self.audio[0].get("bit_rate"))
        if (container.get("format_name") == "wav" and self.duration and rate
                and path.stat().st_size > self.duration * rate / 8 * 1.05 + MB):
            # A recorder that stopped without closing the file can leave a header that
            # declares only part of the audio, and ffmpeg would stop where it says.
            self.options = ["-ignore_length", "1"]
            self.duration = None
        if not self.duration or b"Estimating duration from bitrate" in result.stderr:
            # Raw .aac, mp3 without a length header and the like state only a guess,
            # which was off by hours; reading the packets through gives the real length.
            self.duration = run_ffmpeg(self.input() + ["-map", "0:a:0", "-c", "copy",
                                                       "-f", "null", "-"],
                                       None, "measuring length")
        if not self.duration:
            sys.exit(f"Cannot tell how long {path} is")

    def input(self):
        return self.options + ["-i", str(self.path)]

    def speech(self):
        """Options that take the one audio track, or all of them mixed into one."""
        if len(self.audio) == 1:
            return ["-map", "0:a:0"]
        # A screen recording can have a silent first track and the microphone in the second.
        tracks = "".join(f"[0:a:{i}]" for i in range(len(self.audio)))
        return ["-filter_complex", f"{tracks}amix=inputs={len(self.audio)}[speech]",
                "-map", "[speech]"]

    def tracks_note(self):
        if len(self.audio) > 1:
            print(f"{self.path.name} has {len(self.audio)} audio tracks; they are mixed into one.")


def number(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def report(paths, source_duration):
    total = 0.0
    for path in paths:
        duration = Media(path).duration
        total += duration
        print(f"  {path}  {megabytes(path.stat().st_size)}  {clock(duration)}")
    if abs(total - source_duration) > 1:
        print(f"Warning: the output lasts {total:.1f} s but the input lasted {source_duration:.1f} s")


def numbered(folder, stem, suffix, count):
    return [folder / f"{stem}_part{i:02d}{suffix}" for i in range(1, count + 1)]


def note_leftovers(candidates, finals):
    stale = [p for p in candidates if p not in finals and p.exists()]
    if stale:
        print(f"Note: {', '.join(p.name for p in stale)} "
              f"{'is' if len(stale) == 1 else 'are'} left over from an earlier run "
              "and not part of this result.")


# --- shrink ----------------------------------------------------------------


def plan_shrink(duration, max_bytes, min_parts=1):
    """Fewest parts that fit at MIN_KBPS or more, then the highest bitrate those parts allow."""
    for parts in itertools.count(min_parts):
        seconds = duration / parts + 1
        per_second = (max_bytes - HEADER_BYTES) / seconds - INDEX_BYTES_PER_SECOND
        kbps = min(MAX_KBPS, int(per_second * 8 / 1000 / BITRATE_SLACK))
        if kbps >= MIN_KBPS:
            return parts, kbps


def encode(media, tmp, kbps, parts):
    args = media.input() + media.speech() + [
        "-map_chapters", "-1", "-c:a", "aac", "-b:a", f"{kbps}k", "-ac", "1",
        # Without this, stereo is summed into mono about 3 dB louder and loud recordings clip.
        "-rematrix_maxval", "1.0", "-ar", str(SAMPLE_RATE)]
    if parts == 1:
        args.append(str(tmp / "out.m4a"))
    else:
        # The extra second keeps rounding from leaving a sliver of a last part.
        args += ["-f", "segment", "-segment_time", f"{media.duration / parts + 1:.3f}",
                 "-reset_timestamps", "1", segment_pattern(tmp, ".m4a")]
    run_ffmpeg(args, media.duration, "encoding")
    return [tmp / "out.m4a"] if parts == 1 else pieces_in(tmp)


def shrink(args):
    source = existing_file(args.input)
    max_bytes = int(args.max_mb * MB)
    out = Path(args.output).expanduser().absolute() if args.output else \
        source.with_name(source.stem + "_small.m4a")
    if out.suffix.lower() != ".m4a":
        sys.exit("shrink writes AAC audio, so the output name must end in .m4a")
    size = source.stat().st_size
    media = Media(source)
    if size <= max_bytes and not args.force:
        print(f"{source.name} is already {megabytes(size)}, within {args.max_mb:g} MB. "
              "Nothing to do (--force re-encodes it anyway).")
        return

    parts, kbps = plan_shrink(media.duration, max_bytes)
    check_outputs(source, [out] + numbered(out.parent, out.stem, out.suffix, parts),
                  args.overwrite)
    media.tracks_note()
    for _ in range(8):
        print(f"Re-encoding {clock(media.duration)} of audio ({megabytes(size)}) to mono "
              f"16 kHz AAC at {kbps} kbps" + (f" in {parts} parts" if parts > 1 else "") + " ...")
        with work_dir(out.parent, "-o") as tmp:
            pieces = encode(media, tmp, kbps, parts)
            biggest = max(p.stat().st_size for p in pieces)
            if biggest <= max_bytes:
                finals = [out] if len(pieces) == 1 else \
                    numbered(out.parent, out.stem, out.suffix, len(pieces))
                check_outputs(source, finals, args.overwrite)
                move_into_place(pieces, finals)
                break
        print(f"  came out at {megabytes(biggest)}, over the limit; trying again smaller")
        kbps = int(kbps * max_bytes / biggest * 0.97)
        if kbps < MIN_KBPS:
            parts, kbps = plan_shrink(media.duration, max_bytes, parts + 1)
    else:
        sys.exit(f"Could not get the output under {args.max_mb:g} MB")

    if len(finals) > 1:
        print(f"Even at {MIN_KBPS} kbps one file would be over {args.max_mb:g} MB, "
              f"so it was written as {len(finals)} parts:")
    else:
        print("Wrote:")
    report(finals, media.duration)
    note_leftovers([out] + numbered(out.parent, out.stem, out.suffix, len(finals) + 20), finals)


# --- split -----------------------------------------------------------------


def copy_extension(media):
    """A container that takes the audio as is, so nothing is re-encoded."""
    if len(media.audio) > 1:
        return ".mka"
    codec = media.audio[0].get("codec_name", "")
    if codec in ("pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_u8",
                 "pcm_alaw", "pcm_mulaw"):
        return ".wav"
    # Not .flac for FLAC: copied parts keep the whole recording's length in their
    # header, so every part claimed to be hours long.
    return {"aac": ".m4a", "alac": ".m4a", "mp3": ".mp3", "opus": ".opus",
            "vorbis": ".ogg", "amr_nb": ".amr", "amr_wb": ".amr"}.get(codec, ".mka")


def audio_bytes(media):
    """How much of the file is audio, for containers such as .mkv that do not say."""
    rates = [number(a.get("bit_rate")) for a in media.audio]
    if all(rates):
        return sum(rates) / 8 * media.duration
    result = subprocess.run([FFPROBE, "-v", "error", "-select_streams", "a",
                             "-show_entries", "packet=size", "-of", "csv=p=0",
                             str(media.path)], capture_output=True)
    return sum(int(size) for size in result.stdout.split() if size.isdigit())


def split(args):
    source = existing_file(args.input)
    max_bytes = int(args.max_mb * MB)
    size = source.stat().st_size
    if size <= max_bytes:
        print(f"{source.name} is already {megabytes(size)}, within {args.max_mb:g} MB. "
              "Nothing to split.")
        return
    media = Media(source)
    duration = media.duration
    suffix = copy_extension(media)
    out_dir = Path(args.out_dir).expanduser().absolute() if args.out_dir else source.parent

    # Only the audio is kept, so video or cover art does not count.
    if len(media.streams) == 1:
        bytes_per_second = size / duration
    else:
        bytes_per_second = audio_bytes(media) / duration * 1.02 + INDEX_BYTES_PER_SECOND
    target = max_bytes * 0.95
    parts = max(1, math.ceil(duration * bytes_per_second / target))
    check_outputs(source, numbered(out_dir, source.stem, suffix, parts), args.overwrite)

    for _ in range(8):
        print(f"Splitting {clock(duration)} of audio into {parts} part"
              f"{'s' if parts > 1 else ''} of about {clock(duration / parts)} without re-encoding ...")
        with work_dir(out_dir, "--out-dir") as tmp:
            run_ffmpeg(media.input() + [
                "-map", "0:a", "-map_chapters", "-1", "-c", "copy", "-f", "segment",
                "-segment_time", f"{duration / parts + 1:.3f}", "-reset_timestamps", "1",
                segment_pattern(tmp, suffix)], duration, "splitting")
            pieces = pieces_in(tmp)
            biggest = max(p.stat().st_size for p in pieces)
            if biggest <= max_bytes:
                finals = numbered(out_dir, source.stem, suffix, len(pieces))
                check_outputs(source, finals, args.overwrite)
                move_into_place(pieces, finals)
                break
        # Variable-bitrate audio is denser in some stretches than others.
        print(f"  one part came out at {megabytes(biggest)}, over the limit; "
              "splitting into more parts")
        parts = max(parts + 1, math.ceil(parts * biggest / target))
    else:
        sys.exit(f"Could not get every part under {args.max_mb:g} MB")

    print("Wrote:")
    report(finals, duration)
    note_leftovers(numbered(out_dir, source.stem, suffix, len(finals) + 20), finals)
    if len(media.audio) > 1:
        print(f"All {len(media.audio)} audio tracks were kept; many players play only the "
              "first. shrink mixes them into one.")
    elif len(media.streams) > 1:
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
    sizes, part = {}, None
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
                part = None
                sizes[name] = member.size
    except (tarfile.TarError, EOFError, OSError) as error:
        if part is not None and part.exists():
            part.unlink()
        if isinstance(error, OSError) and error.errno is not None:
            # A full disk or a file another program holds, not a bad archive (bz2
            # reports bad data with no errno), so the download is kept.
            sys.exit(f"Could not unpack {archive.name} into {folder} ({error.strerror}). "
                     "Free some disk space or close programs using the folder, then run "
                     "the same command again.")
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
    writable(folder, "--model-dir")
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
        writable(cache, "--model-dir")
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


def decode(recognizer, samples):
    stream = recognizer.create_stream()
    stream.accept_waveform(SAMPLE_RATE, samples)
    recognizer.decode_stream(stream)
    return stream.result.text


def load_recognizer(name, folder, language, threads):
    """Returns a function that turns the samples of one utterance into text."""
    import numpy as np
    import sherpa_onnx

    model = MODELS[name]
    files = [native_path(folder / f) for f in model["files"]]
    if model["kind"] == "whisper":
        tokens = WhisperTokens(folder / model["files"][2])
        recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=files[0], decoder=files[1], tokens=native_path(tokens.path),
            language=language, task="transcribe", num_threads=threads)

        def recognize(samples):
            # sherpa-onnx lets Whisper write 6 tokens per second of input, and fast Korean
            # needs about that many, so sentence endings were cut off ("...드시고 계").
            # Silence to one and a half times the length lifts the cap to 9. Twice the
            # length left room for repeating a sentence over and over, and padding to the
            # full 30 s window made Whisper leave out one of two speakers.
            padded = np.zeros(len(samples) * 3 // 2, np.float32)
            padded[:len(samples)] = samples
            return REPEATS.sub(r"\1", tokens(decode(recognizer, padded)))
        return recognize
    if model["kind"] == "sense-voice":
        recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=files[0], tokens=files[1], language=language, use_itn=True,
            num_threads=threads)
    else:
        recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=files[0], decoder=files[1], joiner=files[2], tokens=files[3],
            decoding_method="greedy_search", num_threads=threads)
    return lambda samples: decode(recognizer, samples)


class DecodeError(Exception):
    pass


def pieces(start, samples):
    """Cuts an utterance longer than MAX_PIECE_SECONDS at the quietest tenth of a second in
    its last 6 s, so the cut seldom falls inside a word. Yields (start sample, samples)."""
    import numpy as np

    limit, search, frame = MAX_PIECE_SECONDS * SAMPLE_RATE, 6 * SAMPLE_RATE, SAMPLE_RATE // 10
    while len(samples) > limit:
        energy = (samples[limit - search:limit].reshape(-1, frame) ** 2).sum(axis=1)
        cut = limit - search + int(np.argmin(energy)) * frame + frame // 2
        yield start, samples[:cut]
        start, samples = start + cut, samples[cut:]
    yield start, samples


def speech_segments(media, vad_path, progress):
    """Yields (start seconds, samples) for each utterance silero VAD finds, decoding through
    ffmpeg a little at a time so hours of audio never sit in memory at once."""
    import numpy as np
    import sherpa_onnx

    config = sherpa_onnx.VadModelConfig()
    config.silero_vad.model = native_path(vad_path)
    # Lower than the usual 0.5 and 0.25 s: those dropped clearly audible speech in
    # mp3 and phone-line audio, and one-syllable answers such as "네". These still
    # found no speech in ten minutes of room tone.
    config.silero_vad.threshold = 0.3
    # A quarter-second pause ends an utterance, so a sentence and the interpreter's reply
    # in another language become separate pieces. With 0.5 s, 13 of 20 English sentences
    # in quick Korean-English turn-taking vanished into the Korean around them.
    config.silero_vad.min_silence_duration = 0.25
    config.silero_vad.min_speech_duration = 0.1
    # Only a soft limit (utterances still ran past 40 s), which pieces() makes hard.
    config.silero_vad.max_speech_duration = 20
    config.sample_rate = SAMPLE_RATE
    config.num_threads = 1
    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=120)
    window = config.silero_vad.window_size
    context = int(CONTEXT_SECONDS * SAMPLE_RATE)
    keep = 60 * SAMPLE_RATE
    nothing = np.zeros(0, np.float32)
    last_end = 0

    def ready(recent, read):
        # RECENT holds the last samples up to READ, for the context around each utterance.
        nonlocal last_end
        while not vad.empty():
            segment = vad.front
            start, speech = segment.start, np.array(segment.samples, np.float32)
            vad.pop()
            end = start + len(speech)
            first = max(start - context, last_end, read - len(recent))
            if first > start:  # nothing left before it, or it began before RECENT
                first, before = start, nothing
            else:
                before = recent[first - read + len(recent):start - read + len(recent)]
            after = recent[len(recent) - (read - end):][:context] if end < read else nothing
            last_end = end + len(after)
            samples = np.concatenate([before, speech, after])
            for piece_start, piece in pieces(first, samples):
                yield piece_start / SAMPLE_RATE, piece

    decoder = subprocess.Popen(
        [FFMPEG, "-nostdin", "-v", "error", *media.input(), *media.speech(),
         "-f", "f32le", "-acodec", "pcm_f32le", "-ac", "1", "-ar", str(SAMPLE_RATE), "-"],
        stdout=subprocess.PIPE)
    try:
        pending, recent, leftover, read = np.zeros(0, np.float32), np.zeros(0, np.float32), b"", 0
        while True:
            raw = decoder.stdout.read(SAMPLE_RATE * 4 // 2)
            if not raw:
                break
            raw = leftover + raw
            whole = len(raw) // 4 * 4
            leftover = raw[whole:]
            samples = np.frombuffer(raw[:whole], np.float32)
            pending = np.concatenate([pending, samples])
            recent = np.concatenate([recent[-keep:], samples])
            read += len(samples)
            while len(pending) >= window:
                vad.accept_waveform(pending[:window])
                pending = pending[window:]
            progress(read / SAMPLE_RATE)
            yield from ready(recent, read)
        if decoder.wait() != 0:
            raise DecodeError(f"ffmpeg could not decode the rest of {media.path.name}")
        vad.flush()
        yield from ready(recent, read)
    finally:
        if decoder.poll() is None:
            decoder.kill()
            decoder.wait()


def transcript_paths(source, out_dir):
    folder = out_dir or source.parent
    return folder / (source.stem + ".txt"), folder / (source.stem + ".srt")


def transcribe_file(media, out_dir, recognize, vad_path):
    source, duration = media.path, media.duration
    txt_path, srt_path = transcript_paths(source, out_dir)
    print(f"Transcribing {source.name} ({clock(duration)}) ...")
    media.tracks_note()

    status = Status()
    started = time.monotonic()
    position = [0.0]

    def progress(seconds):
        position[0] = seconds
        elapsed = time.monotonic() - started
        # A header can understate the length; then no estimate is better than a negative one.
        eta = f"  about {clock(elapsed / seconds * (duration - seconds))} left" \
            if 30 < seconds < duration else ""
        status.show(f"  {clock(seconds)} / {clock(duration)} ({min(seconds / duration, 1):.0%})"
                    f"  elapsed {clock(elapsed)}{eta}")

    lines = 0
    try:
        with open(txt_path, "w", encoding="utf-8") as txt, \
                open(srt_path, "w", encoding="utf-8") as srt, \
                contextlib.closing(speech_segments(media, vad_path, progress)) as segments:
            for start, samples in segments:
                text = recognize(samples).strip()
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
    length = position[0] or duration  # what was decoded, in case the header was wrong
    if not lines:
        print(f"No speech was found in {source.name}.")
    print(f"Done: {lines} line{'' if lines == 1 else 's'} from {clock(length)} of audio "
          f"in {clock(elapsed)} ({elapsed / length:.2f}x real time).")
    print(f"  {txt_path}\n  {srt_path}")
    return txt_path


def transcribe(args):
    if any(importlib.util.find_spec(name) is None for name in ("numpy", "sherpa_onnx")):
        sys.exit("transcribe needs sherpa-onnx and numpy. Install them with: "
                 f'"{sys.executable}" -m pip install sherpa-onnx numpy')
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
    # Inputs and output folders are checked before the model, which can be a long
    # download, is loaded.
    out_dir = Path(args.out_dir).expanduser().absolute() if args.out_dir else None
    sources, targets = [], {}
    for source in map(existing_file, args.input):
        txt_path, srt_path = transcript_paths(source, out_dir)
        key = str(txt_path).casefold()
        if key in targets:
            sys.exit(f"{targets[key]} and {source} would both be transcribed to {txt_path}; "
                     "run them one at a time with different --out-dir folders.")
        targets[key] = source
        writable(txt_path.parent, "--out-dir")
        check_outputs(source, [txt_path, srt_path], args.overwrite)
        sources.append(Media(source))
    cache = Path(args.model_dir or os.environ.get("PREPARE_AUDIO_MODELS")
                 or Path.home() / ".cache" / "prepare_audio").expanduser().absolute()

    folder = ensure_model(args.model, cache)
    vad_path = ensure_vad(cache)
    shown = "ko only" if model["kind"] == "transducer" else language or "auto"
    print(f"Loading {args.model} (language: {shown}, {args.threads} threads) ...")
    recognize = load_recognizer(args.model, folder, language, args.threads)
    written = [transcribe_file(media, out_dir, recognize, vad_path) for media in sources]
    print("Attach " + ", ".join(p.name for p in written) + " to Claude.")


# --- command line ----------------------------------------------------------


def size_limit(text):
    value = float(text)
    if not math.isfinite(value) or value < 1:
        raise argparse.ArgumentTypeError("must be a number of MB, at least 1")
    return value


def stop(signum, frame):
    # Closing the terminal or a plain kill then cleans up like Ctrl+C does.
    raise KeyboardInterrupt


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
    overwrite_help = "replace output files that already exist"

    shrink_parser = commands.add_parser(
        "shrink", help="re-encode to small speech-quality .m4a under the size limit",
        description="Re-encodes to mono 16 kHz AAC at the highest bitrate from 32 down to "
                    "16 kbps that fits under --max-mb, dropping video and cover art and "
                    "mixing several audio tracks into one. If even 16 kbps does not fit, "
                    "writes numbered parts that each fit.")
    shrink_parser.add_argument("input")
    shrink_parser.add_argument("-o", "--output",
                               help="output .m4a (default: INPUT_small.m4a next to the input)")
    shrink_parser.add_argument("--max-mb", type=size_limit, default=45, help=limit_help)
    shrink_parser.add_argument("--force", action="store_true",
                               help="re-encode even if the input already fits")
    shrink_parser.add_argument("--overwrite", action="store_true", help=overwrite_help)

    split_parser = commands.add_parser(
        "split", help="cut into numbered parts under the size limit, without re-encoding",
        description="Copies the audio unchanged into numbered parts (INPUT_part01, ...) "
                    "that each fit under --max-mb. Video is dropped. FLAC and recordings "
                    "with several audio tracks are written as .mka.")
    split_parser.add_argument("input")
    split_parser.add_argument("--max-mb", type=size_limit, default=45, help=limit_help)
    split_parser.add_argument("--out-dir", help="folder for the parts (default: next to the input)")
    split_parser.add_argument("--overwrite", action="store_true", help=overwrite_help)

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
        help="auto (default) detects the language of each stretch of speech between pauses "
             "(at most 10 s) and writes the stretch in that one language, so when speakers "
             "switch languages with hardly a pause a sentence can be left out or written in "
             "the other language. Forcing ko makes Whisper turn English speech into Korean, "
             "so use it only for recordings that are entirely Korean. Other codes: en, tl "
             "(Tagalog, whisper models only, untested), ja, zh, ...")
    transcribe_parser.add_argument("--out-dir", help="folder for the .txt and .srt "
                                                     "(default: next to the input)")
    transcribe_parser.add_argument("--threads", type=int,
                                   default=max(1, min(os.cpu_count() or 4, 8)),
                                   help="CPU threads for recognition (default: %(default)s)")
    transcribe_parser.add_argument("--model-dir",
                                   help="where models are kept (default: $PREPARE_AUDIO_MODELS "
                                        "or ~/.cache/prepare_audio)")
    transcribe_parser.add_argument("--overwrite", action="store_true", help=overwrite_help)

    args = parser.parse_args()
    global FFMPEG, FFPROBE
    FFMPEG, FFPROBE = require_ffmpeg()
    for name in ("SIGTERM", "SIGHUP"):
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), stop)
    try:
        {"shrink": shrink, "split": split, "transcribe": transcribe}[args.command](args)
    except KeyboardInterrupt:
        sys.exit("\nStopped.")


if __name__ == "__main__":
    main()
