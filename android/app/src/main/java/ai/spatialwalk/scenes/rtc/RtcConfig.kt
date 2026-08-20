package ai.spatialwalk.scenes.rtc

import android.content.Context
import androidx.core.content.edit

/**
 * Backend URL.
 *
 * This is the only setting stored on the device — everything else (Spatius / Agora
 * credentials, avatar, sample rate) lives in the backend's .env, shared by all three
 * clients and read and written through `/api/config`.
 *
 * Why it isn't derived automatically the way the Web client does it: a web page can read
 * `location.hostname` because the backend is on the same machine, but a phone cannot reach
 * the dev machine's localhost, so the user has to type the LAN address. The backend prints
 * it on startup, and `GET /health` returns it as `lanUrl`.
 */
object RtcConfig {

    private const val PREFS = "tutoring.rtc"
    private const val KEY_BASE_URL = "backendBaseUrl"

    /** In the emulator 10.0.2.2 is the host machine, so an emulator install needs no
     * address change. */
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8787"

    @Volatile
    private var cached: String? = null

    fun baseUrl(context: Context): String =
        cached ?: prefs(context).getString(KEY_BASE_URL, null).orEmpty()
            .ifEmpty { DEFAULT_BASE_URL }
            .also { cached = it }

    fun setBaseUrl(context: Context, value: String) {
        val normalized = value.trim().trimEnd('/')
        cached = normalized
        prefs(context).edit { putString(KEY_BASE_URL, normalized) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
