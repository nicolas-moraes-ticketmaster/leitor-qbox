package br.com.leitorqbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Verifica no GitHub se há APK mais novo (as releases são v1.0.<versionCode>). */
object Updater {

    private const val REPO = "nicolas-moraes-ticketmaster/leitor-qbox"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    const val DOWNLOAD_URL = "https://github.com/$REPO/releases/latest/download/LeitorQBOX.apk"

    data class Release(val versionCode: Long, val name: String)

    fun currentVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    /** Release mais nova que a instalada, ou null. Bloqueante: chamar fora da main thread. */
    fun newerRelease(context: Context): Release? {
        val conn = URL(LATEST_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")             // ex.: v1.0.13
            val code = tag.substringAfterLast('.').toLongOrNull() ?: return null
            return if (code > currentVersionCode(context)) Release(code, tag.removePrefix("v")) else null
        } finally {
            conn.disconnect()
        }
    }

    /** Abre o download do APK no navegador; o Android instala por cima ao tocar no arquivo. */
    fun openDownload(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
