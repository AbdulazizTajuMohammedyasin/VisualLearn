package com.visuallearn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
        ) {
            Spacer(Modifier.height(24.dp))
            // The drafting grid figures are drawn on, waiting for the first one.
            IsoGrid(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(24.dp)).background(Color(0xFF121214)),
                moving = true,
            )
            Spacer(Modifier.height(32.dp))
            TwoLineHeading("See how things work.", "Ask, and watch it get built.")
            Spacer(Modifier.height(12.dp))
            Text(
                "VisualLearn answers your questions and, when an idea needs seeing, builds a moving figure of it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text("We sent a 6-digit code to $email.")
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
                )
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
