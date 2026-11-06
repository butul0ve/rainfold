package com.rainfold.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.window.DialogProperties
import com.rainfold.app.R
import com.rainfold.app.localization.AppLanguage

@Composable
fun LanguageDialog(
    currentLanguage: AppLanguage,
    required: Boolean,
    onDismiss: () -> Unit,
    onSelect: (AppLanguage) -> Unit,
) {
    var selectedTag by rememberSaveable { mutableStateOf(currentLanguage.tag) }
    AlertDialog(
        onDismissRequest = { if (!required) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !required, dismissOnClickOutside = !required),
        title = { Text(stringResource(R.string.choose_language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.language_description), modifier = Modifier.padding(bottom = RainfoldTokens.size16))
                Column(Modifier.selectableGroup()) {
                    AppLanguage.entries.forEach { language ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = RainfoldTokens.size56)
                                .selectable(selected = selectedTag == language.tag,
                                    onClick = { selectedTag = language.tag }, role = Role.RadioButton)
                                .padding(vertical = RainfoldTokens.size8),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(RainfoldTokens.size16),
                        ) {
                            Text(language.flag, style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.clearAndSetSemantics {})
                            Text(language.nativeName, modifier = Modifier.weight(1f))
                            RadioButton(selected = selectedTag == language.tag, onClick = null)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(AppLanguage.entries.first { it.tag == selectedTag }) }) {
                Text(stringResource(R.string.language_confirm))
            }
        },
        dismissButton = {
            if (!required) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}