package se.spareparts.inventory.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.spareparts.inventory.AppContainer
import se.spareparts.inventory.BuildConfig
import se.spareparts.inventory.data.AppSettings
import se.spareparts.inventory.ui.auth.RoleChip
import se.spareparts.inventory.ui.theme.AppIcons
import se.spareparts.inventory.ui.theme.CodeStyle

@Composable
fun SettingsScreen(c: AppContainer, changePassword: () -> Unit) {
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val session by c.session.session.collectAsStateWithLifecycle()
    val repo = c.repository
    val status by repo.status.collectAsStateWithLifecycle()
    val pending by repo.pending.collectAsStateWithLifecycle()
    val parts by repo.parts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    // Tick so "last synced x min ago" stays fresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val user = session?.user

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Signed in", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(user?.displayName ?: "–", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (session?.device == true) "Shared device token"
                            else user?.username.orEmpty().ifBlank { "–" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RoleChip(user?.roleLabel ?: "–")
                }
                Text(user?.roleText.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (session?.device != true) {
                        OutlinedButton(onClick = changePassword) { Text("Change password") }
                    }
                    TextButton(onClick = {
                        if (pending.isNotEmpty()) confirmSignOut = true
                        else scope.launch { c.session.signOut() }
                    }) { Text("Sign out") }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                Text(settings.baseUrl.ifBlank { "Not set" }, style = CodeStyle, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text("To use a different server, sign out and sign in there.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sync", style = MaterialTheme.typography.titleMedium)
                Info("Last sync", if (settings.lastSync > 0)
                    DateUtils.getRelativeTimeSpanString(settings.lastSync, now, DateUtils.MINUTE_IN_MILLIS).toString() +
                        " · " + DateUtils.formatDateTime(null, settings.lastSync, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE)
                else "Never")
                Info("Cached parts", parts.size.toString())
                Info("Data version", settings.version.takeIf { it > 0 }?.toString() ?: "–")
                Info("Pending changes", pending.size.toString())
                status.error?.let { StatusLine(false, it) }
                if (status.error == null && status.reachable == true) StatusLine(true, "Server reachable")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { scope.launch { repo.sync(full = true) } },
                        enabled = settings.configured && !status.syncing) {
                        Icon(AppIcons.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sync now")
                    }
                    if (status.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    if (pending.isNotEmpty()) {
                        TextButton(onClick = { confirmDiscard = true }) { Text("Discard pending") }
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                val options = listOf(AppSettings.THEME_DARK to "Dark", AppSettings.THEME_SYSTEM to "System", AppSettings.THEME_LIGHT to "Light")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = settings.theme == value,
                            onClick = { scope.launch { c.settings.setTheme(value) } },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
            }
        }

        Text("Spare Parts ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard ${pending.size} pending change(s)?") },
            text = { Text("These stock changes were never sent to the server and will be lost.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; scope.launch { repo.discardPending() } }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out with ${pending.size} change(s) waiting?") },
            text = {
                Text("These stock changes have not reached the server yet. They are kept on this phone " +
                    "and will be sent after the next sign-in – but only somebody who may book stock can send them.")
            },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; scope.launch { c.session.signOut() } }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Stay signed in") } },
        )
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value)
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
