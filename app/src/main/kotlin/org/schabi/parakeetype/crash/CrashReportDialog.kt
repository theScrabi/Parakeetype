package org.schabi.parakeetype.crash

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.parakeetype.R
import org.schabi.parakeetype.ui.theme.ParakeetypeTheme

/**
 * Offered after a crash: share the pending [CrashReporter] report via the share sheet, or
 * dismiss it. Either choice discards the pending report.
 */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    CrashReportDialogContent(
        onShare = {
            scope.launch {
                val intent = withContext(Dispatchers.IO) { CrashReporter.shareIntent(context) }
                // The share sheet reads the cache copy, so the pending report can go right away.
                if (intent != null) context.startActivity(intent)
                CrashReporter.discard(context)
            }
        },
        onDismiss = { CrashReporter.discard(context) },
    )
}

@Composable
private fun CrashReportDialogContent(
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_dialog_title)) },
        text = { Text(stringResource(R.string.crash_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onShare) { Text(stringResource(R.string.crash_action_share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.crash_action_dismiss)) }
        },
    )
}

@Preview(showBackground = true, name = "Crash report dialog")
@Composable
private fun CrashReportDialogPreview() {
    ParakeetypeTheme {
        CrashReportDialogContent(onShare = {}, onDismiss = {})
    }
}
