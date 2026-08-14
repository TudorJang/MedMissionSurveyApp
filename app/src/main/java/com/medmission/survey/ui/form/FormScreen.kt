package com.medmission.survey.ui.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.medmission.survey.data.model.AlcoholAmount
import com.medmission.survey.data.model.Gender
import com.medmission.survey.data.model.MaritalStatus
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.SmokingDuration
import com.medmission.survey.data.model.SmokingStatus
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.YesNoUnknown
import com.medmission.survey.ui.theme.ClayAmber
import com.medmission.survey.ui.theme.ClinicalTeal
import com.medmission.survey.ui.theme.MutedSlate
import com.medmission.survey.ui.theme.SurfaceTint
import com.medmission.survey.util.calculateAge
import com.medmission.survey.util.filterVitalSignInput
import com.medmission.survey.util.formatBirthDateInput
import com.medmission.survey.util.formatCellPhoneInput
import com.medmission.survey.util.formatYearInput

// ---------------------------------------------------------------------------
// Physician / AI-only content, transcribed from docs/reference/Survey.pdf.
//
// These four sections of the paper form are filled in by a physician or the X-ray AI
// *after* the tablet stage. They are rendered so clinic staff can see the whole form,
// but they are display-only on purpose: nothing here is tappable, nothing is backed by
// SurveyRecord, and nothing reaches the network payload. See design spec 5.3.
// ---------------------------------------------------------------------------

private const val OFF_TABLET_NOTE = "Not entered on this tablet — shown for reference only"

/** One row of the Diagnosis / X-RAY AI Assessment tables. */
private data class AssessmentItem(val item: String, val options: List<String>)

private val ASSESSMENT_ITEMS = listOf(
    AssessmentItem("TB finding", listOf("No TB", "TB suspected", "Old or inactive TB")),
    AssessmentItem("TB activity", listOf("Active suspected", "Inactive", "Undetermined")),
    AssessmentItem("Lung mass", listOf("Suspected", "Not suspected", "Undetermined")),
    AssessmentItem("Other finding", listOf("Pneumonia", "Pleural effusion", "Other ___")),
)

private val TREATMENT_FIELDS =
    listOf("Physician's Name", "PRC License No.", "Signature", "Date")

private val RESULT_GUIDANCE_OPTIONS = listOf(
    "1. No further action",
    "2. Medical evaluation if symptoms persist",
    "3. Referred for TB-DOTS evaluation",
    "4. Referred for hospital evaluation of a non-TB chest abnormality",
    "5. Referred for both TB-DOTS and hospital evaluation",
)

// Enum.values() allocates a fresh array on every call. With a plain scrolling Column the
// whole form recomposes on every keystroke, so these are hoisted once rather than
// re-derived at each of their call sites (some of which — YesNoUnknown — are hit from 5
// separate places).
private val GENDER_OPTIONS = Gender.values().toList()
private val MARITAL_STATUS_OPTIONS = MaritalStatus.values().toList()
private val MEDICAL_HISTORY_ITEMS = MedicalHistoryItem.values().toList()
private val SYMPTOM_OPTIONS = Symptom.values().toList()
private val SMOKING_STATUS_OPTIONS = SmokingStatus.values().toList()
private val SMOKING_DURATION_OPTIONS = SmokingDuration.values().toList()
private val ALCOHOL_AMOUNT_OPTIONS = AlcoholAmount.values().toList()
private val YES_NO_UNKNOWN_OPTIONS = YesNoUnknown.values().toList()

@Composable
fun FormScreen(
    record: SurveyRecord,
    onFieldChange: ((SurveyRecord) -> SurveyRecord) -> Unit,
    onToggleMedicalHistory: (MedicalHistoryItem) -> Unit,
    onToggleSymptom: (Symptom) -> Unit,
    onDone: () -> Unit,
    psgcRepository: com.medmission.survey.data.psgc.PsgcRepository,
) {
    Scaffold { padding ->
        // A plain scrolling Column rather than a LazyColumn: the numeric fields below keep
        // an in-progress text buffer in local state, and LazyColumn would recycle (and so
        // reset) that buffer whenever a section scrolled out of view.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader()

            // ---------------- Patient Information ----------------
            SectionCard(title = "Patient Information") {
                var activeGeoDialog by remember { mutableStateOf<GeoDialogStep?>(null) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // No. and Date are assigned once by FormViewModel when the record is
                    // created (device-prefixed sequential index; local creation date) and
                    // never touched again, so both fields are display-only here.
                    TextFieldRow(
                        label = "No.",
                        value = record.no,
                        onValueChange = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    )
                    TextFieldRow(
                        label = "Date",
                        value = record.date,
                        onValueChange = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextFieldRow(
                        label = "First Name",
                        value = record.firstName,
                        onValueChange = { v -> onFieldChange { it.copy(firstName = v) } },
                        modifier = Modifier.weight(1f),
                    )
                    TextFieldRow(
                        label = "Last Name",
                        value = record.lastName,
                        onValueChange = { v -> onFieldChange { it.copy(lastName = v) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MaskedTextFieldRow(
                        label = "Birth Date",
                        externalValue = record.birthDate.orEmpty(),
                        mask = ::formatBirthDateInput,
                        onValueChange = { formatted ->
                            onFieldChange { it.copy(birthDate = formatted, age = calculateAge(formatted)) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    // Derived from Birth Date by FormScreen on every change above, so this
                    // is display-only — no separate onValueChange path exists for it.
                    IntFieldRow(
                        label = "Age",
                        value = record.age,
                        onValueChange = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                OptionChips(
                    label = "Gender",
                    options = GENDER_OPTIONS,
                    selected = record.gender,
                    optionLabel = { it.label },
                    onSelect = { v -> onFieldChange { it.copy(gender = v) } },
                )
                GeoSelectField(
                    label = "Region",
                    value = record.region,
                    options = psgcRepository.regions(),
                    isDialogOpen = activeGeoDialog == GeoDialogStep.REGION,
                    onOpenDialog = { activeGeoDialog = GeoDialogStep.REGION },
                    onDismissDialog = { activeGeoDialog = null },
                    onSelect = { picked ->
                        val wasEmpty = record.region == null
                        onFieldChange {
                            it.copy(region = picked, province = null, city = null, barangay = null, zip = null)
                        }
                        activeGeoDialog = when {
                            !wasEmpty -> null
                            psgcRepository.provinces(picked).isEmpty() -> GeoDialogStep.CITY
                            else -> GeoDialogStep.PROVINCE
                        }
                    },
                    onFreeText = { text ->
                        // Mirrors onSelect's cascade-reset above, unconditionally — "Not
                        // listed" is a pick too, and a stale downstream selection from
                        // whatever was previously chosen here must not survive a new
                        // region pick, whether this is the first fill or a re-edit.
                        onFieldChange {
                            it.copy(region = text, province = null, city = null, barangay = null, zip = null)
                        }
                        activeGeoDialog = null
                    },
                )
                GeoSelectField(
                    label = "Province",
                    value = record.province,
                    options = record.region?.let { psgcRepository.provinces(it) }.orEmpty(),
                    isDialogOpen = activeGeoDialog == GeoDialogStep.PROVINCE,
                    onOpenDialog = { activeGeoDialog = GeoDialogStep.PROVINCE },
                    onDismissDialog = { activeGeoDialog = null },
                    onSelect = { picked ->
                        val wasEmpty = record.province == null
                        onFieldChange { it.copy(province = picked, city = null, barangay = null, zip = null) }
                        activeGeoDialog = if (wasEmpty) GeoDialogStep.CITY else null
                    },
                    onFreeText = { text ->
                        onFieldChange {
                            it.copy(province = text, city = null, barangay = null, zip = null)
                        }
                        activeGeoDialog = null
                    },
                    enabled = record.region != null,
                )
                GeoSelectField(
                    label = "City / Municipality",
                    value = record.city,
                    options = record.region?.let { psgcRepository.cities(it, record.province) }.orEmpty(),
                    isDialogOpen = activeGeoDialog == GeoDialogStep.CITY,
                    onOpenDialog = { activeGeoDialog = GeoDialogStep.CITY },
                    onDismissDialog = { activeGeoDialog = null },
                    onSelect = { picked ->
                        val wasEmpty = record.city == null
                        val zip = psgcRepository.zip(picked, null)
                        onFieldChange { it.copy(city = picked, barangay = null, zip = zip) }
                        activeGeoDialog = if (wasEmpty) GeoDialogStep.BARANGAY else null
                    },
                    onFreeText = { text ->
                        onFieldChange {
                            it.copy(city = text, barangay = null, zip = null)
                        }
                        activeGeoDialog = null
                    },
                    enabled = record.region != null,
                )
                GeoSelectField(
                    label = "Barangay",
                    value = record.barangay,
                    options = record.city?.let { city ->
                        psgcRepository.barangays(record.region.orEmpty(), record.province, city)
                    }.orEmpty(),
                    isDialogOpen = activeGeoDialog == GeoDialogStep.BARANGAY,
                    onOpenDialog = { activeGeoDialog = GeoDialogStep.BARANGAY },
                    onDismissDialog = { activeGeoDialog = null },
                    onSelect = { picked ->
                        val zip = psgcRepository.zip(record.city.orEmpty(), picked) ?: record.zip
                        onFieldChange { it.copy(barangay = picked, zip = zip) }
                        activeGeoDialog = null
                    },
                    onFreeText = { text ->
                        onFieldChange { it.copy(barangay = text) }
                        activeGeoDialog = null
                    },
                    enabled = record.city != null,
                )
                TextFieldRow(
                    label = "ZIP",
                    value = record.zip,
                    onValueChange = {},
                    enabled = false,
                )
                TextFieldRow(
                    label = "Street / Subdivision / Landmark",
                    value = record.address,
                    onValueChange = { v -> onFieldChange { it.copy(address = v) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextFieldRow(
                        label = "Email",
                        value = record.email,
                        onValueChange = { v -> onFieldChange { it.copy(email = v) } },
                        keyboardType = KeyboardType.Email,
                        modifier = Modifier.weight(1f),
                    )
                    MaskedTextFieldRow(
                        label = "Cell Phone",
                        externalValue = record.cellPhone.orEmpty(),
                        mask = ::formatCellPhoneInput,
                        onValueChange = { v -> onFieldChange { it.copy(cellPhone = v) } },
                        keyboardType = KeyboardType.Phone,
                        modifier = Modifier.weight(1f),
                    )
                }
                OptionChips(
                    label = "Marital Status",
                    options = MARITAL_STATUS_OPTIONS,
                    selected = record.maritalStatus,
                    optionLabel = { it.label },
                    onSelect = { v ->
                        onFieldChange {
                            it.copy(
                                maritalStatus = v,
                                maritalStatusOther = if (v == MaritalStatus.OTHER) it.maritalStatusOther else null,
                            )
                        }
                    },
                )
                TextFieldRow(
                    label = "Other (please specify)",
                    value = record.maritalStatusOther,
                    onValueChange = { v -> onFieldChange { it.copy(maritalStatusOther = v) } },
                    enabled = record.maritalStatus == MaritalStatus.OTHER,
                )
            }

            // ---------------- Medical History ----------------
            SectionCard(title = "Medical History") {
                MEDICAL_HISTORY_ITEMS.forEach { item ->
                    CheckboxRow(
                        label = item.label,
                        checked = item in record.medicalHistory,
                        onCheckedChange = { onToggleMedicalHistory(item) },
                    )
                }
                // The PDF's checkbox row ends in "Others: ____". Typing here is what marks
                // the row; there is no separate boolean in SurveyRecord and none is needed.
                TextFieldRow(
                    label = "Others",
                    value = record.medicalHistoryOthers,
                    onValueChange = { v -> onFieldChange { it.copy(medicalHistoryOthers = v) } },
                )
                TextFieldRow(
                    label = "Recent Surgeries or Hospitalization",
                    value = record.recentSurgeriesOrHospitalization,
                    onValueChange = { v ->
                        onFieldChange { it.copy(recentSurgeriesOrHospitalization = v) }
                    },
                )
                TextFieldRow(
                    label = "Current Medication",
                    value = record.currentMedication,
                    onValueChange = { v -> onFieldChange { it.copy(currentMedication = v) } },
                )
            }

            // ---------------- Vital Signs ----------------
            SectionCard(title = "Vital Signs") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DoubleFieldRow(
                        label = "Height",
                        value = record.height,
                        onValueChange = { v -> onFieldChange { it.copy(height = v) } },
                        modifier = Modifier.weight(1f),
                    )
                    DoubleFieldRow(
                        label = "Weight",
                        value = record.weight,
                        onValueChange = { v -> onFieldChange { it.copy(weight = v) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                // One PDF field ("Blood Pressure: ___ / ___"), so the two halves stay paired.
                FieldLabel("Blood Pressure")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IntFieldRow(
                        label = "Systolic",
                        value = record.bpSystolic,
                        onValueChange = { v -> onFieldChange { it.copy(bpSystolic = v) } },
                        modifier = Modifier.weight(1f),
                    )
                    Text("/", style = MaterialTheme.typography.titleMedium)
                    IntFieldRow(
                        label = "Diastolic",
                        value = record.bpDiastolic,
                        onValueChange = { v -> onFieldChange { it.copy(bpDiastolic = v) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IntFieldRow(
                        label = "Pulse Rate",
                        value = record.pulseRate,
                        onValueChange = { v -> onFieldChange { it.copy(pulseRate = v) } },
                        modifier = Modifier.weight(1f),
                    )
                    IntFieldRow(
                        label = "Respiratory Rate",
                        value = record.respiratoryRate,
                        onValueChange = { v -> onFieldChange { it.copy(respiratoryRate = v) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DoubleFieldRow(
                        label = "Temperature",
                        value = record.temperature,
                        onValueChange = { v -> onFieldChange { it.copy(temperature = v) } },
                        modifier = Modifier.weight(1f),
                    )
                    DoubleFieldRow(
                        label = "Oxygen Saturation",
                        value = record.oxygenSaturation,
                        onValueChange = { v -> onFieldChange { it.copy(oxygenSaturation = v) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                DoubleFieldRow(
                    label = "Blood Glucose",
                    value = record.bloodGlucose,
                    onValueChange = { v -> onFieldChange { it.copy(bloodGlucose = v) } },
                )
            }

            // ------- Diagnosis / Treatment (physician-only, page 1 of the PDF) -------
            ReadOnlySectionCard(title = "Diagnosis") {
                AssessmentTable(secondColumnLabel = "Physician's Assessment")
            }
            ReadOnlySectionCard(title = "Treatment and Medication") {
                TREATMENT_FIELDS.forEach { BlankFieldRow(it) }
            }

            // ---------------- 1. Current Symptoms ----------------
            SectionCard(number = "1.", title = "Current Symptoms") {
                SYMPTOM_OPTIONS.forEach { symptom ->
                    CheckboxRow(
                        label = symptom.label,
                        checked = symptom in record.symptoms,
                        onCheckedChange = { onToggleSymptom(symptom) },
                    )
                }
            }

            // ---------------- 2. TB Related Information ----------------
            SectionCard(number = "2.", title = "TB Related Information") {
                YesNoUnknownChips(
                    label = "Have you ever been diagnosed with TB?",
                    selected = record.everDiagnosedTB,
                    onSelect = { v ->
                        onFieldChange {
                            it.copy(
                                everDiagnosedTB = v,
                                // Clearing this on any non-YES answer stops a stale year
                                // from persisting once the gating answer contradicts it.
                                diagnosisYear = if (v == YesNoUnknown.YES) it.diagnosisYear else null,
                            )
                        }
                    },
                )
                TextFieldRow(
                    label = "If yes, year of diagnosis",
                    value = record.diagnosisYear,
                    onValueChange = { v -> onFieldChange { it.copy(diagnosisYear = formatYearInput(v)) } },
                    keyboardType = KeyboardType.Number,
                    enabled = record.everDiagnosedTB == YesNoUnknown.YES,
                )
                YesNoUnknownChips(
                    label = "Have you ever received TB treatment?",
                    selected = record.everReceivedTreatment,
                    onSelect = { v -> onFieldChange { it.copy(everReceivedTreatment = v) } },
                )
                YesNoUnknownChips(
                    label = "Was the full course of treatment completed?",
                    selected = record.treatmentCompleted,
                    onSelect = { v -> onFieldChange { it.copy(treatmentCompleted = v) } },
                )
                YesNoUnknownChips(
                    label = "Have you had close contact with a person with active TB?",
                    selected = record.closeContactActiveTB,
                    onSelect = { v ->
                        onFieldChange {
                            it.copy(
                                closeContactActiveTB = v,
                                closeContactWhen = if (v == YesNoUnknown.YES) it.closeContactWhen else null,
                            )
                        }
                    },
                )
                TextFieldRow(
                    label = "If yes, when?",
                    value = record.closeContactWhen,
                    onValueChange = { v -> onFieldChange { it.copy(closeContactWhen = v) } },
                    enabled = record.closeContactActiveTB == YesNoUnknown.YES,
                )
                YesNoUnknownChips(
                    label = "Is anyone in your household currently being treated for TB?",
                    selected = record.householdMemberTBTreatment,
                    onSelect = { v -> onFieldChange { it.copy(householdMemberTBTreatment = v) } },
                )
            }

            // ---------------- 3. Smoking ----------------
            SectionCard(number = "3.", title = "Smoking") {
                OptionChips(
                    label = "Smoking Status",
                    options = SMOKING_STATUS_OPTIONS,
                    selected = record.smokingStatus,
                    optionLabel = { it.label },
                    onSelect = { v -> onFieldChange { it.copy(smokingStatus = v) } },
                )
                OptionChips(
                    label = "Smoking Duration",
                    options = SMOKING_DURATION_OPTIONS,
                    selected = record.smokingDuration,
                    optionLabel = { it.label },
                    onSelect = { v -> onFieldChange { it.copy(smokingDuration = v) } },
                )
            }

            // ---------------- 4. Alcohol Consumption ----------------
            SectionCard(number = "4.", title = "Alcohol Consumption") {
                YesNoChips(
                    label = "Do you drink alcohol?",
                    selected = record.drinksAlcohol,
                    onSelect = { v ->
                        onFieldChange {
                            it.copy(
                                drinksAlcohol = v,
                                alcoholAmount = if (v) it.alcoholAmount else null,
                            )
                        }
                    },
                )
                OptionChips(
                    label = "If yes, how much do you usually drink per occasion?",
                    options = ALCOHOL_AMOUNT_OPTIONS,
                    selected = record.alcoholAmount,
                    optionLabel = { it.label },
                    onSelect = { v -> onFieldChange { it.copy(alcoholAmount = v) } },
                    enabled = record.drinksAlcohol == true,
                )
            }

            // ---------------- 5. Environmental Exposure ----------------
            SectionCard(number = "5.", title = "Environmental Exposure") {
                YesNoChips(
                    label = "Do you work or stay in places with a lot of dust, smoke, " +
                        "or chemical exposure?",
                    selected = record.dustSmokeChemicalExposure,
                    onSelect = { v -> onFieldChange { it.copy(dustSmokeChemicalExposure = v) } },
                )
                YesNoChips(
                    label = "Do you usually cook using wood, charcoal, or other solid " +
                        "fuels at home?",
                    selected = record.cooksWithSolidFuels,
                    onSelect = { v -> onFieldChange { it.copy(cooksWithSolidFuels = v) } },
                )
                YesNoChips(
                    label = "Are you frequently exposed to secondhand smoke from family members?",
                    selected = record.secondhandSmokeExposure,
                    onSelect = { v -> onFieldChange { it.copy(secondhandSmokeExposure = v) } },
                )
                YesNoChips(
                    label = "Do you live or work in crowded or tightly enclosed places " +
                        "for long periods?",
                    selected = record.crowdedLivingConditions,
                    onSelect = { v -> onFieldChange { it.copy(crowdedLivingConditions = v) } },
                )
            }

            // ------- 6 & 7: AI / physician-only, page 2 of the PDF -------
            ReadOnlySectionCard(number = "6.", title = "X-RAY AI Assessment") {
                AssessmentTable(secondColumnLabel = "AI Analysis Result")
            }
            ReadOnlySectionCard(number = "7.", title = "Result / Guidance") {
                RESULT_GUIDANCE_OPTIONS.forEach { option ->
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedSlate,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                ReadOnlyOptionRow("Referral completed", listOf("Yes", "No", "Pending"))
                BlankFieldRow("Name of referral facility")
                BlankFieldRow("Date referred")
            }

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Layout building blocks
// ---------------------------------------------------------------------------

@Composable
private fun ScreenHeader() {
    Column {
        Text("Medical Mission", style = MaterialTheme.typography.titleLarge)
        Text(
            "[AI-Powered Chest X-Ray] TB Screening",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedSlate,
        )
    }
}

/**
 * A form section: a tinted card with a colored accent bar down its start edge and a
 * header. The accent color is what separates the sections the tablet operator fills in
 * (teal) from the physician/AI-only ones (muted slate) at a glance.
 *
 * The bar is drawn behind the *content* column rather than via a modifier on the Card,
 * because a Card paints its own container color over anything drawn behind it.
 */
@Composable
private fun SectionCard(
    title: String,
    number: String? = null,
    accent: Color = ClinicalTeal,
    note: String? = null,
    collapsible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Collapsed by default only when collapsible; editable sections (collapsible = false)
    // are always expanded, unchanged from before this was added.
    var expanded by rememberSaveable { mutableStateOf(!collapsible) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceTint.copy(alpha = 0.55f),
            contentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(color = accent, size = Size(ACCENT_BAR_WIDTH.toPx(), size.height))
                }
                .padding(start = 16.dp + ACCENT_BAR_WIDTH, top = 14.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (collapsible) {
                    Modifier.fillMaxWidth().clickable(
                        onClickLabel = if (expanded) "Collapse" else "Expand",
                    ) { expanded = !expanded }
                } else {
                    Modifier
                },
            ) {
                if (number != null) {
                    // The PDF's own section numbering, kept literally so the screen and the
                    // paper form can be read side by side.
                    Text(
                        text = number,
                        style = MaterialTheme.typography.titleMedium,
                        color = ClayAmber,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    modifier = if (collapsible) Modifier.weight(1f) else Modifier,
                )
                if (collapsible) {
                    Text(
                        text = if (expanded) "▾" else "▸",
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                    )
                }
            }
            if (expanded) {
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(color = ClayAmber.copy(alpha = 0.35f))
                content()
            }
        }
    }
}

private val ACCENT_BAR_WIDTH = 4.dp

/**
 * A [SectionCard] for the four physician/AI-only sections. Display only, and collapsed by
 * default: their transcribed content adds real vertical length, and staff need to scroll
 * past them to reach the fields they actually fill in.
 *
 * `content` has the same `@Composable ColumnScope.() -> Unit` shape as [SectionCard]'s, so
 * the type system will not stop a future edit from pasting in an `OutlinedTextField` or
 * `OptionChips` call here. Don't: anything placed inside a [ReadOnlySectionCard] must stay
 * plain `Text` — no control wired to `onFieldChange` belongs in a physician/AI-only section.
 */
@Composable
private fun ReadOnlySectionCard(
    title: String,
    number: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) = SectionCard(
    title = title,
    number = number,
    accent = MutedSlate,
    note = OFF_TABLET_NOTE,
    collapsible = true,
    content = content,
)

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun CheckboxRow(label: String, checked: Boolean, onCheckedChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onCheckedChange() })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TextFieldRow(
    label: String,
    value: String?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
    )
}

/**
 * A text field whose content is rewritten on every keystroke by [mask] (inserting dashes,
 * dropping non-digits, truncating length). Needs [TextFieldValue] rather than plain
 * [TextFieldRow]'s `String`: feeding a reformatted string back through the `String`
 * overload leaves Compose to guess the new cursor position from a diff against the old
 * text, and it guesses wrong as soon as the reformat changes the string's length (e.g.
 * inserting a dash) — later keystrokes land mid-string and scramble already-typed digits.
 * Pinning the cursor to the end after every reformat is correct here because these masks
 * are strictly append-typed; nothing here supports editing in the middle.
 */
@Composable
private fun MaskedTextFieldRow(
    label: String,
    externalValue: String,
    mask: (String) -> String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Number,
) {
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(externalValue, TextRange(externalValue.length)))
    }
    // Re-sync when the record's value changed for a reason other than this field's own
    // edits (e.g. a different record was loaded) — mirrors NumericFieldRow's pattern.
    LaunchedEffect(externalValue) {
        if (externalValue != fieldValue.text) {
            fieldValue = TextFieldValue(externalValue, TextRange(externalValue.length))
        }
    }
    OutlinedTextField(
        value = fieldValue,
        onValueChange = { new ->
            val masked = mask(new.text)
            fieldValue = TextFieldValue(masked, TextRange(masked.length))
            onValueChange(masked)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------
// Numeric input
//
// The displayed text is local state, NOT record.field?.toString(). Deriving the display
// from the parsed value clobbers in-progress input: typing "1.5" passes through "1.",
// which parses to null, which would re-render as "" and eat the keystroke. Instead the
// buffer always shows exactly what was typed, and the parsed value (or null) is what
// gets persisted — an unparseable value simply persists as null, never blocking input.
// ---------------------------------------------------------------------------

private enum class GeoDialogStep { REGION, PROVINCE, CITY, BARANGAY }

@Composable
private fun NumericFieldRow(
    label: String,
    externalText: String,
    keyboardType: KeyboardType,
    /** Parse-then-restringify, so the buffer can be compared against the record's value. */
    normalize: (String) -> String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // rememberSaveable, not remember: an in-progress unparseable entry (e.g. "38.") must
    // survive Activity recreation (rotation, config change, low-memory recreation), or the
    // buffer resets to the last committed value and the next keystroke corrupts it.
    var text by rememberSaveable { mutableStateOf(externalText) }
    // Re-sync only when the record's value changed for a reason other than this field's
    // own edits — e.g. FormViewModel.load() finished and swapped in the stored record.
    LaunchedEffect(externalText) {
        if (externalText != normalize(text)) text = externalText
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            // Vital Signs fields must only ever hold digits and '.': filtering here (not
            // just relying on the Number/Decimal keyboard) also blocks paste and hardware
            // keyboard input.
            val filtered = filterVitalSignInput(it)
            text = filtered
            onTextChange(filtered)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth().onFocusChanged { focusState ->
            // Tidy up ambiguous partial input ("5." -> "5.0", ".5" -> "0.5", "173" ->
            // "173.0") once the user leaves the field — never while they're still typing,
            // or this fights the in-progress buffer the same way deriving display from
            // the parsed value always has (see the comment above this section).
            if (!focusState.isFocused) {
                val normalized = normalize(text)
                if (normalized != text) text = normalized
            }
        },
        enabled = enabled,
    )
}

@Composable
private fun IntFieldRow(
    label: String,
    value: Int?,
    onValueChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = NumericFieldRow(
    label = label,
    externalText = value?.toString().orEmpty(),
    keyboardType = KeyboardType.Number,
    normalize = { it.toIntOrNull()?.toString().orEmpty() },
    onTextChange = { onValueChange(it.toIntOrNull()) },
    modifier = modifier,
    enabled = enabled,
)

@Composable
private fun DoubleFieldRow(
    label: String,
    value: Double?,
    onValueChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = NumericFieldRow(
    label = label,
    externalText = value?.toString().orEmpty(),
    keyboardType = KeyboardType.Decimal,
    // Some locale keyboards emit ',' as the decimal separator; toDoubleOrNull only
    // accepts '.', so without this a value like "36,5" parses to null and is silently
    // dropped even though the field visibly shows a value.
    normalize = { it.toCanonicalDouble()?.toString().orEmpty() },
    onTextChange = { onValueChange(it.toCanonicalDouble()) },
    modifier = modifier,
    enabled = enabled,
)

private fun String.toCanonicalDouble(): Double? = replace(',', '.').toDoubleOrNull()

// ---------------------------------------------------------------------------
// Single-select input
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionChips(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option)) },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun YesNoUnknownChips(
    label: String,
    selected: YesNoUnknown?,
    onSelect: (YesNoUnknown) -> Unit,
) = OptionChips(
    label = label,
    options = YES_NO_UNKNOWN_OPTIONS,
    selected = selected,
    optionLabel = { it.label },
    onSelect = onSelect,
)

/** Plain two-state yes/no, for the `Boolean?` fields the PDF renders as a Yes/No pair. */
@Composable
private fun YesNoChips(label: String, selected: Boolean?, onSelect: (Boolean) -> Unit) =
    OptionChips(
        label = label,
        options = listOf(true, false),
        selected = selected,
        optionLabel = { if (it) "Yes" else "No" },
        onSelect = onSelect,
    )

// ---------------------------------------------------------------------------
// Read-only renderings of the physician/AI sections
// ---------------------------------------------------------------------------

/**
 * The PDF's two-column assessment table. Options are listed as plain separated text
 * rather than controls, so nothing here can be mistaken for something to tap.
 */
@Composable
private fun AssessmentTable(secondColumnLabel: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = "X-RAY Assessment Item",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MutedSlate,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = secondColumnLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MutedSlate,
                modifier = Modifier.weight(1.5f),
            )
        }
        HorizontalDivider(color = MutedSlate.copy(alpha = 0.4f))
        ASSESSMENT_ITEMS.forEach { assessment ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = assessment.item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedSlate,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = assessment.options.joinToString(" / "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedSlate,
                    modifier = Modifier.weight(1.5f),
                )
            }
        }
    }
}

/** "Label: option / option / option", read-only. */
@Composable
private fun ReadOnlyOptionRow(label: String, options: List<String>) {
    Text(
        text = "$label: ${options.joinToString(" / ")}",
        style = MaterialTheme.typography.bodyMedium,
        color = MutedSlate,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** "Label: ______", the paper form's blank-to-be-filled-in, drawn as a rule. */
@Composable
private fun BlankFieldRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedSlate,
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MutedSlate.copy(alpha = 0.4f),
        )
    }
}
