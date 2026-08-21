# Wire contract: tablet → bridge

This is the JSON the tablet app POSTs to a laptop bridge program. It is generated from
`SurveyPayloadDto.kt` + `SurveyPayloadMapper.kt` and is the authoritative reference for
bridge-side developers. If the DTOs change, update this file in the same commit.

Verified against `SurveyPayloadMapperTest`, which asserts the full structure, every enum
wire value, and the exact serialization of an empty record.

## 1. Request

```
POST http://{bridge_host}:{bridge_port}/api/v1/surveys
Content-Type: application/json
X-Api-Key: {the key for this bridge}
```

**The key belongs to the laptop, not to the app.** Each bridge generates its own key on
first run, so it is stored per saved endpoint and entered on the laptop-select screen
(the operator reads it off the bridge's management page). An endpoint with a blank key
falls back to the key compiled into the APK (`-PsurveyApiKey`, default `changeme-dev-key`),
which is what a site building its own APK relies on.

A `401` is not retried: unlike a timeout it never comes good on its own, so the record
goes straight to `FAILED` and the user is told the key was rejected.

Any 2xx response is treated as success. Any non-2xx other than `401`, connection failure
or timeout leaves the record `PENDING` on the tablet for automatic retry. Connect timeout
5s, read timeout 10s.

**Idempotency.** The bridge MUST upsert on `recordId`. The tablet re-sends the identical
`recordId` on every retry (up to 10 attempts) and also re-sends after a user edits an
already-sent record. Duplicate worklist entries for one `recordId` are a bridge-side bug.

## 2. Serialization rules

Two rules the bridge must handle, both consequences of `kotlinx.serialization` defaults:

1. **Unanswered fields are absent, not null.** Every scalar field defaults to `null`, and
   defaults are not encoded. Do not expect `"gender": null` — expect no `gender` key at all.
2. **The nine top-level keys are always present**, except that `no` and `date` follow rule 1.
   The nested group objects (`patient`, `medicalHistory`, …) are always emitted even when
   empty, and `symptoms` is always emitted even when empty.

A completely blank survey therefore serializes to exactly:

```json
{"recordId":"6f1e...","patient":{},"medicalHistory":{},"vitalSigns":{},"symptoms":[],"tbInfo":{},"smoking":{},"alcohol":{},"environmentalExposure":{}}
```

Local sync bookkeeping (`status`, `createdAt`, `sentAt`, `targetLaptopId`, `sendAttempts`)
is tablet-internal and never sent. `recordId` is the only metadata field on the wire.

The four physician/AI-only PDF sections (Diagnosis, Treatment and Medication, X-RAY AI
Assessment, Result/Guidance) are shown in the tablet UI as non-editable informational
markers only. They are not in the data model and never appear in this payload.

## 3. Fully-populated sample payload

```json
{
  "recordId": "3f8b1c2e-9a4d-4c11-8e77-2b6a0d5f9c31",
  "no": "TAB-A3F2-0001",
  "date": "2026-08-12",
  "patient": {
    "firstName": "Juan",
    "lastName": "Dela Cruz",
    "birthDate": "1980-03-04",
    "gender": "MALE",
    "age": 46,
    "address": "12 Mabini St, Simona Subd.",
    "region": "NCR",
    "city": "Manila",
    "barangay": "Ermita",
    "zip": "1000",
    "email": "juan@example.com",
    "cellPhone": "+63-900-000-0000",
    "maritalStatus": "OTHER",
    "maritalStatusOther": "Domestic partnership"
  },
  "medicalHistory": {
    "items": ["ASTHMA", "DIABETES"],
    "others": "Migraine",
    "recentSurgeriesOrHospitalization": "Appendectomy 2019",
    "currentMedication": "Salbutamol"
  },
  "vitalSigns": {
    "height": 170.5,
    "weight": 68.2,
    "bpSystolic": 128,
    "bpDiastolic": 82,
    "pulseRate": 76,
    "respiratoryRate": 18,
    "temperature": 36.8,
    "oxygenSaturation": 98.0,
    "bloodGlucose": 5.4
  },
  "symptoms": ["COUGH", "NIGHT_SWEATS"],
  "tbInfo": {
    "everDiagnosedTB": "YES",
    "diagnosisYear": "2015",
    "everReceivedTreatment": "YES",
    "treatmentCompleted": "NO",
    "closeContactActiveTB": "DONT_KNOW",
    "closeContactWhen": "2024",
    "householdMemberTBTreatment": "NO"
  },
  "smoking": {
    "status": "FORMER",
    "duration": "FIVE_TO_10"
  },
  "alcohol": {
    "drinks": true,
    "amount": "ONE_TO_TWO"
  },
  "environmentalExposure": {
    "dustSmokeChemicalExposure": true,
    "cooksWithSolidFuels": false,
    "secondhandSmokeExposure": true,
    "crowdedLivingConditions": false
  }
}
```

**Note on sample `"region"` value:** The sample above shows `"region": "NCR"` for readability, but this is a short illustrative label only. The actual wire value the app sends is the PSA official region name from the bundled `hierarchy.json` dataset — e.g. `"National Capital Region (NCR)"`, not the short form `"NCR"`. The §4 field-type table and the test fixture reflect the actual values that appear on the wire.

## 4. Field types

Every field is optional; the app never blocks send on a missing value.

| JSON path | Type |
|---|---|
| `recordId` | String (UUID), always present |
| `no` | String — `TAB-` + 기기 접두사 4자(base36, `0-9A-Z`) + `-` + 일련번호 4자리 이상. 접두사는 기기 ID의 SHA-256에서 유도돼 기기마다 고정 (2026-08-21부터 base36 — 이전 릴리스는 16진 대문자였고, 콘솔 측은 형식을 파싱하지 않음이 확인됨) |
| `date` | String — ISO-8601 `YYYY-MM-DD`, machine-generated, always valid (see §6) |
| `patient.firstName`, `.lastName`, `.city`, `.zip`, `.email`, `.cellPhone` | String |
| `patient.address` | String — as of the PSGC address hierarchy feature, holds only street/subdivision/landmark text, not a full address. The complete address is `address` + `barangay` + `city` + `province` + `region` combined; this field previously held the complete free-text address. |
| `patient.region`, `.province`, `.barangay` | String — PSA official name from the bundled PSGC dataset, or an arbitrary free-text string if "Not listed" was chosen on the tablet. `.province` is absent for addresses in NCR (no province level exists there). |
| `patient.birthDate` | String — ISO-8601 `YYYY-MM-DD` shape; may be partial or invalid, parse-or-ignore (see §6) |
| `patient.age` | Int |
| `patient.gender`, `.maritalStatus` | String enum — see §5 |
| `patient.maritalStatusOther` | String — only meaningful when `maritalStatus` is `OTHER`; the app clears it whenever `maritalStatus` changes away from `OTHER` |
| `medicalHistory.items` | Array of String enum — see §5 |
| `medicalHistory.others`, `.recentSurgeriesOrHospitalization`, `.currentMedication` | String |
| `vitalSigns.height`, `.weight`, `.temperature`, `.oxygenSaturation`, `.bloodGlucose` | Double |
| `vitalSigns.bpSystolic`, `.bpDiastolic`, `.pulseRate`, `.respiratoryRate` | Int |
| `symptoms` | Array of String enum — see §5. Always present, may be `[]` |
| `tbInfo.everDiagnosedTB`, `.everReceivedTreatment`, `.treatmentCompleted`, `.closeContactActiveTB`, `.householdMemberTBTreatment` | String enum — see §5 |
| `tbInfo.diagnosisYear` | String — digits only, up to 4 characters (e.g. `"2015"`) |
| `tbInfo.closeContactWhen` | String (free text, no enforced format) |
| `smoking.status`, `smoking.duration` | String enum — see §5 |
| `alcohol.drinks` | Boolean |
| `alcohol.amount` | String enum — see §5 |
| `environmentalExposure.*` (4 fields) | Boolean |

## 5. Enum wire values

Wire values are the Kotlin `.name` strings, not the human-readable labels. The label column
is what the tablet displays; the bridge should never match on it.

### `patient.gender` — `Gender`

| Wire value | Label |
|---|---|
| `MALE` | Male |
| `FEMALE` | Female |

### `patient.maritalStatus` — `MaritalStatus`

| Wire value | Label |
|---|---|
| `MARRIED` | Married |
| `SINGLE` | Single |
| `DIVORCED` | Divorced |
| `WIDOWED` | Widowed |
| `OTHER` | Other — free-text detail travels alongside in `patient.maritalStatusOther` |

### `medicalHistory.items[]` — `MedicalHistoryItem`

| Wire value | Label |
|---|---|
| `HYPERTENSION` | Hypertension |
| `DIABETES` | Diabetes |
| `ASTHMA` | Asthma |
| `HEART_DISEASE` | Heart Disease |
| `KIDNEY_DISEASE` | Kidney Disease |
| `STROKE` | Stroke |
| `TUBERCULOSIS` | Tuberculosis |
| `CANCER` | Cancer |
| `ALLERGIES` | Allergies |

Free-text conditions go in `medicalHistory.others`, not in this array.

### `symptoms[]` — `Symptom`

| Wire value | Label |
|---|---|
| `COUGH` | Cough |
| `COUGH_2WEEKS_PLUS` | Cough for 2 weeks or more |
| `SPUTUM` | Sputum |
| `BLOOD_IN_SPUTUM` | Blood in sputum |
| `FEVER` | Fever |
| `CHEST_PAIN` | Chest pain |
| `SHORTNESS_OF_BREATH` | Shortness of breath |
| `WEIGHT_LOSS` | Weight loss |
| `NIGHT_SWEATS` | Night sweats |
| `FATIGUE` | Fatigue |
| `NONE` | None |

`NONE` is an explicit "no symptoms" answer and is distinct from an empty array, which means
the question was not answered.

### `tbInfo.*` — `YesNoUnknown`

| Wire value | Label |
|---|---|
| `YES` | Yes |
| `NO` | No |
| `DONT_KNOW` | Don't Know |

### `smoking.status` — `SmokingStatus`

| Wire value | Label |
|---|---|
| `NEVER` | Never smoker |
| `CURRENT` | Current smoker |
| `FORMER` | Former smoker |

### `smoking.duration` — `SmokingDuration`

| Wire value | Label | Design spec name |
|---|---|---|
| `NONE` | None | `NONE` |
| `LESS_THAN_5` | < 5 year | `LESS_THAN_5` |
| **`FIVE_TO_10`** | 5–10 years | `5_TO_10` — **renamed** |
| `MORE_THAN_10` | > 10 years | `MORE_THAN_10` |

### `alcohol.amount` — `AlcoholAmount`

| Wire value | Label | Design spec name |
|---|---|---|
| **`ONE_TO_TWO`** | 1-2 drinks | `1_2` — **renamed** |
| **`THREE_TO_FOUR`** | 3-4 drinks | `3_4` — **renamed** |
| **`FIVE_PLUS`** | 5 or more drinks | `5_PLUS` — **renamed** |

**Renames from the design spec.** The design spec
(`docs/superpowers/specs/2026-08-12-tablet-survey-app-design.md` §5.2) names four enum
constants starting with a digit (`5_TO_10`, `1_2`, `3_4`, `5_PLUS`). Those are not legal
Kotlin identifiers, so they were renamed as shown above. **The wire values are the Kotlin
names in the "Wire value" column, not the spec names.** Bridge-side code must match on
`FIVE_TO_10`, `ONE_TO_TWO`, `THREE_TO_FOUR`, `FIVE_PLUS`.

## 6. Date formats

**The wire format for `date` and `patient.birthDate` is ISO-8601 calendar date,
`YYYY-MM-DD`.** This formalizes what the app already produces:

- `date` is machine-generated on record creation (`LocalDate.now().toString()`,
  `todayLocalDateString` in `FormFormatting.kt`) and is always a valid ISO date.
- `patient.birthDate` comes from a masked input (`formatBirthDateInput`) that only
  admits digits laid out as `YYYY-MM-DD`. The mask enforces the **shape**, not
  calendar validity: because the form never blocks send, the value can be a partial
  entry (`"1980-03"`) or a non-existent date (`"1980-13-99"`). The app's own age
  calculation treats unparseable values as absent; bridges must do the same —
  parse as ISO-8601, and treat anything that fails to parse as "not answered"
  rather than rejecting the payload.

**DICOM conversion is the bridge's job.** DICOM `DA` VR wants `YYYYMMDD`; the bridge
strips the hyphens after successful ISO parsing. The tablet will not emit DICOM
formats.

## Status lookup (tablet → bridge)

Besides the POST above, the app polls `GET /api/v1/surveys/{recordId}/status` (same
`X-Api-Key` header) for records it has sent, and shows the answer on the home list:

    200  {"status":"Received"}   also InProgress, Completed, Cancelled
    401  wrong key — shown nowhere, the row simply carries no X-ray line
    404  the bridge does not know the record

Display only: the tablet stores nothing from this call and asks again on the next
poll (every 30 s while the home screen is visible).

