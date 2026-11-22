package com.ambilight.tv

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Servico em primeiro plano: captura a tela da TV, calcula as cores das bordas
 * e envia para o ESP32 pela USB no protocolo Adalight.
 */
class AmbilightService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var link: UsbLink

    @Volatile private var config: Config? = null
    @Volatile private var target = FloatArray(0)
    @Volatile private var running = false
    @Volatile private var screenOff = false
    @Volatile private var reopenUsb = false

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var senderThread: Thread? = null
    private val analyzer = ScreenAnalyzer()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOff = true
                Intent.ACTION_SCREEN_ON -> screenOff = false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE)
        config = Config.load(prefs)
        link = UsbLink(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || running) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // No Android 14+ o servico precisa estar em primeiro plano ANTES de pegar a projecao
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try { mpm.getMediaProjection(resultCode, data) } catch (e: Exception) { null }
        if (mp == null) {
            status = "Não foi possível iniciar a captura"
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        running = true
        isRunning = true
        startCapture(mp)
        startSender()
        return START_NOT_STICKY
    }

    private fun startCapture(mp: MediaProjection) {
        val thread = HandlerThread("ambilight-capture").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                status = "Captura encerrada pelo sistema"
                stopSelf()
            }
        }, handler)

        val dm = getSystemService(DisplayManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val w = CAPTURE_WIDTH
        val h = max(2, ((CAPTURE_WIDTH.toFloat() * metrics.heightPixels / metrics.widthPixels) / 2f).roundToInt() * 2)

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val cfg = config ?: return@setOnImageAvailableListener
                val plane = image.planes[0]
                target = analyzer.analyze(plane.buffer, image.width, image.height, plane.pixelStride, plane.rowStride, cfg)
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = mp.createVirtualDisplay(
            "ambilight", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun startSender() {
        senderThread = Thread({ senderLoop() }, "ambilight-sender").also { it.start() }
    }

    private fun senderLoop() {
        var smoothed = FloatArray(0)
        var nextOpenAttempt = 0L
        var openedAt = 0L
        var frame = ByteArray(0)

        while (running) {
            val cfg = config ?: break
            val now = SystemClock.elapsedRealtime()

            if (reopenUsb) {
                reopenUsb = false
                link.close()
            }
            if (!link.isOpen) {
                if (now >= nextOpenAttempt) {
                    val err = link.open(cfg.baud)
                    if (err == null) {
                        openedAt = now
                        status = "Conectado: ${link.deviceName}"
                    } else {
                        status = err
                        nextOpenAttempt = now + 2000
                    }
                }
                sleepQuiet(100)
                continue
            }
            // Se a placa reiniciou ao abrir a porta, espera ela terminar o teste de cores
            if (now - openedAt < 1500) {
                sleepQuiet(50)
                continue
            }

            val n = cfg.mapping.count
            if (n == 0) {
                sleepQuiet(200)
                continue
            }
            if (frame.size != 6 + n * 3) frame = ByteArray(6 + n * 3)

            when {
                screenOff -> frame.fill(0, 6, frame.size)
                cfg.testMode -> fillTestPattern(frame, cfg, now)
                else -> {
                    val tgt = target
                    if (tgt.size != n * 3) {
                        frame.fill(0, 6, frame.size)
                    } else {
                        if (smoothed.size != tgt.size) smoothed = tgt.copyOf()
                        val a = cfg.alpha
                        for (i in smoothed.indices) smoothed[i] += (tgt[i] - smoothed[i]) * a
                        fillColors(frame, smoothed, cfg)
                    }
                }
            }
            writeHeader(frame, n)

            if (!link.write(frame)) {
                status = "ESP32 desconectado, tentando de novo…"
                nextOpenAttempt = SystemClock.elapsedRealtime() + 1000
                continue
            }

            val elapsed = SystemClock.elapsedRealtime() - now
            sleepQuiet(max(1L, 1000L / cfg.fps - elapsed))
        }

        // Ao parar: apaga a fita na hora
        val n = config?.mapping?.count ?: 0
        if (link.isOpen && n > 0) {
            val black = ByteArray(6 + n * 3)
            writeHeader(black, n)
            repeat(3) {
                link.write(black)
                sleepQuiet(30)
            }
        }
        link.close()
    }

    private fun writeHeader(frame: ByteArray, n: Int) {
        val c = n - 1
        val hi = (c shr 8) and 0xFF
        val lo = c and 0xFF
        frame[0] = 'A'.code.toByte()
        frame[1] = 'd'.code.toByte()
        frame[2] = 'a'.code.toByte()
        frame[3] = hi.toByte()
        frame[4] = lo.toByte()
        frame[5] = (hi xor lo xor 0x55).toByte()
    }

    private fun fillColors(frame: ByteArray, colors: FloatArray, cfg: Config) {
        val sat = cfg.saturation / 100f
        val bright = cfg.brightness / 100f
        val lut = cfg.gammaLut
        val n = colors.size / 3
        for (i in 0 until n) {
            var r = colors[i * 3]
            var g = colors[i * 3 + 1]
            var b = colors[i * 3 + 2]
            // Realce de saturacao (afasta cada canal do cinza)
            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            r = gray + (r - gray) * sat
            g = gray + (g - gray) * sat
            b = gray + (b - gray) * sat
            var ri = lut[r.toInt().coerceIn(0, 255)]
            var gi = lut[g.toInt().coerceIn(0, 255)]
            var bi = lut[b.toInt().coerceIn(0, 255)]
            ri = (ri * bright).toInt()
            gi = (gi * bright).toInt()
            bi = (bi * bright).toInt()
            // Corta tons quase pretos para nao ficar piscando em cenas escuras
            if (max(ri, max(gi, bi)) < 3) { ri = 0; gi = 0; bi = 0 }
            frame[6 + i * 3] = ri.toByte()
            frame[7 + i * 3] = gi.toByte()
            frame[8 + i * 3] = bi.toByte()
        }
    }

    /** Esquerda vermelha, topo verde, direita azul, base branca; LED 1 pisca em rosa. */
    private fun fillTestPattern(frame: ByteArray, cfg: Config, now: Long) {
        val sides = cfg.mapping.sides
        val level = (180 * cfg.brightness / 100)
        val blink = (now / 400) % 2 == 0L
        for (i in sides.indices) {
            var r = 0
            var g = 0
            var b = 0
            when (sides[i]) {
                Side.LEFT -> r = level
                Side.TOP -> g = level
                Side.RIGHT -> b = level
                else -> { r = level / 3; g = level / 3; b = level / 3 }
            }
            if (i == 0) {
                if (blink) { r = level; g = 0; b = level } else { r = 0; g = 0; b = 0 }
            }
            frame[6 + i * 3] = r.toByte()
            frame[7 + i * 3] = g.toByte()
            frame[8 + i * 3] = b.toByte()
        }
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        val old = config
        val new = Config.load(p)
        config = new
        if (old != null && old.baud != new.baud) reopenUsb = true
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ambilight", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AmbilightService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Ambilight ligado")
            .setContentText("Lendo as cores da tela")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        try { senderThread?.join(1000) } catch (_: Exception) { }
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        try { projection?.stop() } catch (_: Exception) { }
        captureThread?.quitSafely()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        link.close()
        if (status.startsWith("Conectado")) status = "Parado"
        super.onDestroy()
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    companion object {
        const val ACTION_START = "com.ambilight.tv.START"
        const val ACTION_STOP = "com.ambilight.tv.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "ambilight"
        private const val NOTIF_ID = 1
        private const val CAPTURE_WIDTH = 160

        @Volatile var isRunning = false
            private set
        @Volatile var status: String = "Parado"
    }
}
