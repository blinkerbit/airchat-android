package com.aird.chat.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aird.ServerStore
import com.aird.chat.data.EmojiPreferences
import com.aird.session.AirdHttp
import com.aird.session.PasskeyDevice
import com.aird.session.Session
import com.aird.ui.PasskeyRegisterDialog
import com.aird.theme.AirdPalettes
import com.aird.theme.ThemeStore
import com.aird.ui.LocalAirdReducedMotion
import com.aird.ui.airdPush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentSkin by remember { mutableStateOf(EmojiPreferences.getSkinTone(context)) }
    var currentTheme by remember { mutableStateOf(ThemeStore.id.value) }
    val reduced = LocalAirdReducedMotion.current
    var page by remember { mutableStateOf("home") }
    BackHandler {
        if (page == "security") page = "home" else onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { airdPush(forward = targetState == "security", reduced = reduced) },
            label = "settingsPage",
        ) { current ->
            if (current == "security") {
                AccountSecurityPage(onBack = { page = "home" })
            } else {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "App Settings",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { page = "security" }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Account security", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            "Password and passkey",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- Section 1: Emoji Default Skin Tone ---
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Face,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Default Emoji Skin Tone",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        "WhatsApp style preference for hands, people and reaction emojis.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EmojiPreferences.allSkins.forEach { skin ->
                            val isSelected = currentSkin == skin.id
                            val fill by animateColorAsState(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                tween(if (reduced) 0 else 140),
                                label = "skinFill",
                            )
                            val scale by animateFloatAsState(
                                if (isSelected && !reduced) 1.05f else 1f,
                                spring(dampingRatio = 1f, stiffness = 900f),
                                label = "skinScale",
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        currentSkin = skin.id
                                        EmojiPreferences.setSkinTone(context, skin.id)
                                    }
                                    .padding(4.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(fill)
                                        .border(
                                            width = if (isSelected) 2.dp else 0.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = CircleShape
                                        )
                                ) {
                                    Text(skin.preview, fontSize = 20.sp)
                                }
                                Text(
                                    skin.label,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // --- Section 2: Color Theme ---
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Theme Palette",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Column(Modifier.padding(top = 12.dp)) {
                        AirdPalettes.all.forEach { palette ->
                            val isSelected = currentTheme == palette.id
                            val rowFill by animateColorAsState(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.surfaceContainer,
                                tween(if (reduced) 0 else 140),
                                label = "themeFill",
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(rowFill)
                                    .clickable {
                                        currentTheme = palette.id
                                        ThemeStore.set(context, palette.id)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    palette.label,
                                    fontSize = 14.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                AnimatedVisibility(visible = isSelected, enter = fadeIn(tween(120)), exit = fadeOut(tween(80))) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
            }
        }
    }
}

@Composable
private fun AccountSecurityPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var passwordUrl by remember { mutableStateOf("") }
    var passkeyUrl by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var register by remember { mutableStateOf(false) }
    val username = Session.username.collectAsState().value
    val devicePasskey = username.isNotBlank() && PasskeyDevice.hasLocal(context, username)
    LaunchedEffect(Unit) {
        val links = loadAccountSecurity(context)
        passwordUrl = links.first
        passkeyUrl = links.second
        loaded = true
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Account security", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Sign-in is managed by Authentik. These open in the browser, which may ask you to sign in once.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        if (!loaded) {
            Text("Loading…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (passwordUrl.isNotBlank()) {
            SecurityLink(
                icon = Icons.Default.Password,
                title = "Change password",
                detail = "Open the Authentik password page",
                onClick = { openSecurityPage(context, passwordUrl) },
            )
        }
        SecurityLink(
            icon = Icons.Default.Key,
            title = "Register passkey",
            detail = if (devicePasskey) "This device already has a passkey" else "Set up a passkey on this device",
            onClick = { register = true },
        )
        if (passkeyUrl.isNotBlank()) {
            SecurityLink(
                icon = Icons.Default.Key,
                title = "Open passkey page",
                detail = "Open the Authentik passkey page",
                onClick = { openSecurityPage(context, passkeyUrl) },
            )
        }
    }
    if (register && username.isNotBlank()) {
        PasskeyRegisterDialog(username) { register = false }
    }
}

@Composable
private fun SecurityLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private suspend fun loadAccountSecurity(context: android.content.Context): Pair<String, String> {
    val server = ServerStore.get(context)
    val fallbackPassword = joinServer(server, "/if/flow/default-password-change/")
    val fallbackPasskey = joinServer(server, "/if/flow/default-authenticator-webauthn-setup/")
    val response = try {
        AirdHttp.get(context, "/api/account/security")
    } catch (_: Exception) {
        return fallbackPassword to fallbackPasskey
    }
    if (!response.ok) return fallbackPassword to fallbackPasskey
    val json = response.json()
    if (!json.has("password_url") && !json.has("passkey_url")) return fallbackPassword to fallbackPasskey
    return joinServer(server, json.optString("password_url")) to joinServer(server, json.optString("passkey_url"))
}

private fun joinServer(server: String, raw: String): String {
    val value = raw.trim()
    if (value.isEmpty()) return ""
    if (value.startsWith("https://") || value.startsWith("http://")) return value
    if (value.startsWith("/") && !value.startsWith("//")) return server.trimEnd('/') + value
    return ""
}

private fun openSecurityPage(context: android.content.Context, url: String) {
    val page = Uri.parse(url)
    if (page.scheme != "https" && page.scheme != "http") return
    val intent = Intent(Intent.ACTION_VIEW, page)
    runCatching { context.startActivity(intent) }
}
