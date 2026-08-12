# 안드로이드 태블릿 설문 앱 설계

- 작성일: 2026-08-12
- 상태: 설계 확정 (사용자 승인 대기)

## 1. 배경 및 목적

야외 의료 현장(Medical Mission)에서 결핵(TB) 스크리닝을 위해 사용되는 고정 설문 양식(`docs/reference/Survey.pdf`, 2페이지 — "MEDICAL MISSION [AI-POWERED CHEST X-RAY]")을 안드로이드 태블릿 앱으로 디지털화한다.

**기존 워크플로우**: 랩톱 의료 프로그램에서 수기 입력 → 스터디리스트 작성 → 포터블 X-ray 촬영 → 전달된 Chest PA 영상을 랩톱 프로그램의 AI 분류모델이 병종 분류.

**목표**: 스터디리스트 작성을 위한 수기 입력 단계를, 환자가 태블릿 화면에 직접 터치로 입력하는 디지털 설문 앱으로 대체한다. 입력된 정보는 무선망을 통해 랩톱으로 전달되어 자동으로 워크리스트에 반영된다.

**AI 관련 범위 명시**: 이 태블릿 앱에는 Claude 등 LLM/AI가 포함되지 않는다. Chest X-ray 분류는 랩톱 프로그램에 이미 존재하는 별도의 분류모델이 전담한다. 이 앱은 순수한 폼 입력 · 로컬 저장 · 네트워크 전송 앱이다.

## 2. 컴포넌트 범위

이 프로젝트는 두 개의 컴포넌트로 구성된다.

| 컴포넌트 | 설명 | 이번 스펙 범위 |
|---|---|---|
| ① 태블릿 앱 | 설문 입력, 로컬 저장, 랩톱으로 전송 | **이번 스펙에서 다룸** |
| ② 브릿지 프로그램 | 랩톱 3대에 각 1개씩 상주. 태블릿의 JSON을 수신해 DICOM Modality Worklist(MWL) 항목으로 변환하고, 기존 랩톱 프로그램이 조회할 수 있도록 DICOM MWL SCP(서버) 역할 수행 | **별도 스펙으로 추후 설계** (이번 문서의 "검증/후속 항목" 참고) |

태블릿 앱의 책임 범위는 **"확정된 JSON 데이터를 브릿지의 HTTP 엔드포인트로 전송"까지**이며, DICOM 태그 변환·워크리스트 서빙은 브릿지의 책임이다. 이 경계 덕분에 태블릿 앱은 DICOM에 대해 전혀 알 필요가 없다.

**기존 랩톱 프로그램은 수정하지 않는다.** 기존 프로그램은 이미 외부 DICOM Worklist 서버(AE Title/IP/Port 설정 가능)를 조회하는 기능을 갖고 있음을 확인했으므로, 그 설정을 브릿지 프로그램 쪽으로 향하게 하는 **설정 변경만으로** 통합이 가능하다. 브릿지 방식은 (a) 기존 검증된 의료 소프트웨어에 손대지 않아 재검증/회귀 리스크가 없고, (b) DICOM MWL이라는 표준 프로토콜을 성숙한 오픈소스 툴킷으로 독립 구현할 수 있으며, (c) 장애가 격리된다는 점에서 직접 수정보다 명확히 낫다고 판단해 채택했다.

## 3. 규모 및 환경

- 태블릿: 3대 이상(M대, 확장 가능). 각 태블릿은 동일한 앱을 실행하며 서로 알 필요 없음(태블릿 간 통신 없음).
- 랩톱: 3대로 고정. 각 랩톱에 브릿지 프로그램이 1개씩 상주.
- 네트워크: 전 구간 무선(Wi-Fi 또는 동글). IP는 DHCP로 유동적일 수 있음을 전제로 설계.
- 통신 관계: 1(태블릿):N(랩톱) — 태블릿 1대가 매 설문마다 3대 중 하나의 랩톱을 선택해 전송.

## 4. 전체 아키텍처

`docs/reference/architecture-overview.png` 참조.

핵심 원칙: **로컬 DB(Room)가 항상 Source of Truth**. 모든 입력은 먼저 태블릿 로컬 DB에 저장되고, 네트워크 전송은 그 이후의 부가 작업으로 분리된다. 오프라인 중에도 입력·저장은 항상 가능하며, 전송은 재시도 가능한 백그라운드 프로세스로 처리한다.

```
설문 입력 UI (Compose) → 로컬 DB (Room, Source of Truth, 상태: DRAFT/PENDING/SENT/FAILED)
                              ├─▶ 기록/큐 조회 UI (전송 이력, 수동 재전송)
                              └─▶ 재전송 워커 (WorkManager, 연결 복구 시 자동 재시도)
랩톱 선택 UI (자동탐색 NSD + 수동 등록) ─▶ 네트워크 계층 (HTTP Client) ──[Wi-Fi/동글, HTTP POST JSON]──▶ 브릿지 프로그램(랩톱, 1~3)
```

### 기술 스택

| 영역 | 선택 |
|---|---|
| UI | Jetpack Compose |
| 로컬 저장 | Room (SQLite ORM) |
| 백그라운드 재전송 | WorkManager |
| 네트워크 | OkHttp/Ktor + Android NSD API (서비스 탐색) |
| 언어 | Kotlin |

## 5. 데이터 모델

### 5.1 메타데이터 (앱 내부 전용, PDF에 없음 · 브릿지로 전송하지 않음)

| 필드 | 타입 | 설명 |
|---|---|---|
| `recordId` | UUID | 앱이 자동 생성. 재전송 시 멱등성 키로 사용 (브릿지가 이 값 기준으로 upsert 처리) |
| `status` | Enum | `DRAFT` / `PENDING` / `SENT` / `FAILED` |
| `createdAt` | Timestamp | 생성 시각 |
| `sentAt` | Timestamp? | 성공 전송 시각 |
| `targetLaptopId` | String? | 전송 대상 랩톱 식별자 |

### 5.2 PDF 필드 매핑 (브릿지로 전송됨)

**헤더**

| 필드 | 타입 | 비고 |
|---|---|---|
| `no` | String? | PDF의 "No." — 사람이 보는 식별자. 앱이 기기 고유 접두사를 포함해 자동 생성(예: `TAB-A3F2-0001`), 수동 수정 가능. 여러 태블릿이 동시에 사용해도 접두사 덕분에 표시상 충돌 없음 |
| `date` | LocalDate? | |

**환자 정보 (Patient Information)**

| 필드 | 타입 |
|---|---|
| `firstName`, `lastName` | String? |
| `birthDate` | LocalDate? |
| `gender` | Enum(`MALE`,`FEMALE`)? |
| `age` | Int? |
| `address`, `city`, `stateProvince`, `zip` | String? |
| `email`, `cellPhone` | String? |
| `maritalStatus` | Enum(`MARRIED`,`SINGLE`,`DIVORCED`,`WIDOWED`,`OTHER`)? |

**병력 (Medical History)**

| 필드 | 타입 |
|---|---|
| `medicalHistory` | `Set<MedicalHistoryItem>` — `HYPERTENSION`, `DIABETES`, `ASTHMA`, `HEART_DISEASE`, `KIDNEY_DISEASE`, `STROKE`, `TUBERCULOSIS`, `CANCER`, `ALLERGIES` |
| `medicalHistoryOthers` | String? (자유입력) |
| `recentSurgeriesOrHospitalization` | String? |
| `currentMedication` | String? |

**활력징후 (Vital Signs)**

| 필드 | 타입 |
|---|---|
| `height`, `weight` | Double? |
| `bpSystolic`, `bpDiastolic` | Int? |
| `pulseRate`, `respiratoryRate` | Int? |
| `temperature`, `oxygenSaturation`, `bloodGlucose` | Double? |

**현재 증상 (Current Symptoms, 2페이지)**

| 필드 | 타입 |
|---|---|
| `symptoms` | `Set<Symptom>` — `COUGH`, `COUGH_2WEEKS_PLUS`, `SPUTUM`, `BLOOD_IN_SPUTUM`, `FEVER`, `CHEST_PAIN`, `SHORTNESS_OF_BREATH`, `WEIGHT_LOSS`, `NIGHT_SWEATS`, `FATIGUE`, `NONE` |

**TB 관련 정보**

| 필드 | 타입 |
|---|---|
| `everDiagnosedTB` | Enum(`YES`,`NO`,`DONT_KNOW`)? |
| `diagnosisYear` | String? (조건부 표시) |
| `everReceivedTreatment` | Enum(YES/NO/DONT_KNOW)? |
| `treatmentCompleted` | Enum(YES/NO/DONT_KNOW)? |
| `closeContactActiveTB` | Enum(YES/NO/DONT_KNOW)? |
| `closeContactWhen` | String? (조건부) |
| `householdMemberTBTreatment` | Enum(YES/NO/DONT_KNOW)? |

**흡연 / 음주**

| 필드 | 타입 |
|---|---|
| `smokingStatus` | Enum(`NEVER`,`CURRENT`,`FORMER`)? |
| `smokingDuration` | Enum(`NONE`,`LESS_THAN_5`,`5_TO_10`,`MORE_THAN_10`)? |
| `drinksAlcohol` | Boolean? |
| `alcoholAmount` | Enum(`1_2`,`3_4`,`5_PLUS`)? |

**환경 노출**

| 필드 | 타입 |
|---|---|
| `dustSmokeChemicalExposure`, `cooksWithSolidFuels`, `secondhandSmokeExposure`, `crowdedLivingConditions` | Boolean? |

### 5.3 태블릿에서 다루지 않는 필드 (의사/AI 전용)

PDF의 다음 섹션은 X-ray 촬영 이후 랩톱/의사 단계 또는 AI가 채우는 영역이다: Diagnosis, Treatment and Medication, X-RAY AI Assessment, Result/Guidance.

이 섹션들은 **데이터 모델에 포함하지 않으며 브릿지로 전송하는 페이로드에도 포함하지 않는다.** 다만 태블릿 담당자가 "이 항목들이 존재하며 이후 단계에서 채워진다"는 것을 알 수 있도록, **설문 입력 화면 하단에 편집 불가능한 안내용 표시(read-only informational marker)로만 노출한다.** 입력 필드·체크박스 등 조작 가능한 요소는 두지 않으며, `SurveyRecord`의 어떤 필드와도 연결되지 않는다.

### 5.4 검증 정책

모든 필드는 **optional**이다. 어떤 필드가 비어 있어도 저장(로컬 DB)과 전송(브릿지 HTTP)이 차단되지 않는다. 명백히 비정상적인 값(예: 음수 나이, 비현실적 체온)은 UI에서 경고만 표시하고 입력/전송 자체를 막지는 않는다.

## 6. 화면 구성

```
[홈/기록 화면] ──[+ 새 설문]──▶ [설문 입력 화면] ──[작성 완료]──▶ [랩톱 선택 화면] ──▶ [전송 결과]
      ▲                              │ (자동저장,                      │                    │
      │                              │  섹션별 스크롤)                  │                    │
      └──────────────────────────────┴──────────────────────────────────┴────────────────────┘
```

1. **홈/기록 화면**: 작성된 설문 목록과 상태 뱃지(`DRAFT`/`PENDING`/`SENT`/`FAILED`) 표시. `FAILED`/`PENDING` 항목 강조. 항목 탭 시 상세조회/수정, 실패 건은 수동 "다시 보내기". "새 설문" 버튼.
2. **설문 입력 화면**: PDF 순서를 따라 섹션 단위로 구성(환자정보 → 병력 → 활력징후 → 증상 → TB정보 → 흡연 → 음주 → 환경노출). 입력 즉시 로컬 DB에 디바운스 자동저장. 의사/AI 전용 섹션은 화면 하단에 편집 불가 안내 표시로만 노출(§5.3). "완료" 버튼으로 랩톱 선택 화면 이동.
3. **랩톱 선택 화면**: 3대 랩톱을 이름으로 사전 등록(예: "1번 X-ray실")한 목록을 기본으로 표시하고, IP 변경 대응용으로 NSD 자동탐색을 보조 수단으로 제공. 목록에 없으면 수동 추가(IP:Port + 이름).
4. **전송 결과**: 성공 시 확인 후 홈 복귀. 실패 시 "PENDING 저장됨, 자동 재시도됩니다" 안내 후 홈 복귀.

## 7. 네트워크 계약

### 7.1 엔드포인트

```
POST http://{브릿지_IP}:{Port}/api/v1/surveys
Content-Type: application/json
X-Api-Key: {사전 공유된 고정 키}
```

요청 바디는 §5.2의 필드를 담은 JSON (메타데이터 중 `recordId`만 포함, 나머지 메타데이터는 태블릿 내부 전용이라 전송하지 않음).

### 7.2 멱등성

브릿지는 `recordId` 기준 **upsert**로 처리해야 한다(동일 ID 재전송 시 워크리스트 중복 생성 금지). 이는 브릿지 스펙에서 반드시 구현할 요구사항으로 명시한다.

### 7.3 인증

앱 배포 시 고정 API 키를 태블릿 앱과 3대 브릿지에 동일하게 설정(1회성 배포 작업, 현장 운용 중 사용자 조작 없음). 모든 요청에 `X-Api-Key` 헤더로 자동 첨부.

### 7.4 대상 관리

로컬 DB에 `LaptopEndpoint`(이름, IP, Port, 마지막 성공 시각) 저장.

### 7.5 타임아웃 및 재시도

연결 타임아웃 5초, 응답 타임아웃 10초. 실패(타임아웃/연결거부/비-2xx) 시 `PENDING`으로 전환, WorkManager가 `NetworkType.CONNECTED` 제약과 지수 백오프로 자동 재시도. 사용자가 기록 화면에서 수동 재전송도 가능.

## 8. 에러 처리

| 상황 | 동작 |
|---|---|
| 폼 검증 | 실패해도 저장/전송 차단 안 함 (§5.4), 비정상 값은 경고만 |
| 전송 성공 | `PENDING`/`DRAFT` → `SENT` |
| 전송 실패 | → `PENDING`, 토스트 알림, 백그라운드 재시도 |
| 반복 실패(예: 10회 초과) | → `FAILED`, 기록 화면 강조 표시, 자동 재시도는 계속하되 수동 재전송 유도 |

## 9. 테스트 전략

- **단위 테스트**: 데이터 모델 직렬화/역직렬화, 폼 상태 → JSON 변환
- **로컬 DB 테스트**: Room in-memory DB로 저장 및 상태전이(`DRAFT`→`PENDING`→`SENT`) 검증
- **네트워크 테스트**: MockWebServer로 성공/타임아웃/오류 응답, 재시도 로직 검증
- **UI 테스트**: 폼 자동저장, 필수값 없이 전송 가능 여부, 오프라인 큐잉 동작

## 10. 범위 밖 (이번 스펙에서 제외, 추후 별도 처리)

- **브릿지 프로그램**(DICOM MWL SCP, 기존+신규 커스텀 태그 매핑) — 별도 스펙에서 설계
- **USB/파일 기반 수동 백업 경로**(완전 무선 불가 상황 대비) — 필요 시 추후 추가 기능으로 검토
- **다국어 지원** — 요청되지 않아 이번 범위에서 제외

## 11. 검증 필요 항목 (Assumptions to Validate)

- 기존 랩톱 프로그램의 "Worklist" 화면이 실제로 원격 DICOM MWL 서버(AE Title/IP/Port 설정 가능)를 C-FIND로 조회하는 기능임을 확인함(사용자 확인 완료). 브릿지 스펙 착수 전 실제 설정 화면에서 AE Title/포트 등 세부 스펙을 재확인 필요.
- 커스텀 DICOM 태그: 기존에 정의된 태그와, 이번 설문에서 새로 추가된 항목에 대한 신규 태그 정의가 모두 필요함 — 브릿지 스펙에서 매핑표로 정리.
