package com.aird.chat.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aird.chat.data.ChatE2E
import com.aird.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyBackupSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val pending = remember { mutableStateOf<String?>(null) }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val json = pending.value
        pending.value = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Could not write file")
                }
                error = false
                status = "Saved"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = true
                status = e.message?.line() ?: "Could not write file"
            } finally {
                busy = false
            }
        }
    }

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = ""
            try {
                val me = signedInUser(context)
                if (me == null) {
                    error = true
                    status = "Not signed in"
                    return@launch
                }
                ChatE2E.prepare(context, me)
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("Could not read file")
                }
                val result = ChatE2E.importBackup(context, json)
                if (result.isSuccess) {
                    error = false
                    status = "Imported"
                } else {
                    error = true
                    status = result.exceptionOrNull()?.message?.line() ?: "Could not import key"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = true
                status = e.message?.line() ?: "Could not import key"
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Key backup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Save this account's chat key to a file, or restore a key file. Keep the file private.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        status = ""
                        try {
                            val me = signedInUser(context)
                            if (me == null) {
                                error = true
                                status = "Not signed in"
                                return@launch
                            }
                            ChatE2E.prepare(context, me)
                            val json = ChatE2E.exportBackup(context)
                            if (json == null) {
                                error = true
                                status = "No encryption key on this device"
                                return@launch
                            }
                            pending.value = json
                            val safe = me.replace(Regex("[^\\w.-]+"), "_")
                            createDoc.launch("aird-chat-e2e-key-$safe.json")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = true
                            status = e.message?.line() ?: "Could not export key"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export") }
            OutlinedButton(
                onClick = { openDoc.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import") }
            if (status.isNotEmpty()) {
                Text(
                    status,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private suspend fun signedInUser(context: android.content.Context): String? {
    var me = Session.username.value
    if (me.isEmpty()) {
        Session.probe(context)
        me = Session.username.value
    }
    return me.ifBlank { null }
}

private fun String.line(): String = trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
