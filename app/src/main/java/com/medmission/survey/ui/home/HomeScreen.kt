package com.medmission.survey.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medmission.survey.data.model.SurveyRecord

@Composable
fun HomeScreen(
    records: List<SurveyRecord>,
    onNewSurvey: () -> Unit,
    onRecordClick: (String) -> Unit,
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNewSurvey, modifier = Modifier.padding(16.dp)) {
                Text("+ 새 설문")
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(records, key = { it.recordId }) { record ->
                    Card(
                        modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
                        onClick = { onRecordClick(record.recordId) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(record.no ?: record.recordId.take(8))
                            Text("${record.firstName.orEmpty()} ${record.lastName.orEmpty()}")
                            Text(record.status.name)
                        }
                    }
                }
            }
        }
    }
}
