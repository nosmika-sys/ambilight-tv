package com.ambilight.tv

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * Diagnostico: testa todas as combinacoes de captura que a TV oferece e anota
 * qual delas enxerga a imagem do video (brilho medio / maximo do quadro).
 */
class CaptureDiag(
    private val mp: MediaProjection,
    private val dpi: Int,
    private val out: File,
    private val isRunning: () -> Boolean,
) {
    private val sizes = listOf(320 to 176, 1280 to 720, 1920 to 1088)

    private fun log(line: String) {
        try { out.appendText(line + "\n") } catch (_: Exception) { }
    }

    fun run() {
        try { out.writeText("DIAG inicio ${System.currentTimeMillis()} sdk=${Build.VERSION.SDK_INT}\n") } catch (_: Exception) { }

        // 1) ImageReader com varios tipos de buffer
        val usages = mutableListOf<Pair<String, Long>>("padrao" to -1L)
        if (Build.VERSION.SDK_INT >= 29) {
            usages += "encode+cpu" to (HardwareBuffer.USAGE_VIDEO_ENCODE or HardwareBuffer.USAGE_CPU_READ_OFTEN)
            usages += "gpu+cpu" to (HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN)
            usages += "gpu+encode+cpu" to (HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE or HardwareBuffer.USAGE_CPU_READ_OFTEN)
        }
        for ((w, h) in sizes) for ((name, usage) in usages) {
            if (!isRunning()) return
            val r = try { testReader(w, h, usage) } catch (e: Exception) { "ERRO ${e.javaClass.simpleName}: ${e.message}" }
            log("READER $name ${w}x$h -> $r")
        }

        // 2) Todos os codificadores de video (hardware e software)
        val list = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val mimes = listOf("video/avc", "video/hevc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9")
        for (info in list) {
            if (!info.isEncoder) continue
            for (mime in info.supportedTypes) {
                if (mime !in mimes) continue
                val caps = try { info.getCapabilitiesForType(mime) } catch (_: Exception) { null } ?: continue
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) {
                    log("CODEC ${info.name} $mime -> sem entrada por superficie")
                    continue
                }
                for ((w, h) in sizes) {
                    if (!isRunning()) return
                    val ok = try { caps.videoCapabilities.isSizeSupported(w, h) } catch (_: Exception) { true }
                    if (!ok) { log("CODEC ${info.name} $mime ${w}x$h -> tamanho nao suportado"); continue }
                    val r = try { testCodec(info.name, mime, w, h) } catch (e: Exception) { "ERRO ${e.javaClass.simpleName}: ${e.message}" }
                    log("CODEC ${info.name} $mime ${w}x$h -> $r")
                }
            }
        }
        log("FIM")
    }

    /** Media e maximo de brilho de uma imagem RGBA */
    private fun statsRgba(img: Image): String {
        val p = img.planes[0]
        val b = p.buffer
        var sum = 0L
        var max = 0
        var n = 0
        val stepX = maxOf(1, img.width / 64)
        val stepY = maxOf(1, img.height / 36)
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val i = y * p.rowStride + x * p.pixelStride
                val v = ((b.get(i).toInt() and 0xFF) + (b.get(i + 1).toInt() and 0xFF) + (b.get(i + 2).toInt() and 0xFF)) / 3
                sum += v; n++; if (v > max) max = v
                x += stepX
            }
            y += stepY
        }
        return "media=${sum / maxOf(1, n)} max=$max"
    }

    /** Media e maximo do canal Y (brilho) de uma imagem YUV */
    private fun statsYuv(img: Image): String {
        val p = img.planes[0]
        val b = p.buffer
        var sum = 0L
        var max = 0
        var n = 0
        val stepX = maxOf(1, img.width / 64)
        val stepY = maxOf(1, img.height / 36)
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val v = b.get(y * p.rowStride + x * p.pixelStride).toInt() and 0xFF
                sum += v; n++; if (v > max) max = v
                x += stepX
            }
            y += stepY
        }
        return "Y media=${sum / maxOf(1, n)} max=$max"
    }

    private fun testReader(w: Int, h: Int, usage: Long): String {
        val reader = if (usage < 0 || Build.VERSION.SDK_INT < 29) ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        else ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2, usage)
        val vd = mp.createVirtualDisplay("diag", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
        var result = "sem quadros"
        var frames = 0
        val end = SystemClock.elapsedRealtime() + 2500
        try {
            while (SystemClock.elapsedRealtime() < end) {
                val img = try { reader.acquireLatestImage() } catch (_: Exception) { null }
                if (img != null) {
                    frames++
                    result = statsRgba(img)
                    img.close()
                }
                Thread.sleep(100)
            }
        } finally {
            vd.release()
            reader.close()
        }
        return "$result quadros=$frames"
    }

    private fun testCodec(name: String, mime: String, w: Int, h: Int): String {
        val enc = MediaCodec.createByCodecName(name)
        val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (w > 640) 4_000_000 else 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
        }
        var dec: MediaCodec? = null
        var vd: android.hardware.display.VirtualDisplay? = null
        var result = "sem quadros"
        var frames = 0
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = enc.createInputSurface()
            enc.start()
            vd = mp.createVirtualDisplay("diag", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null)
            val info = MediaCodec.BufferInfo()
            val dinfo = MediaCodec.BufferInfo()
            val end = SystemClock.elapsedRealtime() + 3500
            while (SystemClock.elapsedRealtime() < end) {
                val idx = enc.dequeueOutputBuffer(info, 20_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = enc.outputFormat
                    of.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    val d = MediaCodec.createDecoderByType(mime)
                    d.configure(of, null, null, 0)
                    d.start()
                    dec = d
                } else if (idx >= 0) {
                    val d = dec
                    val cfgFlag = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (d != null && !cfgFlag && info.size > 0) {
                        val din = d.dequeueInputBuffer(20_000)
                        if (din >= 0) {
                            val src = enc.getOutputBuffer(idx)!!
                            src.position(info.offset); src.limit(info.offset + info.size)
                            val dst = d.getInputBuffer(din)!!
                            dst.clear(); dst.put(src)
                            d.queueInputBuffer(din, 0, info.size, info.presentationTimeUs, 0)
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
                val d = dec ?: continue
                while (true) {
                    val o = d.dequeueOutputBuffer(dinfo, 0)
                    if (o < 0) break
                    frames++
                    val img = try { d.getOutputImage(o) } catch (_: Exception) { null }
                    if (img != null) { result = statsYuv(img); img.close() } else result = "decodificou mas sem imagem"
                    d.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            try { vd?.release() } catch (_: Exception) { }
            try { enc.stop() } catch (_: Exception) { }
            try { enc.release() } catch (_: Exception) { }
            try { dec?.stop() } catch (_: Exception) { }
            try { dec?.release() } catch (_: Exception) { }
        }
        return "$result quadros=$frames"
    }
}
