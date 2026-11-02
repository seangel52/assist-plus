package com.havoice.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HaException(message: String) : Exception(message)

data class Pipeline(
    val id: String,
    val name: String,
    /** Whether Home Assistant can speak this pipeline's replies (it has text-to-speech). */
    val supportsSpeech: Boolean,
    /** The pipeline's voice, so the app's own prompts sound like its replies. */
    val ttsEngine: String? = null,
    val ttsLanguage: String? = null,
    val ttsVoice: String? = null,
)

data class PipelineList(val pipelines: List<Pipeline>, val preferredId: String?)

data class AssistReply(
    val speech: String,
    val conversationId: String?,
    /** Full URL of the spoken reply, or null when it wasn't requested or isn't available. */
    val audioUrl: String?,
    /** True when the assistant asked a follow-up question and expects an answer. */
    val continueConversation: Boolean,
)

/**
 * Talks to Home Assistant over its WebSocket API, the same API the Companion app
 * and voice satellites use. Only text goes to Home Assistant — all listening happens
 * on the phone. Requests take turns.
 */
class HaClient(baseUrl: String, private val token: String) {
    private val httpBase = toHttpUrl(baseUrl)
    private val wsUrl = toWebSocketUrl(baseUrl)
    private val incoming = Channel<JSONObject>(Channel.UNLIMITED)
    private var socket: WebSocket? = null
    private var nextId = 1
    private val lock = Mutex()

    // Home Assistant asking this phone to do something (open an app, set the volume), see [onPhoneEvent]:
    // handlers by subscription id.
    private val phoneEventHandlers = mutableMapOf<Int, (JSONObject) -> Unit>()

    suspend fun connect() {
        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: IllegalArgumentException) {
            throw HaException("That doesn't look like a valid Home Assistant address")
        }
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(JSONObject(text))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.close(HaException("Can't reach Home Assistant (${t.message ?: t.javaClass.simpleName})"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close()
            }
        })

        timed(10_000, "Home Assistant to respond") {
            val first = receive()
            if (first.optString("type") != "auth_required") {
                throw HaException("This address didn't answer like Home Assistant")
            }
            send(JSONObject().put("type", "auth").put("access_token", token))
            when (val type = receive().optString("type")) {
                "auth_ok" -> Unit
                "auth_invalid" -> throw HaException("Home Assistant rejected the access token")
                else -> throw HaException("Unexpected reply while logging in: $type")
            }
        }
    }

    /**
     * Lets Home Assistant scripts act on this phone while Assist is open: a script fires
     * [eventType] (see [OPEN_APP_EVENT], [SET_VOLUME_EVENT]) and [handler] gets the event's data.
     */
    suspend fun onPhoneEvent(eventType: String, handler: (JSONObject) -> Unit) =
        timed(10_000, "Home Assistant to accept the subscription") {
            val id = sendCommand(JSONObject().put("type", "subscribe_events").put("event_type", eventType))
            awaitResult(id)
            phoneEventHandlers[id] = handler
        }

    suspend fun listPipelines(): PipelineList = timed(10_000, "the pipeline list") {
        val id = sendCommand(JSONObject().put("type", "assist_pipeline/pipeline/list"))
        val result = awaitResult(id)
        val array = result.getJSONArray("pipelines")
        val pipelines = (0 until array.length()).map {
            val p = array.getJSONObject(it)
            Pipeline(
                id = p.getString("id"),
                name = p.getString("name"),
                supportsSpeech = p.optStringOrNull("tts_engine") != null,
                ttsEngine = p.optStringOrNull("tts_engine"),
                ttsLanguage = p.optStringOrNull("tts_language"),
                ttsVoice = p.optStringOrNull("tts_voice"),
            )
        }
        PipelineList(pipelines, result.optStringOrNull("preferred_pipeline"))
    }

    /**
     * Sends a request's text through an Assist pipeline, starting at the intent stage.
     * With [speak], the pipeline also renders the reply as speech. [onStatus] gets a short
     * description of each tool the agent uses ("Logging…") while it works.
     */
    suspend fun runIntent(
        text: String,
        conversationId: String?,
        pipeline: Pipeline?,
        speak: Boolean,
        onStatus: (String) -> Unit = {},
    ): AssistReply =
        // Long enough for a web search or several tools; a stuck request gives up instead of waiting forever.
        timed(60_000, "the assistant's reply") {
            val command = JSONObject()
                .put("type", "assist_pipeline/run")
                .put("start_stage", "intent")
                .put("end_stage", if (speak && pipeline?.supportsSpeech == true) "tts" else "intent")
                .put("input", JSONObject().put("text", text))
            conversationId?.let { command.put("conversation_id", it) }
            pipeline?.let { command.put("pipeline", it.id) }

            val id = sendCommand(command)
            awaitResult(id)
            awaitPipelineReply(id, onStatus)
        }

    /**
     * Saves a photo in Home Assistant's media folder (Media → My media) and returns its
     * media-source ID, so it is kept and can be shown to the AI.
     */
    suspend fun uploadMedia(bytes: ByteArray, fileName: String, mimeType: String): String = withContext(Dispatchers.IO) {
        // The media folder's root; HA's own uploader has used both spellings.
        for (folder in listOf("media-source://media_source/local/.", "media-source://media_source/local/")) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("media_content_id", folder)
                .addFormDataPart("file", fileName, bytes.toRequestBody(mimeType.toMediaType()))
                .build()
            val request = Request.Builder()
                .url("$httpBase/api/media_source/local_source/upload")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            val result = try {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: java.io.IOException) {
                throw HaException("Couldn't upload to Home Assistant (${e.message})")
            }
            if (result.first in 200..299) return@withContext JSONObject(result.second).getString("media_content_id")
            AppLog.w("Media upload to $folder refused (${result.first}): ${result.second.take(200)}")
        }
        throw HaException("Home Assistant refused the upload")
    }

    /** The AI Task entities in Home Assistant (any provider), which can describe photos. */
    suspend fun aiTaskEntities(): List<String> = timed(20_000, "the entity list") {
        val states = awaitListResult(sendCommand(JSONObject().put("type", "get_states")))
        (0 until states.length())
            .map { states.getJSONObject(it).optString("entity_id") }
            .filter { it.startsWith("ai_task.") }
    }

    /** Like [awaitResult], for commands whose result is a list. */
    /** The home's latitude and longitude from Home Assistant's general settings. */
    suspend fun homeLocation(): Pair<Double, Double>? = timed(10_000, "the home location") {
        val config = awaitResult(sendCommand(JSONObject().put("type", "get_config")))
        val latitude = config.optDouble("latitude")
        val longitude = config.optDouble("longitude")
        if (latitude.isNaN() || longitude.isNaN() || (latitude == 0.0 && longitude == 0.0)) null else latitude to longitude
    }

    private suspend fun awaitListResult(id: Int): JSONArray {
        while (true) {
            val message = receive()
            if (message.optInt("id") != id || message.optString("type") != "result") continue
            if (!message.optBoolean("success")) throw HaException("Home Assistant refused the request")
            return message.optJSONArray("result") ?: JSONArray()
        }
    }

    /** Asks an AI Task entity to describe an uploaded image with [instructions]. */
    suspend fun describeImage(aiTaskEntity: String, mediaId: String, mimeType: String, instructions: String): String =
        timed(90_000, "the photo description") {
            val id = sendCommand(
                JSONObject()
                    .put("type", "call_service")
                    .put("domain", "ai_task")
                    .put("service", "generate_data")
                    .put(
                        "service_data",
                        JSONObject()
                            .put("task_name", "Describe an attachment for Assist")
                            .put("entity_id", aiTaskEntity)
                            .put("instructions", instructions)
                            .put(
                                "attachments",
                                JSONArray().put(JSONObject().put("media_content_id", mediaId).put("media_content_type", mimeType)),
                            ),
                    )
                    .put("return_response", true),
            )
            val result = awaitResult(id)
            result.optJSONObject("response")?.optStringOrNull("data")
                ?: throw HaException("The AI didn't describe the photo")
        }

    /** A URL of [text] spoken in [pipeline]'s voice (Home Assistant caches it), or null without one. */
    suspend fun speechUrl(pipeline: Pipeline, text: String): String? = withContext(Dispatchers.IO) {
        val engine = pipeline.ttsEngine ?: return@withContext null
        val body = JSONObject().put("engine_id", engine).put("message", text).put("cache", true)
        pipeline.ttsLanguage?.let { body.put("language", it) }
        pipeline.ttsVoice?.let { body.put("options", JSONObject().put("voice", it)) }
        val request = Request.Builder()
            .url("$httpBase/api/tts_get_url")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                JSONObject(response.body?.string().orEmpty()).optStringOrNull("url")?.let(::absoluteUrl)
            }
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** Adds a line to Home Assistant's own log under the "assist_plus" logger. */
    suspend fun writeSystemLog(message: String, level: String) = timed(10_000, "Home Assistant to save the log") {
        val id = sendCommand(
            JSONObject()
                .put("type", "call_service")
                .put("domain", "system_log")
                .put("service", "write")
                .put(
                    "service_data",
                    JSONObject().put("message", message).put("level", level).put("logger", "assist_plus"),
                ),
        )
        awaitResult(id)
        Unit
    }

    fun close() {
        socket?.close(1000, null)
        incoming.close()
    }

    private fun send(message: JSONObject) {
        if (socket?.send(message.toString()) != true) throw HaException("Connection to Home Assistant is closed")
    }

    private fun sendCommand(command: JSONObject): Int {
        val id = nextId++
        send(command.put("id", id))
        return id
    }

    /** Waits for the "result" message of command [id] and returns its payload. */
    private suspend fun awaitResult(id: Int): JSONObject {
        while (true) {
            val message = receive()
            if (message.optInt("id") != id || message.optString("type") != "result") continue
            if (!message.optBoolean("success")) {
                val error = message.optJSONObject("error")
                throw HaException(error?.optStringOrNull("message") ?: "Home Assistant refused the request")
            }
            return message.optJSONObject("result") ?: JSONObject()
        }
    }

    /** Reads the pipeline events of run [id] until "run-end", collecting the reply. */
    private suspend fun awaitPipelineReply(id: Int, onStatus: (String) -> Unit): AssistReply {
        var speech: String? = null
        var conversationId: String? = null
        var continueConversation = false
        var audioUrl: String? = null
        while (true) {
            val message = receive()
            if (message.optInt("id") != id || message.optString("type") != "event") continue
            val event = message.getJSONObject("event")
            val data = event.optJSONObject("data")
            when (event.optString("type")) {
                // Streaming agents report each tool call as it happens.
                "intent-progress" -> {
                    val calls = data?.optJSONObject("chat_log_delta")?.optJSONArray("tool_calls")
                    if (calls != null && calls.length() > 0) {
                        onStatus(toolStatus(calls.optJSONObject(0)?.optString("tool_name").orEmpty()))
                    }
                }
                "intent-end" -> {
                    val output = data?.optJSONObject("intent_output")
                    speech = output?.optJSONObject("response")
                        ?.optJSONObject("speech")
                        ?.optJSONObject("plain")
                        ?.optStringOrNull("speech")
                        .orEmpty()
                    conversationId = output?.optStringOrNull("conversation_id")
                    continueConversation = output?.optBoolean("continue_conversation") == true
                }
                "tts-end" -> {
                    audioUrl = data?.optJSONObject("tts_output")?.optStringOrNull("url")?.let(::absoluteUrl)
                }
                "error" -> throw HaException(data?.optStringOrNull("message") ?: "Assist reported an error")
                "run-end" -> {
                    val reply = speech ?: throw HaException("Assist finished without a reply")
                    return AssistReply(reply, conversationId, audioUrl, continueConversation)
                }
            }
        }
    }

    /**
     * A Home Assistant link through the address this app connects with. HA builds links with
     * its own address, often the local one, which doesn't work away from home.
     */
    private fun absoluteUrl(url: String): String =
        if ("://" in url) httpBase + "/" + url.substringAfter("://").substringAfter('/', "") else httpBase + url

    /**
     * The next message for the request in progress. A phone event ("open Spotify") can arrive in
     * the middle of any request (a script fires it while the agent works); it's handled here.
     */
    private suspend fun receive(): JSONObject {
        while (true) {
            val message = try {
                incoming.receive()
            } catch (e: ClosedReceiveChannelException) {
                throw HaException("Home Assistant closed the connection")
            }
            val handler = phoneEventHandlers[message.optInt("id", -1)]
            if (handler != null && message.optString("type") == "event") {
                val event = message.optJSONObject("event")
                // Events are only read during a request; one fired while Assist sat idle is stale.
                if (event != null && isRecent(event.optString("time_fired"))) event.optJSONObject("data")?.let(handler)
                continue
            }
            return message
        }
    }

    /**
     * Runs one request with a time limit. Requests take turns (one reader of the
     * connection at a time), so a log sync never interleaves with an Assist request.
     */
    /** Whether an event fired at [timeFired] (ISO time) is under a minute old; unknown times count as recent. */
    private fun isRecent(timeFired: String): Boolean = try {
        java.time.OffsetDateTime.parse(timeFired).toInstant().isAfter(java.time.Instant.now().minusSeconds(60))
    } catch (e: java.time.format.DateTimeParseException) {
        true
    }

    private suspend fun <T> timed(millis: Long, what: String, block: suspend () -> T): T = lock.withLock {
        try {
            withTimeout(millis) { block() }
        } catch (e: TimeoutCancellationException) {
            throw HaException("Timed out waiting for $what")
        }
    }

    companion object {
        /** Open an app on the phone; data: package ("com.spotify.music"). Spotify only plays on a phone while its app runs. */
        const val OPEN_APP_EVENT = "assist_plus_open_app"

        /** Set the phone's volume; data: level (0–100), stream ("media", "ring", "alarm" or "notification", default media). */
        const val SET_VOLUME_EVENT = "assist_plus_set_volume"

        private val http = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        fun toHttpUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if ("://" in trimmed) trimmed else "http://$trimmed"
        }

        fun toWebSocketUrl(baseUrl: String): String =
            toHttpUrl(baseUrl)
                .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://") + "/api/websocket"
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }

/**
 * A short, unspoken status for a tool the agent is using, from its name: Home Health's
 * log_strength and log_exercise are "Logging…", Basic Memory's write_note is "Saving to
 * memory…", the Spotify scripts are music, Home Assistant's own Hass* intents are devices.
 */
fun toolStatus(tool: String): String {
    val name = tool.lowercase()
    return when {
        name.startsWith("log_") -> "Logging…"
        "food" in name -> "Looking up the food…"
        "summary" in name -> "Checking today's totals…"
        "spotify" in name || "music" in name -> "Working on the music…"
        "write_note" in name || "edit_note" in name || "save" in name -> "Saving to memory…"
        "note" in name || "memory" in name || "search" in name || "read" in name -> "Checking memory…"
        "web" in name -> "Searching the web…"
        name.startsWith("hass") -> "Controlling the house…"
        "context" in name || "state" in name -> "Checking the house…"
        else -> "Working on it…"
    }
}
