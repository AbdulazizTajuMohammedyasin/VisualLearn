package com.visuallearn.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.visuallearn.app.api.ApiException
import com.visuallearn.app.api.Backend
import com.visuallearn.app.api.VisualLearnApi
import com.visuallearn.app.auth.GoogleSignIn
import kotlinx.coroutines.launch

/** Sign in with Google, or with a 6-digit code sent by email. */
@Composable
fun SignInScreen(api: VisualLearnApi) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var emailMode by rememberSaveable { mutableStateOf(false) }
    var codeSent by rememberSaveable { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            runCatching { block() }.onFailure {
                error = (it as? ApiException)?.error?.message ?: "Couldn't reach VisualLearn. Check your connection."
            }
            busy = false
        }
    }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Icon(Icons.Filled.Home, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(16.dp))
            Text("VisualLearn", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Ask anything. When an idea needs seeing, VisualLearn builds a working figure of it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            if (!emailMode) {
                Button(
                    onClick = {
                        run {
                            when {
                                GoogleSignIn.isConfigured -> when (val result = GoogleSignIn.idToken(context)) {
                                    is GoogleSignIn.Result.Token -> api.signInWithGoogle(result.idToken)
                                    is GoogleSignIn.Result.Failed -> error = result.message
                                    GoogleSignIn.Result.Cancelled -> Unit
                                }
                                Backend.usesFake -> api.signInWithGoogle("fake-google-token")
                                else -> error = "Google sign-in isn't set up in this build. Use email instead."
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Continue with Google") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { emailMode = true; error = null }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue with email")
                }
            } else if (!codeSent) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { run { api.startEmailSignIn(email); codeSent = true } },
                    enabled = !busy && email.contains('@'),
                    modifier = Modifier.fillMaxWidth(),
                ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Send code") }
                TextButton(onClick = { emailMode = false; error = null }) { Text("Back") }
            } else {
                Text("We sent a 6-digit code to $email.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { run { api.signInWithEmail(email, code) } },
                    enabled = !busy && code.length == 6,
                    modifier = Modifier.fillMaxWidth(),
                ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in") }
                Row {
                    TextButton(onClick = { run { api.startEmailSignIn(email) } }, enabled = !busy) { Text("Send a new code") }
                    TextButton(onClick = { codeSent = false; code = ""; error = null }) { Text("Change email") }
                }
            }

            if (Backend.usesFake) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Development build: a built-in fake server answers. Any Google account works; the email code is 123456.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}
