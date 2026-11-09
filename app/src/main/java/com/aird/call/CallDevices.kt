package com.aird.call

import android.content.Context
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

data class DeviceChoice(val id: String, val label: String)

object CallDevices {
    private val main = Handler(Looper.getMainLooper())
    private var routeGeneration = 0

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
        val list = mutableListOf<DeviceChoice>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && audio != null) {
            val commDevices = audio.availableCommunicationDevices
            for (dev in commDevices) {
                val label = when (dev.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                        val name = dev.productName?.toString()?.trim().orEmpty()
                        if (name.isNotEmpty() && !name.contains("bluetooth", true)) "Bluetooth ($name)" else "Bluetooth"
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "Wired Headset"
                    else -> null
                }
                if (label != null) {
                    list.add(DeviceChoice(id = "comm:${dev.id}", label = label))
                }
            }
        }

        if (list.isEmpty()) {
            val switched = room?.audioSwitchHandler?.availableAudioDevices.orEmpty()
            if (switched.isNotEmpty()) {
                for (dev in switched) {
                    val label = when (dev) {
                        is AudioDevice.Speakerphone -> "Speaker"
                        is AudioDevice.Earpiece -> "Earpiece"
                        is AudioDevice.BluetoothHeadset -> "Bluetooth (${dev.name})"
                        is AudioDevice.WiredHeadset -> "Wired Headset"
                    }
                    list.add(DeviceChoice(id = switchId(dev), label = label))
                }
            }
        }

        if (list.none { it.label.contains("Earpiece", true) }) {
            list.add(DeviceChoice("earpiece", "Earpiece"))
        }
        if (list.none { it.label.contains("Speaker", true) }) {
            list.add(DeviceChoice("speaker", "Speaker"))
        }

        return list.distinctBy { it.label }
    }

    fun outputKey(choice: DeviceChoice): String = when (routeOf(choice)) {
        Route.Speaker -> "speaker"
        Route.Earpiece -> "earpiece"
        Route.Headset -> "headset"
    }

    fun hint(choice: DeviceChoice): String = when (routeOf(choice)) {
        Route.Speaker -> "Speaker"
        Route.Earpiece -> "Earpiece"
        Route.Headset -> "Headset"
    }

    fun cancelRoutes() {
        routeGeneration++
    }

    /** Set before the room connects. Voice stays on the earpiece. Video starts on the speaker. */
    fun prepareRouter(room: Room?, speakerDefault: Boolean) {
        val handler = room?.audioSwitchHandler as? AudioSwitchHandler ?: return
        handler.forceHandleAudioRouting = true
        val earpiece = AudioDevice.Earpiece::class.java
        val speaker = AudioDevice.Speakerphone::class.java
        val handsFree = listOf(AudioDevice.BluetoothHeadset::class.java, AudioDevice.WiredHeadset::class.java)
        handler.preferredDeviceList = if (speakerDefault) {
            handsFree + speaker + earpiece
        } else {
            handsFree + earpiece + speaker
        }
    }

    fun applyOutput(context: Context, room: Room?, output: String) {
        val choice = audioOutputs(context, room).firstOrNull { it.id == output }
            ?: audioOutputs(context, room).firstOrNull { outputKey(it) == output }
            ?: DeviceChoice(output, output)
        selectAudioOutput(context, room, choice)
    }

    fun selectAudioOutput(context: Context, room: Room?, choice: DeviceChoice) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val gen = ++routeGeneration
        val apply = Runnable {
            if (gen != routeGeneration) return@Runnable
            try {
                holdRoute(audio, room, choice)
            } catch (_: Exception) {
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else main.post(apply)
        // Samsung reapplies its own route after setCommunicationDevice returns.
        main.postDelayed(apply, 280)
        main.postDelayed(apply, 900)
        if (Build.MANUFACTURER.equals("samsung", true)) main.postDelayed(apply, 1700)
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

    private fun microphoneLabel(dev: AudioDeviceInfo): String? {
        return when (dev.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> bluetoothMic(dev)
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> "Wired"
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                bluetoothMic(dev)
            } else {
                null
            }
        }
    }

    private fun bluetoothMic(dev: AudioDeviceInfo): String {
        val name = dev.productName?.toString()?.trim().orEmpty()
        return if (name.isNotEmpty() && !name.contains("bluetooth", true)) "Bluetooth ($name)" else "Bluetooth"
    }

    private fun holdRoute(audio: AudioManager, room: Room?, choice: DeviceChoice) {
        val route = routeOf(choice)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        preferSwitch(room, route, choice)
        selectSwitchByLabel(room, switchLabel(choice))
        @Suppress("DEPRECATION")
        if (route != Route.Headset && audio.isBluetoothScoOn) {
            @Suppress("DEPRECATION")
            audio.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = false
        }
        // S26 and other Samsung phones report success from setCommunicationDevice and
        // then keep the previous speaker. The speakerphone flag has to be set as well.
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = route == Route.Speaker
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listed = audio.availableCommunicationDevices
            val exactId = choice.id.substringAfter("comm:", "").toIntOrNull()
            val target = listed.firstOrNull { it.id == exactId } ?: listed.firstOrNull { matches(it, route) }
            if (target != null) {
                audio.clearCommunicationDevice()
                audio.setCommunicationDevice(target)
            } else {
                audio.clearCommunicationDevice()
            }
        } else if (route == Route.Headset && (choice.id.startsWith("bt:") || choice.label.contains("Bluetooth", true))) {
            @Suppress("DEPRECATION")
            audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
        }
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = route == Route.Speaker
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        selectSwitchByLabel(room, switchLabel(choice))
    }

    private fun preferSwitch(room: Room?, route: Route, choice: DeviceChoice) {
        val handler = room?.audioSwitchHandler as? AudioSwitchHandler ?: return
        handler.forceHandleAudioRouting = true
        val speaker = AudioDevice.Speakerphone::class.java
        val earpiece = AudioDevice.Earpiece::class.java
        val wired = AudioDevice.WiredHeadset::class.java
        val bluetooth = AudioDevice.BluetoothHeadset::class.java
        handler.preferredDeviceList = when (route) {
            Route.Speaker -> listOf(speaker, wired, bluetooth, earpiece)
            Route.Earpiece -> listOf(earpiece, wired, bluetooth, speaker)
            Route.Headset -> if (choice.label.contains("Wired", true)) {
                listOf(wired, bluetooth, earpiece, speaker)
            } else {
                listOf(bluetooth, wired, earpiece, speaker)
            }
        }
    }

    private fun matches(dev: AudioDeviceInfo, route: Route): Boolean {
        return when (route) {
            Route.Speaker -> dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            Route.Earpiece -> dev.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            Route.Headset -> when (dev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET || dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
    }

    private fun routeOf(choice: DeviceChoice): Route = when {
        choice.id.startsWith("bt:") || choice.label.contains("Bluetooth", true) || choice.label.contains("Wired", true) || choice.label.contains("Headset", true) -> Route.Headset
        choice.id == "speaker" || choice.label.equals("Speaker", true) -> Route.Speaker
        choice.id == "earpiece" || choice.label.contains("Earpiece", true) -> Route.Earpiece
        choice.label.contains("Speaker", true) -> Route.Speaker
        else -> Route.Earpiece
    }

    private fun switchLabel(choice: DeviceChoice): String = when {
        choice.label.contains("Wired", true) -> "Wired"
        choice.id.startsWith("bt:") || choice.label.contains("Bluetooth", true) -> "Bluetooth"
        routeOf(choice) == Route.Speaker -> "Speaker"
        else -> "Earpiece"
    }

    private fun selectSwitchByLabel(room: Room?, label: String) {
        val handler = room?.audioSwitchHandler ?: return
        val devices = handler.availableAudioDevices
        val match = devices.firstOrNull { device ->
            when {
                label.contains("Speaker", true) -> device is AudioDevice.Speakerphone
                label.contains("Bluetooth", true) -> device is AudioDevice.BluetoothHeadset
                label.contains("Wired", true) -> device is AudioDevice.WiredHeadset
                else -> device is AudioDevice.Earpiece
            }
        } ?: return
        handler.selectDevice(match)
    }

    private enum class Route { Speaker, Earpiece, Headset }

    private fun switchId(device: AudioDevice): String = when (device) {
        is AudioDevice.Speakerphone -> "speaker"
        is AudioDevice.Earpiece -> "earpiece"
        is AudioDevice.WiredHeadset -> "wired"
        is AudioDevice.BluetoothHeadset -> "bt:${device.name}"
    }
}
