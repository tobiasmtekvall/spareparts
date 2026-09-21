package se.spareparts.inventory.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.spareparts.inventory.AppContainer
import se.spareparts.inventory.data.ApiException
import se.spareparts.inventory.data.Repository
import kotlin.coroutines.cancellation.CancellationException

const val MIN_PASSWORD = 8

/**
 * "Choose a password". Shown as a wall right after sign-in when the account is flagged
 * `must_change`, and reachable from Settings at any other time.
 */
@Composable
fun ChangePasswordScreen(
    c: AppContainer,
    forced: Boolean,
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val session by c.session.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var current by rememberSaveable { mutableStateOf("") }
    var next by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var done by rememberSaveable { mutableStateOf(false) }

    val tooShort = next.isNotEmpty() && next.length < MIN_PASSWORD
    val mismatch = repeat.isNotEmpty() && repeat != next
    val canSubmit = !busy && current.isNotEmpty() && next.length >= MIN_PASSWORD && repeat == next

    fun submit() {
        if (!canSubmit) return
        keyboard?.hide()
        error = null
        busy = true
        val username = c.session.user?.username.orEmpty()
        val newPassword = next
        scope.launch {
            try {
                c.repository.api.changePassword(current, newPassword)
                // A new password ends every session on the server, including this token, so pick up a
                // fresh one straight away instead of bouncing the person back to the sign-in screen.
                val fresh = runCatching { c.repository.api.login(username, newPassword) }.getOrNull()
                if (fresh != null && fresh.token.isNotBlank()) {
                    c.session.signIn(fresh.toSession(device = false))
                    c.repository.sync()
                } else {
                    c.session.signOut("Password changed - please sign in again")
                }
                done = true
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == 401) c.session.onApiError(e)
                error = e.message ?: "Could not change the password (${e.code})"
            } catch (e: Exception) {
                error = Repository.describe(e)
            } finally {
                busy = false
                current = ""; next = ""; repeat = ""
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Choose a password", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            Text(
                if (forced) "Signed in as ${session?.user?.displayName.orEmpty()}. " +
                    "Your password was set by an administrator, so please pick your own before you continue."
                else "Signed in as ${session?.user?.displayName.orEmpty()}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            error?.let { Banner(it) }
            if (done && !forced) Banner("Password changed.", warning = false)

            OutlinedTextField(
                value = current, onValueChange = { current = it; error = null },
                label = { Text("Current password") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = next, onValueChange = { next = it; error = null },
                label = { Text("New password") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), isError = tooShort,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(if (tooShort) "At least $MIN_PASSWORD characters" else "At least $MIN_PASSWORD characters.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = repeat, onValueChange = { repeat = it; error = null },
                label = { Text("Repeat new password") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), isError = mismatch,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { if (mismatch) Text("The two passwords are not the same") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )

            Button(onClick = { submit() }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Save password")
            }

            if (forced) {
                TextButton(onClick = { scope.launch { c.session.signOut() } },
                    modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Sign out instead") }
            } else if (onCancel != null) {
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
