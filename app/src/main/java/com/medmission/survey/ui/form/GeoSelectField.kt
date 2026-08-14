package com.medmission.survey.ui.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * A field backed by a fixed [options] list, chosen through a tap-to-open searchable dialog
 * rather than typed. If [value] is non-null but not present in [options], the field renders
 * as a plain free-text field instead — this is how "Not listed" (picked once, in the dialog)
 * stays represented: as data, not as a separate flag to keep in sync.
 *
 * Dialog visibility is hoisted to the caller ([isDialogOpen]/[onOpenDialog]/[onDismissDialog])
 * rather than held locally, so a parent composable holding several of these fields can chain
 * them — closing one field's dialog and opening the next's on selection (see `FormScreen`'s
 * Patient Information section for the auto-advance chain).
 */
@Composable
fun GeoSelectField(
    label: String,
    value: String?,
    options: List<String>,
    isDialogOpen: Boolean,
    onOpenDialog: () -> Unit,
    onDismissDialog: () -> Unit,
    onSelect: (String) -> Unit,
    onFreeText: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // "Not listed" sets the value to "" to enter free-text mode immediately (a blank editable
    // field the user can type into) — so free-text mode is keyed on non-null, not non-blank.
    // Getting back to picker mode is a separate affordance (the trailing icon below), not a
    // side effect of clearing the text, so blanking the field doesn't strand the user either
    // way: they can keep typing, or tap the icon to reopen the picker.
    val isFreeText = value != null && value !in options
    if (isFreeText) {
        OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = onFreeText,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = onOpenDialog, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Choose from list")
                }
            },
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
        )
    } else {
        // BasicTextField installs its own pointer-input handling for focus/cursor placement
        // even when readOnly, and that child-first handling can consume a tap before a
        // `clickable` modifier on the field itself ever sees it — making the field
        // intermittently dead to taps. Wrapping the (disabled, so gesture-free) field in a
        // `clickable` Box sidesteps that: the Box's own gesture detector handles the tap
        // directly, and disabled colors are overridden below so a supposedly-active field
        // doesn't render as greyed-out.
        Box(
            modifier = modifier.fillMaxWidth().let {
                if (enabled) it.clickable(onClick = onOpenDialog) else it
            },
        ) {
            OutlinedTextField(
                value = value.orEmpty(),
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text(label) },
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // Rendered regardless of isFreeText: the trailing icon above reopens this same dialog from
    // free-text mode, and picking a real option there is how the user gets back to picker mode.
    if (isDialogOpen) {
        GeoSelectDialog(
            title = label,
            options = options,
            onDismiss = onDismissDialog,
            onSelect = onSelect,
            onNotListed = { onFreeText("") },
        )
    }
}

@Composable
private fun GeoSelectDialog(
    title: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onNotListed: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, options) {
        if (query.isBlank()) options else options.filter { it.contains(query, ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                LazyColumn {
                    items(filtered) { option ->
                        Text(
                            text = option,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) }
                                .padding(vertical = 12.dp),
                        )
                    }
                    item {
                        Text(
                            text = "Not listed",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNotListed() }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
