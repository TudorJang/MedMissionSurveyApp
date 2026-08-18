package com.medmission.survey.ui.laptopselect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.medmission.survey.data.model.LaptopEndpoint
import com.medmission.survey.data.network.DiscoveredLaptop

@Composable
fun LaptopSelectScreen(
    savedEndpoints: List<LaptopEndpoint>,
    discoveredLaptops: List<DiscoveredLaptop>,
    onSelect: (String) -> Unit,
    onAddManual: (String, String, Int, String) -> Unit,
    onApiKeyChange: (String, String) -> Unit,
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { Text("Select a laptop to send to") }
                items(savedEndpoints, key = { it.id }) { endpoint ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(endpoint.name)
                            Text("${endpoint.host}:${endpoint.port}")
                            ApiKeyField(endpoint) { key -> onApiKeyChange(endpoint.id, key) }
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
                            Button(onClick = { onAddManual(laptop.name, laptop.host, laptop.port, "") }) {
                                Text("Add")
                            }
                        }
                    }
                }

                item { ManualEntrySection(onAddManual) }
            }
        }
    }
}

/**
 * Each bridge generates its own key on first run, so the key belongs to the laptop and
 * is entered here rather than built into the app. Blank keeps the key compiled into the
 * APK, which is what a site that builds its own APK relies on.
 */
@Composable
private fun ApiKeyField(endpoint: LaptopEndpoint, onSave: (String) -> Unit) {
    var key by remember(endpoint.id, endpoint.apiKey) { mutableStateOf(endpoint.apiKey) }
    val changed = key.trim() != endpoint.apiKey

    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("API key") },
        placeholder = { Text("shown on the laptop page") },
        supportingText = {
            Text(
                if (endpoint.apiKey.isBlank()) "Blank — using the key built into this app"
                else "Case-sensitive, exactly as shown on the laptop",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSave(key) }, enabled = changed) { Text("Save key") }
}

/**
 * Fallback for when NSD discovery never finds anything — a network without mDNS, a laptop
 * on a different subnet, or a router that blocks it. Search itself has no timeout (it's
 * meant to keep listening in case the bridge starts up late), so this is the only way
 * forward if it simply never succeeds.
 */
@Composable
private fun ManualEntrySection(onAddManual: (String, String, Int, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    val port = portText.toIntOrNull()

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Add a laptop manually")
        Text(
            "If the laptop isn't found automatically, enter its address directly.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host / IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key (optional)") },
            placeholder = { Text("shown on the laptop page") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val resolvedPort = port ?: return@Button
                onAddManual(name.ifBlank { host }, host, resolvedPort, apiKey)
                name = ""
                host = ""
                portText = ""
                apiKey = ""
            },
            enabled = host.isNotBlank() && port != null && port in 1..65535,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add")
        }
    }
}
