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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.spareparts.inventory.AppContainer
import se.spareparts.inventory.BuildConfig
import se.spareparts.inventory.data.ApiClient
import se.spareparts.inventory.data.AppSettings
import se.spareparts.inventory.data.Repository
import se.spareparts.inventory.data.ServerConfig
import se.spareparts.inventory.ui.theme.AppIcons
import kotlin.coroutines.cancellation.CancellationException

private sealed interface TestResult {
    data object Running : TestResult
    data class Ok(val text: String) : TestResult
    data class Failed(val text: String) : TestResult
}

@Composable
fun SettingsScreen(c: AppContainer) {
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val repo = c.repository
    val status by repo.status.collectAsStateWithLifecycle()
    val pending by repo.pending.collectAsStateWithLifecycle()
    val parts by repo.parts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var key by rememberSaveable { mutableStateOf(settings.apiKey) }
    var user by rememberSaveable { mutableStateOf(settings.user) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var test by androidx.compose.runtime.remember { mutableStateOf<TestResult?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val dirty = url.trim() != settings.baseUrl || key.trim() != settings.apiKey || user.trim() != settings.user

    // Tick so "last synced x min ago" stays fresh.
    var now by androidx.compose.runtime.remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    fun save(thenSync: Boolean) = scope.launch {
        c.settings.saveServer(normalizeUrl(url), key, user)
        url = normalizeUrl(url)
        if (thenSync) repo.sync(full = true)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = url, onValueChange = { url = it; test = null },
                    label = { Text("Server URL") }, placeholder = { Text("http://192.168.1.20:8765") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    supportingText = { Text("Shown in the server console as “Network”.") },
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it; test = null },
                    label = { Text("API key (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Person, null) },
                    supportingText = { Text("Recorded with every stock change.") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            test = TestResult.Running
                            scope.launch { test = testConnection(ServerConfig(normalizeUrl(url), key.trim(), user.trim())) }
                        },
                        enabled = url.isNotBlank() && test != TestResult.Running,
                    ) { Text("Test connection") }
                    Button(onClick = { save(thenSync = true) }, enabled = dirty && url.isNotBlank()) { Text("Save") }
                }
                when (val t = test) {
                    TestResult.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Connecting…")
                    }
                    is TestResult.Ok -> StatusLine(true, t.text)
                    is TestResult.Failed -> StatusLine(false, t.text)
                    null -> Unit
                }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { scope.launch { repo.sync(full = true) } }, enabled = settings.configured && !status.syncing) {
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

internal fun normalizeUrl(raw: String): String {
    var u = raw.trim().trimEnd('/')
    if (u.isEmpty()) return u
    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
    return u
}

private suspend fun testConnection(cfg: ServerConfig): TestResult {
    val api = ApiClient({ cfg })
    return try {
        val t0 = System.nanoTime()
        val ping = api.ping()
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (!ping.ok) return TestResult.Failed("Server answered but reported a problem")
        // /api/ping is open; check the key against a protected endpoint.
        if (ping.auth) {
            if (cfg.apiKey.isBlank()) return TestResult.Failed("Server requires an API key")
            api.parts(since = ping.version)
        }
        TestResult.Ok("Connected in $ms ms · data version ${ping.version}" + if (ping.auth) " · key accepted" else "")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TestResult.Failed(Repository.describe(e))
    }
}

