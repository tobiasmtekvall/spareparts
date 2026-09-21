package se.spareparts.inventory.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.spareparts.inventory.AppContainer
import se.spareparts.inventory.BuildConfig
import se.spareparts.inventory.data.ApiClient
import se.spareparts.inventory.data.ApiException
import se.spareparts.inventory.data.Repository
import se.spareparts.inventory.data.ServerConfig
import se.spareparts.inventory.data.Session
import kotlin.coroutines.cancellation.CancellationException

/** Trims the address and adds `http://` when no scheme was typed (LAN servers rarely have TLS). */
fun normalizeUrl(raw: String): String {
    var u = raw.trim().trimEnd('/')
    if (u.isEmpty()) return u
    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
    return u
}

/**
 * Shown whenever there is no valid token: sign in with a personal account, or – behind the
 * "Use a device token instead" link – with the shared token of a scanner phone.
 */
@Composable
fun SignInScreen(c: AppContainer) {
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val notice by c.session.notice.collectAsStateWithLifecycle()
    val pending by c.repository.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var url by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var useToken by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun finish(session: Session) = scope.launch {
        c.settings.setBaseUrl(normalizeUrl(url))
        c.session.signIn(session)
        // Signing in flushes whatever was queued while nobody was signed in.
        c.repository.sync(full = true)
    }

    fun signIn() {
        if (busy) return
        keyboard?.hide()
        error = null
        busy = true
        scope.launch {
            val address = normalizeUrl(url)
            val api = ApiClient({ ServerConfig(address, if (useToken) token.trim() else "") })
            try {
                if (useToken) {
                    // A device token has no login call – ask the server who it belongs to.
                    val me = api.me()
                    finish(Session(token = token.trim(), expires = 0, user = me.user, device = true))
                } else {
                    val r = api.login(username, password)
                    password = ""
                    finish(r.toSession(device = false))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                error = e.message ?: "Sign-in failed (${e.code})"
            } catch (e: Exception) {
                error = Repository.describe(e) + " – check the address and that you are on the same network."
            } finally {
                busy = false
            }
        }
    }

    val canSubmit = url.isNotBlank() && !busy &&
        if (useToken) token.isNotBlank() else username.isNotBlank() && password.isNotBlank()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Spare Parts", style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold))
            Text("Sign in with your own account. Every stock change is saved under your name.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            notice?.let { Banner(it) }
            if (pending.isNotEmpty()) {
                Banner("${pending.size} stock change(s) are waiting to be sent. They are kept and will " +
                    "go out as soon as you sign in.", warning = false)
            }

            OutlinedTextField(
                value = url, onValueChange = { url = it; error = null },
                label = { Text("Server address") }, placeholder = { Text("http://192.168.1.20:8765") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                supportingText = { Text("Shown in the server console as “Network”.") },
            )

            if (useToken) {
                OutlinedTextField(
                    value = token, onValueChange = { token = it; error = null },
                    label = { Text("Device token") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { signIn() }),
                    supportingText = { Text("From the web app: Accounts → Device token. For a shared scanner phone.") },
                )
            } else {
                OutlinedTextField(
                    value = username, onValueChange = { username = it; error = null },
                    label = { Text("Username") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Person, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it; error = null },
                    label = { Text("Password") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { signIn() }),
                )
            }

            error?.let { Banner(it) }

            Button(onClick = { signIn() }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (useToken) "Connect" else "Sign in")
            }

            TextButton(onClick = { useToken = !useToken; error = null }, enabled = !busy,
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (useToken) "Sign in with a username instead" else "Use a device token instead")
            }

            Text("Spare Parts ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
internal fun Banner(text: String, warning: Boolean = true) {
    val bg = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Card(colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (warning) {
                Icon(Icons.Filled.Warning, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Role chip used on the settings screen. */
@Composable
fun RoleChip(label: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}
