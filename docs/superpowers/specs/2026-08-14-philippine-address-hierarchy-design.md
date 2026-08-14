# 필리핀 주소 계층 드롭다운 재설계

- 작성일: 2026-08-14
- 상태: 설계 확정 (사용자 승인 대기)

## 1. 배경 및 목적

현재 `SurveyRecord`의 주소 관련 필드(`address`, `city`, `stateProvince`, `zip`)는 전부 자유 입력 텍스트다. 실제 필드 데이터(`docs/reference/` 인근에서 수집된 샘플 주소 6건, Taytay·Rizal 지역)를 분석한 결과 다음 문제가 실제로 발생하고 있음을 확인했다:

1. **표기 불일치** — `"BRGY. SAN ISIDRO, TAYTAY, RIZAL"` vs `"BLK 10 L 24 SIMONA SUBD. BRGY SAN ISIDRO TAYTAY RIZAL"`처럼 콤마 구분/공백 구분/"BRGY." 유무가 레코드마다 다르다.
2. **오타** — `"BRGY. ISIDRO, TAYTAY, RIZAL"`처럼 공식 바랑가이명 "San Isidro"가 "Isidro"로 잘못 입력된 사례가 있다.
3. **지역 정보 누락** — `"BLK 31 LOT 1 SITIO SIMONA"`, `"BLK 15 LOT 15"`처럼 Block/Lot 정보만 있고 Barangay/City/Province가 통째로 빠진 레코드가 있다.

이 세 문제는 전부 "자유 입력"이라는 근본 원인에서 나온다. Region/Province/City/Barangay를 PSA(필리핀 통계청)의 공식 PSGC(Philippine Standard Geographic Code) 목록에서 선택하도록 강제하면 오타와 누락은 구조적으로 차단되고, 표기도 항상 PSA 공식 명칭으로 통일된다.

## 2. 범위

- **지리적 범위**: 필리핀 전국 (Region 17개, Province 81개, City/Municipality 약 1,634개, Barangay 약 42,000개) — 특정 지역(Rizal 등)으로 한정하지 않는다. 다른 지역 환자도 등록 가능해야 하기 때문이다.
- 이 스펙은 **태블릿 앱(`app/` 모듈)의 Patient Information 섹션**만 다룬다. 브릿지 프로그램은 이번 스펙에 포함되지 않는다(기존 `wire-contract.md` 갱신만 해당).

## 3. 데이터 출처

- **행정구역 계층(Region/Province/City/Barangay)**: PSA가 분기별로 공식 배포하는 PSGC 원본을 가공한 오픈소스 JSON(예: `xemasiv/psgc2`, CC BY 4.0). 전체 용량은 대략 2~5MB로 추정되며, 앱 asset으로 통째로 내장한다.
  - **라이선스 의무**: CC BY 4.0은 출처 표기를 요구한다. 앱 내 어딘가(설정 화면 또는 `docs/`)에 "Geographic data © Philippine Statistics Authority (PSA), licensed CC BY 4.0" 표기를 추가해야 한다.
- **ZIP 코드**: PSGC 자체에는 우편번호가 없다. PHLPost(필리핀 우정공사)가 별도로 관리하는 우편번호 테이블을 추가로 확보해야 하며, **barangay 단위**로 확보해야 한다(§6 참고). 정확한 출처는 구현 단계에서 PHLPost 공개 자료 또는 앞서 언급한 오픈소스 저장소 중 barangay 단위 ZIP을 포함하는 것을 조사해 확정한다 — 이 스펙 문서 확정 시점까지 특정 파일로 못박지 않는다.

## 4. 데이터 모델 변경

### 4.1 `SurveyRecord` (`app/src/main/java/com/medmission/survey/data/model/SurveyRecord.kt`)

| 필드 | 변경 내용 |
|---|---|
| `region` | 신규 추가. `String?`. Region 드롭다운에서 사용자가 직접 선택한 값이 그대로 저장된다 (§5.1 참고 — 화면에서 숨기지 않기로 함) |
| `stateProvince` → `province`로 개명 | 필리핀에는 "State"가 없어 기존 이름이 부정확했다. 브릿지가 아직 없어 breaking change 비용이 0이므로 지금 바로잡는다. |
| `city` | 기존 필드 재사용. 자유 입력 → 드롭다운 선택값으로 의미 변경 |
| `barangay` | 신규 추가. `String?` |
| `address` | 기존 필드 재사용. 의미를 "Street / Subdivision / Landmark" 자유 입력으로 재정의 (필드명은 유지) |
| `zip` | 기존 필드 재사용. 자유 입력 → City+Barangay 선택 시 자동 조회된 값, 입력 불가로 전환 |

PSGC 코드(숫자 코드)는 저장하지 않고 PSA 공식 명칭 문자열만 저장한다 — barangay 이름 중복은 함께 저장되는 city+province로 이미 구분되므로 코드 저장은 지금 요구사항에 과하다 (YAGNI).

### 4.2 PSGC 데이터셋 보관 방식

Room에 별도 테이블로 넣지 않고, **JSON asset을 앱 시작 시(또는 최초 접근 시) 파싱해 메모리에 올려두는 정적 데이터**로 다룬다. 이 데이터는 런타임에 절대 바뀌지 않는 참조 데이터이므로 Room 스키마·마이그레이션을 얹을 이유가 없고, 42,000건 규모의 리스트 필터링은 최신 태블릿에서 문제없이 즉시 처리된다. 기존 코드베이스가 `MEDICAL_HISTORY_ITEMS` 등 정적 옵션 목록을 파일 상단 `val`로 호이스팅해온 패턴과 같은 결의 접근이다.

"목록에 없음"을 선택한 필드는 별도 boolean 플래그 없이, 그냥 사용자가 입력한 자유 텍스트가 그 필드의 값이 된다.

### 4.3 DTO / Mapper / wire-contract.md

`SurveyPayloadDto.kt`의 `PatientDto`에 `region`, `barangay` 필드를 추가하고 `stateProvince` → `province`로 개명한다. `SurveyPayloadMapper.kt`와 `docs/reference/wire-contract.md`도 동일하게 갱신한다 (§8에서 상세).

## 5. UI/UX

### 5.1 화면 순서와 검증된 근거

필리핀 시민이 가장 널리 쓰는 주소 입력 폼(Shopee 배송지 등록 — 공식 도움말에 "region, province, city, barangay, 우편번호를 순서대로 입력"이라 명시됨)과 PHLPost 공식 우편번호 조회 도구(Region → Province → City/Municipality 연동 드롭다운)를 조사한 결과, **Region을 화면에서 숨기지 않고 명시적인 첫 번째 드롭다운으로 노출하는 것이 시민에게 가장 친숙한 방식**임을 확인했다. (처음에는 "우편물에는 Region을 안 쓴다"는 이유로 숨기는 안을 제시했으나, 이는 손글씨 우편 주소 표기 관행이지 디지털 입력 폼의 관행이 아니었다 — Shopee 조사로 정정.)

**최종 화면 순서** (Patient Information 섹션, 기존 Address/City/State-Province/ZIP 4개 필드를 대체):

1. Region (드롭다운)
2. Province (드롭다운, Region으로 필터링 — NCR이면 이 단계 생략, §6)
3. City/Municipality (드롭다운, Province로 필터링)
4. Barangay (드롭다운, City로 필터링)
5. ZIP (자동 조회, 입력 불가)
6. Street / Subdivision / Landmark (자유 입력, 맨 마지막 — 기존 `address` 필드)

### 5.2 선택 UI 컴포넌트

Region(17개)부터 Barangay(도시당 최대 수백 개)까지 전부 **동일한 하나의 "검색 가능한 선택" 컴포넌트**로 통일한다:

- 필드를 탭하면 검색창 + 목록이 있는 다이얼로그(또는 전체화면)가 뜬다.
- 타이핑하면 목록이 실시간으로 필터링된다 (예: "Riz" → Rizal만 남음).
- 목록 맨 아래(또는 맨 위)에 **"Not listed / 목록에 없음"** 옵션이 있다. 선택하면 그 자리가 일반 자유입력 텍스트필드로 바뀐다.
- Region처럼 목록이 작아 검색이 굳이 필요 없는 경우도 동일 컴포넌트를 재사용한다 (일관성이 이점이 더 크고, 구현도 별도로 만들 필요가 없다).

### 5.3 자동 연쇄 이동 (auto-advance)

**신규 레코드에서 처음 값을 채울 때만**: Region 다이얼로그에서 하나를 선택하면 다이얼로그가 닫히면서 곧바로 Province 다이얼로그가 열린다. Province → City → Barangay도 동일하게 연쇄된다. 사용자가 4번 따로 탭할 필요 없이 죽 이어서 선택할 수 있다.

**이미 값이 채워진 필드를 재선택할 때는 자동 이동하지 않는다.** 기존 레코드를 열어서 Province만 고치는 경우처럼, 이미 완성된 나머지 단계로 매번 강제 이동시키면 오히려 방해가 된다. 판단 기준은 "그 필드가 이 조작 직전까지 비어 있었는가"이다.

## 6. NCR(메트로 마닐라) 예외 처리

PSGC 확인 결과, NCR은 Province 단계가 없다 — Manila·Quezon City 등 16개 도시와 Pateros 1개 지자체가 Province 없이 바로 NCR Region 아래 있다.

**처리 방식**: Region으로 "NCR"을 선택하면 Province 다이얼로그 단계를 건너뛰고, City 다이얼로그가 곧바로 뜬다(§5.3의 자동 연쇄 이동 로직에서 Province 단계만 스킵). `SurveyRecord.province`는 이 경우 `null`로 남는다 — "NCR" 같은 가짜 값을 채워 넣지 않는다.

## 7. ZIP 자동 조회

대도시(Manila시, Quezon City 약 30개, Davao City 등)는 City 하나에 barangay/구역별로 ZIP이 여러 개 배정되어 있어, City 단위로만 조회하면 틀린 값이 나올 수 있다. 반면 대다수 지방 지자체는 City 전체가 ZIP 하나다.

**처리 방식**: ZIP은 **Barangay가 선택된 시점**에 조회한다 — barangay 단위 데이터가 있으면 그 값을, 없으면(barangay 단위로 갈리지 않는 지자체) city 단위 값을 사용한다. City만 선택되고 Barangay가 아직 없는 중간 상태에서는 ZIP 필드를 비워둔다.

## 8. Wire Contract 변경 (`docs/reference/wire-contract.md`)

`patient` 객체의 필드 구성이 다음과 같이 바뀐다:

```diff
   "patient": {
     "firstName": "Juan",
     "lastName": "Dela Cruz",
     "birthDate": "1980-03-04",
     "gender": "MALE",
     "age": 46,
-    "address": "12 Mabini St",
-    "city": "Manila",
-    "stateProvince": "NCR",
-    "zip": "1000",
+    "address": "12 Mabini St, Simona Subd.",
+    "region": "NCR",
+    "province": null,
+    "city": "Manila",
+    "barangay": "Ermita",
+    "zip": "1000",
     "email": "juan@example.com",
     "cellPhone": "+63-900-000-0000",
     "maritalStatus": "MARRIED"
   }
```

- `patient.address`의 의미가 "전체 주소 자유입력"에서 "Street/Subdivision/Landmark만"으로 좁아진다는 점을 `wire-contract.md`의 §2(직렬화 규칙) 인근에 명시한다.
- `patient.region`, `.province`, `.city`, `.barangay`는 전부 PSA 공식 명칭 문자열(코드 아님)이고, NCR인 경우 `.province`가 부재(absent)할 수 있음을 명시한다.
- "목록에 없음"으로 자유 입력된 값은 PSA 공식 목록에 없는 임의 문자열일 수 있음을 브릿지 개발자에게 경고한다 (매칭/검증 시 참고).

## 9. 테스트 계획

- **순수 함수 유닛 테스트** (TDD, `FormFormattingTest.kt` 패턴 재사용):
  - Region 선택 → NCR이면 Province 스킵 여부를 판단하는 순수 함수
  - City+Barangay → ZIP 조회 순수 함수 (barangay 매치 우선, city 폴백)
  - PSGC 데이터셋 파싱/필터링 함수 (Region으로 Province 필터링, Province로 City 필터링 등)
- **데이터셋 로딩 테스트**: asset JSON 파싱 결과가 기대한 계층 구조(Region당 올바른 Province 개수, NCR에 Province가 없는지 등)를 갖는지 검증하는 순수 유닛 테스트. Room을 쓰지 않으므로(§4.3) Robolectric은 이 부분에 필요 없다.
- **Compose 통합 동작**(자동 연쇄 이동, 검색 다이얼로그, "목록에 없음" 전환)은 이 프로젝트에 Compose UI 테스트 인프라가 없다는 기존 방침에 따라 **에뮬레이터 수동 검증**으로 커버한다 (오늘 Birth Date 마스킹 커서 버그를 에뮬레이터에서 잡아낸 선례와 동일한 접근)

## 10. 미해결 사항 / 후속 과제

1. **PSGC/ZIP 데이터셋의 정확한 파일·라이선스 확정**은 구현 계획(writing-plans) 단계에서 실제 후보 저장소를 다시 확인하고 못박는다.
2. **앱 크기 증가**: 바랑가이 데이터(약 42,000건) 내장으로 APK 크기가 늘어난다 — 정확한 영향은 실제 파일 확보 후 측정한다.
3. 기존에 저장된(오늘 이전 테스트 데이터 등) 자유 입력 주소 레코드의 마이그레이션은 다루지 않는다 — 이 앱은 아직 파일럿 단계라 실사용 데이터가 없다고 보고 범위에서 제외했다. 실사용 데이터가 존재하는 시점이 되면 별도로 다룬다.
