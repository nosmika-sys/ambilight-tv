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
    @Volatile private var frames = 0L
    @Volatile private var sentFrames = 0L
    private var lastDebug = 0L

    /** Diagnostico: salva o ultimo quadro capturado e um resumo em arquivos (lidos via ADB). */
    private fun saveDebug(buf: java.nio.ByteBuffer, w: Int, h: Int, ps: Int, rs: Int) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            val px = IntArray(w * h)
            var sum = 0L
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * rs + x * ps
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                sum += r + g + b
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            java.io.File(dir, "frame.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val t = target
            val sample = (0 until minOf(6, t.size / 3)).joinToString(" ") { "(${t[it*3].toInt()},${t[it*3+1].toInt()},${t[it*3+2].toInt()})" }
            java.io.File(dir, "debug.txt").writeText(
                "time=${System.currentTimeMillis()} status=$status usbOpen=${link.isOpen}\n" +
                "capturedFrames=$frames sentFrames=$sentFrames size=${w}x$h avgBrightness=${sum / (w * h * 3)}\n" +
                "leds=${config?.mapping?.count} first=$sample\n"
            )
        } catch (_: Exception) { }
    }

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
        if (intent.getBooleanExtra(EXTRA_DIAG, false)) {
            status = "Diagnóstico de captura rodando…"
            val dpi = resources.displayMetrics.densityDpi
            val file = java.io.File(getExternalFilesDir(null), "diag.txt")
            Thread({
                try { CaptureDiag(mp, dpi, file) { running }.run() } catch (e: Exception) {
                    try { file.appendText("ERRO GERAL ${e.message}\n") } catch (_: Exception) { }
                }
                status = "Diagnóstico concluído"
                stopSelf()
            }, "ambilight-diag").start()
            return START_NOT_STICKY
        }
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
        val compat = config?.compatCapture ?: true
        if (compat) {
            try {
                startCodecCapture(mp, metrics)
                return
            } catch (e: Exception) {
                // Codificador indisponivel: cai para a captura normal
                try { virtualDisplay?.release() } catch (_: Exception) { }
                try { encoder?.release() } catch (_: Exception) { }
                encoder = null
            }
        }

        val w = CAPTURE_WIDTH
        val h = max(2, ((CAPTURE_WIDTH.toFloat() * metrics.heightPixels / metrics.widthPixels) / 2f).roundToInt() * 2)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        var lastAnalyze = 0L
        reader.setOnImageAvailableListener({ r ->
            // Sempre libera o quadro na hora (nunca segura o compositor da TV);
            // so analisa no ritmo de "Capturas da tela por segundo".
            val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val cfg = config ?: return@setOnImageAvailableListener
                val now = SystemClock.elapsedRealtime()
                if (now - lastAnalyze >= 1000L / cfg.captureFps) {
                    lastAnalyze = now
                    val plane = image.planes[0]
                    onFrame(plane.buffer, image.width, image.height, plane.pixelStride, plane.rowStride, cfg, now)
                }
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

    private fun onFrame(buf: java.nio.ByteBuffer, w: Int, h: Int, ps: Int, rs: Int, cfg: Config, now: Long) {
        target = analyzer.analyze(buf, w, h, ps, rs, cfg)
        frames++
        if (now - lastDebug > 3000) {
            lastDebug = now
            saveDebug(buf, w, h, ps, rs)
        }
    }

    // ------------------------------------------------------------------
    // Captura "Codec": tela -> codificador H.264 -> decodificador -> pixels.
    // Em TVs TCL/Amlogic/MediaTek o hardware so mistura a camada do video
    // quando o destino da captura e um codificador de video de verdade.
    // ------------------------------------------------------------------
    @Volatile private var codecThread: Thread? = null
    private var encoder: android.media.MediaCodec? = null
    private var decoder: android.media.MediaCodec? = null

    private fun startCodecCapture(mp: MediaProjection, metrics: DisplayMetrics) {
        val w = CODEC_W
        val h = CODEC_H
        val enc = android.media.MediaCodec.createEncoderByType("video/avc")
        val fmt = android.media.MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Repete o ultimo quadro em telas paradas, para o fluxo nunca travar
            setLong(android.media.MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
        }
        enc.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = enc.createInputSurface()
        enc.start()
        encoder = enc

        virtualDisplay = mp.createVirtualDisplay(
            "ambilight", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            input, null, null
        )
        codecThread = Thread({ codecLoop(enc, w, h) }, "ambilight-codec").also { it.start() }
    }

    private fun codecLoop(enc: android.media.MediaCodec, w: Int, h: Int) {
        val info = android.media.MediaCodec.BufferInfo()
        val dinfo = android.media.MediaCodec.BufferInfo()
        var dec: android.media.MediaCodec? = null
        var rgba = java.nio.ByteBuffer.allocateDirect(w * h * 4)
        var lastAnalyze = 0L
        try {
            while (running) {
                val idx = enc.dequeueOutputBuffer(info, 20_000)
                if (idx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFmt = enc.outputFormat
                    outFmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                        android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    val d = android.media.MediaCodec.createDecoderByType("video/avc")
                    d.configure(outFmt, null, null, 0)
                    d.start()
                    dec = d
                    decoder = d
                } else if (idx >= 0) {
                    val d = dec
                    val isConfig = (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (d != null && !isConfig && info.size > 0) {
                        val din = d.dequeueInputBuffer(20_000)
                        if (din >= 0) {
                            val src = enc.getOutputBuffer(idx)!!
                            src.position(info.offset)
                            src.limit(info.offset + info.size)
                            val dst = d.getInputBuffer(din)!!
                            dst.clear()
                            dst.put(src)
                            d.queueInputBuffer(din, 0, info.size, info.presentationTimeUs, 0)
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
                // Esvazia o decodificador
                val d = dec ?: continue
                while (true) {
                    val dout = d.dequeueOutputBuffer(dinfo, 0)
                    if (dout < 0) break
                    val cfg = config
                    val now = SystemClock.elapsedRealtime()
                    if (cfg != null && now - lastAnalyze >= 1000L / cfg.captureFps) {
                        lastAnalyze = now
                        val img = try { d.getOutputImage(dout) } catch (_: Exception) { null }
                        if (img != null) {
                            try {
                                val iw = img.width
                                val ih = img.height
                                if (rgba.capacity() < iw * ih * 4) rgba = java.nio.ByteBuffer.allocateDirect(iw * ih * 4)
                                yuvToRgba(img, rgba, iw, ih)
                                onFrame(rgba, iw, ih, 4, iw * 4, cfg, now)
                            } catch (_: Exception) {
                            } finally {
                                img.close()
                            }
                        }
                    }
                    d.releaseOutputBuffer(dout, false)
                }
            }
        } catch (e: Exception) {
            status = "Erro na captura: ${e.message}"
        } finally {
            try { dec?.stop() } catch (_: Exception) { }
            try { dec?.release() } catch (_: Exception) { }
        }
    }

    /** Converte YUV_420_888 (saida do decodificador) para RGBA. */
    private fun yuvToRgba(img: android.media.Image, out: java.nio.ByteBuffer, w: Int, h: Int) {
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val yB = yP.buffer
        val uB = uP.buffer
        val vB = vP.buffer
        val yRs = yP.rowStride
        val yPs = yP.pixelStride
        val uRs = uP.rowStride
        val uPs = uP.pixelStride
        val vRs = vP.rowStride
        val vPs = vP.pixelStride
        for (y in 0 until h) {
            for (x in 0 until w) {
                val yy = (yB.get(y * yRs + x * yPs).toInt() and 0xFF) - 16
                val cx = x / 2
                val cy = y / 2
                val u = (uB.get(cy * uRs + cx * uPs).toInt() and 0xFF) - 128
                val v = (vB.get(cy * vRs + cx * vPs).toInt() and 0xFF) - 128
                val c = 1.164f * yy
                val r = (c + 1.596f * v).toInt().coerceIn(0, 255)
                val g = (c - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
                val b = (c + 2.017f * u).toInt().coerceIn(0, 255)
                val o = (y * w + x) * 4
                out.put(o, r.toByte())
                out.put(o + 1, g.toByte())
                out.put(o + 2, b.toByte())
                out.put(o + 3, 0xFF.toByte())
            }
        }
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

            if (link.write(frame)) sentFrames++ else {
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
            putColor(frame, i, ri, gi, bi, cfg.colorOrder)
        }
    }

    /** Escreve o LED i no quadro, trocando os canais conforme a "Ordem das cores" do app. */
    private fun putColor(frame: ByteArray, i: Int, r: Int, g: Int, b: Int, order: Int) {
        val o = 6 + i * 3
        val name = Config.COLOR_ORDERS[order]
        for (k in 0 until 3) {
            val v = when (name[k]) {
                'R' -> r
                'G' -> g
                else -> b
            }
            frame[o + k] = v.toByte()
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
            putColor(frame, i, r, g, b, cfg.colorOrder)
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
        try { codecThread?.join(500) } catch (_: Exception) { }
        try { encoder?.stop() } catch (_: Exception) { }
        try { encoder?.release() } catch (_: Exception) { }
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
        const val EXTRA_DIAG = "diag"
        private const val CHANNEL = "ambilight"
        private const val NOTIF_ID = 1
        private const val CAPTURE_WIDTH = 128
        private const val CODEC_W = 320
        private const val CODEC_H = 176

        @Volatile var isRunning = false
            private set
        @Volatile var status: String = "Parado"
    }
}
