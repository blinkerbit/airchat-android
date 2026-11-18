package com.aird.core.crypto

enum class CipherChoice {
    AES_256_GCM,
    ASCON_128A,
}

data class DeviceProfile(
    val flagship: Boolean,
    val onAcPower: Boolean,
    val batteryPercent: Int,
    val thermalThrottling: Boolean,
)

fun selectCipher(profile: DeviceProfile): CipherChoice {
    val constrained = !profile.flagship || profile.batteryPercent < 20 || profile.thermalThrottling
    if (profile.onAcPower && profile.flagship && !profile.thermalThrottling) return CipherChoice.AES_256_GCM
    if (!profile.onAcPower && constrained) return CipherChoice.ASCON_128A
    return if (profile.flagship && !profile.thermalThrottling) CipherChoice.AES_256_GCM else CipherChoice.ASCON_128A
}
