package com.medmission.survey.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medmission.survey.BuildConfig
import com.medmission.survey.data.model.SurveyRecord
import com.medmission.survey.data.model.SyncStatus
import com.medmission.survey.ui.theme.DraftGrey
import com.medmission.survey.ui.theme.FailedRed
import com.medmission.survey.ui.theme.PendingAmber
import com.medmission.survey.ui.theme.SentGreen

/** At-a-glance sync signal. Colors come from the theme so the hex values live in one place. */
private fun statusColor(status: SyncStatus): Color = when (status) {
    SyncStatus.DRAFT -> DraftGrey
    SyncStatus.PENDING -> PendingAmber
    SyncStatus.SENT -> SentGreen
    SyncStatus.FAILED -> FailedRed
}

/** The bridge's worklist words, in the registration desk's words. */
private fun xrayLabel(status: String): String = when (status) {
    "Received" -> "Waiting for X-ray"
    "InProgress" -> "In the X-ray room"
    "Completed" -> "X-ray done"
    "Cancelled" -> "Cancelled at the laptop"
    else -> status
}

@Composable
fun HomeScreen(
    records: List<SurveyRecord>,
    onNewSurvey: () -> Unit,
    onRecordClick: (String) -> Unit,
    onResend: (String) -> Unit,
    xrayStatuses: Map<String, String> = emptyMap(),
    onSettings: () -> Unit = {},
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onNewSurvey) {
                    Text("+ New Survey")
                }
                // Which build this tablet runs — the answer to "is this one updated?"
                // asked across a table at a screening site.
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    TextButton(onClick = onSettings) { Text("Settings") }
                }
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
                            Text(
                                text = record.status.name,
                                color = statusColor(record.status),
                                fontWeight = FontWeight.Bold,
                            )
                            // The retry worker deliberately gives up on a rejected key,
                            // so a failed record only moves again when the operator asks
                            // for it — after fixing the key on the laptop page.
                            if (record.status == SyncStatus.FAILED) {
                                Button(onClick = { onResend(record.recordId) }) {
                                    Text("Send again")
                                }
                            }
                            // What the X-ray side did with it, fetched from the laptop.
                            // Only for SENT records — everything else hasn't left yet.
                            if (record.status == SyncStatus.SENT) {
                                xrayStatuses[record.recordId]?.let { status ->
                                    Text(
                                        text = xrayLabel(status),
                                        color = if (status == "Completed") SentGreen else PendingAmber,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
