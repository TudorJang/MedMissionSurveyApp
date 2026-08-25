package com.medmission.survey.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medmission.survey.data.settings.FormMode
import com.medmission.survey.ui.form.GeoSelectField
import com.medmission.survey.ui.form.OptionChips
import com.medmission.survey.util.Countries

/**
 * The two choices that belong to the tablet rather than to a patient: which address
 * form to ask, and — for the global form — which country's addresses and phone numbers
 * are being collected. Both are set once when a tablet is prepared for a site.
 */
@Composable
fun SettingsScreen(
    formMode: FormMode,
    countryCode: String,
    onFormModeChange: (FormMode) -> Unit,
    onCountryChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    var countryDialogOpen by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)

            OptionChips(
                label = "Address form",
                options = FormMode.entries.toList(),
                selected = formMode,
                optionLabel = {
                    when (it) {
                        FormMode.PHILIPPINES -> "Philippines"
                        FormMode.GLOBAL -> "Global"
                    }
                },
                onSelect = onFormModeChange,
            )
            Text(
                when (formMode) {
                    FormMode.PHILIPPINES ->
                        "Region, province, city and barangay are picked from the Philippine " +
                            "address list, and the ZIP fills itself in."
                    FormMode.GLOBAL ->
                        "Address, City, State/Province and ZIP are typed in. Phone numbers " +
                            "are formatted for the country below."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            if (formMode == FormMode.GLOBAL) {
                GeoSelectField(
                    label = "Country",
                    value = Countries.nameOf(countryCode),
                    options = Countries.all.map { it.second },
                    isDialogOpen = countryDialogOpen,
                    onOpenDialog = { countryDialogOpen = true },
                    onDismissDialog = { countryDialogOpen = false },
                    onSelect = { picked ->
                        Countries.codeOf(picked)?.let(onCountryChange)
                        countryDialogOpen = false
                    },
                    onFreeText = { countryDialogOpen = false },
                )
                Text(
                    "Asked once per tablet, not once per patient: a screening site sees " +
                        "everybody from the same country.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Button(onClick = onDone) { Text("Done") }
        }
    }
}
