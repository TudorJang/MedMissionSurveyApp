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
X-Api-Key: {pre-shared fixed key}
```

Any 2xx response is treated as success. Any non-2xx, connection failure or timeout leaves
the record `PENDING` on the tablet for automatic retry. Connect timeout 5s, read timeout 10s.

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
    "address": "12 Mabini St",
    "city": "Manila",
    "stateProvince": "NCR",
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

## 4. Field types

Every field is optional; the app never blocks send on a missing value.

| JSON path | Type |
|---|---|
| `recordId` | String (UUID), always present |
| `no` | String |
| `date` | String — see §6 |
| `patient.firstName`, `.lastName`, `.address`, `.city`, `.stateProvince`, `.zip`, `.email`, `.cellPhone` | String |
| `patient.birthDate` | String — see §6 |
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

## 6. Open question: date formats

`date` and `patient.birthDate` are **unformatted `String?`** in the app today. The design
spec types them as `LocalDate?`, but the current implementation stores and transmits
whatever string the field holds, with no parsing, validation or canonical format.

The samples above use ISO-8601 (`YYYY-MM-DD`) for readability only — **that is not a
decision this document makes and nothing in the app enforces it.**

Picking the format is a genuine design choice for the bridge integration, because it is
driven by what DICOM MWL needs (DICOM `DA` VR is `YYYYMMDD`) and by how the tablet's date
input is eventually built. Whoever specs the bridge integration should decide, then the
app side should conform and this section should be replaced with the decision.

Until then, bridge implementations must not assume a format, and the tablet must not be
assumed to emit a valid date at all.
