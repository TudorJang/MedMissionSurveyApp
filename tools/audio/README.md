# 긴 녹음을 Claude에게 분석시키기 (`prepare_audio.py`)

## 무엇이 문제인가

- Claude 앱은 **50 MB가 넘는 파일을 첨부할 수 없습니다.** 휴대폰 녹음(보통 128 kbps)은
  1시간에 약 58 MB여서, 50분 정도만 넘어도 막힙니다.
- 그리고 Claude가 녹음 **내용**을 분석하려면 **글로 된 대본**이 필요합니다.

그래서 이 도구는 내 컴퓨터에서 녹음을 **시간이 찍힌 대본(.txt)** 으로 바꿔 줍니다.
대본은 1시간짜리 녹음이라도 수십 KB라 크기 제한과는 상관이 없습니다.
**Claude에는 만들어진 `.txt` 파일을 첨부하세요.** 녹음 파일은 컴퓨터 밖으로 나가지 않고,
인터넷은 처음에 음성 인식 모델을 내려받을 때만 씁니다.

오디오 파일 자체를 50 MB 밑으로 맞춰야 할 때를 위해 `shrink`(용량 줄이기)와
`split`(자르기)도 들어 있습니다.

## 준비 (한 번만)

**Windows** (PowerShell):

```powershell
winget install ffmpeg
winget install Python.Python.3.12
py -m pip install sherpa-onnx numpy      # PowerShell 창을 새로 연 뒤 실행
```

**macOS** (터미널):

```bash
brew install ffmpeg
python3 -m venv ~/audio-env
~/audio-env/bin/pip install sherpa-onnx numpy
```

`sherpa-onnx`와 `numpy`는 `transcribe`에만 필요합니다. 아래 명령은 `prepare_audio.py`가
있는 폴더에서 실행합니다. 예시의 `"회의 녹음.m4a"`는 **따옴표까지 지운 자리에** 녹음 파일을
터미널 창으로 끌어다 놓으면 됩니다(공백이 있는 경로는 터미널이 알아서 처리합니다).

결과 파일과 같은 이름의 파일이 이미 있으면 덮어쓰지 않고 멈춥니다. 바꿔도 될 때만
명령 끝에 `--overwrite`를 붙이세요.

## 사용법

### 1) `transcribe`: 대본 만들기 (추천)

```powershell
py prepare_audio.py transcribe "회의 녹음.m4a"                       # Windows
~/audio-env/bin/python prepare_audio.py transcribe "회의 녹음.m4a"   # macOS
```

결과는 녹음 옆에 생깁니다.

- `회의 녹음.txt`: Claude에 첨부할 파일입니다. 한 줄에 `[00:12:34] 말한 내용` 형식입니다.
- `회의 녹음.srt`: 자막 파일입니다(동영상 플레이어용).

| 모델 | 1시간 녹음 처리 시간\* | 메모리 | 첫 다운로드 |
|---|---|---|---|
| `whisper-turbo` (기본) | 약 12분 | 약 2.3 GB | 564 MB |
| `--model sense-voice` (빠름) | 약 1.5분 | 약 0.4 GB | 163 MB |

\* 4코어 CPU에서 53분짜리 녹음으로 잰 값입니다. 진행률과 남은 시간이 화면에 나옵니다.

- 테스트 녹음의 한국어 글자 오류율은 `whisper-turbo` 약 5%, `sense-voice` 약 10%였습니다.
  `sense-voice`는 "접종 을"처럼 단어 안에 띄어쓰기가 끼는 일이 있습니다.
- 언어는 자동으로 감지합니다(`--lang auto`, 기본값). 녹음을 말이 잠깐 멈추는 곳마다(쉬지
  않는 말은 10초 단위로) 조각내고, **한 조각은 한 가지 언어로만** 적습니다. 그래서 한국어와
  영어(통역)가 거의 쉬지 않고 오가면 한쪽 문장이 빠지거나 다른 언어로 옮겨 적힐 수 있습니다.
  테스트에서 0.2~1초 간격으로 번갈아 말한 영어 20문장 중 `whisper-turbo`는 2문장을 놓쳤고
  (`sense-voice`는 모두 적음), 0.7초 넘게 쉬면 놓친 문장이 없었습니다. 같은 언어라도 두 사람이
  쉬지 않고 빠르게 주고받으면 한 사람의 말이 빠지는 일이 있습니다. 중요한 대목은 녹음으로
  확인하세요.
- `--lang ko`는 녹음이 **전부 한국어일 때만** 쓰세요. 영어 발언까지 한국어로 바꿔 버립니다.
- 타갈로그어(`--lang tl`)는 whisper 모델만 지원하며, 정확도는 검증하지 않았습니다.
- 알려진 버릇: 말이 중간에서 끊긴 조각은 `whisper-turbo`가 그럴듯한 말로 채우는 일이
  있습니다. 녹음이 말 중간에서 끝날 때("주민등록증을 보…" → "주민등록증을 보내고"),
  문장 중간에서 잠깐 멈췄을 때("그는 괜찮은 척하려고 … 애쓰는 것 같았다" → "그는 괜찮은 척
  하려고 애매한 척을 해냈습니다 애쓰는 것 같았다"), 10초 넘게 쉬지 않은 말이 잘린 곳에서
  그렇습니다.
- 다른 모델과 옵션은 `transcribe --help`로 볼 수 있습니다.

### 2) `shrink`: 오디오 용량 줄이기

```powershell
py prepare_audio.py shrink "회의 녹음.m4a"        # Windows → 회의 녹음_small.m4a
python3 prepare_audio.py shrink "회의 녹음.m4a"   # macOS
```

음성용 모노 16 kHz AAC로 다시 압축합니다. 45 MB(`--max-mb`로 변경 가능) 안에
들어가는 가장 좋은 음질(32 kbps부터 최저 16 kbps까지)을 자동으로 고릅니다.
16 kbps로도 넘치면(약 5시간 45분 이상) `_part01`, `_part02`… 로 나눠서 저장합니다.
오디오 트랙이 여러 개인 화면 녹화는 모든 트랙을 하나로 섞습니다. 원본은 건드리지 않습니다.

| 비트레이트 | 1시간당 크기 | 45 MB에 들어가는 길이 |
|---|---|---|
| 128 kbps (휴대폰 녹음 원본) | 약 58 MB | 약 46분 |
| 32 kbps (shrink 기본) | 약 14.8 MB | 약 3시간 |
| 24 kbps | 약 11.1 MB | 약 4시간 |
| 16 kbps (shrink 최저) | 약 7.5 MB | 약 5시간 45분 |

### 3) `split`: 다시 압축하지 않고 자르기

```powershell
py prepare_audio.py split "회의 녹음.m4a"         # Windows → 회의 녹음_part01.m4a, …
python3 prepare_audio.py split "회의 녹음.m4a"    # macOS
```

음질 손실 없이 45 MB 이하의 같은 길이 조각으로 자릅니다. 128 kbps 원본이면 한 조각이
최대 약 45분입니다(1시간 녹음 → 30분 × 2). FLAC이나 오디오 트랙이 여러 개인 파일은
`.mka`로 저장합니다. 조각 수를 줄이려면 `shrink`가 낫습니다.

## 문제가 생기면

- **`ffmpeg ... not on the PATH`**: ffmpeg을 설치한 뒤 터미널 창을 새로 여세요.
- **`transcribe needs sherpa-onnx`**: 위 "준비"의 `pip install` 줄을 실행하세요.
- **Windows에서 사용자 이름이 한글이고 모델을 불러오지 못할 때**: 모델을 영문 경로에
  두세요. 예: `--model-dir C:\prepare_audio`
- **다운로드가 끊겼을 때**: 같은 명령을 다시 실행하면 이어서 받습니다.
- **macOS에서 `CERTIFICATE_VERIFY_FAILED`**: 응용 프로그램 > Python 폴더의
  `Install Certificates.command`를 한 번 실행하세요.
