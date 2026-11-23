package com.ambilight.tv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Tela de configuracao (feita para controle remoto: tudo navegavel pelo direcional). */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var totalText: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var autoTried = false
    private var hideAfterStart = false
    private lateinit var updateText: TextView
    private lateinit var updateButton: Button
    private var pendingRelease: Updater.Release? = null

    private val refresher = object : Runnable {
        override fun run() {
            statusText.text = if (AmbilightService.isRunning) "● ${AmbilightService.status}" else "○ ${AmbilightService.status}"
            startButton.text = if (AmbilightService.isRunning) "Parar Ambilight" else "Iniciar Ambilight"
            if (Updater.status.isNotEmpty()) updateText.text = Updater.status
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE)
        setContentView(buildUi())
        checkUpdateThenStart()
    }

    /** Confere atualizacao no GitHub; se nao houver (ou sem internet), segue o inicio normal. */
    private fun checkUpdateThenStart() {
        updateText.text = "Versão ${Updater.currentVersion(this)} · procurando atualização…"
        Thread {
            val rel = try { Updater.check(this) } catch (_: Exception) { null }
            ui.post {
                if (rel == null) {
                    updateText.text = "Versão ${Updater.currentVersion(this)} · atualizado"
                    maybeAutoStart()
                    return@post
                }
                pendingRelease = rel
                updateButton.visibility = View.VISIBLE
                updateButton.text = "Atualizar para ${rel.version}"
                val auto = prefs.getBoolean(Config.K_AUTOUPDATE, true)
                if (auto && !AmbilightService.isRunning && Updater.canInstall(this)) {
                    installUpdate()
                } else {
                    updateText.text = if (Updater.canInstall(this))
                        "Versão ${rel.version} disponível"
                    else "Versão ${rel.version} disponível · clique para permitir atualizações"
                    maybeAutoStart()
                }
            }
        }.start()
    }

    private fun installUpdate() {
        val rel = pendingRelease ?: return
        if (!Updater.canInstall(this)) {
            Updater.askInstallPermission(this)
            return
        }
        if (AmbilightService.isRunning) {
            startService(Intent(this, AmbilightService::class.java).setAction(AmbilightService.ACTION_STOP))
        }
        updateButton.isEnabled = false
        Thread { Updater.downloadAndInstall(this, rel) }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        autoTried = false
        maybeAutoStart()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
    }

    private fun maybeAutoStart() {
        if (autoTried || AmbilightService.isRunning) return
        if (!prefs.getBoolean(Config.K_AUTOSTART, true)) return
        autoTried = true
        // Se foi aberto pela conexao do ESP32 (ex.: TV ligando), some da frente depois de iniciar
        hideAfterStart = intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
            intent?.getBooleanExtra(EXTRA_AFTER_UPDATE, false) == true ||
            intent?.getBooleanExtra(AmbilightService.EXTRA_DIAG, false) == true
        requestStart()
    }

    // ---------------- Iniciar / parar ----------------

    private fun toggle() {
        if (AmbilightService.isRunning) {
            startService(Intent(this, AmbilightService::class.java).setAction(AmbilightService.ACTION_STOP))
        } else {
            hideAfterStart = false
            requestStart()
        }
    }

    private fun requestStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: Exception) {
            AmbilightService.status = "Esta TV não permite captura de tela: ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) askCapture()   // segue mesmo se recusar a notificacao
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            AmbilightService.status = "Captura de tela não autorizada"
            return
        }
        val i = Intent(this, AmbilightService::class.java)
            .setAction(AmbilightService.ACTION_START)
            .putExtra(AmbilightService.EXTRA_CODE, resultCode)
            .putExtra(AmbilightService.EXTRA_DATA, data)
            .putExtra(AmbilightService.EXTRA_DIAG, intent?.getBooleanExtra(AmbilightService.EXTRA_DIAG, false) == true)
        startForegroundService(i)
        AmbilightService.status = "Iniciando…"
        // Ligou sozinho (TV ligando / ESP32 conectado): volta para o que estava na tela
        if (hideAfterStart) ui.postDelayed({ moveTaskToBack(true) }, 800)
    }

    // ---------------- Interface ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(24))
            setBackgroundColor(Color.rgb(16, 16, 24))
        }
        root.addView(TextView(this).apply {
            text = "Ambilight TV"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        statusText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(170, 200, 255))
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(statusText)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply {
            text = "Iniciar Ambilight"
            textSize = 18f
            setOnClickListener { toggle() }
        }
        buttons.addView(startButton)
        updateButton = Button(this).apply {
            textSize = 18f
            visibility = View.GONE
            setOnClickListener { installUpdate() }
        }
        buttons.addView(updateButton)
        root.addView(buttons)
        updateText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(updateText)

        totalText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(200, 200, 200))
            setPadding(0, dp(12), 0, dp(4))
        }

        root.addView(section("Fita de LED (quantidade em cada lado)"))
        root.addView(stepper("Lado esquerdo", Config.K_LEFT, 0, 150, 1))
        root.addView(stepper("Topo", Config.K_TOP, 0, 200, 1))
        root.addView(stepper("Lado direito", Config.K_RIGHT, 0, 150, 1))
        root.addView(stepper("Base (LEDs instalados)", Config.K_BOTTOM, 0, 200, 1))
        root.addView(stepper("Vão na base (pé da TV)", Config.K_GAP, 0, 100, 1))
        root.addView(totalText)
        root.addView(stepper("Deslocamento do início", Config.K_OFFSET, -300, 300, 1))
        root.addView(toggleRow("Inverter sentido", Config.K_REVERSE, false))
        root.addView(orderRow())
        root.addView(toggleRow("Modo teste (esq. vermelho, topo verde, dir. azul, base branca)", Config.K_TEST, false))

        root.addView(section("Imagem"))
        root.addView(stepper("Brilho", Config.K_BRIGHT, 5, 100, 5, "%"))
        root.addView(stepper("Saturação", Config.K_SAT, 50, 250, 10, "%"))
        root.addView(stepper("Suavização", Config.K_SMOOTH, 0, 95, 5, "%"))
        root.addView(stepper("Profundidade da borda", Config.K_DEPTH, 2, 40, 1, "%"))
        root.addView(stepper("Gama (x10)", Config.K_GAMMA, 5, 35, 1))
        root.addView(stepper("Capturas da tela por segundo (menos = mais leve)", Config.K_CAPFPS, 2, 30, 2))
        root.addView(stepper("Atualizações da fita por segundo", Config.K_FPS, 5, 60, 5))
        root.addView(toggleRow("Ignorar barras pretas (filmes)", Config.K_LETTERBOX, true))
        root.addView(toggleRow("Captura compatível com vídeo (reinicie o Ambilight ao mudar)", Config.K_COMPAT, true))

        root.addView(section("Conexão e inicialização"))
        root.addView(baudRow())
        root.addView(toggleRow("Iniciar sozinho ao abrir / ao conectar o ESP32", Config.K_AUTOSTART, true))
        root.addView(toggleRow("Atualizar o app sozinho (pelo GitHub)", Config.K_AUTOUPDATE, true))

        root.addView(TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, dp(16), 0, 0)
            text = "Netflix, Prime Video, Disney+ e outros apps protegidos aparecem pretos para a captura " +
                "(bloqueio do Android). YouTube, jogos e a maioria dos apps funcionam."
        })

        updateTotal()
        startButton.requestFocus()
        return ScrollView(this).apply { addView(root) }
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(255, 201, 61))
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun row(label: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 18f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun stepper(label: String, key: String, min: Int, max: Int, step: Int, suffix: String = ""): View {
        val r = row(label)
        val value = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(90)
        }
        fun show() { value.text = "${Config.int(prefs, key)}$suffix" }
        fun change(delta: Int) {
            val v = (Config.int(prefs, key) + delta).coerceIn(min, max)
            prefs.edit().putInt(key, v).apply()
            show()
            updateTotal()
        }
        r.addView(Button(this).apply { text = "−"; textSize = 20f; setOnClickListener { change(-step) } })
        r.addView(value)
        r.addView(Button(this).apply { text = "+"; textSize = 20f; setOnClickListener { change(step) } })
        show()
        return r
    }

    private fun toggleRow(label: String, key: String, def: Boolean): View {
        val r = row(label)
        r.addView(Switch(this).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        })
        return r
    }

    private fun orderRow(): View {
        val r = row("Ordem das cores (troque se o modo teste errar as cores)")
        val value = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(90)
        }
        fun idx() = prefs.getInt(Config.K_ORDER, 2).coerceIn(0, Config.COLOR_ORDERS.size - 1)
        fun show() { value.text = Config.COLOR_ORDERS[idx()] }
        fun change(d: Int) {
            val n = Config.COLOR_ORDERS.size
            prefs.edit().putInt(Config.K_ORDER, (idx() + d + n) % n).apply()
            show()
        }
        r.addView(Button(this).apply { text = "−"; textSize = 20f; setOnClickListener { change(-1) } })
        r.addView(value)
        r.addView(Button(this).apply { text = "+"; textSize = 20f; setOnClickListener { change(1) } })
        show()
        return r
    }

    private fun baudRow(): View {
        val r = row("Velocidade da USB (igual ao firmware)")
        val value = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(130)
        }
        fun idx() = Config.BAUDS.indexOf(Config.int(prefs, Config.K_BAUD)).let { if (it < 0) 4 else it }
        fun show() { value.text = Config.BAUDS[idx()].toString() }
        fun change(d: Int) {
            val i = (idx() + d).coerceIn(0, Config.BAUDS.size - 1)
            prefs.edit().putInt(Config.K_BAUD, Config.BAUDS[i]).apply()
            show()
        }
        r.addView(Button(this).apply { text = "−"; textSize = 20f; setOnClickListener { change(-1) } })
        r.addView(value)
        r.addView(Button(this).apply { text = "+"; textSize = 20f; setOnClickListener { change(1) } })
        show()
        return r
    }

    private fun updateTotal() {
        val total = Config.int(prefs, Config.K_LEFT) + Config.int(prefs, Config.K_TOP) +
            Config.int(prefs, Config.K_RIGHT) + Config.int(prefs, Config.K_BOTTOM)
        totalText.text = if (total > 300)
            "Total: $total LEDs — acima de 300, aumente MAX_LEDS no firmware"
        else "Total: $total LEDs"
    }

    companion object {
        private const val REQ_CAPTURE = 10
        private const val REQ_NOTIF = 11
        const val EXTRA_AFTER_UPDATE = "after_update"
    }
}
