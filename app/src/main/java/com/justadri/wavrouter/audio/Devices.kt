package com.justadri.wavrouter.audio

import android.media.AudioDeviceInfo
import android.media.AudioDeviceInfo.TYPE_AUX_LINE
import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
import android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_DOCK
import android.media.AudioDeviceInfo.TYPE_HDMI
import android.media.AudioDeviceInfo.TYPE_HEARING_AID
import android.media.AudioDeviceInfo.TYPE_LINE_ANALOG
import android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL
import android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY
import android.media.AudioDeviceInfo.TYPE_USB_DEVICE
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
import android.media.AudioManager

data class DeviceOption(
    val id: Int,
    val type: Int,
    val label: String,
    val detail: String,
    val builtin: Boolean,
)

object Devices {
    private val INPUT_TYPES = setOf(
        TYPE_BUILTIN_MIC, TYPE_WIRED_HEADSET, TYPE_USB_DEVICE, TYPE_USB_HEADSET, TYPE_USB_ACCESSORY,
        TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET, TYPE_LINE_ANALOG, TYPE_LINE_DIGITAL, TYPE_DOCK,
    )
    private val OUTPUT_TYPES = setOf(
        TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_EARPIECE, TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES,
        TYPE_USB_DEVICE, TYPE_USB_HEADSET, TYPE_USB_ACCESSORY, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO,
        TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_HEARING_AID, TYPE_LINE_ANALOG, TYPE_LINE_DIGITAL,
        TYPE_AUX_LINE, TYPE_DOCK, TYPE_HDMI,
    )
    private val BUILTIN = setOf(TYPE_BUILTIN_MIC, TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_EARPIECE)

    /** Outputs that only route under communication mode. */
    val COMM_OUTPUT_TYPES = setOf(TYPE_BUILTIN_EARPIECE, TYPE_BLUETOOTH_SCO)
    /** Inputs that need setCommunicationDevice() to come up (HFP / LE Audio mic). */
    val COMM_INPUT_TYPES = setOf(TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET)

    fun inputs(am: AudioManager): List<DeviceOption> =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in INPUT_TYPES }
            .map(::toOption)
            .sortedBy { it.builtin }

    fun outputs(am: AudioManager): List<DeviceOption> =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in OUTPUT_TYPES }
            .map(::toOption)
            .sortedBy { it.builtin }

    fun toOption(d: AudioDeviceInfo) = DeviceOption(d.id, d.type, label(d), detail(d), d.type in BUILTIN)

    fun label(d: AudioDeviceInfo): String {
        val name = d.productName?.toString()?.trim().orEmpty()
        // Built-in mics often expose their position as the address ("bottom", "back", ...).
        val addr = d.address?.takeIf { it.isNotBlank() && d.type == TYPE_BUILTIN_MIC }
        return buildString {
            append(typeName(d.type))
            if (name.isNotEmpty() && d.type !in BUILTIN) append(" · ").append(name)
            if (addr != null) append(" (").append(addr).append(')')
        }
    }

    private fun detail(d: AudioDeviceInfo): String {
        val rates = d.sampleRates.takeIf { it.isNotEmpty() }
            ?.joinToString(",") { (it / 1000.0).toString().removeSuffix(".0") + "k" } ?: "any"
        val ch = d.channelCounts.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "any"
        return "id ${d.id} · rates $rates · ch $ch"
    }

    fun typeName(t: Int): String = when (t) {
        TYPE_BUILTIN_MIC -> "Built-in mic"
        TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        TYPE_BUILTIN_EARPIECE -> "Earpiece"
        TYPE_WIRED_HEADSET -> "Wired headset"
        TYPE_WIRED_HEADPHONES -> "Wired headphones"
        TYPE_USB_DEVICE -> "USB"
        TYPE_USB_HEADSET -> "USB headset"
        TYPE_USB_ACCESSORY -> "USB accessory"
        TYPE_BLUETOOTH_A2DP -> "BT A2DP"
        TYPE_BLUETOOTH_SCO -> "BT SCO"
        TYPE_BLE_HEADSET -> "BLE headset"
        TYPE_BLE_SPEAKER -> "BLE speaker"
        TYPE_HEARING_AID -> "Hearing aid"
        TYPE_LINE_ANALOG -> "Line analog"
        TYPE_LINE_DIGITAL -> "Line digital"
        TYPE_AUX_LINE -> "Aux line"
        TYPE_DOCK -> "Dock"
        TYPE_HDMI -> "HDMI"
        else -> "Type $t"
    }
}
