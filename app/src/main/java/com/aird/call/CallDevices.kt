package com.aird.call

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.Room
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executor

data class DeviceChoice(val id: String, val label: String)

/**
 * One owner for call audio. On Android 12 and newer the communication device is the route.
 * AudioSwitch only keeps the voice-call focus, because letting it also switch the speaker
 * puts the call back on the earpiece. Older phones still use AudioSwitch for the route.
 */
object CallDevices {
    var onRouteChanged: ((DeviceChoice) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { main.post(it) }
    private var session = false
    private var ready = false
    private var videoCall = false
    private var userLocked = false
    private var locked: DeviceChoice? = null
    private var appliedId = ""
    private var repairs = 0
    private var raisedVolume = false
    private var listening = false
    private var audioRef: AudioManager? = null
    private var commToken: Any? = null
    private var roomRef: Room? = null
    private var appRef: Context? = null

    fun cameras(context: Context): List<DeviceChoice> {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames?.toList().orEmpty()
        return names.map { id ->
            val label = when {
                enumerator.isFrontFacing(id) -> "Front Camera"
                enumerator.isBackFacing(id) -> "Back Camera"
                else -> "Camera"
            }
            DeviceChoice(id, label)
        }
    }

    fun audioOutputs(context: Context, room: Room?): List<DeviceChoice> {
        val audio = context.getSystemService(AudioManager::class.java)
        val found = mutableListOf<DeviceChoice>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && audio != null) {
            for (dev in audio.availableCommunicationDevices) {
                val kind = kindOf(dev.type) ?: continue
                found.add(DeviceChoice(id = "comm:$kind:${dev.id}", label = labelOf(dev, kind)))
            }
        }
        if (found.isEmpty() && audio != null) {
            for (dev in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                val kind = kindOf(dev.type) ?: continue
                found.add(DeviceChoice(id = "out:$kind:${dev.id}", label = labelOf(dev, kind)))
            }
        }
        if (found.isEmpty()) {
            val switched = room?.audioSwitchHandler?.availableAudioDevices.orEmpty()
            for (dev in switched) {
                val (kind, label) = when (dev) {
                    is AudioDevice.Speakerphone -> "speaker" to "Speaker"
                    is AudioDevice.Earpiece -> "earpiece" to "Earpiece"
                    is AudioDevice.BluetoothHeadset -> "bluetooth" to dev.name.ifBlank { "Bluetooth" }
                    is AudioDevice.WiredHeadset -> "wired" to "Wired headset"
                }
                found.add(DeviceChoice(id = "sw:$kind:${switchName(dev)}", label = label))
            }
        }
        if (audio != null && found.none { kindOfId(it.id) == "speaker" }) {
            found.add(DeviceChoice("speaker", "Speaker"))
        }
        val earpiece = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } == true
        if (earpiece && found.none { kindOfId(it.id) == "earpiece" }) {
            found.add(DeviceChoice("earpiece", "Earpiece"))
        }
        return numberDuplicates(found.distinctBy { it.id })
    }

    fun outputKey(choice: DeviceChoice): String = when (kindOfId(choice.id)) {
        "speaker" -> "speaker"
        "earpiece" -> "earpiece"
        else -> "headset"
    }

    fun hint(choice: DeviceChoice): String = when (outputKey(choice)) {
        "speaker" -> "Speaker"
        "earpiece" -> "Earpiece"
        else -> choice.label
    }

    /** Headset if one is connected, otherwise speaker for video and for tablets, otherwise the earpiece. */
    fun defaultOutput(context: Context, video: Boolean): DeviceChoice {
        val outputs = audioOutputs(context, roomRef)
        val headset = outputs.firstOrNull { outputKey(it) == "headset" }
        if (headset != null) return headset
        if (video || outputs.none { outputKey(it) == "earpiece" }) {
            return outputs.firstOrNull { outputKey(it) == "speaker" } ?: DeviceChoice("speaker", "Speaker")
        }
        return outputs.first { outputKey(it) == "earpiece" }
    }

    fun cancelRoutes() {
        repairs = 8
    }

    /** Keeps call focus. On Android 12+ the communication device is the route that stays. */
    fun prepareRouter(room: Room?, speakerDefault: Boolean) {
        val handler = room?.audioSwitchHandler as? AudioSwitchHandler ?: return
        handler.manageAudioFocus = true
        handler.audioMode = AudioManager.MODE_IN_COMMUNICATION
        handler.audioStreamType = AudioManager.STREAM_VOICE_CALL
        handler.audioAttributeUsageType = AudioAttributes.USAGE_VOICE_COMMUNICATION
        handler.audioAttributeContentType = AudioAttributes.CONTENT_TYPE_SPEECH
        handler.forceHandleAudioRouting = false
        val speaker = AudioDevice.Speakerphone::class.java
        val earpiece = AudioDevice.Earpiece::class.java
        val handsFree = listOf(AudioDevice.BluetoothHeadset::class.java, AudioDevice.WiredHeadset::class.java)
        handler.preferredDeviceList = if (speakerDefault) {
            handsFree + speaker + earpiece
        } else {
            handsFree + earpiece + speaker
        }
    }

    fun begin(context: Context, room: Room?, video: Boolean): DeviceChoice {
        ready = false
        session = true
        videoCall = video
        repairs = 0
        roomRef = room
        appRef = context.applicationContext
        bindVolume(context, true)
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio != null) {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            listen(audio)
            ensureVoiceVolume(audio)
        }
        val choice = if (userLocked && locked != null && stillPresent(context, locked!!)) locked!! else {
            userLocked = false
            locked = null
            defaultOutput(context, video)
        }
        hold(context, room, choice)
        ready = true
        return choice
    }

    fun noteCallVideo(video: Boolean) {
        videoCall = video
    }

    fun reapply(context: Context, room: Room?) {
        if (!session) return
        roomRef = room ?: roomRef
        val choice = locked?.takeIf { userLocked && stillPresent(context, it) }
            ?: lastApplied(context)
            ?: defaultOutput(context, videoCall)
        hold(context, roomRef, choice)
    }

    fun applyOutput(context: Context, room: Room?, output: String) {
        if (userLocked && locked != null && stillPresent(context, locked!!)) {
            hold(context, room, locked!!)
            return
        }
        val choice = audioOutputs(context, room).firstOrNull { it.id == output }
            ?: audioOutputs(context, room).firstOrNull { outputKey(it) == output }
            ?: defaultOutput(context, videoCall)
        hold(context, room, choice)
    }

    fun selectAudioOutput(context: Context, room: Room?, choice: DeviceChoice) {
        play(context, room, choice, lock = true)
    }

    /** lock is false when the call itself picks a route, so a newly plugged headset can still take over. */
    fun play(context: Context, room: Room?, choice: DeviceChoice, lock: Boolean) {
        if (lock) {
            userLocked = true
            locked = choice
        } else {
            userLocked = false
            locked = null
        }
        repairs = 0
        hold(context, room, choice)
    }

    fun playback(context: Context, room: Room?): List<DeviceChoice> = audioOutputs(context, room)
    fun selectPlayback(context: Context, room: Room?, id: String) = applyOutput(context, room, id)

    fun microphones(context: Context): List<DeviceChoice> {
        val audio = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        return audio.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { dev ->
            val label = microphoneLabel(dev) ?: return@mapNotNull null
            DeviceChoice(dev.id.toString(), label)
        }.distinctBy { it.id }
    }

    fun selectMicrophone(context: Context, room: Room?, id: String) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val devId = id.toIntOrNull() ?: return
        val target = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == devId } ?: return
        val module = room?.lkObjects?.audioDeviceModule as? JavaAudioDeviceModule ?: return
        try {
            module.setPreferredInputDevice(target)
        } catch (_: Exception) {
        }
    }

    fun release(context: Context) {
        ready = false
        session = false
        userLocked = false
        locked = null
        appliedId = ""
        repairs = 8
        raisedVolume = false
        roomRef = null
        val audio = context.getSystemService(AudioManager::class.java)
        unlisten(audio)
        if (audio != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    audio.clearCommunicationDevice()
                } catch (_: Exception) {
                }
            }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
            audio.mode = AudioManager.MODE_NORMAL
        }
        bindVolume(context, false)
        appRef = null
    }

    private fun stillPresent(context: Context, choice: DeviceChoice): Boolean {
        return audioOutputs(context, roomRef).any { it.id == choice.id }
    }

    private fun lastApplied(context: Context): DeviceChoice? {
        if (appliedId.isEmpty()) return null
        return audioOutputs(context, roomRef).firstOrNull { it.id == appliedId }
    }

    private fun hold(context: Context, room: Room?, choice: DeviceChoice, syncSwitch: Boolean = true) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val app = context.applicationContext
            main.post { hold(app, room, choice, syncSwitch) }
            return
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        roomRef = room ?: roomRef
        appRef = context.applicationContext
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        listen(audio)
        ensureVoiceVolume(audio)

        val kind = kindOfId(choice.id)
        val isSpeaker = (kind == "speaker")

        AirdTelecom.setSpeaker(isSpeaker)

        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = isSpeaker

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = resolve(audio, choice)
            if (target != null) {
                try {
                    audio.setCommunicationDevice(target)
                } catch (_: Exception) {
                }
                followInput(audio, roomRef, target)
            } else {
                try {
                    audio.clearCommunicationDevice()
                } catch (_: Exception) {
                }
            }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = isSpeaker
            appliedId = choice.id
        } else {
            applyLegacy(audio, roomRef, choice)
            appliedId = choice.id
        }
    }

    /** Point AudioSwitch at the same kind of device, then the communication device is applied after it. */
    private fun alignSwitch(room: Room?, choice: DeviceChoice) {
        val handler = room?.audioSwitchHandler as? AudioSwitchHandler ?: return
        val kind = kindOfId(choice.id)
        val devices = handler.availableAudioDevices
        val match = devices.firstOrNull { device ->
            when (kind) {
                "speaker" -> device is AudioDevice.Speakerphone
                "earpiece" -> device is AudioDevice.Earpiece
                "wired", "usb" -> device is AudioDevice.WiredHeadset
                "bluetooth" -> device is AudioDevice.BluetoothHeadset
                else -> false
            }
        } ?: devices.firstOrNull { kind == "hdmi" && it is AudioDevice.Earpiece }
            ?: return
        val selected = handler.selectedAudioDevice
        if (selected != null && selected::class == match::class) return
        handler.selectDevice(match)
    }

    private fun applyLegacy(audio: AudioManager, room: Room?, choice: DeviceChoice) {
        val handler = room?.audioSwitchHandler as? AudioSwitchHandler
        val kind = kindOfId(choice.id)
        handler?.forceHandleAudioRouting = true
        val speaker = AudioDevice.Speakerphone::class.java
        val earpiece = AudioDevice.Earpiece::class.java
        val wired = AudioDevice.WiredHeadset::class.java
        val bluetooth = AudioDevice.BluetoothHeadset::class.java
        handler?.preferredDeviceList = when (kind) {
            "speaker" -> listOf(speaker, wired, bluetooth, earpiece)
            "earpiece" -> listOf(earpiece, wired, bluetooth, speaker)
            "wired" -> listOf(wired, bluetooth, earpiece, speaker)
            else -> listOf(bluetooth, wired, earpiece, speaker)
        }
        val devices = handler?.availableAudioDevices.orEmpty()
        val match = devices.firstOrNull { device ->
            when (kind) {
                "speaker" -> device is AudioDevice.Speakerphone
                "earpiece" -> device is AudioDevice.Earpiece
                "wired" -> device is AudioDevice.WiredHeadset
                else -> device is AudioDevice.BluetoothHeadset
            }
        }
        if (match != null) handler?.selectDevice(match)
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = kind == "speaker"
        @Suppress("DEPRECATION")
        if (kind == "bluetooth") {
            audio.startBluetoothSco()
            audio.isBluetoothScoOn = true
        } else if (audio.isBluetoothScoOn) {
            audio.stopBluetoothSco()
            audio.isBluetoothScoOn = false
        }
    }

    private fun resolve(audio: AudioManager, choice: DeviceChoice): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val listed = audio.availableCommunicationDevices
        val id = choice.id.substringAfterLast(":").toIntOrNull()
        if (id != null) listed.firstOrNull { it.id == id }?.let { return it }
        val kind = kindOfId(choice.id)
        return listed.firstOrNull { kindOf(it.type) == kind }
    }

    private fun followInput(audio: AudioManager, room: Room?, output: AudioDeviceInfo) {
        val module = room?.lkObjects?.audioDeviceModule as? JavaAudioDeviceModule ?: return
        val wanted = inputKind(output.type)
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val match = inputs.firstOrNull { inputKind(it.type) == wanted }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: return
        try {
            module.setPreferredInputDevice(match)
        } catch (_: Exception) {
        }
    }

    private fun ensureVoiceVolume(audio: AudioManager) {
        try {
            val maxVoice = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (maxVoice > 0) {
                audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0)
            }
            val maxMusic = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxMusic > 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            }
        } catch (_: Exception) {
        }
        raisedVolume = true
    }

    private fun scheduleRepair() {
        if (!session || repairs >= 6) return
        repairs++
        val ctx = appRef ?: return
        val choice = locked ?: lastApplied(ctx) ?: return
        main.postDelayed({
            if (!session) return@postDelayed
            hold(ctx, roomRef, choice, syncSwitch = false)
        }, 350)
    }

    private fun listen(audio: AudioManager) {
        if (listening) return
        audioRef = audio
        audio.registerAudioDeviceCallback(deviceCallback, main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            commToken = Api31.listen(audio, executor) { device ->
                if (!session) return@listen
                val wanted = kindOfId(locked?.id ?: appliedId)
                val now = device?.type?.let { kindOf(it) }.orEmpty()
                if (wanted.isNotEmpty() && now != wanted) scheduleRepair()
            }
        }
        listening = true
    }

    private fun unlisten(audio: AudioManager?) {
        val current = audio ?: audioRef
        if (listening && current != null) {
            try {
                current.unregisterAudioDeviceCallback(deviceCallback)
            } catch (_: Exception) {
            }
            val token = commToken
            if (token != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    Api31.unlisten(current, token)
                } catch (_: Exception) {
                }
            }
        }
        commToken = null
        listening = false
        audioRef = null
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = reconcile()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = reconcile()
    }

    private fun reconcile() {
        if (!session || !ready) return
        val ctx = appRef ?: return
        val outputs = audioOutputs(ctx, roomRef)
        val kept = locked?.takeIf { choice -> userLocked && outputs.any { it.id == choice.id } }
        if (kept == null) {
            userLocked = false
            locked = null
        }
        val next = kept ?: defaultOutput(ctx, videoCall)
        if (next.id == appliedId) return
        hold(ctx, roomRef, next)
        onRouteChanged?.invoke(next)
    }

    private fun bindVolume(context: Context, active: Boolean) {
        val activity = context as? Activity ?: return
        activity.volumeControlStream = if (active) {
            AudioManager.STREAM_VOICE_CALL
        } else {
            AudioManager.USE_DEFAULT_STREAM_TYPE
        }
    }

    private fun numberDuplicates(devices: List<DeviceChoice>): List<DeviceChoice> {
        val counts = devices.groupingBy { it.label }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return devices.map { choice ->
            if ((counts[choice.label] ?: 0) < 2) {
                choice
            } else {
                val n = (seen[choice.label] ?: 0) + 1
                seen[choice.label] = n
                choice.copy(label = "${choice.label} $n")
            }
        }
    }

    private fun microphoneLabel(dev: AudioDeviceInfo): String? {
        return when (dev.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> bluetoothName(dev)
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> "Wired"
            else -> if (bleKind(dev.type) == "bluetooth") bluetoothName(dev) else null
        }
    }

    private fun bluetoothName(dev: AudioDeviceInfo): String {
        val name = dev.productName?.toString()?.trim().orEmpty()
        return name.ifBlank { "Bluetooth" }
    }

    private fun labelOf(dev: AudioDeviceInfo, kind: String): String {
        val name = dev.productName?.toString()?.trim().orEmpty()
        return when (kind) {
            "speaker" -> "Speaker"
            "earpiece" -> "Earpiece"
            "wired" -> "Wired headset"
            "usb" -> if (name.isNotEmpty()) name else "USB audio"
            "hdmi" -> "HDMI"
            else -> name.ifBlank { "Bluetooth" }
        }
    }

    private fun kindOf(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth"
        else -> bleKind(type)
    }

    private fun bleKind(type: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return if (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
            "bluetooth"
        } else {
            null
        }
    }

    private fun kindOfId(id: String): String = when {
        id.startsWith("comm:") || id.startsWith("out:") || id.startsWith("sw:") ->
            id.split(":").getOrNull(1).orEmpty()
        id == "speaker" || id == "earpiece" -> id
        else -> ""
    }

    private fun inputKind(type: Int): String = when (kindOf(type)) {
        "bluetooth" -> "bluetooth"
        "wired", "usb" -> "wired"
        else -> "builtin"
    }

    private fun switchName(device: AudioDevice): String = when (device) {
        is AudioDevice.BluetoothHeadset -> device.name
        else -> device.javaClass.simpleName
    }

    /** Loaded only on Android 12+, where the communication-device listener exists. */
    private object Api31 {
        fun listen(audio: AudioManager, executor: Executor, onMoved: (AudioDeviceInfo?) -> Unit): Any {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { onMoved(it) }
            audio.addOnCommunicationDeviceChangedListener(executor, listener)
            return listener
        }

        fun unlisten(audio: AudioManager, token: Any) {
            audio.removeOnCommunicationDeviceChangedListener(token as AudioManager.OnCommunicationDeviceChangedListener)
        }
    }
}
