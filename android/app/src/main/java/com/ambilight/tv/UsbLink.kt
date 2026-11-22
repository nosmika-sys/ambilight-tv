package com.ambilight.tv

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/** Conexao serial USB com o ESP32 (CH340, CP2102, FTDI ou USB nativa). */
class UsbLink(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var lastPermissionRequest = 0L

    var deviceName: String = ""
        private set

    val isOpen: Boolean get() = port != null

    private fun findDriver(): UsbSerialDriver? {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isNotEmpty()) return drivers[0]
        // Plano B: qualquer dispositivo CDC-ACM (ex.: ESP32-S3 com USB nativa)
        for (dev in usbManager.deviceList.values) {
            for (i in 0 until dev.interfaceCount) {
                if (dev.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_COMM) {
                    return CdcAcmSerialDriver(dev)
                }
            }
        }
        return null
    }

    /** Abre a porta. Retorna null se deu certo, ou uma mensagem explicando o problema. */
    fun open(baud: Int): String? {
        close()
        val driver = findDriver() ?: return "ESP32 não encontrado na USB"
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPermissionRequest > 15_000) {
                lastPermissionRequest = now
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
                usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
            }
            return "Aguardando permissão da USB (aceite o aviso na tela)"
        }

        val connection = usbManager.openDevice(device) ?: return "Não foi possível abrir a USB"
        val p = driver.ports[0]
        return try {
            p.open(connection)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // DTR/RTS desligados: evita que o circuito de auto-reset reinicie o ESP32
            try {
                p.setDTR(false)
                p.setRTS(false)
            } catch (_: Exception) {
            }
            port = p
            deviceName = try { device.productName ?: device.deviceName } catch (_: Exception) { device.deviceName }
            null
        } catch (e: Exception) {
            try { p.close() } catch (_: Exception) { }
            try { connection.close() } catch (_: Exception) { }
            "Erro ao abrir a porta USB: ${e.message}"
        }
    }

    fun write(data: ByteArray): Boolean {
        val p = port ?: return false
        return try {
            p.write(data, 500)
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    fun close() {
        try { port?.close() } catch (_: Exception) { }
        port = null
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.ambilight.tv.USB_PERMISSION"
    }
}
