package com.aird.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.aird.AppForeground
import com.aird.chat.crypto.ChatCrypto
import com.aird.session.AirdSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private object EnhancedCrypto {
    private const val INFO = "aird-enhanced-secure-chat-v1"
    private val random = SecureRandom()

    fun generateKeyPair(): ChatCrypto.Identity = ChatCrypto.generateIdentity()

    fun deriveAesKey(myIdentity: ChatCrypto.Identity, peerX: String, peerY: String, roomId: String): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKeyFromD(myIdentity.d))
        agreement.doPhase(publicKeyFromXY(peerX, peerY), true)
        return hkdf(
            fixed32(agreement.generateSecret()),
            roomId.toByteArray(Charsets.UTF_8),
            INFO.toByteArray(Charsets.UTF_8),
        )
    }

    fun encrypt(aesKey: ByteArray, plaintext: String): Pair<String, String> {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return b64(iv) to b64(ct)
    }

    fun decrypt(aesKey: ByteArray, ivB64: String, ctB64: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, unb64(ivB64)))
        return cipher.doFinal(unb64(ctB64)).toString(Charsets.UTF_8)
    }

    fun randomMessageId(): String {
        val bytes = ByteArray(18).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val saltKey = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(saltKey, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        return mac.doFinal().copyOf(32)
    }

    private fun privateKeyFromD(d: String): PrivateKey {
        val secret = BigInteger(1, unb64(d))
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(secret, ecParams()))
    }

    private fun publicKeyFromXY(x: String, y: String): PublicKey {
        val point = ECPoint(BigInteger(1, unb64(x)), BigInteger(1, unb64(y)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, ecParams()))
    }

    private fun ecParams(): ECParameterSpec {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        return params.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun fixed32(raw: ByteArray): ByteArray {
        if (raw.size == 32) return raw
        if (raw.size > 32) return raw.copyOfRange(raw.size - 32, raw.size)
        val out = ByteArray(32)
        raw.copyInto(out, 32 - raw.size)
        return out
    }

    private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    private fun unb64(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        val pad = when (normalized.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.getDecoder().decode(normalized + pad)
    }
}

private data class SecureLine(val id: String, val text: String, val mine: Boolean, val status: String)

private data class SecureView(
    val roomId: String,
    val peer: String,
    val requested: Boolean,
    val open: Boolean,
    val messages: List<SecureLine>,
)

private class SecretRoom(
    val roomId: String,
    var peer: String,
    var identity: ChatCrypto.Identity?,
    var aes: ByteArray? = null,
    var requested: Boolean = false,
    var open: Boolean = false,
    val messages: MutableList<SecureLine> = mutableListOf(),
)

private object EnhancedSecureHub {
    private val started = AtomicBoolean(false)
    private val rooms = LinkedHashMap<String, SecretRoom>()
    private var draft: Pair<String, ChatCrypto.Identity>? = null
    private var viewing: String? = null

    private val _view = MutableStateFlow<SecureView?>(null)
    val view: StateFlow<SecureView?> = _view
    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice
    private val _noticeError = MutableStateFlow(false)
    val noticeError: StateFlow<Boolean> = _noticeError

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        AirdSocket.addListener { raw -> onSocket(raw) }
        AirdSocket.addOpenListener { rejoin() }
    }

    fun start(peer: String) {
        val name = peer.trim()
        if (name.isEmpty()) {
            fail("Enter a username")
            return
        }
        val existing = rooms.values.find { it.peer == name }
        if (existing != null) {
            if (existing.requested) {
                viewing = existing.roomId
                publish()
                return
            }
            if (existing.identity != null && !sendJoin(existing)) {
                fail("Chat connection is not open")
                return
            }
            viewing = existing.roomId
            publish()
            return
        }
        val keys = EnhancedCrypto.generateKeyPair()
        draft = name to keys
        val sent = AirdSocket.send(
            JSONObject()
                .put("type", "enhanced_invite")
                .put("peer", name)
                .put("epk", publicJwk(keys))
                .toString(),
        )
        if (!sent) {
            draft = null
            fail("Chat connection is not open")
            return
        }
        note("Waiting for $name to accept")
    }

    fun accept(roomId: String) {
        val room = rooms[roomId] ?: return
        if (!room.requested) return
        val keys = EnhancedCrypto.generateKeyPair()
        room.identity = keys
        room.requested = false
        if (!sendJoin(room)) {
            room.identity = null
            room.requested = true
            fail("Chat connection is not open")
            return
        }
        viewing = room.roomId
        note("Waiting for ${room.peer} to accept")
        publish()
    }

    fun end(roomId: String) {
        val room = rooms[roomId] ?: return
        if (room.roomId.isNotEmpty()) {
            AirdSocket.send(
                JSONObject().put("type", "enhanced_close").put("room_id", room.roomId).toString(),
            )
        }
        wipe(room.roomId)
    }

    fun sendText(text: String): Boolean {
        val room = viewing?.let { rooms[it] } ?: return false
        val body = text.trim()
        if (body.isEmpty()) return false
        val key = room.aes
        if (!room.open || key == null) {
            fail("Waiting for ${room.peer}")
            return false
        }
        val messageId = EnhancedCrypto.randomMessageId()
        val (iv, ct) = try {
            EnhancedCrypto.encrypt(key, body)
        } catch (err: Exception) {
            fail(err.message ?: "Could not send")
            return false
        }
        val sent = AirdSocket.send(
            JSONObject()
                .put("type", "enhanced_msg")
                .put("room_id", room.roomId)
                .put("message_id", messageId)
                .put("v", 1)
                .put("iv", iv)
                .put("ct", ct)
                .toString(),
        )
        if (!sent) {
            fail("Chat connection is not open")
            return false
        }
        room.messages.add(SecureLine(messageId, body, mine = true, status = ""))
        trim(room)
        publish()
        return true
    }

    private fun onSocket(raw: String) {
        val msg = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        try {
            when (msg.optString("type")) {
                "enhanced_request" -> onRequest(msg)
                "enhanced_room" -> onRoom(msg)
                "enhanced_open" -> onOpen(msg)
                "enhanced_msg" -> onMsg(msg)
                "enhanced_receipt" -> onReceipt(msg)
                "enhanced_ended" -> wipe(msg.optString("room_id"))
                "enhanced_error" -> {
                    draft = null
                    fail(msg.optString("message").ifBlank { "Enhanced Secure Chat failed" })
                }
            }
        } catch (err: Exception) {
            fail(err.message ?: "Enhanced Secure Chat failed")
        }
    }

    private fun onRequest(msg: JSONObject) {
        val roomId = msg.optString("room_id")
        val from = msg.optString("from")
        if (roomId.isEmpty() || from.isEmpty()) return
        val room = rooms.getOrPut(roomId) {
            SecretRoom(roomId, from, identity = null, requested = true)
        }
        room.peer = from
        room.requested = true
        room.open = false
        val current = viewing?.let { rooms[it] }
        if (current == null || current.requested || !current.open) viewing = roomId
        note("$from requested an Enhanced Secure Chat")
        publish()
    }

    private fun onRoom(msg: JSONObject) {
        val room = ensure(msg.optString("room_id"), msg.optString("peer")) ?: return
        room.open = false
        room.requested = false
        viewing = room.roomId
        note("Waiting for ${room.peer} to accept")
        publish()
    }

    private fun onOpen(msg: JSONObject) {
        val room = ensure(msg.optString("room_id"), msg.optString("peer")) ?: return
        val epk = epkObject(msg)
        val identity = room.identity
        val x = epk?.optString("x").orEmpty()
        val y = epk?.optString("y").orEmpty()
        if (identity == null || x.isEmpty() || y.isEmpty()) {
            fail("Could not open Enhanced Secure Chat")
            publish()
            return
        }
        room.aes = try {
            EnhancedCrypto.deriveAesKey(identity, x, y, room.roomId)
        } catch (_: Exception) {
            fail("Could not open Enhanced Secure Chat")
            null
        }
        room.open = room.aes != null
        room.requested = false
        viewing = room.roomId
        if (room.open) note("")
        publish()
    }

    private fun onMsg(msg: JSONObject) {
        val room = rooms[msg.optString("room_id")]
        val key = room?.aes
        val messageId = msg.optString("message_id")
        if (room == null || key == null || messageId.isEmpty()) {
            fail("Could not decrypt an Enhanced Secure Chat message")
            return
        }
        if (room.messages.any { it.id == messageId }) return
        val text = try {
            EnhancedCrypto.decrypt(key, msg.optString("iv"), msg.optString("ct"))
        } catch (_: Exception) {
            fail("Could not decrypt an Enhanced Secure Chat message")
            return
        }
        val seen = viewing == room.roomId && AppForeground.resumed()
        receipt(room.roomId, messageId, "decrypted")
        if (seen) receipt(room.roomId, messageId, "seen")
        room.messages.add(SecureLine(messageId, text, mine = false, status = if (seen) "seen" else "decrypted"))
        trim(room)
        publish()
    }

    private fun onReceipt(msg: JSONObject) {
        val room = rooms[msg.optString("room_id")] ?: return
        val id = msg.optString("message_id")
        val status = msg.optString("status")
        val index = room.messages.indexOfFirst { it.mine && it.id == id }
        if (index < 0) return
        val current = room.messages[index]
        if (status == "seen" || current.status.isEmpty()) {
            room.messages[index] = current.copy(status = status)
            publish()
        }
    }

    private fun rejoin() {
        rooms.values.forEach { room ->
            if (room.requested || room.identity == null || room.roomId.isEmpty()) return@forEach
            sendJoin(room)
        }
    }

    private fun ensure(roomId: String, peer: String): SecretRoom? {
        if (roomId.isEmpty()) return null
        rooms[roomId]?.let { room ->
            if (peer.isNotEmpty()) room.peer = peer
            return room
        }
        val pending = draft
        val keys = if (pending != null && (peer.isEmpty() || pending.first == peer)) {
            draft = null
            pending.second
        } else {
            null
        }
        val room = SecretRoom(roomId, peer.ifBlank { pending?.first.orEmpty() }, keys)
        rooms[roomId] = room
        return room
    }

    private fun sendJoin(room: SecretRoom): Boolean {
        val identity = room.identity ?: return false
        return AirdSocket.send(
            JSONObject()
                .put("type", "enhanced_join")
                .put("room_id", room.roomId)
                .put("epk", publicJwk(identity))
                .toString(),
        )
    }

    private fun wipe(roomId: String) {
        val room = rooms.remove(roomId) ?: return
        room.aes?.fill(0)
        room.aes = null
        if (viewing == roomId) {
            val next = rooms.values.firstOrNull { it.requested }
            viewing = next?.roomId
            if (next != null) note("${next.peer} requested an Enhanced Secure Chat")
            else note("Enhanced Secure Chat ended")
        }
        publish()
    }

    private fun receipt(roomId: String, messageId: String, status: String) {
        AirdSocket.send(
            JSONObject()
                .put("type", "enhanced_receipt")
                .put("room_id", roomId)
                .put("message_id", messageId)
                .put("status", status)
                .toString(),
        )
    }

    private fun publish() {
        val room = viewing?.let { rooms[it] }
        _view.value = room?.let {
            SecureView(
                roomId = it.roomId,
                peer = it.peer,
                requested = it.requested,
                open = it.open,
                messages = it.messages.toList(),
            )
        }
    }

    private fun trim(room: SecretRoom) {
        while (room.messages.size > 400) room.messages.removeAt(0)
    }

    private fun note(text: String) {
        _noticeError.value = false
        _notice.value = text
    }

    private fun fail(text: String) {
        _noticeError.value = true
        _notice.value = text.substringBefore('\n').ifBlank { "Enhanced Secure Chat failed" }
    }

    private fun publicJwk(identity: ChatCrypto.Identity) = JSONObject()
        .put("kty", "EC")
        .put("crv", "P-256")
        .put("x", identity.x)
        .put("y", identity.y)

    private fun epkObject(msg: JSONObject): JSONObject? {
        val raw = msg.opt("epk") ?: return null
        return when (raw) {
            is JSONObject -> raw
            is String -> try {
                JSONObject(raw)
            } catch (_: Exception) {
                null
            }
            else -> null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedSecureScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { EnhancedSecureHub.ensureStarted() }
    val room by EnhancedSecureHub.view.collectAsState()
    val notice by EnhancedSecureHub.notice.collectAsState()
    val noticeError by EnhancedSecureHub.noticeError.collectAsState()
    var peer by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(room?.messages?.size) {
        val count = room?.messages?.size ?: return@LaunchedEffect
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (room == null) "Enhanced Secure Chat" else room?.peer.orEmpty(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (room != null) {
                        TextButton(onClick = { EnhancedSecureHub.end(room?.roomId.orEmpty()) }) {
                            Text(if (room?.requested == true) "Decline" else "End")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    "Not saved",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text(
                    "This chat stays on this phone only while you are both here. The server does not keep it.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 13.sp,
                )
            }
            if (notice.isNotEmpty()) {
                Text(
                    notice,
                    color = if (noticeError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when {
                room == null -> Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = peer,
                        onValueChange = { peer = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Username") },
                    )
                    Button(
                        onClick = { EnhancedSecureHub.start(peer) },
                        enabled = peer.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start") }
                }
                room?.requested == true -> Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("${room?.peer} wants to start an Enhanced Secure Chat.")
                    Button(
                        onClick = { EnhancedSecureHub.accept(room?.roomId.orEmpty()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Accept") }
                    OutlinedButton(
                        onClick = { EnhancedSecureHub.end(room?.roomId.orEmpty()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Decline") }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val lines = room?.messages.orEmpty()
                        if (lines.isEmpty()) {
                            item {
                                Text(
                                    if (room?.open == true) "No messages yet" else "Waiting for ${room?.peer} to accept",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                )
                            }
                        }
                        items(lines, key = { it.id }) { line ->
                            SecureBubble(line)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            enabled = room?.open == true,
                            placeholder = {
                                Text(if (room?.open == true) "Enhanced secure message…" else "Waiting…")
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (EnhancedSecureHub.sendText(draft)) draft = ""
                            }),
                            maxLines = 4,
                        )
                        IconButton(
                            onClick = { if (EnhancedSecureHub.sendText(draft)) draft = "" },
                            enabled = room?.open == true && draft.isNotBlank(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecureBubble(line: SecureLine) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (line.mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (line.mine) Alignment.End else Alignment.Start) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (line.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    line.text,
                    color = if (line.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (line.mine && line.status.isNotEmpty()) {
                Text(
                    if (line.status == "seen") "Decrypted & seen" else "Decrypted",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                )
            }
        }
    }
}
