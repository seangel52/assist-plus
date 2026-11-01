package com.havoice.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

enum class LocationSharing(val label: String, val description: String) {
    OFF("Off", "No location goes with requests."),
    CITY("City", "Just the city and state, like \"Springfield, IL\"."),
    CITY_ZIP("City and ZIP code", "Like \"Springfield, IL 62701\"."),
    EXACT("Exact", "The street you're near and coordinates, for \"what's the closest…\" questions."),
}

/** Where the phone is, for nearby address lookups and the location note sent with requests. */
object PhoneLocation {
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    fun allowed(context: Context) = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private var cached: Location? = null

    /** The phone's position: the newest one Android already has, or a fresh one within a few seconds. Null without permission. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        if (!allowed(context)) return null
        cached?.takeIf { System.currentTimeMillis() - it.time < FRESH_MS }?.let { return it }
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = manager.getProviders(true)
        val known = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (known != null && System.currentTimeMillis() - known.time < FRESH_MS) return known.also { cached = it }
        val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in providers }
            provider?.let {
                withTimeoutOrNull(5_000) {
                    suspendCancellableCoroutine<Location?> { continuation ->
                        manager.getCurrentLocation(it, null, context.mainExecutor) { location -> continuation.resume(location) }
                    }
                }
            }
        } else {
            null
        }
        return (fresh ?: known)?.also { cached = it }
    }

    /**
     * The note sent with a request, by the user's [LocationSharing] choice; empty when off or
     * unknown. Bracketed and labeled as background so the agent doesn't take it as a request.
     */
    suspend fun contextNote(context: Context, sharing: LocationSharing): String {
        if (sharing == LocationSharing.OFF) return ""
        noteCache?.takeIf { it.first == sharing && System.currentTimeMillis() - it.second < FRESH_MS }?.let { return it.third }
        return buildNote(context, sharing).also { if (it.isNotEmpty()) noteCache = Triple(sharing, System.currentTimeMillis(), it) }
    }

    private var noteCache: Triple<LocationSharing, Long, String>? = null

    private suspend fun buildNote(context: Context, sharing: LocationSharing): String {
        val location = current(context) ?: return ""
        val place = withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            } catch (e: IOException) {
                null
            }
        } ?: return ""
        val city = listOfNotNull(place.locality ?: place.subAdminArea, place.adminArea?.let(::stateCode)).joinToString(", ")
        val where = when (sharing) {
            LocationSharing.CITY -> city
            LocationSharing.CITY_ZIP -> listOfNotNull(city.ifBlank { null }, place.postalCode).joinToString(" ")
            else -> listOfNotNull(place.thoroughfare?.let { "near $it" }, city, place.postalCode).joinToString(", ") +
                " (%.4f, %.4f)".format(Locale.US, location.latitude, location.longitude)
        }
        if (where.isBlank()) return ""
        return "[Background, not part of the request: the user's phone is in $where. Use it only if the request " +
            "needs a location; it is not a request to navigate.]"
    }

    // Two-letter codes read better ("Springfield, IL"); Geocoder gives full US state names.
    private fun stateCode(state: String) = US_STATES[state] ?: state

    private val US_STATES = mapOf(
        "Alabama" to "AL", "Alaska" to "AK", "Arizona" to "AZ", "Arkansas" to "AR", "California" to "CA",
        "Colorado" to "CO", "Connecticut" to "CT", "Delaware" to "DE", "Florida" to "FL", "Georgia" to "GA",
        "Hawaii" to "HI", "Idaho" to "ID", "Illinois" to "IL", "Indiana" to "IN", "Iowa" to "IA", "Kansas" to "KS",
        "Kentucky" to "KY", "Louisiana" to "LA", "Maine" to "ME", "Maryland" to "MD", "Massachusetts" to "MA",
        "Michigan" to "MI", "Minnesota" to "MN", "Mississippi" to "MS", "Missouri" to "MO", "Montana" to "MT",
        "Nebraska" to "NE", "Nevada" to "NV", "New Hampshire" to "NH", "New Jersey" to "NJ", "New Mexico" to "NM",
        "New York" to "NY", "North Carolina" to "NC", "North Dakota" to "ND", "Ohio" to "OH", "Oklahoma" to "OK",
        "Oregon" to "OR", "Pennsylvania" to "PA", "Rhode Island" to "RI", "South Carolina" to "SC",
        "South Dakota" to "SD", "Tennessee" to "TN", "Texas" to "TX", "Utah" to "UT", "Vermont" to "VT",
        "Virginia" to "VA", "Washington" to "WA", "West Virginia" to "WV", "Wisconsin" to "WI", "Wyoming" to "WY",
        "District of Columbia" to "DC",
    )

    private const val FRESH_MS = 10 * 60_000L
}
