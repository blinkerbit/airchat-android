package com.aird.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.room.Room
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.audio.JavaAudioDeviceModule

data class DeviceChoice(val id: String, val label: String)

object CallDevices {
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

    fun selectAudioOutput(context: Context, room: Room?, choice: DeviceChoice) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && choice.id.startsWith("comm:")) {
            val devId = choice.id.substringAfter("comm:").toIntOrNull()
            val target = audio.availableCommunicationDevices.firstOrNull { it.id == devId }
            if (target != null) {
                audio.setCommunicationDevice(target)
                selectSwitchByLabel(room, choice.label)
                return
            }
        }

        when {
            choice.id == "speaker" || choice.label.contains("Speaker", true) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
                @Suppress("DEPRECATION")
                audio.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
                selectSwitchByLabel(room, "Speaker")
            }
            choice.id.startsWith("bt:") || choice.label.contains("Bluetooth", true) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
                @Suppress("DEPRECATION")
                audio.startBluetoothSco()
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = true
                selectSwitchByLabel(room, "Bluetooth")
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
                @Suppress("DEPRECATION")
                audio.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
                selectSwitchByLabel(room, "Earpiece")
            }
        }
    }

    fun playback(context: Context, room: Room?): List<DeviceChoice> = audioOutputs(context, room)
    fun selectPlayback(context: Context, room: Room?, id: String) {
        val match = audioOutputs(context, room).firstOrNull { it.id == id } ?: DeviceChoice(id, id)
        selectAudioOutput(context, room, match)
    }

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

    private fun selectSwitchByLabel(room: Room?, label: String) {
        val devices = room?.audioSwitchHandler?.availableAudioDevices ?: return
        val match = devices.firstOrNull { device ->
            when {
                label.contains("Speaker", true) -> device is AudioDevice.Speakerphone
                label.contains("Bluetooth", true) -> device is AudioDevice.BluetoothHeadset
                label.contains("Wired", true) -> device is AudioDevice.WiredHeadset
                else -> device is AudioDevice.Earpiece
            }
        }
        if (match != null) room.audioSwitchHandler?.selectDevice(match)
    }

    private fun switchId(device: AudioDevice): String = when (device) {
        is AudioDevice.Speakerphone -> "speaker"
        is AudioDevice.Earpiece -> "earpiece"
        is AudioDevice.WiredHeadset -> "wired"
        is AudioDevice.BluetoothHeadset -> "bt:${device.name}"
    }
}
