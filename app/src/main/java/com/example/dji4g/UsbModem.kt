package com.example.dji4g

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager

class UsbModem(private val ctx: Context) {

    val usbManager: UsbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findDjiDevice(): UsbDevice? {
        val all = usbManager.deviceList.values
        all.firstOrNull { it.vendorId == 0x2CA3 }?.let { return it }
        return all.firstOrNull {
            it.interfaceCount > 0 && it.deviceClass != UsbConstants.USB_CLASS_HUB
        }
    }

    fun listDevices(): String {
        val sb = StringBuilder()
        val all = usbManager.deviceList.values
        if (all.isEmpty()) return ""
        for (d in all) {
            sb.append("VID=${d.vendorId.toString(16).padStart(4, '0')} " +
                "PID=${d.productId.toString(16).padStart(4, '0')} " +
                "class=${d.deviceClass} sub=${d.deviceSubclass} ifaces=${d.interfaceCount} name=${d.deviceName}\n")
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                sb.append("  if$i class=${itf.interfaceClass} sub=${itf.interfaceSubclass} " +
                    "proto=${itf.interfaceProtocol} eps=${itf.endpointCount}\n")
                for (j in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(j)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    sb.append("    ep$j $dir type=${ep.type} addr=${ep.address}\n")
                }
            }
        }
        return sb.toString()
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice, receiver: BroadcastReceiver) {
        val action = "com.example.dji4g.USB_PERMISSION"
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(action).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, pi)
    }

    data class AtPort(
        val connection: UsbDeviceConnection,
        val epIn: UsbEndpoint,
        val epOut: UsbEndpoint,
        val ifaceIdx: Int
    )

    /** Scan interfaces for a bulk IN+OUT pair that answers "AT". */
    fun openAtPort(device: UsbDevice): AtPort? {
        if (!usbManager.hasPermission(device)) return null
        val conn = usbManager.openDevice(device) ?: return null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (j in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
                    else if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
                }
            }
            if (epIn != null && epOut != null) {
                try {
                    conn.claimInterface(itf, true)
                    val r = sendAt(conn, epIn, epOut, "AT", 800)
                    if (r.contains("OK") || r.contains("ERROR")) {
                        return AtPort(conn, epIn, epOut, i)
                    }
                } catch (e: Exception) {
                    // try next interface
                }
            }
        }
        conn.close()
        return null
    }

    fun sendAt(
        conn: UsbDeviceConnection,
        epIn: UsbEndpoint,
        epOut: UsbEndpoint,
        cmd: String,
        timeoutMs: Int = 1500
    ): String {
        val data = (cmd + "\r\n").toByteArray(Charsets.US_ASCII)
        val sent = conn.bulkTransfer(epOut, data, data.size, timeoutMs)
        if (sent < 0) return "ERR:out($sent)"
        val buf = ByteArray(4096)
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = conn.bulkTransfer(epIn, buf, buf.size, 200)
            if (n > 0) {
                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                val s = sb.toString()
                if (s.contains("OK") || s.contains("ERROR") || s.contains("+CME ERROR")) break
            }
        }
        return sb.toString().trim()
    }
}
