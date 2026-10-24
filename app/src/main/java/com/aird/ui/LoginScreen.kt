package com.aird.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aird.ServerStore
import com.aird.session.Session
import kotlinx.coroutines.launch

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

@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved = ServerStore.get(context)
    var browsing by remember { mutableStateOf(ServerStore.isValid(saved)) }
    var server by remember { mutableStateOf(saved) }
    var error by remember { mutableStateOf("") }
    var serverOpen by remember { mutableStateOf(false) }
    if (browsing) {
        BrowserLogin(
            onSignedIn = onSignedIn,
            onClose = { browsing = false },
        )
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("aird chat", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Sign in with the same Aird account you use on the desktop.",
            modifier = Modifier.padding(top = 8.dp),
        )
        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
        TextButton(onClick = { serverOpen = true }, modifier = Modifier.padding(top = 16.dp)) { Text("Server") }
        TextButton(
            onClick = {
                if (!ServerStore.isValid(server)) {
                    error = "Enter a full http or https address."
                    serverOpen = true
                    return@TextButton
                }
                error = ""
                browsing = true
            },
        ) { Text("Sign in") }
    }
    if (serverOpen) {
        ServerDialog(
            server = server,
            onServer = { server = it },
            error = error,
            onDismiss = { serverOpen = false },
            onSave = {
                if (!ServerStore.isValid(server)) {
                    error = "Enter a full http or https address."
                    return@ServerDialog
                }
                ServerStore.set(context, server)
                error = ""
                serverOpen = false
                browsing = true
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserLogin(onSignedIn: () -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var serverOpen by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(ServerStore.get(context)) }
    var error by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
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
                    setBackgroundColor(Color.WHITE)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.textZoom = 100
                    setInitialScale(0)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(LOGIN_FIT, null)
                            scope.launch {
                                if (Session.probe(context)) onSignedIn()
                            }
                        }
                    }
                    loadUrl(ServerStore.get(viewContext))
                }
            },
            update = { view ->
                if (view.tag != page) {
                    view.tag = page
                    if (page > 0) view.loadUrl(ServerStore.get(view.context))
                }
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
                    error = "Enter a full http or https address."
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
                    Text(error, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
