package com.myra.assistant.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import com.myra.assistant.util.Logger
import com.myra.assistant.util.PermissionHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fetches real-world info (weather, distances) from free, key-less public APIs
 * (Open-Meteo for weather + geocoding, OSRM for road routing). All calls are
 * blocking and must run off the main thread; [PhoneController.dispatch] already
 * runs on a background coroutine, so calling these from there is safe.
 */
class InfoController(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        Logger.e(TAG, "GET failed: $url", e)
        null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private data class Place(val lat: Double, val lon: Double, val label: String)

    private fun geocode(name: String): Place? {
        val body = get(
            "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" + enc(name)
        ) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val o = results.getJSONObject(0)
        val label = listOfNotNull(
            o.optString("name").ifBlank { null },
            o.optString("country").ifBlank { null }
        ).joinToString(", ")
        return Place(o.getDouble("latitude"), o.getDouble("longitude"), label.ifBlank { name })
    }

    @SuppressLint("MissingPermission")
    private fun currentLocation(): Place? {
        val hasFine = PermissionHelper.hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = PermissionHelper.hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasFine && !hasCoarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val loc = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: Exception) {
            Logger.e(TAG, "Location lookup failed", e)
            null
        } ?: return null
        return Place(loc.latitude, loc.longitude, "your location")
    }

    /** Human-readable current weather for a named place, or the device location if blank. */
    fun weather(location: String): String {
        val place: Place = if (location.isBlank()) {
            currentLocation()
                ?: return "I couldn't get your current location. Tell me a city name, or turn on location permission."
        } else {
            geocode(location) ?: return "I couldn't find a place called \"$location\"."
        }
        val body = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"
        ) ?: return "The weather service is not reachable right now."
        val cur = JSONObject(body).optJSONObject("current") ?: return "Weather data is unavailable right now."
        val temp = cur.optDouble("temperature_2m").roundToInt()
        val feels = cur.optDouble("apparent_temperature").roundToInt()
        val hum = cur.optInt("relative_humidity_2m")
        val wind = cur.optDouble("wind_speed_10m").roundToInt()
        val desc = weatherText(cur.optInt("weather_code", -1))
        return "Weather in ${place.label}: $desc, ${temp}\u00B0C (feels like ${feels}\u00B0C), " +
            "humidity ${hum}%, wind ${wind} km/h."
    }

    /** Road distance + straight-line distance between two named places. */
    fun distance(from: String, to: String): String {
        if (from.isBlank() || to.isBlank()) return "Tell me both the starting point and the destination."
        val a = geocode(from) ?: return "I couldn't find \"$from\"."
        val b = geocode(to) ?: return "I couldn't find \"$to\"."
        val straight = haversine(a.lat, a.lon, b.lat, b.lon).roundToInt()
        val osrm = get(
            "https://router.project-osrm.org/route/v1/driving/" +
                "${a.lon},${a.lat};${b.lon},${b.lat}?overview=false"
        )
        if (osrm != null) {
            try {
                val route = JSONObject(osrm).optJSONArray("routes")?.optJSONObject(0)
                if (route != null) {
                    val km = (route.getDouble("distance") / 1000.0).roundToInt()
                    val mins = (route.getDouble("duration") / 60.0).roundToInt()
                    val time = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
                    return "By road, ${a.label} to ${b.label} is about ${km} km (~$time driving). " +
                        "Straight-line distance is about ${straight} km."
                }
            } catch (e: Exception) {
                Logger.e(TAG, "OSRM parse failed", e)
            }
        }
        return "Straight-line distance from ${a.label} to ${b.label} is about ${straight} km " +
            "(road distance is unavailable right now)."
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    private fun weatherText(code: Int): String = when (code) {
        0 -> "clear sky"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61, 63, 65 -> "rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "current conditions"
    }

    companion object { private const val TAG = "InfoController" }
}
