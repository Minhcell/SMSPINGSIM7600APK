package com.smsping.otg

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kết nối OTG tới modem bằng USB Host API thô (bulk transfer) — GIỮ NGUYÊN như bản gốc.
 * Không set baud (modem SIM7600/Qualcomm nhận AT thẳng trên endpoint bulk).
 */
class UsbAtManager(
    private val context: Context,
    private val onDataReceived: (String) -> Unit,
    private val onStatusChanged: (Boolean) -> Unit
) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.smsping.otg.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private val running = AtomicBoolean(false)
    private var permissionCallback: ((Boolean) -> Unit)? = null

    val isOpen: Boolean get() = claimedInterface != null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra("permission", false)
                permissionCallback?.invoke(granted)
                permissionCallback = null
            }
        }
    }

    fun register() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }
    }

    fun unregister() {
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Throwable) {}
    }

    /** Liệt kê mọi thiết bị USB (để chẩn đoán). */
    fun listAllRawDevices(): List<String> = usbManager.deviceList.values.map { d ->
        "Tên: ${d.deviceName} | VID: ${d.vendorId} (0x${Integer.toString(d.vendorId, 16)})" +
            " | PID: ${d.productId} (0x${Integer.toString(d.productId, 16)})" +
            " | Interfaces: ${d.interfaceCount}"
    }

    /** Lọc modem theo VID 0x1E0E (7694) hoặc 0x05C6 (1478). */
    fun findMatchingDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { it.vendorId == 7694 || it.vendorId == 1478 }

    /** Tất cả thiết bị USB (dùng khi dongle có VID lạ, cho người dùng tự chọn). */
    fun allDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    fun connect(device: UsbDevice, interfaceIndex: Int, onResult: (Boolean, String) -> Unit) {
        if (!usbManager.hasPermission(device)) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            permissionCallback = { granted ->
                if (granted) openInterface(device, interfaceIndex, onResult)
                else onResult(false, "Bạn chưa cấp quyền truy cập USB")
            }
            usbManager.requestPermission(device, pi)
            return
        }
        openInterface(device, interfaceIndex, onResult)
    }

    private fun openInterface(device: UsbDevice, interfaceIndex: Int, onResult: (Boolean, String) -> Unit) {
        if (interfaceIndex < 0 || interfaceIndex >= device.interfaceCount) {
            onResult(false, "Interface số $interfaceIndex không tồn tại trên thiết bị này"); return
        }
        val iface = device.getInterface(interfaceIndex)
        val conn = usbManager.openDevice(device)
        if (conn == null) { onResult(false, "Không mở được thiết bị USB"); return }

        var foundIn: UsbEndpoint? = null
        var foundOut: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) foundIn = ep else foundOut = ep
            }
        }
        if (foundIn == null || foundOut == null) {
            conn.close()
            onResult(false, "Interface $interfaceIndex không có endpoint bulk IN/OUT (thử interface khác)"); return
        }
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            onResult(false, "Không claim được interface $interfaceIndex (Android đang dùng riêng?)"); return
        }
        connection = conn
        claimedInterface = iface
        epIn = foundIn
        epOut = foundOut
        running.set(true)
        Executors.newSingleThreadExecutor().submit { readLoop() }
        onStatusChanged(true)
        onResult(true, "Kết nối thành công (interface $interfaceIndex)")
    }

    private fun readLoop() {
        val buffer = ByteArray(4096)
        while (running.get()) {
            var len = -1
            val conn = connection
            val inEp = epIn
            if (conn != null && inEp != null) {
                try { len = conn.bulkTransfer(inEp, buffer, buffer.size, 500) } catch (_: Exception) {}
            }
            if (len > 0) onDataReceived(String(buffer, 0, len, Charsets.US_ASCII))
        }
    }

    fun write(text: String) {
        val out = epOut ?: return
        val conn = connection ?: return
        val data = text.toByteArray(Charsets.US_ASCII)
        try { conn.bulkTransfer(out, data, data.size, 1000) } catch (_: Exception) {}
    }

    fun disconnect() {
        running.set(false)
        try {
            claimedInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) {} finally {
            connection = null; claimedInterface = null; epIn = null; epOut = null
            onStatusChanged(false)
        }
    }
}
