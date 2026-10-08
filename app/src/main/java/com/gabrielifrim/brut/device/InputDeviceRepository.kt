package com.gabrielifrim.brut.device

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Famille d'entrée, du point de vue de l'utilisateur. */
enum class InputKind { USB, WIRED, BUILTIN, BLUETOOTH, OTHER }

/** Une entrée audio telle qu'Android la voit. */
data class InputDevice(
    val id: Int,
    val kind: InputKind,
    val productName: String,
    val address: String,
    /** Nombres de canaux annoncés ; vide = le pilote ne précise pas (tout est tenté). */
    val channelCounts: List<Int>,
    /** Fréquences annoncées ; vide = le pilote ne précise pas. */
    val sampleRates: List<Int>,
    val info: AudioDeviceInfo,
) {
    val isExternal: Boolean get() = kind == InputKind.USB || kind == InputKind.WIRED

    fun supportsChannels(n: Int) = channelCounts.isEmpty() || channelCounts.any { it >= n }
    fun supportsRate(rate: Int) = sampleRates.isEmpty() || rate in sampleRates
}

/**
 * Liste les entrées audio et suit les branchements à chaud (micro USB-C, adaptateur
 * jack). Aucune entrée n'est imposée : le choix reste à l'utilisateur.
 */
class InputDeviceRepository(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _devices = MutableStateFlow(query())
    val devices: StateFlow<List<InputDevice>> = _devices.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            _devices.value = query()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            _devices.value = query()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    /**
     * Entrée à proposer par défaut : une entrée externe si elle existe — c'est
     * précisément pour elle qu'on branche un micro — sinon le micro interne.
     */
    fun preferredDefault(list: List<InputDevice> = _devices.value): InputDevice? =
        list.firstOrNull { it.kind == InputKind.USB }
            ?: list.firstOrNull { it.kind == InputKind.WIRED }
            ?: list.firstOrNull { it.kind == InputKind.BUILTIN }
            ?: list.firstOrNull()

    private fun query(): List<InputDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
            .mapNotNull { info ->
                val kind = kindOf(info.type) ?: return@mapNotNull null
                InputDevice(
                    id = info.id,
                    kind = kind,
                    productName = info.productName?.toString().orEmpty().trim(),
                    address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address.orEmpty() else "",
                    channelCounts = info.channelCounts.toList().distinct().sorted(),
                    sampleRates = info.sampleRates.toList().distinct().sorted(),
                    info = info,
                )
            }
            .sortedBy { it.kind.ordinal }

    companion object {
        /** Types exclus (téléphonie, mixage interne, tuner...) : `null`. */
        fun kindOf(type: Int): InputKind? = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> InputKind.USB
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> InputKind.WIRED
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputKind.BUILTIN
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET -> InputKind.BLUETOOTH
            AudioDeviceInfo.TYPE_DOCK -> InputKind.OTHER
            else -> null
        }
    }
}
