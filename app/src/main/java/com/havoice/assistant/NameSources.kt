package com.havoice.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The lists of names the user already has, read into [KnownNames]. Each is read only when its
 * switch in Settings is on, and everything read stays on the phone.
 *
 * - **Home Assistant** ([NameSource.HOME]): what any Home Assistant can tell the app through the
 *   connection it already has: areas, floors, and the names of the things people talk to
 *   (lights, players, scripts, people…), with players' sources ("Radio").
 * - **The user's own data** ([NameSource.YOURS]): optional. If Home Assistant has a script
 *   called [YOURS_SCRIPT] that answers with `{"names": ["…", "…"]}`, those names are used too.
 *   Nothing else has to be installed: whoever wants names from their own notes, purchases or
 *   food log writes that script to return them.
 * - **Contacts** ([NameSource.CONTACT]): full names and first names; Android asks before the
 *   app may read them.
 * - **Apps** ([NameSource.APP]): the apps on the phone, for "open …".
 */
object NameSources {
    const val YOURS_SCRIPT = "assist_plus_names"

    /** How often the lists are read again. */
    const val REFRESH_EVERY_MILLIS = 24 * 3_600_000L

    /** The kinds of Home Assistant entity people call by name. */
    private val NAMED_DOMAINS = setOf(
        "light", "switch", "fan", "cover", "lock", "climate", "media_player", "vacuum", "scene", "script", "person",
        "zone", "todo", "calendar", "input_boolean", "input_select", "humidifier", "water_heater", "camera", "remote",
        "alarm_control_panel", "siren", "valve", "lawn_mower",
    )

    private const val MAX_WORDS = 5

    /** A name worth knowing: some letters, and short enough to be something a person says. */
    private fun usable(name: String) = name.count { it.isLetter() } >= 3 && name.split(' ').size <= MAX_WORDS

    /** The names in Home Assistant's answer to "get_states": named entities and players' sources. */
    fun fromStates(states: List<JSONObject>): List<String> {
        val names = LinkedHashSet<String>()
        for (state in states) {
            val domain = state.optString("entity_id").substringBefore('.')
            if (domain !in NAMED_DOMAINS) continue
            val attributes = state.optJSONObject("attributes") ?: continue
            attributes.optString("friendly_name").trim().takeIf { it.isNotEmpty() }?.let { names += it }
            if (domain == "media_player") {
                attributes.optJSONArray("source_list")?.let { list ->
                    for (i in 0 until list.length()) list.optString(i).trim().takeIf { it.isNotEmpty() }?.let { names += it }
                }
            }
        }
        return names.filter(::usable)
    }

    /** Contacts' names: the whole name, and the first name on its own (how people are usually called). */
    fun fromContactNames(displayNames: List<String>): List<String> {
        val names = LinkedHashSet<String>()
        for (display in displayNames.map { it.trim() }.filter { it.any(Char::isLetter) && '@' !in it }) {
            names += display
            display.split(' ').firstOrNull()?.takeIf { it.count(Char::isLetter) >= 3 }?.let { names += it }
        }
        return names.filter(::usable)
    }

    fun contactsAllowed(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun contacts(context: Context): List<String> {
        if (!contactsAllowed(context)) return emptyList()
        val found = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { found += it }
        }
        return fromContactNames(found)
    }

    private fun apps(context: Context): List<String> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val manager = context.packageManager
        return manager.queryIntentActivities(launcher, 0).map { it.loadLabel(manager).toString().trim() }.distinct().filter(::usable)
    }

    /**
     * Reads every list that is switched on again and saves the names; a list switched off loses
     * its names. Returns a line saying what was read, for the log and for Settings.
     */
    suspend fun refresh(context: Context): String {
        val settings = HaSettings(context)
        val parts = mutableListOf<String>()
        KnownNames.setSource(context, NameSource.APP, if (settings.namesFromApps) apps(context) else emptyList())
        KnownNames.setSource(context, NameSource.CONTACT, if (settings.namesFromContacts) contacts(context) else emptyList())
        if (settings.namesFromHome && settings.isConfigured) {
            // A connection of its own, so reading the lists never holds up a request.
            val client = HaClient(settings.baseUrl, settings.token)
            try {
                client.connect()
                val home = client.homeNames()
                KnownNames.setSource(context, NameSource.HOME, home)
                parts += "${home.size} from Home Assistant"
                val yours = client.yourNames()
                KnownNames.setSource(context, NameSource.YOURS, yours.orEmpty())
                if (yours != null) parts += "${yours.size} from your $YOURS_SCRIPT script"
            } catch (e: HaException) {
                // What was read last time stays.
                parts += "Home Assistant couldn't be read (${e.message})"
            } finally {
                client.close()
            }
        } else if (!settings.namesFromHome) {
            KnownNames.setSource(context, NameSource.HOME, emptyList())
            KnownNames.setSource(context, NameSource.YOURS, emptyList())
        }
        val all = KnownNames.load(context)
        fun count(source: NameSource) = all.count { source in it.sources }
        if (settings.namesFromContacts) parts += "${count(NameSource.CONTACT)} from contacts"
        if (settings.namesFromApps) parts += "${count(NameSource.APP)} apps"
        settings.namesRefreshedAt = System.currentTimeMillis()
        val line = "Names read: " + parts.joinToString(", ").ifEmpty { "no lists are switched on" } +
            " (${KnownNames.inUse(KnownNames.all(context, settings), KnownNames.today()).size} names in use)"
        AppLog.i(line)
        return line
    }

    /** Reads the lists again in the background if a day has passed since the last time. */
    fun refreshIfDue(context: Context) {
        val settings = HaSettings(context)
        val any = settings.namesFromHome || settings.namesFromContacts || settings.namesFromApps
        if (!any || System.currentTimeMillis() - settings.namesRefreshedAt < REFRESH_EVERY_MILLIS) return
        // Marked first, so several openings in a row don't each start a read.
        settings.namesRefreshedAt = System.currentTimeMillis()
        val app = context.applicationContext
        UnfinishedRequest.scope.launch { refresh(app) }
    }
}
