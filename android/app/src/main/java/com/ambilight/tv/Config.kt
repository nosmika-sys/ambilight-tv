package com.ambilight.tv

import android.content.SharedPreferences
import kotlin.math.pow

/** Lados da TV */
object Side {
    const val LEFT: Byte = 0
    const val TOP: Byte = 1
    const val RIGHT: Byte = 2
    const val BOTTOM: Byte = 3
}

/**
 * Posicao de cada LED fisico na tela.
 * norm: 4 floats por LED (x0, y0, x1, y1) de 0 a 1, relativos a area util da imagem.
 */
class Mapping(val count: Int, val norm: FloatArray, val sides: ByteArray)

/** Todas as configuracoes do app (imutavel; recriada quando algo muda). */
class Config(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val bottomGap: Int,
    val offset: Int,
    val reverse: Boolean,
    val brightness: Int,   // %
    val saturation: Int,   // % (100 = original)
    val smoothing: Int,    // % (0 = sem suavizacao)
    val depth: Int,        // % da tela lida a partir da borda
    val fps: Int,
    val gamma10: Int,      // gama x10 (18 = 1.8)
    val letterbox: Boolean,
    val baud: Int,
    val autoStart: Boolean,
    val testMode: Boolean,
    val colorOrder: Int,   // indice em COLOR_ORDERS: ordem em que R, G e B sao enviados
    val captureFps: Int,   // capturas de tela por segundo (menor = mais leve para a TV)
) {
    val mapping: Mapping = buildMapping()
    val gammaLut: IntArray = IntArray(256) { i ->
        (255.0 * (i / 255.0).pow(gamma10 / 10.0) + 0.5).toInt().coerceIn(0, 255)
    }
    val alpha: Float = (1f - smoothing / 100f).coerceIn(0.05f, 1f)

    /**
     * Ordem "canonica": comeca no canto inferior esquerdo, sobe pela esquerda,
     * atravessa o topo da esquerda para a direita, desce pela direita e volta
     * pela base da direita para a esquerda (sentido horario olhando de frente).
     * Depois aplica "inverter sentido" e "deslocamento" para casar com a fita real.
     */
    private fun buildMapping(): Mapping {
        val rects = ArrayList<FloatArray>()
        val sides = ArrayList<Byte>()
        val dx = depth / 100f
        val dy = depth / 100f

        for (i in 0 until left) {
            rects += floatArrayOf(0f, 1f - (i + 1f) / left, dx, 1f - i.toFloat() / left)
            sides += Side.LEFT
        }
        for (j in 0 until top) {
            rects += floatArrayOf(j.toFloat() / top, 0f, (j + 1f) / top, dy)
            sides += Side.TOP
        }
        for (k in 0 until right) {
            rects += floatArrayOf(1f - dx, k.toFloat() / right, 1f, (k + 1f) / right)
            sides += Side.RIGHT
        }
        val totalBottom = bottom + bottomGap
        val gapStart = (totalBottom - bottomGap) / 2
        for (m in 0 until totalBottom) {
            if (bottomGap > 0 && m >= gapStart && m < gapStart + bottomGap) continue
            rects += floatArrayOf(1f - (m + 1f) / totalBottom, 1f - dy, 1f - m.toFloat() / totalBottom, 1f)
            sides += Side.BOTTOM
        }

        val n = rects.size
        val order = IntArray(n) { it }
        if (reverse) order.reverse()
        val norm = FloatArray(n * 4)
        val outSides = ByteArray(n)
        if (n > 0) {
            val off = ((offset % n) + n) % n
            for (i in 0 until n) {
                val c = order[(i + off) % n]
                System.arraycopy(rects[c], 0, norm, i * 4, 4)
                outSides[i] = sides[c]
            }
        }
        return Mapping(n, norm, outSides)
    }

    companion object {
        const val K_LEFT = "left"
        const val K_TOP = "top"
        const val K_RIGHT = "right"
        const val K_BOTTOM = "bottom"
        const val K_GAP = "bottom_gap"
        const val K_OFFSET = "offset"
        const val K_REVERSE = "reverse"
        const val K_BRIGHT = "brightness"
        const val K_SAT = "saturation"
        const val K_SMOOTH = "smoothing"
        const val K_DEPTH = "depth"
        const val K_FPS = "fps"
        const val K_GAMMA = "gamma10"
        const val K_LETTERBOX = "letterbox"
        const val K_BAUD = "baud"
        const val K_AUTOSTART = "autostart"
        const val K_TEST = "test_mode"
        const val K_ORDER = "color_order"
        const val K_CAPFPS = "capture_fps"

        const val PREFS = "ambilight"
        /** Ordem dos canais enviados. Corrige a fita sem regravar o ESP32. */
        val COLOR_ORDERS = arrayOf("RGB", "RBG", "GRB", "GBR", "BRG", "BGR")
        val BAUDS = intArrayOf(115200, 230400, 460800, 500000, 921600, 1000000, 2000000)

        /** Padrao pensado para TV de 50" com fita de 60 LEDs/m */
        val DEFAULTS = mapOf(
            K_LEFT to 36, K_TOP to 64, K_RIGHT to 36, K_BOTTOM to 64, K_GAP to 0,
            K_OFFSET to 0, K_BRIGHT to 100, K_SAT to 120, K_SMOOTH to 40,
            K_DEPTH to 12, K_FPS to 30, K_GAMMA to 18, K_BAUD to 921600, K_CAPFPS to 12,
        )

        fun int(p: SharedPreferences, key: String) = p.getInt(key, DEFAULTS[key] ?: 0)

        fun load(p: SharedPreferences) = Config(
            left = int(p, K_LEFT),
            top = int(p, K_TOP),
            right = int(p, K_RIGHT),
            bottom = int(p, K_BOTTOM),
            bottomGap = int(p, K_GAP),
            offset = int(p, K_OFFSET),
            reverse = p.getBoolean(K_REVERSE, false),
            brightness = int(p, K_BRIGHT),
            saturation = int(p, K_SAT),
            smoothing = int(p, K_SMOOTH),
            depth = int(p, K_DEPTH).coerceIn(2, 40),
            fps = int(p, K_FPS).coerceIn(5, 60),
            gamma10 = int(p, K_GAMMA).coerceIn(5, 35),
            letterbox = p.getBoolean(K_LETTERBOX, true),
            baud = int(p, K_BAUD),
            autoStart = p.getBoolean(K_AUTOSTART, true),
            testMode = p.getBoolean(K_TEST, false),
            colorOrder = p.getInt(K_ORDER, 0).coerceIn(0, COLOR_ORDERS.size - 1),
            captureFps = int(p, K_CAPFPS).coerceIn(2, 30),
        )
    }
}
