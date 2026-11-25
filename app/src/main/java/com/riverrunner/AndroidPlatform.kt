package com.riverrunner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.riverrunner.audio.SoundPlayer
import com.riverrunner.ui.AppFonts
import java.io.File
import java.util.Locale

/** The Android side of [Platform]: the same SharedPreferences and files the app has always used. */
class AndroidPlatform(context: Context) : Platform {
    private val app = context.applicationContext

    override val settings: Settings = PrefsSettings(app.getSharedPreferences("river_runner", Context.MODE_PRIVATE))
    override val files: TextFiles = DirFiles(app.filesDir)
    override val sound: SoundOut by lazy { SoundPlayer(app) }
    override val appVersion: String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
    override val systemChinese: Boolean get() = Locale.getDefault().language == "zh"

    /** CHANGELOG.md, packed into the APK as assets/changelog.md (see app/build.gradle.kts). */
    override fun changelogText(): String =
        runCatching { app.assets.open("changelog.md").bufferedReader().use { it.readText() } }.getOrDefault("")

    override fun deliverExport(filename: String, text: String): String {
        val safe = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return runCatching {
            val dir = File(app.filesDir, "exports")
            dir.mkdirs()
            File(dir, safe).writeText(text)
            "saved"
        }.getOrDefault("fail")
    }

    override fun copyExport(text: String): Boolean = runCatching {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("river-runner", text))
        true
    }.getOrDefault(false)
}

/** Condensed numerals (Barlow Condensed, SIL OFL) from the app's font resources. */
val AndroidFonts = AppFonts(
    numeric = FontFamily(
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    ),
)

private class PrefsSettings(private val sp: SharedPreferences) : Settings {
    override fun contains(key: String) = sp.contains(key)
    override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    override fun getInt(key: String, default: Int) = sp.getInt(key, default)
    override fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    override fun getString(key: String, default: String) = sp.getString(key, default) ?: default
    override fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()
    override fun getFloat(key: String, default: Float) = sp.getFloat(key, default)
}

private class DirFiles(private val dir: File) : TextFiles {
    private fun f(name: String) = File(dir, name)
    override fun exists(name: String) = f(name).exists()
    override fun read(name: String): String? = f(name).takeIf { it.exists() }?.readText()
    override fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(f(name))
    }
    override fun append(name: String, text: String) = f(name).appendText(text)
    override fun delete(name: String) { f(name).delete() }
    override fun rename(from: String, to: String) { f(from).renameTo(f(to)) }
}
