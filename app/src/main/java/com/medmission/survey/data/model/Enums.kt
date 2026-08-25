package com.medmission.survey.data.model

enum class SyncStatus { DRAFT, PENDING, SENT, FAILED }

enum class Gender(val label: String) {
    MALE("Male"),
    FEMALE("Female"),

    /**
     * DICOM's Patient Sex has had M, F and O since the beginning, and the console's own
     * patient screen offers the same three — so a patient who is neither can be recorded
     * as themselves rather than as a guess. Deliberately left with no default: an
     * unanswered question stays unanswered, because a default here would file every
     * patient nobody asked as the same sex.
     */
    OTHER("Other"),
}

enum class MaritalStatus(val label: String) {
    MARRIED("Married"),
    SINGLE("Single"),
    DIVORCED("Divorced"),
    WIDOWED("Widowed"),
    OTHER("Other"),
}

enum class MedicalHistoryItem(val label: String) {
    HYPERTENSION("Hypertension"),
    DIABETES("Diabetes"),
    ASTHMA("Asthma"),
    HEART_DISEASE("Heart Disease"),
    KIDNEY_DISEASE("Kidney Disease"),
    STROKE("Stroke"),
    TUBERCULOSIS("Tuberculosis"),
    CANCER("Cancer"),
    ALLERGIES("Allergies"),
}

enum class Symptom(val label: String) {
    COUGH("Cough"),
    COUGH_2WEEKS_PLUS("Cough for 2 weeks or more"),
    SPUTUM("Sputum"),
    BLOOD_IN_SPUTUM("Blood in sputum"),
    FEVER("Fever"),
    CHEST_PAIN("Chest pain"),
    SHORTNESS_OF_BREATH("Shortness of breath"),
    WEIGHT_LOSS("Weight loss"),
    NIGHT_SWEATS("Night sweats"),
    FATIGUE("Fatigue"),
    NONE("None"),
}

enum class YesNoUnknown(val label: String) {
    YES("Yes"),
    NO("No"),
    DONT_KNOW("Don't Know"),
}

enum class SmokingStatus(val label: String) {
    NEVER("Never smoker"),
    CURRENT("Current smoker"),
    FORMER("Former smoker"),
}

enum class SmokingDuration(val label: String) {
    NONE("None"),
    LESS_THAN_5("< 5 year"),
    FIVE_TO_10("5–10 years"),
    MORE_THAN_10("> 10 years"),
}

enum class AlcoholAmount(val label: String) {
    ONE_TO_TWO("1-2 drinks"),
    THREE_TO_FOUR("3-4 drinks"),
    FIVE_PLUS("5 or more drinks"),
}
