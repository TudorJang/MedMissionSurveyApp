package com.medmission.survey.ui.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medmission.survey.data.model.MedicalHistoryItem
import com.medmission.survey.data.model.Symptom
import com.medmission.survey.data.model.SurveyRecord

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
            item { Text("환자 정보") }
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

            item { Text("병력") }
            items(MedicalHistoryItem.values().toList()) { item ->
                Row {
                    Checkbox(
                        checked = item in record.medicalHistory,
                        onCheckedChange = { onToggleMedicalHistory(item) },
                    )
                    Text(item.label)
                }
            }

            item { Text("현재 증상") }
            items(Symptom.values().toList()) { symptom ->
                Row {
                    Checkbox(
                        checked = symptom in record.symptoms,
                        onCheckedChange = { onToggleSymptom(symptom) },
                    )
                    Text(symptom.label)
                }
            }

            item {
                androidx.compose.material3.Button(onClick = onDone) { Text("완료") }
            }
        }
    }
}
