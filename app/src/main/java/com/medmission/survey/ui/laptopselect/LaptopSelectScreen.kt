package com.medmission.survey.ui.laptopselect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.medmission.survey.data.network.DiscoveredLaptop

@Composable
fun LaptopSelectScreen(
    savedEndpoints: List<LaptopEndpoint>,
    discoveredLaptops: List<DiscoveredLaptop>,
    onSelect: (String) -> Unit,
    onAddManual: (String, String, Int) -> Unit,
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { Text("Select a laptop to send to") }
                items(savedEndpoints, key = { it.id }) { endpoint ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(endpoint.name)
                            Text("${endpoint.host}:${endpoint.port}")
                            Button(onClick = { onSelect(endpoint.id) }) { Text("Send") }
                        }
                    }
                }

                item { Text("Discovered Laptops") }
                if (discoveredLaptops.isEmpty()) {
                    item { Text("Searching...") }
                }
                items(discoveredLaptops, key = { "${it.host}:${it.port}" }) { laptop ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(laptop.name)
                            Text("${laptop.host}:${laptop.port}")
                            Button(onClick = { onAddManual(laptop.name, laptop.host, laptop.port) }) {
                                Text("Add")
                            }
                        }
                    }
                }
            }
        }
    }
}
