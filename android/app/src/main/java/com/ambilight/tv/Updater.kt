package com.ambilight.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualizacao automatica: confere o ultimo release no GitHub e instala o APK novo.
 * Na primeira vez o Android pede confirmacao; depois disso (Android 12+) costuma instalar sozinho.
 */
object Updater {
    private const val API = "https://api.github.com/repos/nosmika-sys/ambilight-tv/releases/latest"

    class Release(val version: String, val apkUrl: String)

    @Volatile var status: String = ""

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    /** Compara "1.10" > "1.9" numericamente. */
    private fun newer(remote: String, local: String): Boolean {
        val r = remote.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Roda fora da thread principal. Retorna o release se for mais novo que o instalado. */
    fun check(ctx: Context): Release? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name")
            val assets = json.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
            }
            if (url == null || !newer(tag, currentVersion(ctx))) return null
            return Release(tag.trimStart('v', 'V'), url)
        } finally {
            conn.disconnect()
        }
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Abre a tela para permitir que o Ambilight TV instale atualizacoes (so na primeira vez). */
    fun askInstallPermission(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) { }
    }

    /** Baixa e instala. Roda fora da thread principal. */
    fun downloadAndInstall(ctx: Context, rel: Release) {
        status = "Baixando versão ${rel.version}…"
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val conn = URL(rel.apkUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.inputStream.use { input ->
                session.openWrite("app.apk", 0, -1).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            conn.disconnect()
            status = "Instalando versão ${rel.version}…"
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                ctx, 7, Intent(ctx, InstallReceiver::class.java), flags
            )
            session.commit(pi.intentSender)
        } catch (e: Exception) {
            session.abandon()
            status = "Falha na atualização: ${e.message}"
        } finally {
            session.close()
        }
    }
}

/** Recebe o resultado da instalacao; se o Android pedir confirmacao, mostra a tela. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { if (confirm != null) context.startActivity(confirm) } catch (_: Exception) { }
                Updater.status = "Confirme a atualização na tela"
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.status = "Atualizado!"
            else -> Updater.status = "Atualização não concluída: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "")
        }
    }
}

/** Depois de atualizar, reabre o app sozinho. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_AFTER_UPDATE, true)
            )
        } catch (_: Exception) { }
    }
}
