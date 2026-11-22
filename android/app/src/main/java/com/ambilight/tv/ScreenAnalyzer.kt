package com.ambilight.tv

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Area util da imagem (sem barras pretas), em pixels: [left, right) x [top, bottom) */
data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Le um quadro RGBA reduzido (ex.: 160x90) e calcula a cor media de cada LED.
 * Tambem detecta barras pretas (filmes 21:9, videos 4:3) para usar so a imagem real.
 */
class ScreenAnalyzer {

    private var crop: Crop? = null
    private var pending: Crop? = null
    private var pendingCount = 0

    private var rects: IntArray = IntArray(0)
    private var rectsMapping: Mapping? = null
    private var rectsCrop: Crop? = null

    fun analyze(buf: ByteBuffer, w: Int, h: Int, pixelStride: Int, rowStride: Int, cfg: Config): FloatArray {
        val full = Crop(0, 0, w, h)
        val area = if (cfg.letterbox) detectBars(buf, w, h, pixelStride, rowStride) else full

        val mapping = cfg.mapping
        if (rectsMapping !== mapping || rectsCrop != area) {
            rects = toPixels(mapping, area)
            rectsMapping = mapping
            rectsCrop = area
        }

        val n = mapping.count
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val x0 = rects[i * 4]
            val y0 = rects[i * 4 + 1]
            val x1 = rects[i * 4 + 2]
            val y1 = rects[i * 4 + 3]
            val sx = max(1, (x1 - x0) / 8)
            val sy = max(1, (y1 - y0) / 8)
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0
            var y = y0
            while (y < y1) {
                var idx = y * rowStride + x0 * pixelStride
                var x = x0
                while (x < x1) {
                    r += buf.get(idx).toInt() and 0xFF
                    g += buf.get(idx + 1).toInt() and 0xFF
                    b += buf.get(idx + 2).toInt() and 0xFF
                    count++
                    x += sx
                    idx += sx * pixelStride
                }
                y += sy
            }
            if (count > 0) {
                out[i * 3] = r.toFloat() / count
                out[i * 3 + 1] = g.toFloat() / count
                out[i * 3 + 2] = b.toFloat() / count
            }
        }
        return out
    }

    private fun toPixels(m: Mapping, c: Crop): IntArray {
        val cw = (c.right - c.left).toFloat()
        val ch = (c.bottom - c.top).toFloat()
        val out = IntArray(m.count * 4)
        for (i in 0 until m.count) {
            val x0 = (c.left + (m.norm[i * 4] * cw).toInt()).coerceIn(c.left, c.right - 1)
            val y0 = (c.top + (m.norm[i * 4 + 1] * ch).toInt()).coerceIn(c.top, c.bottom - 1)
            val x1 = (c.left + ceil(m.norm[i * 4 + 2] * cw).toInt()).coerceIn(x0 + 1, c.right)
            val y1 = (c.top + ceil(m.norm[i * 4 + 3] * ch).toInt()).coerceIn(y0 + 1, c.bottom)
            out[i * 4] = x0
            out[i * 4 + 1] = y0
            out[i * 4 + 2] = x1
            out[i * 4 + 3] = y1
        }
        return out
    }

    private fun pixelMax(buf: ByteBuffer, idx: Int): Int {
        val r = buf.get(idx).toInt() and 0xFF
        val g = buf.get(idx + 1).toInt() and 0xFF
        val b = buf.get(idx + 2).toInt() and 0xFF
        return max(r, max(g, b))
    }

    private fun rowIsBlack(buf: ByteBuffer, y: Int, x0: Int, x1: Int, ps: Int, rs: Int): Boolean {
        var x = x0
        while (x < x1) {
            if (pixelMax(buf, y * rs + x * ps) > BLACK) return false
            x += 2
        }
        return true
    }

    private fun colIsBlack(buf: ByteBuffer, x: Int, y0: Int, y1: Int, ps: Int, rs: Int): Boolean {
        var y = y0
        while (y < y1) {
            if (pixelMax(buf, y * rs + x * ps) > BLACK) return false
            y += 2
        }
        return true
    }

    private fun detectBars(buf: ByteBuffer, w: Int, h: Int, ps: Int, rs: Int): Crop {
        val current = crop ?: Crop(0, 0, w, h)
        if (current.right > w || current.bottom > h) {
            crop = null
            return Crop(0, 0, w, h)
        }
        val maxV = (h * 0.3f).toInt()
        val maxH = (w * 0.3f).toInt()

        var top = 0
        while (top < maxV && rowIsBlack(buf, top, 0, w, ps, rs)) top++
        // Tela toda escura (cena noturna, fade): mantem o recorte atual
        if (top >= maxV && rowIsBlack(buf, h / 2, 0, w, ps, rs)) return current

        var bottom = h
        while (h - bottom < maxV && rowIsBlack(buf, bottom - 1, 0, w, ps, rs)) bottom--
        var left = 0
        while (left < maxH && colIsBlack(buf, left, top, bottom, ps, rs)) left++
        var right = w
        while (w - right < maxH && colIsBlack(buf, right - 1, top, bottom, ps, rs)) right--

        // Barras simetricas: usa a menor das duas para nao cortar imagem escura de um lado so
        val v = minOf(top, h - bottom)
        val hz = minOf(left, w - right)
        val candidate = Crop(hz, v, w - hz, h - v)

        if (near(candidate, current)) {
            pending = null
            pendingCount = 0
            return current
        }
        if (pending != null && near(candidate, pending!!)) {
            pendingCount++
        } else {
            pending = candidate
            pendingCount = 1
        }
        if (pendingCount >= STABLE_FRAMES) {
            crop = candidate
            pending = null
            pendingCount = 0
            return candidate
        }
        return current
    }

    private fun near(a: Crop, b: Crop) =
        abs(a.left - b.left) <= 1 && abs(a.top - b.top) <= 1 &&
            abs(a.right - b.right) <= 1 && abs(a.bottom - b.bottom) <= 1

    companion object {
        private const val BLACK = 24          // abaixo disso o pixel conta como preto
        private const val STABLE_FRAMES = 12  // quadros iguais antes de mudar o recorte
    }
}
