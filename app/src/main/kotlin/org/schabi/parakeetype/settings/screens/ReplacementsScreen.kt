package org.schabi.parakeetype.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.schabi.parakeetype.R
import org.schabi.parakeetype.inference.TextReplacement
import org.schabi.parakeetype.settings.preferences.PreferencesViewModel
import org.schabi.parakeetype.ui.theme.ParakeetypeTheme

/**
 * The user's lookup table of word / phrase replacements: a list of rules, each opened in a
 * dialog to edit, and a button to add one. Backed by [PreferencesViewModel] / DataStore, so the
 * table is backed up with the other settings.
 */
@Composable
fun ReplacementsScreen(
    viewModel: PreferencesViewModel = viewModel(),
) {
    val rules by viewModel.textReplacements.collectAsState()
    ReplacementsContent(
        rules = rules,
        onSave = viewModel::saveTextReplacement,
        onRemove = viewModel::removeTextReplacement,
    )
}

/** The rule being edited: its position in the table, or `null` for a new one. */
private data class EditTarget(val index: Int?, val rule: TextReplacement)

@Composable
private fun ReplacementsContent(
    rules: List<TextReplacement>,
    onSave: (rule: TextReplacement, index: Int?) -> Unit,
    onRemove: (index: Int) -> Unit,
) {
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.replacements_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = { editing = EditTarget(null, TextReplacement("", "")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.replacements_add))
        }

        if (rules.isEmpty()) {
            Text(
                text = stringResource(R.string.replacements_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        rules.forEachIndexed { index, rule ->
            ReplacementRow(
                rule = rule,
                onClick = { editing = EditTarget(index, rule) },
                onRemove = { onRemove(index) },
            )
        }
    }

    editing?.let { target ->
        ReplacementDialog(
            initial = target.rule,
            isNew = target.index == null,
            onSave = { rule ->
                onSave(rule, target.index)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ReplacementRow(
    rule: TextReplacement,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rule.from,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(18.dp),
            )
            if (rule.to.isEmpty()) {
                Text(
                    text = stringResource(R.string.replacements_removed),
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    text = rule.to,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.cd_delete_replacement),
                )
            }
        }
    }
}

@Composable
private fun ReplacementDialog(
    initial: TextReplacement,
    isNew: Boolean,
    onSave: (TextReplacement) -> Unit,
    onDismiss: () -> Unit,
) {
    var from by rememberSaveable { mutableStateOf(initial.from) }
    var to by rememberSaveable { mutableStateOf(initial.to) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) stringResource(R.string.replacements_add)
                else stringResource(R.string.replacements_edit)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it },
                    label = { Text(stringResource(R.string.replacements_from_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    label = { Text(stringResource(R.string.replacements_to_label)) },
                    supportingText = { Text(stringResource(R.string.replacements_to_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(TextReplacement(from.trim(), to.trim())) },
                enabled = from.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Preview(showBackground = true, name = "Replacements · Empty")
@Composable
private fun ReplacementsEmptyPreview() {
    ParakeetypeTheme {
        ReplacementsContent(rules = emptyList(), onSave = { _, _ -> }, onRemove = {})
    }
}

@Preview(showBackground = true, name = "Replacements · With rules")
@Composable
private fun ReplacementsWithRulesPreview() {
    ParakeetypeTheme {
        ReplacementsContent(
            rules = listOf(
                TextReplacement("parakeet type", "Parakeetype"),
                TextReplacement("Komma", ","),
                TextReplacement("also", ""),
            ),
            onSave = { _, _ -> },
            onRemove = {},
        )
    }
}

@Preview(showBackground = true, name = "Replacement Dialog")
@Composable
private fun ReplacementDialogPreview() {
    ParakeetypeTheme {
        ReplacementDialog(
            initial = TextReplacement("parakeet type", "Parakeetype"),
            isNew = false,
            onSave = {},
            onDismiss = {},
        )
    }
}
