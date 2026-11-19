package com.aird.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aird.ServerStore
import com.aird.designsystem.components.AirdLoading
import com.aird.designsystem.components.Avatar
import com.aird.designsystem.theme.LocalAirdColors
import com.aird.designsystem.theme.Spacing
import com.aird.discovery.DiscoveryManager
import com.aird.security.HostPolicy
import com.aird.session.AirdHttp
import com.aird.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val LOGIN_FIT = """
(function(){
  var head = document.head || document.documentElement;
  var meta = document.querySelector('meta[name="viewport"]');
  if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; head.appendChild(meta); }
  meta.setAttribute('content', 'width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover');
  var style = document.getElementById('aird-login-fit');
  if (!style) { style = document.createElement('style'); style.id = 'aird-login-fit'; head.appendChild(style); }
  style.textContent = 'html,body{width:100%!important;min-height:100%!important;height:auto!important;margin:0!important;overflow-x:hidden!important;} .pf-c-login,.pf-c-login__container{max-width:none!important;width:100%!important;min-height:100vh!important;padding:0!important;} .pf-c-login__main{max-width:none!important;width:100%!important;min-height:100vh!important;margin:0!important;border-radius:0!important;box-shadow:none!important;} .pf-c-login__main-header,.pf-c-login__main-body,.pf-c-login__main-footer{padding-left:28px!important;padding-right:28px!important;}';
})();
"""

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val saved = remember { ServerStore.get(context) }
    var browsing by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(saved) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val hosts by DiscoveryManager.hosts.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        DiscoveryManager.onResume(context)
        onDispose { DiscoveryManager.onPause() }
    }

    LaunchedEffect(Unit) {
        try {
            val manager = CredentialManager.create(context)
            val result = manager.getCredential(
                context,
                GetCredentialRequest(listOf(GetPasswordOption())),
            )
            val credential = result.credential
            if (credential is PasswordCredential) {
                if (username.isEmpty()) username = credential.id
                if (password.isEmpty()) password = credential.password
            }
        } catch (_: Exception) {
        }
    }

    if (browsing) {
        BrowserLogin(
            onSignedIn = onSignedIn,
            onClose = { browsing = false },
            onFail = { message ->
                error = message
                browsing = false
            },
        )
        return
    }

    fun openBrowser() {
        if (!ServerStore.isValid(server)) {
            error = "Use https, or http on your local network."
            return
        }
        ServerStore.set(context, server)
        error = ""
        browsing = true
    }

    fun signIn() {
        if (busy) return
        if (!ServerStore.isValid(server)) {
            error = "Use https, or http on your local network."
            return
        }
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            error = "Enter your username and password."
            return
        }
        busy = true
        error = ""
        ServerStore.set(context, server)
        scope.launch {
            val ok = try {
                val response = AirdHttp.send(
                    context,
                    "/api/auth/login",
                    "POST",
                    JSONObject().put("username", user).put("password", password),
                )
                if (!response.ok) {
                    error = when (response.code) {
                        401 -> "Incorrect username or password."
                        429 -> "Too many attempts. Try again shortly."
                        0 -> "Could not reach the server."
                        else -> response.json().optString("error").ifBlank { "Sign-in failed (${response.code})." }
                    }
                    false
                } else {
                    val name = response.json().optString("username").ifBlank { user }
                    Session.markSignedIn(context, name)
                    if (!Session.signedIn.value) Session.probe(context)
                    savePasswordCredential(context, user, password)
                    true
                }
            } catch (_: Exception) {
                error = "Could not reach the server."
                false
            }
            busy = false
            if (ok) onSignedIn()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xxxl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.xl))
        Box(
            Modifier
                .size(112.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(name = "Aird", size = 72.dp)
        }
        Spacer(Modifier.height(Spacing.xxl))
        Text(
            "Aird",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "Sign in with your Aird account.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xxxl))

        OutlinedTextField(
            value = server,
            onValueChange = { server = it; if (error.isNotEmpty()) error = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server") },
            leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )

        if (hosts.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                "Nearby servers",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                hosts.forEach { host ->
                    val selected = ServerStore.normalize(server).equals(host.url, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            server = host.url
                            error = ""
                        },
                        label = { Text(host.name) },
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; if (error.isNotEmpty()) error = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Username") },
            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )
        Spacer(Modifier.height(Spacing.lg))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; if (error.isNotEmpty()) error = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                    )
                }
            },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Password),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); signIn() }),
        )

        if (error.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                error,
                color = LocalAirdColors.current.danger,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(Spacing.xxl))
        Button(
            onClick = { signIn() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) AirdLoading(size = 24.dp) else Text("Sign in")
        }
        Spacer(Modifier.height(Spacing.sm))
        TextButton(
            onClick = { openBrowser() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue in browser (SSO / LDAP / passkey)")
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

private suspend fun savePasswordCredential(context: Context, username: String, password: String) {
    withContext(Dispatchers.Main.immediate) {
        try {
            val manager = CredentialManager.create(context)
            manager.createCredential(
                context,
                CreatePasswordRequest(id = username, password = password),
            )
        } catch (_: Exception) {
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserLogin(onSignedIn: () -> Unit, onClose: () -> Unit, onFail: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverOpen by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(ServerStore.get(context)) }
    var error by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val loginError = remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Close") } },
                actions = { TextButton(onClick = { serverOpen = true }) { Text("Server") } },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.WHITE)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.textZoom = 100
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setGeolocationEnabled(false)
                    setInitialScale(0)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url ?: return true
                            val scheme = target.scheme?.lowercase().orEmpty()
                            if (scheme != "http" && scheme != "https") return true
                            if (scheme == "http" && !HostPolicy.allowsCleartext(target.host.orEmpty())) return true
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(LOGIN_FIT, null)
                            if (view == null || url.isNullOrEmpty()) return
                            if (view.url != null && view.url != url) return
                            val pageView = view
                            val finishedUrl = url
                            scope.launch {
                                val signedIn = try {
                                    Session.probe(context)
                                } catch (_: Exception) {
                                    if (pageView.url == null || pageView.url == finishedUrl) {
                                        loginError.value = "Could not sign in."
                                    }
                                    return@launch
                                }
                                val current = pageView.url
                                if (current != null && current != finishedUrl) return@launch
                                if (signedIn) {
                                    loginError.value = ""
                                    onSignedIn()
                                } else if (!awaitingCredentials(context, finishedUrl)) {
                                    loginError.value = "Could not sign in. Check the server address."
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) loginError.value = "Could not open the server."
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse,
                        ) {
                            if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                                loginError.value = "Could not open the server."
                            }
                        }
                    }
                    val start = ServerStore.get(viewContext)
                    if (HostPolicy.isSafeRemoteUrl(start)) loadUrl(start) else onFail("Use https, or http on your local network.")
                }
            },
            update = { view ->
                if (view.tag != page) {
                    view.tag = page
                    val next = ServerStore.get(view.context)
                    if (page > 0 && HostPolicy.isSafeRemoteUrl(next)) view.loadUrl(next)
                }
            },
        )
    }
    if (loginError.value.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { loginError.value = "" },
            title = { Text("Sign-in failed") },
            text = { Text(loginError.value) },
            confirmButton = {
                TextButton(onClick = { onFail(loginError.value) }) { Text("Server") }
            },
            dismissButton = {
                TextButton(onClick = { loginError.value = "" }) { Text("Dismiss") }
            },
        )
    }
    if (serverOpen) {
        ServerDialog(
            server = server,
            onServer = { server = it },
            error = error,
            onDismiss = { serverOpen = false },
            onSave = {
                if (!ServerStore.isValid(server)) {
                    error = "Use https, or http on your local network."
                    return@ServerDialog
                }
                ServerStore.set(context, server)
                error = ""
                serverOpen = false
                page += 1
            },
        )
    }
}

private fun awaitingCredentials(context: Context, url: String): Boolean {
    val page = Uri.parse(url)
    val path = page.path.orEmpty().lowercase()
    val host = page.host.orEmpty()
    if (path.contains("login") || path.contains("/if/") || path.contains("authentik") || host.contains("authentik", ignoreCase = true)) {
        return true
    }
    val serverHost = Uri.parse(ServerStore.get(context)).host.orEmpty()
    return serverHost.isNotEmpty() && !host.equals(serverHost, ignoreCase = true)
}

@Composable
private fun ServerDialog(
    server: String,
    onServer: (String) -> Unit,
    error: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server") },
        text = {
            Column {
                OutlinedTextField(
                    value = server,
                    onValueChange = onServer,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server") },
                    singleLine = true,
                )
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
