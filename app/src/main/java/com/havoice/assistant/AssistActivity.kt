package com.havoice.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import kotlinx.coroutines.withContext
import android.os.Build
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class AssistMessage(
    val message: String,
    val isInput: Boolean,
    val isError: Boolean = false,
    val isPlaceholder: Boolean = false,
)

enum class InputMode { TEXT, VOICE_INACTIVE, VOICE_ACTIVE }

private const val BUBBLE_ENTER_MS = 220
private const val WELCOME = "How can I assist?"

private fun quote(text: String) = "\"" + text.take(200) + "\""

/**
 * The Assist sheet that slides up over any app when the assistant button is long-pressed.
 * Layout, colors and animations follow the Home Assistant Companion app's Assist sheet.
 */
class AssistActivity : ComponentActivity() {
    private lateinit var settings: HaSettings
    private var client: HaClient? = null
    private var conversationId: String? = null
    private val player = ReplyPlayer()
    private var voiceJob: Job? = null
    private var idleWakeJob: Job? = null
    private var stopSignal: CompletableDeferred<Unit>? = null

    // Set while a reply is being spoken; completing it cuts the reply short (see say).
    private var interruptSpeech: CompletableDeferred<Unit>? = null

    private val messages = mutableStateListOf(AssistMessage(WELCOME, isInput = false))
    private var pipelines by mutableStateOf<List<Pipeline>>(emptyList())
    private var currentPipeline by mutableStateOf<Pipeline?>(null)
    private var inputMode by mutableStateOf<InputMode?>(null)
    private var busy by mutableStateOf(false)

    // Spoken PIN while locked: asked for before the first request after the sheet opens.
    private var pinVerified = false
    private var awaitingPin = false

    // Photos, barcodes and files waiting to go with the next request.
    private val attachments = mutableStateListOf<Attachment>()
    private var preparingAttachment by mutableStateOf(false)

    // Whether this Home Assistant has an AI Task that can describe photos.
    private var photosAvailable by mutableStateOf(true)

    // True while the camera, photo picker or barcode scanner is open on top of the sheet,
    // so leaving for it doesn't close the conversation.
    private var usingOtherScreen = false
    private var cameraUri: Uri? = null

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        usingOtherScreen = false
        if (saved) cameraUri?.let(::attachUri)
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        usingOtherScreen = false
        uri?.let(::attachUri)
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        usingOtherScreen = false
        uri?.let(::attachUri)
    }

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    // Pauses music and other audio while the assistant listens and talks.
    private val audioFocus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startListening()
        } else {
            inputMode = InputMode.TEXT
            messages += AssistMessage("Microphone access is needed to talk to Assist.", isInput = false, isError = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = HaSettings(this)
        // The wake word listener lets go of the mic while the sheet is open. If it isn't running
        // (stopped by an app update), it starts now and listens once the sheet closes.
        if (WakeWordService.isRunning) WakeWordService.pause(this) else WakeWordService.startPaused(this)
        intent.getStringExtra(EXTRA_FROM_WAKE_WORD)
            ?.takeIf { intent.getStringExtra(EXTRA_WAKE_TOKEN) == WakeWordService.LAUNCH_TOKEN }
            ?.let { phrase ->
            AppLog.i("Opened by wake word \"$phrase\"")
            if (settings.wakeWhenLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
        }
        // The manifest lets the sheet show over the lock screen (holding the side button, as in the
        // Companion app; Android decides that before this code runs). The phone stays locked:
        // anything that opens another app asks to unlock first, and the spoken PIN still applies.
        // Wake word launches on a locked phone are only made with "Work when locked" on.
        AppLog.i(
            "Assist opened (noise ${settings.noiseReduction}, hands-free ${settings.handsFree}, " +
                "voice ID ${if (voiceProfile() != null) "on" else "off"})",
        )
        // Load the models in the background so the first listen isn't delayed.
        lifecycleScope.launch(Dispatchers.Default) {
            NoiseFilter.get(applicationContext, settings.noiseReduction)
            SpeechEngines.get(applicationContext)
        }
        // Look up the city while the user speaks; the note is cached for the request.
        lifecycleScope.launch { locationNote() }
        if (settings.isConfigured) {
            loadPipelines()
        } else {
            inputMode = InputMode.TEXT
            messages += AssistMessage(
                "Not connected to Home Assistant yet. Tap the gear to set it up.",
                isInput = false,
                isError = true,
            )
        }
        setContent {
            HaVoiceTheme {
                AssistSheet(
                    messages = messages,
                    pipelines = pipelines,
                    currentPipeline = currentPipeline,
                    inputMode = inputMode,
                    textEnabled = settings.isConfigured && !busy,
                    attachments = attachments,
                    preparingAttachment = preparingAttachment,
                    photosAvailable = photosAvailable,
                    onAttach = ::openAttach,
                    onRemoveAttachment = { attachments.remove(it) },
                    onSelectPipeline = ::selectPipeline,
                    onTextInput = ::send,
                    onMicrophone = ::toggleListening,
                    onChangeInput = ::changeInput,
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        finish()
                    },
                    onHide = ::finish,
                )
            }
        }
        handleShare(intent)
        // Development only: "adb shell am start -n com.havoice.assistant/.AssistActivity --es debug_request '…'"
        // runs a typed request, so phone actions can be tested from a computer. Not in release builds.
        if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_DEBUG_REQUEST)?.let(::send)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStop() {
        super.onStop()
        // Behave like a transient panel: leaving it ends the conversation.
        if (!isChangingConfigurations && !usingOtherScreen) finish()
    }

    /** "Share → Assist" from another app: attach what was shared. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        when {
            stream != null -> attachUri(stream)
            !text.isNullOrBlank() -> attachments += Attachments.fromSharedText(text)
        }
    }

    private fun openAttach(kind: AttachKind) {
        cancelVoice()
        usingOtherScreen = true
        when (kind) {
            AttachKind.CAMERA -> {
                val folder = File(cacheDir, "camera").apply { mkdirs() }
                val uri = FileProvider.getUriForFile(this, "$packageName.files", File(folder, "photo_${System.currentTimeMillis()}.jpg"))
                cameraUri = uri
                takePhoto.launch(uri)
            }
            AttachKind.PHOTO -> pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            AttachKind.FILE -> pickFile.launch(arrayOf("image/*", "application/pdf", "text/*", "application/json"))
            AttachKind.BARCODE -> GmsBarcodeScanning.getClient(this).startScan()
                .addOnSuccessListener { barcode ->
                    usingOtherScreen = false
                    barcode.rawValue?.let { value -> prepare { listOf(Attachment.Barcode(Attachments.scanned(value))) } }
                }
                .addOnCanceledListener { usingOtherScreen = false }
                .addOnFailureListener { e ->
                    usingOtherScreen = false
                    // Backing out of the scanner arrives as a failure ("Failed to scan code").
                    val code = (e as? MlKitException)?.errorCode
                    if (code == MlKitException.CANCELLED || code == MlKitException.CODE_SCANNER_CANCELLED) {
                        AppLog.i("Barcode scanner closed without a scan")
                    } else {
                        AppLog.w("Barcode scanner failed ($code): ${e.message}")
                        messages += AssistMessage("Barcode scanner: ${e.message}", isInput = false, isError = true)
                    }
                }
        }
    }

    private fun attachUri(uri: Uri) = prepare { Attachments.fromUri(applicationContext, uri) }

    /** Prepares attachments on the phone, then (hands-free) listens for what to do with them. */
    private fun prepare(load: suspend () -> List<Attachment>) {
        preparingAttachment = true
        lifecycleScope.launch {
            try {
                val added = load()
                attachments += added
                AppLog.i("Attached: ${added.joinToString("; ") { it.label }}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Couldn't attach: ${e.message}")
                messages += AssistMessage(e.message ?: "Couldn't attach that", isInput = false, isError = true)
            } finally {
                preparingAttachment = false
            }
            if (settings.handsFree && inputMode != InputMode.TEXT && attachments.isNotEmpty()) requestListening()
        }
    }

    /**
     * The AI Task entity that describes photos: the one in settings, or the first one Home
     * Assistant has (remembered). Null when this Home Assistant has none.
     */
    private suspend fun aiTaskEntity(): String? {
        settings.aiTaskEntity.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            connectedClient().aiTaskEntities().firstOrNull()?.also {
                settings.aiTaskEntity = it
                AppLog.i("Photo descriptions will use $it")
            }
        } catch (e: HaException) {
            null
        }
    }

    /**
     * The text for Home Assistant: the request plus a note per attachment (photos are uploaded and
     * described on the way). Attachments are used up; on failure they're put back for a retry.
     */
    private suspend fun withAttachments(request: String): String {
        if (attachments.isEmpty()) return request
        val sending = attachments.toList()
        attachments.clear()
        val started = System.currentTimeMillis()
        return try {
            val needsAi = sending.any { it is Attachment.Photo }
            val entity = if (needsAi) {
                aiTaskEntity() ?: throw HaException(
                    "Your Home Assistant has no AI Task that can look at photos. Add an AI integration " +
                        "(like Anthropic, OpenAI or Google Gemini) with an AI Task, or send a barcode instead.",
                )
            } else {
                ""
            }
            AttachmentMessage.build(request, sending, connectedClient(), entity).also {
                AppLog.i("Attachments ready in ${System.currentTimeMillis() - started} ms: ${quote(it.removePrefix(request).trim())}")
            }
        } catch (e: HaException) {
            attachments.addAll(0, sending)
            throw e
        }
    }

    /** What the owner's bubble shows: their words, plus what was attached. */
    /** Takes the agent's "[heard: X -> Y]" tags out of [reply] and adds those names to listen for. */
    private fun learnNames(reply: String): String {
        val (clean, learned) = NameCorrections.strip(reply)
        NameCorrections.record(settings, learned)
        return clean
    }

    /** The hidden location note for a request (Settings → Assistant), or nothing. */
    private suspend fun locationNote(): String {
        val note = try {
            withTimeoutOrNull(3_000) { PhoneLocation.contextNote(applicationContext, settings.locationSharing) }
        } catch (e: SecurityException) {
            null
        }
        return if (note.isNullOrEmpty()) "" else "\n\n$note"
    }

    /**
     * Puts [message] in bubble [index], or adds it at the end if that bubble is gone (opening
     * the photo picker while a reply is on its way clears the waiting bubble).
     */
    private fun setMessage(index: Int, message: AssistMessage) {
        if (index in messages.indices) messages[index] = message else messages += message
    }

    /** Shows what the assistant is doing ("Logging…") in its waiting bubble; never spoken. */
    private fun showStatus(index: Int, status: String) {
        if (messages.getOrNull(index)?.let { it.isPlaceholder && !it.isInput } == true) {
            messages[index] = AssistMessage(status, isInput = false, isPlaceholder = true)
        }
    }

    private fun inputBubble(request: String): String {
        if (attachments.isEmpty()) return request
        val list = "📎 " + attachments.joinToString(", ") { it.label }
        return if (request.isBlank()) list else "$request\n$list"
    }

    override fun onDestroy() {
        stopIdleWake()
        voiceJob?.cancel()
        player.stop()
        audioManager.abandonAudioFocusRequest(audioFocus)
        client?.close()
        WakeWordService.resume(this)
        super.onDestroy()
    }

    companion object {
        /** Set (to the phrase heard) when the wake word listener opened the sheet. */
        const val EXTRA_FROM_WAKE_WORD = "from_wake_word"
        const val EXTRA_WAKE_TOKEN = "wake_token"
        private const val EXTRA_DEBUG_REQUEST = "debug_request"

        // Asks about an address at most this many times, then lets the maps app search for it.
        private const val MAX_ADDRESS_TRIES = 3
    }

    private fun loadPipelines() {
        lifecycleScope.launch {
            try {
                val list = connectedClient().listPipelines()
                pipelines = list.pipelines
                val pipeline = list.pipelines.firstOrNull { it.id == settings.pipelineId }
                    ?: list.pipelines.firstOrNull { it.id == list.preferredId }
                    ?: list.pipelines.firstOrNull()
                currentPipeline = pipeline
                AppLog.i("Connected to Home Assistant, pipeline '${pipeline?.name}'")
                usePipelineInput(pipeline, autoListen = settings.handsFree)
                syncLog()
                photosAvailable = aiTaskEntity() != null
            } catch (e: HaException) {
                AppLog.w("Couldn't connect: ${e.message}")
                dropConnection()
                inputMode = InputMode.TEXT
                messages += AssistMessage(e.message.orEmpty(), isInput = false, isError = true)
            }
        }
    }

    /** Sends queued log lines while the connection is open anyway. */
    private fun syncLog() {
        val connected = client ?: return
        lifecycleScope.launch {
            try {
                LogSync.sync(connected)
            } catch (e: HaException) {
                // Lines stay queued for the next sync.
            }
        }
    }

    /** Voice works with every pipeline, since the phone does all the listening. */
    private fun usePipelineInput(pipeline: Pipeline?, autoListen: Boolean) {
        if (pipeline == null) {
            inputMode = InputMode.TEXT
            return
        }
        inputMode = InputMode.VOICE_INACTIVE
        if (!pinVerified && PinLock.required(this, settings)) {
            askForPin()
            return
        }
        if (autoListen) requestListening() else startIdleWake()
    }

    /** The phone is locked and the spoken PIN is on: ask for it, in the assistant's voice. */
    private fun askForPin() {
        awaitingPin = true
        val prompt = "This phone is locked. Say your Assist PIN to continue."
        messages += AssistMessage(prompt, isInput = false)
        lifecycleScope.launch {
            say(prompt)
            requestListening()
        }
    }

    /** Checks a PIN attempt and answers it. Returns whether to listen again. */
    private suspend fun handlePin(attempt: String): Boolean {
        val result = PinLock.check(settings, attempt)
        val reply = when (result) {
            PinLock.Result.Correct -> "Thanks, go ahead."
            is PinLock.Result.Wrong ->
                "That PIN didn't match. ${result.triesLeft} ${if (result.triesLeft == 1) "try" else "tries"} left."
            is PinLock.Result.LockedOut -> "Too many wrong tries. Try again in ${result.minutes} minutes."
            PinLock.Result.NoDigits -> "I didn't hear any numbers. Say your PIN one digit at a time."
        }
        if (result == PinLock.Result.Correct) {
            pinVerified = true
            awaitingPin = false
            AppLog.i("Spoken PIN accepted")
        }
        messages += AssistMessage(reply, isInput = false, isError = result != PinLock.Result.Correct)
        say(reply)
        if (result is PinLock.Result.LockedOut) {
            finish()
            return false
        }
        return true
    }

    /**
     * Speaks [text]. The wake phrase in the owner's voice, or a mic tap, cuts it short and
     * listens once the caller is done; true if that happened. Other voices (including the
     * reply itself) don't interrupt.
     */
    private suspend fun say(text: String): Boolean {
        val caller = currentCoroutineContext()[Job]
        val cut = CompletableDeferred<Unit>().also { interruptSpeech = it }
        coroutineScope {
            val speech = launch { speak(text) }
            val watcher = launch {
                launch { if (awaitOwnerWake("while speaking")) cut.complete(Unit) }
                cut.await()
                speech.cancel()
            }
            speech.join()
            watcher.cancel()
        }
        interruptSpeech = null
        if (!cut.isCompleted) return false
        AppLog.i("Reply cut short; listening")
        beep()
        listenAfter(caller)
        return true
    }

    /** Speaks [text] in the pipeline's own voice, or the phone's voice if Home Assistant can't. */
    private suspend fun speak(text: String) {
        val url = currentPipeline?.let { pipeline ->
            try {
                connectedClient().speechUrl(pipeline, text)
            } catch (e: HaException) {
                null
            }
        }
        // If Home Assistant's audio can't be played (no voice set up, or unreachable), the phone speaks.
        if (url == null || !player.play(url)) sayOnPhone(text)
    }

    private suspend fun sayOnPhone(text: String) = suspendCancellableCoroutine { continuation ->
        var engine: TextToSpeech? = null
        fun done() {
            engine?.shutdown()
            if (continuation.isActive) continuation.resume(Unit)
        }
        engine = TextToSpeech(this) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                done()
                return@TextToSpeech
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = done()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = done()
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "prompt")
        }
        continuation.invokeOnCancellation { engine?.shutdown() }
    }

    private fun voiceProfile(): Voiceprint? = if (settings.voiceIdEnabled) VoiceProfile.load(this) else null

    private fun selectPipeline(pipeline: Pipeline) {
        if (pipeline.id == currentPipeline?.id) return
        cancelVoice()
        settings.pipelineId = pipeline.id
        currentPipeline = pipeline
        // A different pipeline starts a fresh conversation, as in the Companion app.
        conversationId = null
        messages.clear()
        messages += AssistMessage(WELCOME, isInput = false)
        usePipelineInput(pipeline, autoListen = false)
    }

    private fun send(text: String) {
        player.stop()
        if (awaitingPin) {
            messages += AssistMessage("••••", isInput = true)
            lifecycleScope.launch { handlePin(text) }
            return
        }
        val exit = if (attachments.isEmpty()) ExitWords.classify(text, WakeWords.load(settings)) else ExitWords.Kind.NONE
        if (exit == ExitWords.Kind.CANCEL) {
            AppLog.i("Typed ${quote(text)} — closing Assist")
            finish()
            return
        }
        messages += AssistMessage(inputBubble(text), isInput = true)
        pendingNavigation?.let { pending ->
            pendingNavigation = null
            if (exit == ExitWords.Kind.GOODBYE) finish() else lifecycleScope.launch { answerAboutAddress(pending, text) }
            return
        }
        phoneAction(text)?.let { action ->
            lifecycleScope.launch { runPhoneAction(action, text) }
            return
        }
        messages += AssistMessage("", isInput = false, isPlaceholder = true)
        val replyIndex = messages.lastIndex
        busy = true
        val started = System.currentTimeMillis()
        lifecycleScope.launch {
            var closing = false
            val answer = try {
                val message = withAttachments(text) + locationNote()
                val reply = connectedClient().runIntent(message, conversationId, currentPipeline, speak = false) { status ->
                    showStatus(replyIndex, status)
                }
                conversationId = reply.conversationId
                val (shown, agentEnded) = ExitWords.stripEndTag(learnNames(reply.speech))
                closing = agentEnded || exit == ExitWords.Kind.GOODBYE
                AppLog.i("Typed ${quote(text)} → reply in ${System.currentTimeMillis() - started} ms: ${quote(shown)}")
                AssistMessage(shown.ifBlank { "Done." }, isInput = false)
            } catch (e: HaException) {
                AppLog.w("Typed ${quote(text)} failed: ${e.message}")
                dropConnection()
                AssistMessage(e.message.orEmpty(), isInput = false, isError = true)
            } finally {
                busy = false
            }
            setMessage(replyIndex, answer)
            syncLog()
            if (closing) {
                // Leave the sign-off on screen for a moment before closing.
                kotlinx.coroutines.delay(1500)
                finish()
            }
        }
    }

    private fun phoneAction(text: String): PhoneAction? =
        if (settings.phoneActions && attachments.isEmpty()) PhoneActions.parse(text, settings.navigationApp) else null

    /**
     * Does [action] on the phone instead of asking Home Assistant, then closes Assist if it worked.
     * A street address is looked up first; returns true when that needs a question answered.
     * [confirmed]: the address was already settled, and this is what to say.
     */
    private suspend fun runPhoneAction(
        action: PhoneAction,
        request: String,
        confirmed: String? = null,
        attempt: Int = 1,
    ): Boolean {
        AppLog.i("Phone action for ${quote(request)}: $action")
        var todo = action
        var saying = confirmed
        if (action is PhoneAction.Navigate && confirmed == null && AddressCheck.needsCheck(action.place)) {
            val result = AddressCheck.check(this, action.place, searchCenter())
            AppLog.i("Address check for ${quote(action.place)}: $result")
            when (result) {
                is AddressCheck.Result.Found -> if (result.sure) {
                    todo = action.copy(place = result.address.query)
                    saying = "Navigating to ${result.address.spoken}."
                } else {
                    return askAboutAddress(PendingNavigation(action, result.address, attempt), "Did you mean ${result.address.spoken}?")
                }
                AddressCheck.Result.NotFound -> if (attempt < MAX_ADDRESS_TRIES) {
                    return askAboutAddress(
                        PendingNavigation(action, null, attempt),
                        "I couldn't find ${action.place}. What city is it in, or can you say the address again?",
                    )
                } else {
                    saying = "I couldn't confirm that address, so Maps will search for it."
                }
                // No lookup available: navigate as heard.
                AddressCheck.Result.Unavailable -> Unit
            }
        }
        if (todo.opensApp && !unlocked()) {
            messages += AssistMessage("Unlock your phone to do that.", isInput = false, isError = true)
            return false
        }
        if (saying != null) {
            messages += AssistMessage(saying, isInput = false)
            if (currentPipeline?.supportsSpeech == true) say(saying)
        }
        val problem = PhoneActions.start(this, todo)
        if (problem != null) {
            AppLog.w("Phone action failed: $problem")
            messages += AssistMessage(problem, isInput = false, isError = true)
            syncLog()
            return false
        }
        if (saying == null) messages += AssistMessage(todo.reply, isInput = false)
        syncLog()
        if (action is PhoneAction.Timer && currentPipeline?.supportsSpeech == true) {
            say(action.reply)
        } else if (!action.opensApp) {
            // Leave the confirmation on screen for a moment.
            delay(1200)
        }
        finish()
        return false
    }

    /** A street address that needs the user's answer before navigating. [suggestion]: the closest match found. */
    private data class PendingNavigation(val action: PhoneAction.Navigate, val suggestion: AddressCheck.Address?, val attempt: Int)

    private var pendingNavigation: PendingNavigation? = null

    private suspend fun askAboutAddress(pending: PendingNavigation, question: String): Boolean {
        pendingNavigation = pending
        messages += AssistMessage(question, isInput = false)
        syncLog()
        if (currentPipeline?.supportsSpeech == true) say(question)
        return true
    }

    /** Handles the answer to [askAboutAddress]; true when there's another question. */
    private suspend fun answerAboutAddress(pending: PendingNavigation, answer: String): Boolean {
        val kind = ConfirmAnswer.classify(answer)
        val suggestion = pending.suggestion
        return when {
            suggestion != null && kind == ConfirmAnswer.Kind.YES ->
                runPhoneAction(
                    pending.action.copy(place = suggestion.query),
                    answer,
                    confirmed = "Navigating to ${suggestion.spoken}.",
                )
            kind != ConfirmAnswer.Kind.OTHER ->
                askAboutAddress(pending.copy(suggestion = null, attempt = pending.attempt + 1), "Okay. What's the address?")
            else -> {
                // A whole new address ("take me to…" or just the address), or the missing city.
                val said = answer.trim()
                    .replace(Regex("^(?:(?:no|nope|nah|yes|yeah)[,.]?\\s+)?(?:(?:it's|it is|that's|that is)\\s+)?(?:in\\s+)?", RegexOption.IGNORE_CASE), "")
                    .trimEnd('.', '?', '!')
                val place = (PhoneActions.parse(answer, settings.navigationApp) as? PhoneAction.Navigate)?.place
                    ?: PhoneActions.withHouseNumber(said).takeIf { it.firstOrNull()?.isDigit() == true }
                    ?: "${pending.action.place}, $said"
                runPhoneAction(pending.action.copy(place = place), answer, attempt = pending.attempt + 1)
            }
        }
    }

    private var home: Pair<Double, Double>? = null
    private var homeLooked = false

    /** Home Assistant's home location, so addresses are looked up nearby first. */
    private suspend fun homeLocation(): Pair<Double, Double>? {
        if (!homeLooked) {
            homeLooked = true
            home = try {
                connectedClient().homeLocation()
            } catch (e: HaException) {
                null
            }
        }
        return home
    }

    /** Where addresses are looked up around: the phone, if location is allowed, else home. */
    private suspend fun searchCenter(): Pair<Double, Double>? {
        val phone = try {
            PhoneLocation.current(applicationContext)
        } catch (e: SecurityException) {
            null
        }
        return phone?.let { it.latitude to it.longitude } ?: homeLocation()
    }

    /** Listens for an answer once [run] (the current listening run) has finished. */
    private fun listenAfter(run: Job?) {
        lifecycleScope.launch {
            run?.join()
            startListening()
        }
    }

    /** True once the phone is unlocked, asking for the screen lock first if needed. */
    private suspend fun unlocked(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return true
        // The unlock screen covers the sheet; that mustn't close the conversation.
        usingOtherScreen = true
        return try {
            suspendCancellableCoroutine { continuation ->
                keyguard.requestDismissKeyguard(
                    this,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() = continuation.resume(true)
                        override fun onDismissCancelled() = continuation.resume(false)
                        override fun onDismissError() = continuation.resume(false)
                    },
                )
            }
        } finally {
            usingOtherScreen = false
        }
    }

    private fun toggleListening() {
        val speaking = interruptSpeech
        if (speaking != null) {
            // Tapping the mic while a reply plays stops it and listens.
            speaking.complete(Unit)
        } else if (inputMode == InputMode.VOICE_ACTIVE) {
            // Tapping the mic while listening finishes the command now.
            stopSignal?.complete(Unit)
        } else {
            requestListening()
        }
    }

    private fun changeInput() {
        if (inputMode == InputMode.TEXT) {
            inputMode = InputMode.VOICE_INACTIVE
            requestListening()
        } else {
            cancelVoice()
            inputMode = InputMode.TEXT
        }
    }

    private fun requestListening() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        val pipeline = currentPipeline ?: return
        if (voiceJob?.isActive == true) return
        player.stop()
        audioManager.requestAudioFocus(audioFocus)
        // Stop listening for the wake phrase; the recording below waits until the mic is free.
        val idleWake = idleWakeJob
        idleWakeJob = null
        idleWake?.cancel()

        val stop = CompletableDeferred<Unit>().also { stopSignal = it }
        inputMode = InputMode.VOICE_ACTIVE
        messages += AssistMessage("", isInput = true, isPlaceholder = true)
        val inputIndex = messages.lastIndex
        var replyIndex = -1

        // Live words arrive from the listening thread; once the final text is in, late ones are ignored.
        var showLive = true

        voiceJob = lifecycleScope.launch {
            var listenAgain = false
            val noise = settings.noiseReduction
            val started = System.currentTimeMillis()
            fun elapsed() = System.currentTimeMillis() - started
            var heard = ""
            try {
                idleWake?.join()
                val threshold = settings.voiceIdThreshold
                // A spoken PIN is never shown, logged or saved.
                val forPin = awaitingPin
                val meter = SignalMeter()
                val listener = PhoneListener(
                    applicationContext,
                    voiceProfile(),
                    threshold,
                    endPauseSeconds = settings.endPauseSeconds,
                    keepPrivate = forPin,
                    hotwords = NameSteering.hotwords(applicationContext, NameCorrections.names(settings)),
                    meter = meter,
                )
                val capture = if (settings.saveRecordings && !forPin) Captures.Recorder() else null
                val result = listener.listen(MicRecorder.frames(applicationContext, noise, capture, meter), stop) { live ->
                    runOnUiThread {
                        val shown = if (forPin && live.isNotEmpty()) "••••" else live
                        if (showLive) setMessage(inputIndex, AssistMessage(shown, isInput = true, isPlaceholder = live.isEmpty()))
                    }
                }
                showLive = false
                inputMode = InputMode.VOICE_INACTIVE
                capture?.let { Captures.save(applicationContext, "assist", it, "noise $noise | ${result.details}") }
                val command = result.command
                if (command == null) {
                    val notice = if (forPin) "Didn't catch that. Say your PIN again." else notHeardMessage(result)
                    setMessage(inputIndex, AssistMessage(notice, isInput = false, isError = true))
                    return@launch
                }
                if (forPin) {
                    setMessage(inputIndex, AssistMessage("••••", isInput = true))
                    if (handlePin(command.text)) {
                        // Listen for the request (or another try) once this listening run has ended.
                        val thisRun = coroutineContext[Job]
                        lifecycleScope.launch {
                            thisRun?.join()
                            requestListening()
                        }
                    }
                    return@launch
                }
                val text = command.text
                heard = text
                val exit = if (command.soundsLikeStop) ExitWords.Kind.CANCEL else ExitWords.classify(text, WakeWords.load(settings))
                if (exit == ExitWords.Kind.CANCEL) {
                    // "cancel", "never mind", "stop": close without asking Home Assistant.
                    AppLog.i("Voice: ${quote(text)} — closing Assist")
                    setMessage(inputIndex, AssistMessage(text, isInput = true))
                    finish()
                    return@launch
                }
                val heardAt = elapsed()
                setMessage(inputIndex, AssistMessage(inputBubble(text), isInput = true))
                pendingNavigation?.let { pending ->
                    // The answer to "Did you mean …?" about an address.
                    pendingNavigation = null
                    if (exit == ExitWords.Kind.GOODBYE) {
                        finish()
                    } else if (answerAboutAddress(pending, text) && settings.handsFree) {
                        listenAfter(coroutineContext[Job])
                    }
                    return@launch
                }
                phoneAction(text)?.let { action ->
                    if (runPhoneAction(action, text) && settings.handsFree) listenAfter(coroutineContext[Job])
                    return@launch
                }
                messages += AssistMessage("", isInput = false, isPlaceholder = true)
                replyIndex = messages.lastIndex

                val message = withAttachments(text) + locationNote()
                // The reply is spoken by the app (in the pipeline's voice) rather than by the
                // pipeline, so the agent's "[end]" tag can be taken out first.
                val reply = connectedClient().runIntent(message, conversationId, pipeline, speak = false) { status ->
                    showStatus(replyIndex, status)
                }
                conversationId = reply.conversationId
                val (spoken, agentEnded) = ExitWords.stripEndTag(learnNames(reply.speech))
                val closing = agentEnded || exit == ExitWords.Kind.GOODBYE
                AppLog.i(
                    "Voice (noise $noise): sent ${quote(text)} at ${heardAt} ms " +
                        "(ended by ${command.endReason}), " +
                        "reply at ${elapsed()} ms: ${quote(spoken)}" +
                        (if (reply.continueConversation) " [follow-up]" else "") +
                        if (closing) " [closing]" else "",
                )
                setMessage(replyIndex, AssistMessage(spoken.ifBlank { "Done." }, isInput = false))
                syncLog()
                val interrupted = pipeline.supportsSpeech && spoken.isNotBlank() && say(spoken)
                if (interrupted) return@launch
                if (closing) {
                    finish()
                    return@launch
                }
                // Assist asked a follow-up question: answer it without touching the screen.
                listenAgain = reply.continueConversation && settings.handsFree
            } catch (e: HaException) {
                showLive = false
                AppLog.w(
                    "Voice (noise $noise) failed after ${elapsed()} ms: ${e.message}" +
                        if (heard.isNotEmpty()) " (heard ${quote(heard)})" else "",
                )
                dropConnection()
                val error = AssistMessage(e.message.orEmpty(), isInput = false, isError = true)
                when {
                    replyIndex >= 0 -> setMessage(replyIndex, error)
                    messages.getOrNull(inputIndex)?.isPlaceholder == true -> setMessage(inputIndex, error)
                    else -> messages += error
                }
            } finally {
                if (inputMode == InputMode.VOICE_ACTIVE) inputMode = InputMode.VOICE_INACTIVE
                stopSignal = null
                voiceJob = null
                if (!listenAgain) {
                    audioManager.abandonAudioFocusRequest(audioFocus)
                    startIdleWake()
                }
            }
            if (listenAgain) startListening()
        }
    }

    /**
     * While the sheet is open but not listening, the wake phrase turns the mic back on
     * (the background listener has let go of the mic while the sheet is open).
     */
    private fun startIdleWake() {
        if (!settings.wakeEnabled || isFinishing || idleWakeJob?.isActive == true || voiceJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        idleWakeJob = lifecycleScope.launch {
            if (!awaitOwnerWake("in the open sheet")) return@launch
            idleWakeJob = null
            beep()
            requestListening()
        }
    }

    /** Waits for the wake phrase in the owner's voice; false if wake words are off or the mic can't be used. */
    private suspend fun awaitOwnerWake(where: String): Boolean {
        if (!settings.wakeEnabled) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val phrases = WakeWords.load(settings)
        while (true) {
            val (phrase, audio) = WakeWords.awaitWake(applicationContext, phrases, settings.wakeSensitivity) ?: return false
            val (mine, score) = withContext(Dispatchers.Default) {
                WakeWords.voiceMatches(applicationContext, settings, phrase, audio)
            }
            AppLog.i(
                "Wake word \"${phrase.text}\" heard $where" +
                    (score?.let { ", voice %.2f".format(it) } ?: "") + if (mine) "" else " — not your voice; ignored",
            )
            if (mine) return true
        }
    }

    private fun stopIdleWake() {
        idleWakeJob?.cancel()
        idleWakeJob = null
    }

    private fun beep() {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 60).startTone(ToneGenerator.TONE_PROP_ACK, 150)
        } catch (e: RuntimeException) {
            // No tone is fine.
        }
    }

    /** Explains an empty result: nothing said, or speech that voice ID didn't think was you. */
    private fun notHeardMessage(result: ListenResult): String {
        val dropped = result.segments.filter { !it.accepted }
        if (dropped.isEmpty()) return "Sorry, I didn't catch that."
        val list = dropped.joinToString(", ") { s ->
            val words = s.preview.ifBlank { "…" }
            s.score?.let { "\"$words\" (%.2f)".format(it) } ?: "\"$words\""
        }
        return "Didn't recognize your voice. Ignored: $list"
    }

    /** Abandons any listening or speaking in progress. */
    private fun cancelVoice() {
        stopIdleWake()
        voiceJob?.cancel()
        voiceJob = null
        player.stop()
        if (messages.lastOrNull()?.isPlaceholder == true) messages.removeAt(messages.lastIndex)
        if (inputMode == InputMode.VOICE_ACTIVE) inputMode = InputMode.VOICE_INACTIVE
        audioManager.abandonAudioFocusRequest(audioFocus)
    }

    /** Reuses one connection for the whole time the sheet is open. */
    private suspend fun connectedClient(): HaClient =
        client ?: HaClient(settings.baseUrl, settings.token).also {
            client = it
            try {
                it.connect()
            } catch (e: HaException) {
                dropConnection()
                throw e
            }
        }

    /** Forgets a failed connection so the next request reconnects. */
    private fun dropConnection() {
        client?.close()
        client = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistSheet(
    messages: List<AssistMessage>,
    pipelines: List<Pipeline>,
    currentPipeline: Pipeline?,
    inputMode: InputMode?,
    textEnabled: Boolean,
    attachments: List<Attachment>,
    preparingAttachment: Boolean,
    photosAvailable: Boolean,
    onAttach: (AttachKind) -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onSelectPipeline: (Pipeline) -> Unit,
    onTextInput: (String) -> Unit,
    onMicrophone: () -> Unit,
    onChangeInput: () -> Unit,
    onOpenSettings: () -> Unit,
    onHide: () -> Unit,
) {
    val colors = LocalHaColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onHide,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = HaRadius.X3L, topEnd = HaRadius.X3L),
        containerColor = colors.surfaceDefault,
        scrimColor = colors.overlayModal,
        dragHandle = {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HaDimens.Space4)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .imePadding(),
        ) {
            AssistSheetHeader(
                pipelines = pipelines,
                currentPipeline = currentPipeline,
                onSelectPipeline = onSelectPipeline,
                onOpenSettings = { scope.launch { sheetState.hide() }.invokeOnCompletion { onOpenSettings() } },
                modifier = Modifier.padding(top = HaDimens.Space4),
            )
            AssistConversation(messages = messages, modifier = Modifier.weight(1f, fill = false))
            AttachmentRow(attachments, preparingAttachment, onRemoveAttachment)
            AssistSheetControls(
                inputMode = inputMode,
                voiceAvailable = currentPipeline != null,
                textEnabled = textEnabled,
                hasAttachments = attachments.isNotEmpty(),
                photosAvailable = photosAvailable,
                onAttach = onAttach,
                onTextInput = onTextInput,
                onMicrophone = onMicrophone,
                onChangeInput = onChangeInput,
            )
            Spacer(modifier = Modifier.height(HaDimens.Space2))
        }
    }
}

@Composable
private fun AssistSheetHeader(
    pipelines: List<Pipeline>,
    currentPipeline: Pipeline?,
    onSelectPipeline: (Pipeline) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHaColors.current
    Row(verticalAlignment = Alignment.Top, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Assist", style = HaText.HeadlineMedium, color = colors.textPrimary)
            if (currentPipeline != null) {
                PipelineSelector(pipelines, currentPipeline, onSelectPipeline)
            }
        }
        HaIconButton(icon = MdiIcons.Cog, contentDescription = "Settings", onClick = onOpenSettings, neutral = true)
    }
}

@Composable
private fun PipelineSelector(
    pipelines: List<Pipeline>,
    currentPipeline: Pipeline,
    onSelectPipeline: (Pipeline) -> Unit,
) {
    val colors = LocalHaColors.current
    Box {
        var expanded by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(HaRadius.S))
                .clickable(role = Role.DropdownList) { expanded = !expanded },
        ) {
            Text(currentPipeline.name, style = HaText.BodyMedium, color = colors.textSecondary)
            Icon(
                imageVector = MdiIcons.ChevronDown,
                contentDescription = "Change assistant",
                tint = colors.textSecondary,
                modifier = Modifier
                    .padding(start = HaDimens.Space1)
                    .size(HaDimens.Space4),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceDefault,
            shape = RoundedCornerShape(HaRadius.XL),
        ) {
            pipelines.forEach { pipeline ->
                DropdownMenuItem(
                    text = { Text(pipeline.name, style = HaText.Body, color = colors.textPrimary) },
                    trailingIcon = if (pipeline.id == currentPipeline.id) {
                        {
                            Icon(
                                MdiIcons.Check,
                                contentDescription = null,
                                tint = colors.fillPrimaryLoud,
                                modifier = Modifier.size(HaDimens.Space5),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        onSelectPipeline(pipeline)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AssistConversation(messages: List<AssistMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    // Enter animations live outside the lazy items so already-shown bubbles don't replay them.
    val enterTransitions = remember { mutableMapOf<Int, MutableTransitionState<Boolean>>() }
    LaunchedEffect(messages.size) { enterTransitions.keys.removeAll { it >= messages.size } }

    // Reversed so the list stays anchored to its bottom edge while the newest bubble grows.
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.padding(vertical = HaDimens.Space4),
    ) {
        itemsIndexed(messages.asReversed(), key = { index, _ -> messages.lastIndex - index }) { index, message ->
            SpeechBubble(
                message = message,
                enterTransition = enterTransitions.getOrPut(messages.lastIndex - index) {
                    MutableTransitionState(false).apply { targetState = true }
                },
                modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = tween(BUBBLE_ENTER_MS)),
            )
        }
    }
}

@Composable
private fun SpeechBubble(
    message: AssistMessage,
    enterTransition: MutableTransitionState<Boolean>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHaColors.current
    val isInput = message.isInput
    val background = when {
        message.isError -> colors.fillDangerLoud
        isInput -> colors.fillPrimaryLoud
        else -> colors.fillPrimaryQuiet
    }
    val content = when {
        message.isError -> colors.onDangerLoud
        isInput -> colors.onPrimaryLoud
        else -> colors.textPrimary
    }
    AnimatedVisibility(
        visibleState = enterTransition,
        enter = fadeIn(tween(BUBBLE_ENTER_MS)) + slideInVertically(tween(BUBBLE_ENTER_MS)) { it },
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = if (isInput) Arrangement.End else Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (isInput) HaDimens.Space6 else 0.dp,
                    end = if (isInput) 0.dp else HaDimens.Space6,
                    top = HaDimens.Space2,
                    bottom = HaDimens.Space2,
                ),
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = background,
                        shape = RoundedCornerShape(
                            topStart = HaRadius.XL,
                            topEnd = HaRadius.XL,
                            bottomStart = if (isInput) HaRadius.XL else HaRadius.S,
                            bottomEnd = if (isInput) HaRadius.S else HaRadius.XL,
                        ),
                    )
                    .animateContentSize(tween(BUBBLE_ENTER_MS))
                    .padding(horizontal = HaDimens.Space3, vertical = HaDimens.Space2),
            ) {
                AnimatedContent(
                    targetState = message.isPlaceholder,
                    transitionSpec = {
                        (fadeIn(tween(BUBBLE_ENTER_MS)) togetherWith fadeOut(tween(BUBBLE_ENTER_MS))).using(null)
                    },
                    label = "bubbleContent",
                ) { placeholder ->
                    if (placeholder) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypingIndicator(color = content)
                            // A status like "Logging…" while the assistant works; the reply replaces it.
                            if (message.message.isNotEmpty() && !message.isInput) {
                                Text(
                                    text = message.message,
                                    style = HaText.Body,
                                    color = content.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(start = HaDimens.Space2),
                                )
                            }
                        }
                    } else {
                        Text(text = message.message, style = HaText.Body, color = content)
                    }
                }
            }
        }
    }
}

/** Three dots pulsing one after the other while waiting for the other side. */
@Composable
private fun TypingIndicator(color: Color) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.spacedBy(HaDimens.Space1),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = HaDimens.Space6),
    ) {
        repeat(3) { index ->
            val start = index * 150
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1500
                        0f at start
                        1f at start + 300
                        0f at start + 600
                    },
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        alpha = 0.4f + 0.6f * progress
                        val scale = 0.75f + 0.25f * progress
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(color = color, shape = CircleShape),
            )
        }
    }
}

@Composable
private fun AssistSheetControls(
    inputMode: InputMode?,
    voiceAvailable: Boolean,
    textEnabled: Boolean,
    hasAttachments: Boolean,
    photosAvailable: Boolean,
    onAttach: (AttachKind) -> Unit,
    onTextInput: (String) -> Unit,
    onMicrophone: () -> Unit,
    onChangeInput: () -> Unit,
) = Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    when (inputMode) {
        null -> Spacer(modifier = Modifier.height(HaDimens.Space16)) // pipelines still loading
        InputMode.TEXT -> AssistTextInput(
            enabled = textEnabled,
            voiceAvailable = voiceAvailable,
            hasAttachments = hasAttachments,
            photosAvailable = photosAvailable,
            onAttach = onAttach,
            onTextInput = onTextInput,
            onChangeInput = onChangeInput,
        )
        else -> AssistVoiceInput(
            isActive = inputMode == InputMode.VOICE_ACTIVE,
            photosAvailable = photosAvailable,
            onAttach = onAttach,
            onMicrophone = onMicrophone,
            onChangeInput = onChangeInput,
        )
    }
}

enum class AttachKind { CAMERA, PHOTO, FILE, BARCODE }

/** The paperclip: take a photo, pick a photo or file, or scan a barcode. */
@Composable
private fun AttachButton(onAttach: (AttachKind) -> Unit, photosAvailable: Boolean) {
    val colors = LocalHaColors.current
    var open by remember { mutableStateOf(false) }
    Box {
        HaIconButton(icon = MdiIcons.Paperclip, contentDescription = "Attach", onClick = { open = true }, neutral = true)
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.surfaceDefault,
            shape = RoundedCornerShape(HaRadius.XL),
        ) {
            listOf(
                Triple(AttachKind.CAMERA, MdiIcons.Camera, "Take a photo"),
                Triple(AttachKind.PHOTO, MdiIcons.Image, "Choose a photo"),
                Triple(AttachKind.FILE, MdiIcons.File, "Choose a file"),
                Triple(AttachKind.BARCODE, MdiIcons.Barcode, "Scan a barcode"),
            ).forEach { (kind, icon, label) ->
                // Photos need an AI Task in Home Assistant to be described; barcodes and files don't.
                val usable = photosAvailable || (kind != AttachKind.CAMERA && kind != AttachKind.PHOTO)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(label, style = HaText.Body, color = if (usable) colors.textPrimary else colors.onDisabled)
                            if (!usable) {
                                Text("Needs an AI Task in Home Assistant", style = HaText.BodyMedium, color = colors.onDisabled)
                            }
                        }
                    },
                    leadingIcon = { Icon(icon, contentDescription = null, tint = if (usable) colors.onNeutralQuiet else colors.onDisabled) },
                    enabled = usable,
                    onClick = {
                        open = false
                        onAttach(kind)
                    },
                )
            }
        }
    }
}

/**
 * Attachments waiting to be sent, each with a thumbnail or icon and a remove button, and a note
 * that they go with the next request (nothing is sent, or paid for, until then).
 */
@Composable
private fun AttachmentRow(attachments: List<Attachment>, preparing: Boolean, onRemove: (Attachment) -> Unit) {
    if (attachments.isEmpty() && !preparing) return
    val colors = LocalHaColors.current
    Column {
    Row(
        horizontalArrangement = Arrangement.spacedBy(HaDimens.Space2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = HaDimens.Space2),
    ) {
        attachments.forEach { attachment ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(HaRadius.M))
                    .background(colors.fillPrimaryQuiet)
                    .padding(start = HaDimens.Space1),
            ) {
                when (attachment) {
                    is Attachment.Photo -> Image(
                        bitmap = attachment.thumbnail.asImageBitmap(),
                        contentDescription = attachment.label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(vertical = HaDimens.Space1)
                            .size(40.dp)
                            .clip(RoundedCornerShape(HaRadius.S)),
                    )
                    is Attachment.Barcode -> Icon(MdiIcons.Barcode, null, tint = colors.textPrimary, modifier = Modifier.size(24.dp))
                    is Attachment.TextFile -> Icon(MdiIcons.File, null, tint = colors.textPrimary, modifier = Modifier.size(24.dp))
                }
                Text(
                    attachment.label,
                    style = HaText.BodyMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = HaDimens.Space2)
                        .widthIn(max = 180.dp),
                )
                IconButton(onClick = { onRemove(attachment) }, modifier = Modifier.size(36.dp)) {
                    Icon(MdiIcons.Close, contentDescription = "Remove", tint = colors.onNeutralQuiet, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (preparing) Text("Adding…", style = HaText.BodyMedium, color = colors.textSecondary)
    }
    if (attachments.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = HaDimens.Space2),
        ) {
            Icon(MdiIcons.ClockOutline, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            Text(
                "Not sent yet. Say or type what to do with " + if (attachments.size == 1) "it." else "them.",
                style = HaText.BodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = HaDimens.Space1),
            )
        }
    }
    }
}

@Composable
private fun RowScope.AssistTextInput(
    enabled: Boolean,
    voiceAvailable: Boolean,
    hasAttachments: Boolean,
    photosAvailable: Boolean,
    onAttach: (AttachKind) -> Unit,
    onTextInput: (String) -> Unit,
    onChangeInput: () -> Unit,
) {
    val colors = LocalHaColors.current
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // An attachment can be sent on its own, without any words.
    val canSend = enabled && (text.isNotBlank() || hasAttachments)
    val submit = {
        if (canSend) {
            onTextInput(text.trim())
            text = ""
        }
    }

    AttachButton(onAttach, photosAvailable)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(if (hasAttachments) "What should I do with it?" else "Enter a request") },
        singleLine = true,
        shape = RoundedCornerShape(HaRadius.M),
        textStyle = HaText.Body,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { submit() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            focusedContainerColor = colors.surfaceDefault,
            unfocusedContainerColor = colors.surfaceDefault,
            cursorColor = colors.borderPrimaryNormal,
            focusedBorderColor = colors.borderPrimaryNormal,
            unfocusedBorderColor = colors.borderNeutralQuiet,
            focusedPlaceholderColor = colors.textPrimary,
            unfocusedPlaceholderColor = colors.textPrimary,
        ),
        modifier = Modifier
            .weight(1f)
            .widthIn(max = 380.dp)
            .focusRequester(focus),
    )
    // As in the original: an empty field shows the microphone, typing turns it into Send.
    val showSend = text.isNotBlank() || hasAttachments || !voiceAvailable
    HaIconButton(
        icon = if (showSend) MdiIcons.Send else MdiIcons.Microphone,
        contentDescription = if (showSend) "Send" else "Start listening",
        onClick = { if (showSend) submit() else onChangeInput() },
        enabled = if (showSend) canSend else true,
    )
}

@Composable
private fun RowScope.AssistVoiceInput(
    isActive: Boolean,
    photosAvailable: Boolean,
    onAttach: (AttachKind) -> Unit,
    onMicrophone: () -> Unit,
    onChangeInput: () -> Unit,
) {
    AttachButton(onAttach, photosAvailable)
    Spacer(modifier = Modifier.weight(0.5f))
    MicrophoneButton(isActive = isActive, onClick = onMicrophone)
    Spacer(modifier = Modifier.weight(0.5f))
    HaIconButton(
        icon = MdiIcons.KeyboardOutline,
        contentDescription = "Type a request",
        onClick = onChangeInput,
        neutral = true,
    )
}

@Composable
private fun MicrophoneButton(isActive: Boolean, onClick: () -> Unit) {
    val colors = LocalHaColors.current
    Box(modifier = Modifier.size(HaDimens.Space16), contentAlignment = Alignment.Center) {
        if (isActive) MicrophoneRipples()
        val background = if (isActive) {
            Modifier.background(color = colors.fillPrimaryLoud, shape = CircleShape)
        } else {
            Modifier.border(width = 1.dp, color = colors.borderNeutralNormal, shape = CircleShape)
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .then(background)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MdiIcons.Microphone,
                contentDescription = if (isActive) "Stop listening" else "Start listening",
                tint = if (isActive) colors.onPrimaryLoud else colors.textPrimary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** Rings expanding and fading out behind the microphone while it listens. */
@Composable
private fun MicrophoneRipples() {
    val colors = LocalHaColors.current
    val transition = rememberInfiniteTransition(label = "micRipples")
    repeat(2) { index ->
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(index * 700),
            ),
            label = "micRipple$index",
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    val scale = 1f + 0.6f * progress
                    scaleX = scale
                    scaleY = scale
                    alpha = 0.5f * (1f - progress)
                }
                .background(color = colors.fillPrimaryLoud, shape = CircleShape),
        )
    }
}

@Composable
private fun HaIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    neutral: Boolean = false,
) {
    val colors = LocalHaColors.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (neutral) colors.onNeutralQuiet else colors.onPrimaryNormal,
            disabledContentColor = colors.onDisabled,
        ),
        modifier = Modifier.size(48.dp),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
    }
}
