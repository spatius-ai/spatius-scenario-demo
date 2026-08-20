package ai.spatialwalk.scenes.rtc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Connection credentials for one classroom session, issued by the backend. */
data class SessionCredentials(
    /** Every later say / interrupt / free-talk / stop uses this to find the session. */
    val sessionId: String,
    val appId: String,
    val channelName: String,
    val token: String,
    val uid: Long,
    /**
     * The conversational agent's uid.
     *
     * Used to tell whether it has joined the channel: the ConvoAI agent is spun up
     * asynchronously only after the backend's `/api/session` returns, a second or two
     * later than the client connects. A say sent during that window still gets a 200 from
     * the backend, but nobody speaks the line — it surfaces as "I'm in the classroom but
     * it won't read the question".
     */
    val agentUid: Long,
    /** The avatar the backend actually started; the client loads that model. */
    val avatarId: String,
    /** Spatius app id and region, needed to initialize the SDK. */
    val spatiusAppId: String,
    val spatiusRegion: String,
)

/**
 * Backend client.
 *
 * The backend runs on the user's own machine (`backend/`) with the credentials
 * configured in its .env; all this needs from it is a session. The API is identical to the
 * Web client's.
 */
object AgentClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    /**
     * Start a classroom session. **Billing begins on this call**, so always call
     * [stopSession] on the way out.
     *
     * The avatar is left to the backend: it lives in .env and the config screen can
     * already change it.
     */
    data class AvatarEntry(val id: String, val name: String, val coverUrl: String)

    /**
     * The characters this backend offers, with their cover art.
     *
     * Fetched rather than compiled in: adding a character is then a backend change
     * instead of a rebuild of three apps, and the cover art stays a CDN URL rather
     * than going back into the release cycle as a bundled image.
     */
    suspend fun fetchCatalogue(context: Context): List<AvatarEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${RtcConfig.baseUrl(context)}/api/catalogue")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val arr = JSONObject(body).getJSONArray("avatars")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AvatarEntry(o.getString("id"), o.getString("name"), o.optString("coverUrl"))
            }
        }
    }

    suspend fun createSession(context: Context, lang: String): SessionCredentials =
        withContext(Dispatchers.IO) {
        // Send the UI language: the avatar's persona has to follow it, otherwise after
        // switching to English the student opens the mic and the teacher answers in
        // Chinese — the questions and the read-aloud text are already English, so only the
        // LLM path gives it away.
        val request = Request.Builder()
            .url("${RtcConfig.baseUrl(context)}/api/session")
            // Ask for Agora explicitly. This client ships the Agora SDK alone, and the
            // backend's TRANSPORT defaults to LiveKit — served that, we would get a room
            // name and a URL we cannot use, and fail on a parse error naming none of this.
            .post(
                JSONObject()
                    .put("lang", lang)
                    .put("transport", "agora")
                    .toString()
                    .toRequestBody(jsonType)
            )
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(JSONObject(body).optString("error").ifEmpty { "HTTP ${response.code}" })
            }
            val json = JSONObject(body)
            SessionCredentials(
                sessionId = json.getString("sessionId"),
                appId = json.getString("appId"),
                channelName = json.getString("channelName"),
                token = json.optString("token"),
                uid = json.optLong("uid", 0),
                agentUid = json.optLong("agentUid", 0),
                avatarId = json.getString("avatarId"),
                spatiusAppId = json.getString("spatiusAppId"),
                spatiusRegion = json.optString("spatiusRegion"),
            )
        }
    }

    /**
     * Everything below is fire-and-forget: failures are swallowed. Failing to speak one
     * line of feedback should not interrupt the class.
     */
    private suspend fun post(context: Context, path: String, payload: JSONObject) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${RtcConfig.baseUrl(context)}$path")
                .post(payload.toString().toRequestBody(jsonType))
                .build()
            runCatching { http.newCall(request).execute().close() }
            Unit
        }

    /** Have the avatar speak a piece of text. Upstream caps it at 512 bytes. */
    suspend fun say(context: Context, sessionId: String, text: String) {
        if (sessionId.isEmpty()) return
        post(context, "/api/session/say", JSONObject().put("sessionId", sessionId).put("text", text))
    }

    /**
     * Interrupt whatever is currently being spoken.
     *
     * For the case where the UI moved on but there is nothing new to read — paging to a
     * question that was already answered correctly, for instance: without this, the agent
     * keeps reading the previous question to the end, out of sync with the UI.
     */
    suspend fun interrupt(context: Context, sessionId: String) {
        if (sessionId.isEmpty()) return
        post(context, "/api/session/interrupt", JSONObject().put("sessionId", sessionId))
    }

    /**
     * Switch to a conversational persona. The server only changes state and does not
     * speak; the transition line is sent by the client via say.
     *
     * The persona names which one, because the scenes want different things from the same
     * backend: the classroom moves to open questions from a teacher, the live room to a
     * streamer talking to whoever came on the mic.
     */
    suspend fun startFreeTalk(
        context: Context,
        sessionId: String,
        lang: String,
        persona: String = "freetalk",
        /** A character written by the user, for the companion scene. Ignored by the others. */
        custom: String = "",
        /** Which stored memory this character reads, for the companion scene. */
        memoryKey: String = "",
    ) {
        if (sessionId.isEmpty()) return
        post(
            context,
            "/api/session/free-talk",
            JSONObject()
                .put("sessionId", sessionId)
                .put("lang", lang)
                .put("persona", persona)
                .put("custom", custom)
                .put("memoryKey", memoryKey),
        )
    }

    /**
     * End the session, optionally keeping what was said. **Must be called** — a session
     * bills continuously from the moment it is established.
     *
     * Only the companion scene remembers. On the Agora path the transcript lives with the
     * agent and goes away when it stops, so collecting it is part of stopping rather than a
     * call of its own — hence the memory key travelling with the stop request.
     *
     * @param remember which stored memory to append this conversation to; empty keeps
     *   nothing, which is what the other three scenes want.
     */
    suspend fun stopSession(context: Context, sessionId: String, remember: String = "") {
        if (sessionId.isEmpty()) return
        post(
            context,
            "/api/session/stop",
            JSONObject()
                .put("sessionId", sessionId)
                .put("remember", if (remember.isNotEmpty()) "1" else "")
                .put("persona", remember),
        )
    }

    /** What the companion remembers so far. */
    data class MemorySummary(
        /** Earlier conversations folded into notes, empty until the first compaction. */
        val summary: String,
        /** How many turns are held raw, on top of the summary. */
        val turns: Int,
        /** Total characters, which is what the compaction threshold is measured against. */
        val size: Int,
    )

    /**
     * What one character currently remembers.
     *
     * A read failure comes back as an empty memory rather than throwing: the scene shows
     * this on entry, and losing sight of the memory should not stop the room opening.
     */
    suspend fun fetchMemory(context: Context, persona: String): MemorySummary =
        withContext(Dispatchers.IO) {
            val url = "${RtcConfig.baseUrl(context)}/api/memory".toHttpUrl().newBuilder()
                .addQueryParameter("persona", persona)
                .build()
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val json = JSONObject(body)
                    MemorySummary(
                        summary = json.optString("summary"),
                        turns = json.optInt("turns"),
                        size = json.optInt("size"),
                    )
                }
            }.getOrElse { MemorySummary("", 0, 0) }
        }

    /** Forget everything for one character, so the scene can be shown from a blank slate. */
    suspend fun clearMemory(context: Context, persona: String) {
        post(context, "/api/memory/clear", JSONObject().put("persona", persona))
    }

    /** Whether the backend is up. The config screen checks this after the URL is entered,
     * so a typo isn't discovered only once inside the classroom. */
    /**
     * Whether the backend at this address answers.
     *
     * The transport it reports is deliberately ignored: this client only speaks Agora, so
     * naming the backend's setting on screen offers a choice that does not exist here and
     * shows a word the user can do nothing with.
     */
    suspend fun health(context: Context, baseUrl: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("${baseUrl.trim().trimEnd('/')}/health")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                }
            }
        }

    /** The backend's current config. The key names match EDITABLE_KEYS in server.py. */
    suspend fun fetchConfig(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${RtcConfig.baseUrl(context)}/api/config")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val json = JSONObject(body)
            buildMap {
                json.keys().forEach { key ->
                    // `_fields` lists the fields each transport requires; it is not a
                    // config value itself.
                    if (!key.startsWith("_")) put(key, json.optString(key))
                }
            }
        }
    }

    /** Write back to the backend's .env; takes effect immediately. */
    suspend fun saveConfig(context: Context, config: Map<String, String>) =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply { config.forEach { (k, v) -> put(k, v) } }
            val request = Request.Builder()
                .url("${RtcConfig.baseUrl(context)}/api/config")
                .post(payload.toString().toRequestBody(jsonType))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
            }
            Unit
        }
}
