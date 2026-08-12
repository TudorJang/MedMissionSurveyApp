package com.medmission.survey.ui.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.SurveyRecord

/**
 * Sections of the paper form that a physician or the X-ray AI fills in after the tablet
 * stage. Display-only markers so staff know they exist; deliberately not backed by
 * SurveyRecord and never sent to the bridge. See design spec 5.3.
 */
private data class InfoSection(val title: String, val note: String)

private val PHYSICIAN_ONLY_SECTIONS = listOf(
    InfoSection("Diagnosis", "Completed by physician after X-ray"),
    InfoSection("Treatment and Medication", "Completed by physician after X-ray"),
    InfoSection("X-RAY AI Assessment", "Completed automatically by AI"),
    InfoSection("Result / Guidance", "Completed by physician after X-ray"),
)

@Composable
fun FormScreen(
    record: SurveyRecord,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onToggleMedicalHistory: (MedicalHistoryItem) -> Unit,
    onToggleSymptom: (Symptom) -> Unit,
    onDone: () -> Unit,
) {
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxWidth().padding(padding).padding(16.dp)) {
            item { Text("Patient Information") }
            item {
                OutlinedTextField(
                    value = record.firstName.orEmpty(),
                    onValueChange = onFirstNameChange,
                    label = { Text("First Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = record.lastName.orEmpty(),
                    onValueChange = onLastNameChange,
                    label = { Text("Last Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Text("Medical History") }
            items(MedicalHistoryItem.values().toList()) { item ->
                Row {
                    Checkbox(
                        checked = item in record.medicalHistory,
                        onCheckedChange = { onToggleMedicalHistory(item) },
                    )
                    Text(item.label)
                }
            }

            item { Text("Current Symptoms") }
            items(Symptom.values().toList()) { symptom ->
                Row {
                    Checkbox(
                        checked = symptom in record.symptoms,
                        onCheckedChange = { onToggleSymptom(symptom) },
                    )
                    Text(symptom.label)
                }
            }

            // Physician/AI-only PDF sections. Shown so tablet staff know these parts
            // exist and are completed later, off-device. Display only: not editable,
            // not in SurveyRecord, not in the network payload.
            item {
                HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 12.dp))
                Text(
                    "The sections below are not entered on this tablet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(PHYSICIAN_ONLY_SECTIONS) { section ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleSmall)
                        Text(section.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                androidx.compose.material3.Button(onClick = onDone) { Text("Done") }
            }
        }
    }
}
