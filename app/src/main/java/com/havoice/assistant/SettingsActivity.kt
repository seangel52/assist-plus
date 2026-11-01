package com.havoice.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-time setup: where Home Assistant is, the access token, and which pipeline to use. */
class SettingsActivity : ComponentActivity() {
    private var isDefaultAssistant by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = HaSettings(this)
        // Opened from the "restart after reboot" notification, or to keep the listener running.
        if (settings.wakeEnabled && !WakeWordService.isRunning) WakeWordService.start(this)
        if (intent.getBooleanExtra(EXTRA_START_WAKE, false)) {
            finish()
            return
        }
        setContent {
            HaVoiceTheme {
                SettingsScreen(
                    settings = settings,
                    isDefaultAssistant = isDefaultAssistant,
                    onOpenDefaultApps = {
                        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isDefaultAssistant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    companion object {
        const val EXTRA_START_WAKE = "start_wake"
    }
}

@Composable
private fun NoiseSection(settings: HaSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(settings.noiseReduction) }
    var recording by remember { mutableStateOf<ByteArray?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    Text("Noise reduction", style = MaterialTheme.typography.titleMedium)
    NoiseReduction.entries.forEach { option ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = mode == option,
                    onClick = {
                        mode = option
                        settings.noiseReduction = option
                    },
                ),
        ) {
            RadioButton(selected = mode == option, onClick = null)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(option.label)
                Text(option.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    Text("Test microphone", style = MaterialTheme.typography.titleMedium)
    Text(
        "Record 5 seconds of talking with some background noise, then compare how each setting sounds.",
        style = MaterialTheme.typography.bodySmall,
    )

    val startRecording: () -> Unit = {
        scope.launch {
            working = true
            status = "Recording… talk now."
            try {
                recording = MicTest.record(context)
                status = "Recorded. Tap a version to listen."
            } catch (e: HaException) {
                status = e.message
            } finally {
                working = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else status = "Microphone permission is needed for the test."
    }

    Button(
        enabled = !working,
        onClick = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (recording == null) "Record 5 seconds" else "Record again") }

    recording?.let { pcm ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                enabled = !working,
                onClick = {
                    scope.launch {
                        working = true
                        status = "Playing the original."
                        MicTest.play(pcm)
                        working = false
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Original") }
            listOf(NoiseReduction.LIGHT, NoiseReduction.STRONG).forEach { option ->
                OutlinedButton(
                    enabled = !working,
                    onClick = {
                        scope.launch {
                            working = true
                            status = "Cleaning…"
                            val (cleaned, timing) = MicTest.clean(context, option, pcm)
                            status = "${option.label}: adds ${timing.stepMillis} ms delay, " +
                                "uses ${timing.millisPerSecondOfAudio} ms of processing per second of speech."
                            AppLog.i("Mic test $option: step ${timing.stepMillis} ms, ${timing.millisPerSecondOfAudio} ms per second")
                            MicTest.play(cleaned)
                            working = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(option.label) }
            }
        }
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun PhoneActionsSection(settings: HaSettings) {
    var enabled by remember { mutableStateOf(settings.phoneActions) }
    var navigationApp by remember { mutableStateOf(settings.navigationApp) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Phone actions", style = MaterialTheme.typography.titleMedium)
            Text(
                "Handle these on the phone instead of sending them to Home Assistant:\n" +
                    "• \"Take me to Target on Main Street\"\n" +
                    "• \"Set a timer for 10 minutes\"\n" +
                    "Music (\"play … on Spotify\") goes to Home Assistant; see the README for SpotifyPlus.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                settings.phoneActions = it
            },
        )
    }
    if (enabled) {
        Text("Navigation app", style = MaterialTheme.typography.bodyMedium)
        NavigationApp.entries.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = navigationApp == option,
                        onClick = {
                            navigationApp = option
                            settings.navigationApp = option
                        },
                    ),
            ) {
                RadioButton(selected = navigationApp == option, onClick = null)
                Text(option.label, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

// Short, command-like phrases, so the voiceprint matches how Assist is actually spoken to.
private val ENROLL_SENTENCES = listOf(
    "Hey, what time is it?",
    "Turn on the kitchen lights.",
    "Is the garage door open?",
    "Set the living room to seventy two.",
    "What's the weather tomorrow morning?",
    "Add milk and eggs to the shopping list.",
)

/** Runs [action] once the microphone permission is granted, asking for it if needed. */
@Composable
private fun rememberMicAccess(onDenied: () -> Unit, action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) action() else onDenied()
    }
    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) action() else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
private fun VoiceIdSection(settings: HaSettings) {
    val context = LocalContext.current
    // The activity's scope, so setup isn't cancelled if this part of the screen scrolls away.
    val scope = (context as ComponentActivity).lifecycleScope
    var enrolled by remember { mutableStateOf(VoiceProfile.isEnrolled(context)) }
    var enabled by remember { mutableStateOf(settings.voiceIdEnabled) }
    var threshold by remember { mutableFloatStateOf(settings.voiceIdThreshold) }
    var prompt by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    Text("Voice ID", style = MaterialTheme.typography.titleMedium)
    Text(
        if (enrolled && !VoiceProfile.sampleFile(context).exists()) {
            "Your voice is set up, but run setup again to turn on voice isolation for when others talk over you."
        } else if (enrolled) {
            "Your voice is set up. When on, Assist ignores other voices — the TV, videos, the kids."
        } else {
            "Teach Assist your voice so it only acts on what you say. You'll say six short commands, " +
                "holding the phone the way you normally do."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (enrolled) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Only respond to my voice", modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    settings.voiceIdEnabled = it
                },
            )
        }
        Text("Strictness: %.2f".format(threshold))
        Slider(
            value = threshold,
            onValueChange = { threshold = (it * 20).roundToInt() / 20f },
            onValueChangeFinished = {
                settings.voiceIdThreshold = threshold
                AppLog.i("Voice ID strictness set to %.2f".format(threshold))
            },
            valueRange = 0.3f..0.9f,
            steps = 11,
        )
        Text(
            "Use the two test buttons: your scores should land above this, the TV's or a video's below it.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    var saveRecordings by remember { mutableStateOf(settings.saveRecordings) }
    var savedCount by remember { mutableStateOf(Captures.count(context)) }

    val enroll = rememberMicAccess(onDenied = { status = "Microphone permission is needed." }) {
        scope.launch {
            working = true
            try {
                val clips = ENROLL_SENTENCES.mapIndexed { i, sentence ->
                    prompt = "Say (${i + 1} of ${ENROLL_SENTENCES.size}):\n\"$sentence\""
                    status = "Recording…"
                    val capture = Captures.Recorder()
                    MicTest.record(context, seconds = 4, noise = settings.noiseReduction, capture = capture).also {
                        if (settings.saveRecordings) Captures.save(context, "enroll${i + 1}", capture, "\"$sentence\"")
                    }
                }
                prompt = null
                status = "Learning your voice…"
                val consistency = withContext(Dispatchers.Default) {
                    val engines = SpeechEngines.get(context) ?: throw HaException("Speech engine failed to load")
                    VoiceProfile.enroll(context, engines, clips)
                }
                AppLog.i("Voice ID set up (consistency %.2f)".format(consistency))
                enrolled = true
                savedCount = Captures.count(context)
                status = "Voice saved. Now try \"Record me\" and \"Record TV/video\" a few times each."
            } catch (e: Exception) {
                prompt = null
                AppLog.w("Voice ID setup failed: ${e.message}")
                status = "Setup failed: ${e.message}"
            } finally {
                working = false
            }
        }
    }
    // Labelled 5-second samples: "me" (you talking) or "other" (only the TV/video, you silent).
    fun sampleTest(label: String, instructions: String) {
        scope.launch {
            working = true
            status = instructions
            try {
                val capture = Captures.Recorder()
                val clip = MicTest.record(context, seconds = 5, noise = settings.noiseReduction, capture = capture)
                val score = withContext(Dispatchers.Default) {
                    val engines = SpeechEngines.get(context) ?: throw HaException("Speech engine failed to load")
                    val profile = VoiceProfile.load(context) ?: throw HaException("Voice isn't set up")
                    val speech = VoiceProfile.speechOnly(engines, VoiceProfile.toFloats(clip))
                    VoiceProfile.score(engines, profile, speech)
                }
                val verdict = when {
                    score == null -> "no speech heard"
                    score >= threshold -> "counts as you"
                    else -> "counts as someone else"
                }
                val line = "Voice ID sample '$label': ${score?.let { "%.2f".format(it) } ?: "-"} vs strictness %.2f ($verdict)"
                    .format(threshold)
                AppLog.i(line)
                if (settings.saveRecordings) {
                    Captures.save(context, label, capture, line)
                    savedCount = Captures.count(context)
                }
                status = "${if (label == "me") "You" else "TV/video"}: ${score?.let { "%.2f".format(it) } ?: "-"} — $verdict " +
                    "(strictness %.2f)".format(threshold)
            } catch (e: HaException) {
                status = e.message
            } finally {
                working = false
            }
        }
    }
    val recordMe = rememberMicAccess(onDenied = { status = "Microphone permission is needed." }) {
        sampleTest("me", "Recording 5 seconds… say a command or two, like you would to Assist.")
    }
    val recordOther = rememberMicAccess(onDenied = { status = "Microphone permission is needed." }) {
        sampleTest("other", "Recording 5 seconds… stay quiet and let the TV or video play.")
    }

    prompt?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
    Button(enabled = !working, onClick = enroll, modifier = Modifier.fillMaxWidth()) {
        Text(if (enrolled) "Set up my voice again" else "Set up my voice")
    }
    if (enrolled) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(enabled = !working, onClick = recordMe, modifier = Modifier.weight(1f)) { Text("Record me") }
            OutlinedButton(enabled = !working, onClick = recordOther, modifier = Modifier.weight(1f)) {
                Text("Record TV/video")
            }
        }
        OutlinedButton(
            enabled = !working,
            onClick = {
                VoiceProfile.clear(context)
                enrolled = false
                AppLog.i("Voice ID removed")
                status = "Voice removed."
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Remove my voice") }
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Save recordings for tuning")
            Text(
                "Keeps the audio of tests and Assist sessions on this phone ($savedCount saved, oldest removed " +
                    "automatically) so voice ID can be tuned on your real voice.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = saveRecordings,
            onCheckedChange = {
                saveRecordings = it
                settings.saveRecordings = it
            },
        )
    }
    if (savedCount > 0) {
        OutlinedButton(
            onClick = {
                Captures.clear(context)
                savedCount = 0
                AppLog.i("Saved recordings deleted")
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Delete saved recordings") }
    }
}

private const val WAKE_TAKES = 5

@Composable
private fun WakeWordSection(settings: HaSettings) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = activity.lifecycleScope
    var enabled by remember { mutableStateOf(settings.wakeEnabled) }
    var phrases by remember { mutableStateOf(WakeWords.load(settings)) }
    var newPhrase by remember { mutableStateOf("") }
    var onlyMyVoice by remember { mutableStateOf(settings.wakeOnlyMyVoice) }
    var whenLocked by remember { mutableStateOf(settings.wakeWhenLocked) }
    var sleepWhenStill by remember { mutableStateOf(settings.wakeSleepWhenStill) }
    var canPopUp by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var prompt by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun restartIfOn() {
        if (!enabled) return
        WakeWordService.stop(context)
        WakeWordService.start(context)
    }

    fun savePhrases(updated: List<WakePhrase>) {
        phrases = updated
        WakeWords.save(settings, updated)
        restartIfOn()
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        WakeWordService.start(context)
    }
    val turnOn = rememberMicAccess(onDenied = { status = "Microphone permission is needed." }) {
        enabled = true
        settings.wakeEnabled = true
        AppLog.i("Wake word turned on")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            WakeWordService.start(context)
        }
    }

    // Records the owner saying a phrase several times and tunes it from those takes.
    fun record(index: Int) {
        val phrase = phrases[index]
        scope.launch {
            working = true
            WakeWordService.pause(context)
            try {
                val takes = (1..WAKE_TAKES).map { take ->
                    prompt = "Say \"${phrase.text}\" ($take of $WAKE_TAKES)"
                    VoiceProfile.toFloats(MicTest.record(context, seconds = 3))
                }
                prompt = null
                status = "Tuning…"
                val profile = if (settings.voiceIdEnabled) VoiceProfile.load(context) else null
                val (tuned, caught) = withContext(Dispatchers.Default) { WakeWords.tune(context, phrase.text, takes, profile) }
                savePhrases(phrases.toMutableList().also { it[index] = tuned })
                status = when {
                    caught >= WAKE_TAKES - 1 -> "\"${phrase.text}\": caught $caught of $WAKE_TAKES. Ready."
                    caught > 0 -> "\"${phrase.text}\": caught only $caught of $WAKE_TAKES. A longer or clearer phrase works better."
                    else -> "\"${phrase.text}\" wasn't recognized. Try a longer phrase with distinct sounds, like \"hey kitchen helper\"."
                }
            } catch (e: Exception) {
                prompt = null
                status = "Recording failed: ${e.message}"
            } finally {
                working = false
                WakeWordService.resume(context)
            }
        }
    }
    val recordAccess = remember { mutableStateOf(-1) }
    val startRecording = rememberMicAccess(onDenied = { status = "Microphone permission is needed." }) {
        recordAccess.value.takeIf { it >= 0 }?.let { record(it) }
    }

    Text("Wake word", style = MaterialTheme.typography.titleMedium)
    Text(
        "Say a phrase to open Assist hands-free. Android shows a notification while it listens.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Listen for a wake word", modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                if (on) {
                    turnOn()
                } else {
                    enabled = false
                    settings.wakeEnabled = false
                    WakeWordService.stop(context)
                    AppLog.i("Wake word turned off")
                }
            },
        )
    }

    phrases.forEachIndexed { index, phrase ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("\"${phrase.text}\"")
                Text(
                    if (phrase.voiceMin != null || phrase.threshold != WakePhrase.DEFAULT_THRESHOLD) {
                        "Tuned to your voice"
                    } else {
                        "Not recorded yet — record it for better accuracy"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                enabled = !working,
                onClick = {
                    recordAccess.value = index
                    startRecording()
                },
            ) { Text("Record") }
            TextButton(
                enabled = !working && phrases.size > 1,
                onClick = { savePhrases(phrases.filterIndexed { i, _ -> i != index }) },
            ) { Text("Remove") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = newPhrase,
            onValueChange = { newPhrase = it },
            placeholder = { Text("Add a phrase, like \"hey house\"") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = newPhrase.isNotBlank() && !working,
            onClick = {
                savePhrases(phrases + WakePhrase(newPhrase.trim().lowercase()))
                newPhrase = ""
            },
        ) { Text("Add") }
    }
    prompt?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    Text(
        "Phrases of 3 or more syllables with everyday words (\"hey house\", \"okay kitchen\") work best; " +
            "short phrases and unusual names get missed more often.",
        style = MaterialTheme.typography.bodySmall,
    )

    var sensitivity by remember { mutableFloatStateOf(settings.wakeSensitivity) }
    Text("Wake sensitivity", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = sensitivity,
        onValueChange = { sensitivity = (it * 10).roundToInt() / 10f },
        onValueChangeFinished = {
            settings.wakeSensitivity = sensitivity
            AppLog.i("Wake sensitivity set to %.1f".format(sensitivity))
            restartIfOn()
        },
        valueRange = 0f..1f,
        steps = 9,
    )
    Text(
        "Slide right if you have to raise your voice; left if it wakes up when you didn't say it.",
        style = MaterialTheme.typography.bodySmall,
    )

    @Composable
    fun Option(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
    Option("Only wake for my voice", "Uses voice ID, so the TV or the kids saying it won't trigger it.", onlyMyVoice) {
        onlyMyVoice = it
        settings.wakeOnlyMyVoice = it
    }
    Option("Work when the phone is locked", "Assist opens over the lock screen.", whenLocked) {
        whenLocked = it
        settings.wakeWhenLocked = it
    }

    var pinOn by remember { mutableStateOf(settings.pinRequiredWhenLocked) }
    var pinSet by remember { mutableStateOf(PinLock.isSet(settings)) }
    var newPin by remember { mutableStateOf("") }
    Option(
        "Require a spoken PIN when locked (experimental)",
        "While the phone is locked, the first request after Assist opens needs your Assist PIN. " +
            "It's separate from your phone's PIN, and the phone itself stays locked.",
        pinOn,
    ) { on ->
        if (on && !pinSet) {
            status = "Set an Assist PIN first."
        } else {
            pinOn = on
            settings.pinRequiredWhenLocked = on
            AppLog.i("Spoken PIN when locked turned ${if (on) "on" else "off"}")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = newPin,
            onValueChange = { newPin = it.filter(Char::isDigit).take(8) },
            label = { Text(if (pinSet) "Change Assist PIN" else "Set Assist PIN (4–8 digits)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = newPin.length in 4..8,
            onClick = {
                PinLock.setPin(settings, newPin)
                newPin = ""
                pinSet = true
                status = "Assist PIN saved."
                AppLog.i("Assist PIN set")
            },
        ) { Text("Save") }
    }
    Text(
        "Don't reuse your phone's PIN: a spoken PIN can be overheard. Three wrong tries pause it for 5 minutes.",
        style = MaterialTheme.typography.bodySmall,
    )
    Option(
        "Sleep when the phone is still",
        "Stops listening after ${settings.wakeStillMinutes} minutes without moving, to save battery. " +
            "It won't hear you while asleep; picking the phone up wakes it. Keeps listening while charging.",
        sleepWhenStill,
    ) {
        sleepWhenStill = it
        settings.wakeSleepWhenStill = it
        restartIfOn()
    }
    if (!canPopUp) {
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Allow Assist to open over other apps") }
        Text(
            "Needed so the wake word can open Assist while you're in another app.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    LaunchedEffect(Unit) {
        // Re-check after returning from the permission screen.
        activity.lifecycle.currentStateFlow.collect { canPopUp = Settings.canDrawOverlays(context) }
    }
}

@Composable
private fun LogSection(settings: HaSettings) {
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(AppLog.recent(30)) }
    var pending by remember { mutableStateOf(AppLog.pendingCount()) }
    var status by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    Text("Log", style = MaterialTheme.typography.titleMedium)
    Text(
        "Copied to Home Assistant's log (Settings → System → Logs, logger \"assist_plus\") " +
            "whenever Assist is used, and hourly if anything is waiting. Waiting to send: $pending.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(
        enabled = !syncing && settings.isConfigured,
        onClick = {
            syncing = true
            status = "Syncing…"
            scope.launch {
                val client = HaClient(settings.baseUrl, settings.token)
                status = try {
                    client.connect()
                    "Sent ${LogSync.sync(client)} lines."
                } catch (e: HaException) {
                    e.message
                } finally {
                    client.close()
                    syncing = false
                    lines = AppLog.recent(30)
                    pending = AppLog.pendingCount()
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Sync now") }
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    Text(
        lines.joinToString("\n").ifEmpty { "Nothing logged yet." },
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun SettingsScreen(
    settings: HaSettings,
    isDefaultAssistant: Boolean,
    onOpenDefaultApps: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(settings.baseUrl.ifBlank { "http://homeassistant.local:8123" }) }
    var token by remember { mutableStateOf(settings.token) }
    var pipelines by remember { mutableStateOf<PipelineList?>(null) }
    var selectedPipeline by remember { mutableStateOf(settings.pipelineId) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold { padding ->
      Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            Text("Assist Plus", style = HaText.HeadlineMedium, color = LocalHaColors.current.textPrimary)
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
            SETTINGS_TABS.forEachIndexed { i, name ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) })
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          when (tab) {
          0 -> {
            Text(
                "Connects to your Home Assistant's Assist. Long-press the power or home button " +
                    "to talk to it from anywhere.",
                style = HaText.BodyMedium,
                color = LocalHaColors.current.textSecondary,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Home Assistant address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Long-lived access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Create a token in Home Assistant: your profile → Security → Long-lived access tokens.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                enabled = !testing && url.isNotBlank() && token.isNotBlank(),
                onClick = {
                    testing = true
                    status = "Connecting…"
                    scope.launch {
                        val client = HaClient(url, token)
                        try {
                            client.connect()
                            val list = client.listPipelines()
                            settings.baseUrl = url
                            settings.token = token
                            pipelines = list
                            if (selectedPipeline == null) selectedPipeline = list.preferredId
                            settings.pipelineId = selectedPipeline
                            status = "Connected and saved."
                        } catch (e: HaException) {
                            status = e.message
                        } finally {
                            client.close()
                            testing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Test connection & save") }

            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            pipelines?.let { list ->
                HorizontalDivider()
                Text("Assistant pipeline", style = MaterialTheme.typography.titleMedium)
                list.pipelines.forEach { pipeline ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedPipeline == pipeline.id,
                                onClick = {
                                    selectedPipeline = pipeline.id
                                    settings.pipelineId = pipeline.id
                                },
                            ),
                    ) {
                        RadioButton(selected = selectedPipeline == pipeline.id, onClick = null)
                        val suffix = if (pipeline.id == list.preferredId) " (preferred)" else ""
                        Text(pipeline.name + suffix, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            HorizontalDivider()
            Text("Default assistant", style = MaterialTheme.typography.titleMedium)
            Text(
                if (isDefaultAssistant) {
                    "Assist Plus is your default digital assistant."
                } else {
                    "Not set yet. Open Default apps → Digital assistant app and choose Assist Plus."
                },
            )
            OutlinedButton(onClick = onOpenDefaultApps, modifier = Modifier.fillMaxWidth()) {
                Text("Open default apps settings")
            }
          }
          1 -> {
            var handsFree by remember { mutableStateOf(settings.handsFree) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hands-free", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Start listening as soon as Assist opens, and listen again when it asks a follow-up question.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = handsFree,
                    onCheckedChange = {
                        handsFree = it
                        settings.handsFree = it
                    },
                )
            }

            var wordFixes by remember { mutableStateOf(settings.wordFixes) }
            Text("Word fixes", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = wordFixes,
                onValueChange = {
                    wordFixes = it
                    settings.wordFixes = it
                },
                placeholder = { Text("nest cafe => Nescafe") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "One per line, as heard => meant. Fixes words the speech model keeps getting wrong " +
                    "before they're sent to Home Assistant.",
                style = MaterialTheme.typography.bodySmall,
            )

            var endPause by remember { mutableFloatStateOf(settings.endPauseSeconds) }
            Text("Pause before finishing: %.1f s".format(endPause), style = MaterialTheme.typography.titleMedium)
            Slider(
                value = endPause,
                onValueChange = { endPause = (it * 5).roundToInt() / 5f },
                onValueChangeFinished = {
                    settings.endPauseSeconds = endPause
                    AppLog.i("Pause before finishing set to %.1f s".format(endPause))
                },
                valueRange = 0.6f..3f,
                steps = 11,
            )
            Text(
                "How long you can pause before Assist decides you're done. It waits an extra second " +
                    "when you stop mid-sentence (on \"and…\", \"um…\", or without finishing the thought).",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            NamesSection(settings)

            HorizontalDivider()
            VoiceIdSection(settings)
          }
          2 -> WakeWordSection(settings)
          3 -> {
            PhoneActionsSection(settings)
            HorizontalDivider()
            LocationSection(settings)
          }
          else -> {
            NoiseSection(settings)
            HorizontalDivider()
            LogSection(settings)
          }
          }
        }
      }
    }
}

private val SETTINGS_TABS = listOf("Connection", "Listening", "Wake word", "Assistant", "Advanced")

/** Names the speech recognizer leans toward, and the corrections that taught it. */
@Composable
private fun NamesSection(settings: HaSettings) {
    var names by remember { mutableStateOf(settings.listenNames) }
    var corrections by remember { mutableStateOf(NameCorrections.load(settings)) }
    Text("Names to listen for", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = names,
        onValueChange = {
            names = it
            settings.listenNames = it
        },
        placeholder = { Text("Riley\nMarlon") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "One per line: people, pets, places, device names. When a word sounds like one of these, " +
            "the speech recognizer leans toward it; a clearly different word stays as said. The " +
            "assistant adds names here when you correct a mishearing.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (corrections.isNotEmpty()) {
        Text("Corrections learned", style = MaterialTheme.typography.bodyMedium)
        corrections.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${c.heard} → ${c.meant}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    NameCorrections.remove(settings, c)
                    corrections = NameCorrections.load(settings)
                }) { Text("Remove") }
            }
        }
    }
}

/** What location, if any, goes along with each request. */
@Composable
private fun LocationSection(settings: HaSettings) {
    val context = LocalContext.current
    var sharing by remember { mutableStateOf(settings.locationSharing) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    Text("Share my location with the assistant", style = MaterialTheme.typography.titleMedium)
    Text(
        "Sent along with each request as background, never shown on screen, so \"the closest " +
            "Target\" or \"weather here\" work where you are. It isn't a request to navigate. " +
            "Navigation on the phone always searches near you.",
        style = MaterialTheme.typography.bodySmall,
    )
    LocationSharing.entries.forEach { option ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = sharing == option,
                    onClick = {
                        sharing = option
                        settings.locationSharing = option
                        if (option != LocationSharing.OFF && !PhoneLocation.allowed(context)) {
                            permission.launch(PhoneLocation.PERMISSIONS)
                        }
                    },
                ),
        ) {
            RadioButton(selected = sharing == option, onClick = null)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(option.label)
                Text(option.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
