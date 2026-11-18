package com.aird.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aird.ServerStore
import com.aird.security.HostPolicy
import com.aird.session.AirdHttp
import com.aird.session.PasskeyDevice
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val OFFER_SECONDS = 20

@Composable
fun PasskeyOffer(username: String, onContinue: () -> Unit) {
    var ceremony by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var remaining by remember { mutableIntStateOf(OFFER_SECONDS) }
    LaunchedEffect(ceremony) {
        if (ceremony) return@LaunchedEffect
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        PasskeyDevice.skipped = true
        onContinue()
    }
    if (ceremony) {
        PasskeyCeremony(
            username = username,
            onRegistered = onContinue,
            onCancel = {
                ceremony = false
                remaining = OFFER_SECONDS
            },
            onFailed = { message ->
                error = message
                ceremony = false
                remaining = OFFER_SECONDS
            },
        )
        return
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text("Register a passkey", style = MaterialTheme.typography.headlineMedium)
        Text(
            "This device has no passkey for $username. Register one for a faster, more secure sign-in.",
            modifier = Modifier.padding(top = 12.dp),
        )
        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { ceremony = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Register passkey")
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = {
                PasskeyDevice.skipped = true
                onContinue()
            }) { Text("Skip") }
            Text(
                "Continuing in ${remaining}s",
                modifier = Modifier.align(Alignment.CenterVertically),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PasskeyRegisterDialog(username: String, onDismiss: () -> Unit) {
    var error by remember { mutableStateOf("") }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        if (error.isNotEmpty()) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("Passkey was not registered", style = MaterialTheme.typography.headlineSmall)
                    Text(error, modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) { Text("Close") }
                }
            }
        } else {
            PasskeyCeremony(
                username = username,
                onRegistered = onDismiss,
                onCancel = onDismiss,
                onFailed = { error = it },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PasskeyCeremony(
    username: String,
    onRegistered: () -> Unit,
    onCancel: () -> Unit,
    onFailed: (String) -> Unit,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<PasskeyDevice.Mode?>(null) }
    val finished = remember { mutableStateOf(false) }
    val bridge = remember {
        PasskeyBridge(
            onOk = { id ->
                if (finished.value) return@PasskeyBridge
                finished.value = true
                if (id.isNotBlank()) PasskeyDevice.markCredential(context, username, id)
                else PasskeyDevice.markAuthentik(context, username)
                onRegistered()
            },
            onFail = { message ->
                if (finished.value) return@PasskeyBridge
                finished.value = true
                onFailed(message.ifBlank { "Passkey registration failed." })
            },
        )
    }
    LaunchedEffect(Unit) {
        mode = try {
            PasskeyDevice.resolve(context)
        } catch (_: Exception) {
            null
        }
        if (mode == null) onFailed("Passkeys are not available for this server.")
    }
    val current = mode
    if (current == null) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            Text("Checking passkey…", modifier = Modifier.padding(24.dp))
        }
        return
    }
    val server = ServerStore.get(context)
    val sawFlow = remember { mutableStateOf(false) }
    fun succeedAuthentik() {
        if (finished.value) return
        finished.value = true
        PasskeyDevice.markAuthentik(context, username)
        onRegistered()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Register passkey") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("Cancel") } },
                actions = {
                    if (current is PasskeyDevice.Mode.Authentik) {
                        TextButton(onClick = { succeedAuthentik() }, enabled = sawFlow.value) { Text("Done") }
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    if (current is PasskeyDevice.Mode.Aird) addJavascriptInterface(bridge, "AirdPasskey")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url?.toString().orEmpty()
                            if (!HostPolicy.isSafeRemoteUrl(target)) return true
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (view == null || url.isNullOrBlank()) return
                            if (current is PasskeyDevice.Mode.Aird) {
                                if (!HostPolicy.sameHost(server, url)) return
                                val token = JSONObject.quote(AirdHttp.xsrf(server))
                                view.evaluateJavascript(registerScript(token), null)
                                return
                            }
                            val path = android.net.Uri.parse(url).path.orEmpty().lowercase()
                            val inFlow = path.contains("webauthn") || path.contains("/if/flow/")
                            if (inFlow) sawFlow.value = true
                            else if (sawFlow.value) succeedAuthentik()
                        }
                    }
                    CookieManager.getInstance().flush()
                    val start = when (current) {
                        PasskeyDevice.Mode.Aird -> server.trimEnd('/') + "/api/webauthn/status"
                        is PasskeyDevice.Mode.Authentik -> current.url
                    }
                    if (HostPolicy.isSafeRemoteUrl(start)) loadUrl(start)
                }
            },
        )
    }
}

private class PasskeyBridge(
    private val onOk: (String) -> Unit,
    private val onFail: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun ok(id: String) {
        main.post { onOk(id) }
    }

    @JavascriptInterface
    fun fail(message: String) {
        main.post { onFail(message) }
    }
}

private fun registerScript(quotedToken: String): String = """
(function(){
  if (window.__airdPasskeyStarted) return;
  window.__airdPasskeyStarted = true;
  function b64ToBuf(b64){
    var pad = (4 - (b64.length % 4)) % 4;
    var s = (b64 + '='.repeat(pad)).replace(/-/g,'+').replace(/_/g,'/');
    var bin = atob(s);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out.buffer;
  }
  function bufToB64(buf){
    var bytes = new Uint8Array(buf);
    var bin = '';
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
  }
  function credJson(cred){
    var r = cred.response;
    return {
      id: cred.id,
      rawId: bufToB64(cred.rawId),
      type: cred.type,
      authenticatorAttachment: cred.authenticatorAttachment || undefined,
      response: {
        clientDataJSON: bufToB64(r.clientDataJSON),
        attestationObject: bufToB64(r.attestationObject),
        transports: r.getTransports ? r.getTransports() : []
      }
    };
  }
  var token = $quotedToken;
  if (!navigator.credentials || !window.PublicKeyCredential) {
    AirdPasskey.fail('This device cannot create a passkey.');
    return;
  }
  fetch('/api/webauthn/register/options', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {'Content-Type':'application/json','X-XSRFToken': token},
    body: '{}'
  }).then(function(res){
    return res.json().then(function(body){
      if (!res.ok) throw new Error(body.error || 'Could not start passkey registration.');
      return body;
    });
  }).then(function(options){
    delete options.prfSalt;
    options.challenge = b64ToBuf(options.challenge);
    if (options.user && options.user.id) options.user.id = b64ToBuf(options.user.id);
    if (Array.isArray(options.excludeCredentials)) {
      options.excludeCredentials = options.excludeCredentials.map(function(c){
        c.id = b64ToBuf(c.id);
        return c;
      });
    }
    options.authenticatorSelection = Object.assign(
      {residentKey:'preferred', userVerification:'preferred'},
      options.authenticatorSelection || {},
      {authenticatorAttachment:'platform'}
    );
    return navigator.credentials.create({publicKey: options});
  }).then(function(cred){
    if (!cred) throw new Error('Passkey registration was cancelled.');
    return fetch('/api/webauthn/register/verify', {
      method: 'POST',
      credentials: 'same-origin',
      headers: {'Content-Type':'application/json','X-XSRFToken': token},
      body: JSON.stringify({credential: credJson(cred), nickname: 'Android', transports: cred.response.getTransports ? cred.response.getTransports() : []})
    }).then(function(res){
      return res.json().then(function(body){
        if (!res.ok) throw new Error(body.error || 'Passkey registration failed.');
        AirdPasskey.ok(body.id || cred.id || '');
      });
    });
  }).catch(function(err){
    AirdPasskey.fail((err && err.message) ? err.message : 'Passkey registration failed.');
  });
})();
""".trimIndent()
