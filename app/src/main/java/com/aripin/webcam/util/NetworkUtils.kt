package com.aripin.webcam.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    data class NetworkEndpoints(
        val wifiIp: String?,
        val usbTetherIp: String?,
        val primaryIp: String
    )

    fun detectLocalEndpoints(): NetworkEndpoints {
        var wifiIp: String? = null
        var usbIp: String? = null
        var fallbackIp: String? = null

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val addresses = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress ?: continue
                        when {
                            name.startsWith("wlan") || name.startsWith("ap") -> {
                                wifiIp = hostAddress
                            }
                            name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("eth") -> {
                                usbIp = hostAddress
                            }
                            else -> {
                                if (fallbackIp == null) fallbackIp = hostAddress
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val primary = wifiIp ?: usbIp ?: fallbackIp ?: "127.0.0.1"
        return NetworkEndpoints(
            wifiIp = wifiIp,
            usbTetherIp = usbIp,
            primaryIp = primary
        )
    }
}
