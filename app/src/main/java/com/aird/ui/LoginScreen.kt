package com.aird.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aird.ServerStore
import com.aird.session.Session
import com.aird.ui.pressScale
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
    var server by remember { mutableStateOf(ServerStore.get(context)) }
    var browsing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    if (browsing) {
        BrowserLogin(onSignedIn = onSignedIn, onClose = { browsing = false })
        return
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("aird chat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Sign in with the same Aird account you use on the desktop. Chat, voice, and video stay in this app.")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server") },
            singleLine = true,
        )
        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        SignInButton(server, onInvalid = { error = "Enter a full http or https address." }, onOpen = { browsing = true })
    }
}

@Composable
private fun SignInButton(server: String, onInvalid: () -> Unit, onOpen: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val press = remember { MutableInteractionSource() }
    Button(
        onClick = {
            val value = server.ifBlank { ServerStore.get(context) }
            if (!ServerStore.isValid(value)) {
                onInvalid()
                return@Button
            }
            ServerStore.set(context, value)
            onOpen()
        },
        modifier = Modifier.fillMaxWidth().then(pressScale(press)),
        interactionSource = press,
    ) {
        Text("Sign in")
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserLogin(onSignedIn: () -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
        )
        val press = remember { MutableInteractionSource() }
        Button(
            onClick = onClose,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).then(pressScale(press)),
            interactionSource = press,
        ) { Text("Close") }
    }
}
