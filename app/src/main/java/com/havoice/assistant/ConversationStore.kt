package com.havoice.assistant

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last conversation, kept after the Assist sheet closes, so opening it again shows what was
 * said and answered and carries on from there instead of starting blank.
 */
data class SavedConversation(
    val conversationId: String?,
    val pipelineId: String?,
    /** When the last request or reply happened (epoch millis). */
    val at: Long,
    val messages: List<AssistMessage>,
    /** A request was sent and the sheet closed before its reply came back. */
    val waiting: Boolean = false,
) {
    /** Recent enough to show again when the sheet opens. */
    fun worthShowing(now: Long) = messages.isNotEmpty() && now - at in 0..SHOW_FOR_MILLIS

    companion object {
        /**
         * Half an hour: long enough to come back and read a reply that was missed, short enough
         * that an old conversation isn't in the way. (Home Assistant itself forgets what a
         * conversation was about after five idle minutes; older ones are shown, not continued.)
         */
        const val SHOW_FOR_MILLIS = 30 * 60_000L

        /** Only the latest turns are kept: the screen shows a conversation, not a history. */
        const val MAX_MESSAGES = 20
    }
}

object ConversationStore {
    private fun file(context: Context) = File(context.filesDir, "conversation.json")

    fun encode(saved: SavedConversation): String = JSONObject()
        .put("conversation_id", saved.conversationId ?: JSONObject.NULL)
        .put("pipeline_id", saved.pipelineId ?: JSONObject.NULL)
        .put("at", saved.at)
        .put("waiting", saved.waiting)
        .put(
            "messages",
            JSONArray(saved.messages.takeLast(SavedConversation.MAX_MESSAGES).map { m ->
                JSONObject().put("text", m.message).put("input", m.isInput).put("error", m.isError)
            }),
        ).toString()

    fun decode(json: String): SavedConversation? = try {
        val o = JSONObject(json)
        val array = o.getJSONArray("messages")
        SavedConversation(
            conversationId = o.optString("conversation_id").takeIf { it.isNotBlank() && !o.isNull("conversation_id") },
            pipelineId = o.optString("pipeline_id").takeIf { it.isNotBlank() && !o.isNull("pipeline_id") },
            at = o.getLong("at"),
            messages = (0 until array.length()).map { array.getJSONObject(it) }.map {
                AssistMessage(it.getString("text"), isInput = it.getBoolean("input"), isError = it.optBoolean("error", false))
            },
            waiting = o.optBoolean("waiting", false),
        )
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun load(context: Context): SavedConversation? = file(context).takeIf { it.exists() }?.let { decode(it.readText()) }

    @Synchronized
    fun save(context: Context, saved: SavedConversation) {
        val target = file(context)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(encode(saved))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    /**
     * Adds the [reply] to a request that was still waiting when the sheet closed (see
     * [UnfinishedRequest]), so it is there to read when the sheet opens again.
     */
    @Synchronized
    fun finishWaiting(context: Context, reply: AssistMessage, conversationId: String?, now: Long) {
        val saved = load(context)?.takeIf { it.waiting } ?: return
        save(context, saved.copy(conversationId = conversationId ?: saved.conversationId, at = now, messages = saved.messages + reply, waiting = false))
    }
}

/** What became of a request to Home Assistant. */
sealed interface RequestOutcome {
    class Replied(val reply: AssistReply) : RequestOutcome
    class Failed(val error: HaException) : RequestOutcome

    /** The user stopped it before the reply came. */
    data object Stopped : RequestOutcome
}

/**
 * Requests to Home Assistant run here rather than in the Assist sheet, so one that is on its way
 * isn't lost when the sheet closes by accident: it finishes, and its reply is saved
 * ([ConversationStore.finishWaiting]). [outcome] is the request still running after the sheet
 * closed, for a sheet opened in the meantime to wait on.
 */
object UnfinishedRequest {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var outcome: CompletableDeferred<RequestOutcome>? = null
}
