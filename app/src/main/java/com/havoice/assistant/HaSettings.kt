package com.havoice.assistant

import android.content.Context

/** The Home Assistant connection details, saved privately on the phone. */
class HaSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ha_connection", Context.MODE_PRIVATE)

    init {
        // Spotify used to be handled in the app (its own sign-in and search keys); it now goes
        // through Home Assistant, so remove what an older version may have saved.
        // Word fixes (find-and-replace on what was heard) gave way to the dictionary's names.
        val leftovers = prefs.all.keys.filter { it.startsWith("spotify_") || it == "word_fixes" }
        if (leftovers.isNotEmpty()) prefs.edit().apply { leftovers.forEach { remove(it) } }.apply()
    }

    var baseUrl: String
        get() = prefs.getString("base_url", "").orEmpty()
        set(value) = prefs.edit().putString("base_url", value.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "").orEmpty()
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    /** Assist pipeline to use; null means Home Assistant's preferred pipeline. */
    var pipelineId: String?
        get() = prefs.getString("pipeline_id", null)
        set(value) = prefs.edit().putString("pipeline_id", value).apply()

    /** Start listening as soon as the sheet opens, and keep listening for follow-up questions. */
    var handsFree: Boolean
        get() = prefs.getBoolean("hands_free", true)
        set(value) = prefs.edit().putBoolean("hands_free", value).apply()

    /** Navigation and timers handled on the phone instead of by Home Assistant. */
    var phoneActions: Boolean
        get() = prefs.getBoolean("phone_actions", true)
        set(value) = prefs.edit().putBoolean("phone_actions", value).apply()

    var navigationApp: NavigationApp
        get() = prefs.getString("navigation_app", null)
            ?.let { saved -> NavigationApp.entries.firstOrNull { it.name == saved } }
            ?: NavigationApp.GOOGLE_MAPS
        set(value) = prefs.edit().putString("navigation_app", value.name).apply()

    /** Names the speech recognizer leans toward, one per line (see [NameCorrections]). */
    var listenNames: String
        get() = prefs.getString("listen_names", null).orEmpty()
        set(value) = prefs.edit().putString("listen_names", value).apply()

    var nameCorrectionsJson: String
        get() = prefs.getString("name_corrections", null) ?: "[]"
        set(value) = prefs.edit().putString("name_corrections", value).apply()

    /** Names that joined [listenNames] because a correction was learned (not typed in by the user), one per line. */
    var learnedNames: String
        get() = prefs.getString("learned_names", null).orEmpty()
        set(value) = prefs.edit().putString("learned_names", value).apply()

    /** Ordinary-word mishearings seen once, waiting to see if they happen again (see [NameCorrections]). */
    var pendingSlipsJson: String
        get() = prefs.getString("pending_slips", null) ?: "[]"
        set(value) = prefs.edit().putString("pending_slips", value).apply()

    var locationSharing: LocationSharing
        get() = prefs.getString("location_sharing", null)
            ?.let { saved -> LocationSharing.entries.firstOrNull { it.name == saved } }
            ?: LocationSharing.CITY_ZIP
        set(value) = prefs.edit().putString("location_sharing", value.name).apply()

    var noiseReduction: NoiseReduction
        get() = prefs.getString("noise_reduction", null)
            ?.let { saved -> NoiseReduction.entries.firstOrNull { it.name == saved } }
            ?: NoiseReduction.STRONG
        set(value) = prefs.edit().putString("noise_reduction", value.name).apply()

    /** Only act on phrases in the owner's voice (needs a voiceprint from setup). */
    var voiceIdEnabled: Boolean
        get() = prefs.getBoolean("voice_id_enabled", true)
        set(value) = prefs.edit().putBoolean("voice_id_enabled", value).apply()

    /** How closely a phrase must match the voiceprint to count as the owner (0–1). */
    var voiceIdThreshold: Float
        // "_v3": strictness chosen for the TitaNet model; earlier settings were for CAM++.
        get() = prefs.getFloat("voice_id_threshold_v3", VoiceProfile.DEFAULT_THRESHOLD)
        set(value) = prefs.edit().putFloat("voice_id_threshold_v3", value).apply()

    /**
     * The noise reduction the voice setup recordings went through. Voiceprints from before this
     * was saved were made with the noise reduction chosen at the time, most likely today's.
     */
    var voiceprintNoise: NoiseReduction
        get() = prefs.getString("voiceprint_noise", null)
            ?.let { saved -> NoiseReduction.entries.firstOrNull { it.name == saved } }
            ?: noiseReduction
        set(value) = prefs.edit().putString("voiceprint_noise", value.name).apply()

    /** Troubleshooting: keep recordings of listening sessions, voice tests and wake words on the phone (Settings → Advanced). */
    var saveRecordings: Boolean
        get() = prefs.getBoolean("save_recordings", false)
        set(value) = prefs.edit().putBoolean("save_recordings", value).apply()

    /** Silence (seconds) after a finished-sounding sentence before the command is sent. */
    var endPauseSeconds: Float
        get() = prefs.getFloat("end_pause_seconds", 1.2f)
        set(value) = prefs.edit().putFloat("end_pause_seconds", value).apply()

    /**
     * Inventory mode: the side button opens the inventory scanner instead of the assistant
     * until switched back; the wake word always opens the assistant and switches back.
     */
    var inventoryMode: Boolean
        get() = prefs.getBoolean("inventory_mode", false)
        set(value) = prefs.edit().putBoolean("inventory_mode", value).apply()

    /** Where scanned items go (a Grocy location's name); stays set between scans. */
    var inventoryLocation: String
        get() = prefs.getString("inventory_location", null) ?: "Pantry"
        set(value) = prefs.edit().putString("inventory_location", value).apply()

    /** Unknown barcodes wait in a list to be dealt with later, instead of right away. */
    var inventoryQueueUnknowns: Boolean
        get() = prefs.getBoolean("inventory_queue_unknowns", true)
        set(value) = prefs.edit().putBoolean("inventory_queue_unknowns", value).apply()

    /** The AI Task entity that describes attached photos; found automatically when blank. */
    var aiTaskEntity: String
        get() = prefs.getString("ai_task_entity", null).orEmpty()
        set(value) = prefs.edit().putString("ai_task_entity", value.trim()).apply()

    // Wake words.
    var wakeEnabled: Boolean
        get() = prefs.getBoolean("wake_enabled", false)
        set(value) = prefs.edit().putBoolean("wake_enabled", value).apply()

    /** JSON list of tuned wake phrases; see [WakeWords]. */
    var wakePhrasesJson: String
        get() = prefs.getString("wake_phrases", null) ?: """[{"text":"hey jarvis","threshold":0.25}]"""
        set(value) = prefs.edit().putString("wake_phrases", value).apply()

    /** Only wake up when the wake phrase is said in the owner's voice. */
    var wakeOnlyMyVoice: Boolean
        get() = prefs.getBoolean("wake_only_my_voice", true)
        set(value) = prefs.edit().putBoolean("wake_only_my_voice", value).apply()

    /** Answer the wake phrase while the phone is locked. */
    var wakeWhenLocked: Boolean
        get() = prefs.getBoolean("wake_when_locked", false)
        set(value) = prefs.edit().putBoolean("wake_when_locked", value).apply()

    /** Stop listening after the phone sits still for [wakeStillMinutes]; resume when it moves. */
    var wakeSleepWhenStill: Boolean
        get() = prefs.getBoolean("wake_sleep_when_still", false)
        set(value) = prefs.edit().putBoolean("wake_sleep_when_still", value).apply()

    /**
     * 0 (only clear, deliberate wake phrases) to 1 (catches quiet ones, more false wakes); 0.5 = as
     * tuned. Defaults to the looser side: voice ID still turns away other voices.
     */
    var wakeSensitivity: Float
        get() = prefs.getFloat("wake_sensitivity", 0.75f)
        set(value) = prefs.edit().putFloat("wake_sensitivity", value).apply()

    var wakeStillMinutes: Int
        get() = prefs.getInt("wake_still_minutes", 25)
        set(value) = prefs.edit().putInt("wake_still_minutes", value).apply()

    // Spoken PIN while locked; see [PinLock]. Only a salted hash of the PIN is stored.
    var pinRequiredWhenLocked: Boolean
        get() = prefs.getBoolean("pin_required_when_locked", false)
        set(value) = prefs.edit().putBoolean("pin_required_when_locked", value).apply()

    var pinHash: String
        get() = prefs.getString("pin_hash", null).orEmpty()
        set(value) = prefs.edit().putString("pin_hash", value).apply()

    var pinSalt: String
        get() = prefs.getString("pin_salt", null).orEmpty()
        set(value) = prefs.edit().putString("pin_salt", value).apply()

    var pinFailures: Int
        get() = prefs.getInt("pin_failures", 0)
        set(value) = prefs.edit().putInt("pin_failures", value).apply()

    var pinLockedUntil: Long
        get() = prefs.getLong("pin_locked_until", 0)
        set(value) = prefs.edit().putLong("pin_locked_until", value).apply()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()
}
