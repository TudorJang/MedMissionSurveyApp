package com.medmission.survey.ui.laptopselect

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
import com.medmission.survey.data.model.LaptopEndpoint

@Composable
fun LaptopSelectScreen(
    savedEndpoints: List<LaptopEndpoint>,
    onSelect: (String) -> Unit,
    onAddManual: () -> Unit,
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("전송할 랩톱을 선택하세요")
            LazyColumn(Modifier.fillMaxSize()) {
                items(savedEndpoints, key = { it.id }) { endpoint ->
                    Card(modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(endpoint.name)
                            Text("${endpoint.host}:${endpoint.port}")
                            Button(onClick = { onSelect(endpoint.id) }) { Text("전송") }
                        }
                    }
                }
            }
            Button(onClick = onAddManual) { Text("수동 추가") }
        }
    }
}
