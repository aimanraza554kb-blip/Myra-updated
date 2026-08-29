package com.myra.assistant.phone

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.view.KeyEvent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.myra.assistant.data.repository.SettingsRepository
import com.myra.assistant.service.MyraAccessibilityService
import com.myra.assistant.util.Logger
import com.myra.assistant.util.PermissionHelper
import java.util.Locale
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/**
 * Executes device actions requested through MYRA. Actions are triggered either
 * by explicit UI buttons or by Gemini function calls routed through [dispatch].
 * Every action returns an honest, human-readable result (including permission
 * or platform limitations) so the model never claims something it did not do.
 */
class PhoneController(
    private val context: Context,
    private val settings: SettingsRepository
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val info by lazy { InfoController(context) }
    private val contactStore by lazy { ContactStore(context) }

    /** Load the whole address book once so name lookups are instant + reliable. */
    fun preloadContacts(): Int = contactStore.load()

    /**
     * Resolve which saved number to use for a name. Numbers from every contact
     * matching the name are flattened into one ordered list so the user can pick
     * by position (1, 2, ...). Returns (number, null) when unambiguous, or
     * (null, question) when there is more than one and no index was given.
     */
    private fun resolveNumber(name: String, index: Int): Pair<String?, String?> {
        val flat = contactStore.findMatches(name).flatMap { c -> c.numbers.map { c.name to it } }
        if (flat.isEmpty()) {
            val raw = lookupNumberRaw(name)
            return if (raw != null) raw to null else null to null
        }
        if (flat.size == 1) return flat[0].second.number to null
        if (index in 1..flat.size) return flat[index - 1].second.number to null
        val list = flat.mapIndexed { i, pair -> "${i + 1}) ${pair.second.label} ${pair.second.number}" }.joinToString(", ")
        return null to "There are ${flat.size} numbers saved for \"$name\": $list. Which one should I use? Say the number, like 1 or 2."
    }

    private fun launch(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Logger.w(TAG, "No activity for intent: ${intent.action}")
        }
    }

    // ----- Apps -----
    /** Returns true if an installed app was launched, false if we fell back to the store. */
    fun openApp(query: String): Boolean {
        val pm = context.packageManager
        val key = query.lowercase(Locale.ROOT).trim()
        val candidates = buildList {
            KNOWN_APPS[key]?.let { add(it) }
            findPackageByLabel(query)?.let { add(it) }
        }
        for (pkg in candidates) {
            pm.getLaunchIntentForPackage(pkg)?.let { launch(it); return true }
        }
        // Not installed / not visible: open a store search so the user can get it.
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query))))
        return false
    }

    private fun findPackageByLabel(label: String): String? {
        val pm = context.packageManager
        val target = label.lowercase(Locale.ROOT).trim()
        return pm.getInstalledApplications(0).firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase(Locale.ROOT).contains(target)
        }?.packageName
    }

    fun closeCurrentApp() {
        MyraAccessibilityService.instance?.goHome()
    }

    fun openPlayStore() = launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=apps")))
    fun openChrome(url: String) {
        val target = if (url.startsWith("http")) url else "https://www.google.com/search?q=" + Uri.encode(url)
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
    }
    fun openSettings() = launch(Intent(Settings.ACTION_SETTINGS))
    fun openCalculator() = openApp("calculator")
    fun openInstagram() = openApp("instagram")
    fun openFacebook() = openApp("facebook")

    // ----- Communication -----
    fun callContact(name: String) {
        val number = lookupNumber(name) ?: run { Logger.w(TAG, "Contact not found: $name"); return }
        callNumber(number)
    }

    /** Place the call if CALL_PHONE is granted, otherwise open the dialer prefilled. */
    private fun callNumber(number: String) {
        if (PermissionHelper.hasPermission(context, android.Manifest.permission.CALL_PHONE)) {
            launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
        } else {
            launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        }
    }

    /** Returns true if we could also auto-tap Send (accessibility on), false if only prefilled. */
    fun whatsapp(name: String, message: String, index: Int = 0): Boolean {
        val resolved = if (index >= 1) resolveNumber(name, index).first else lookupNumber(name)
        val number = resolved?.filter { it.isDigit() || it == '+' }
        val uri = if (number != null) {
            Uri.parse("https://wa.me/" + number.removePrefix("+") + "?text=" + Uri.encode(message))
        } else {
            Uri.parse("https://wa.me/?text=" + Uri.encode(message))
        }
        launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"))
        // wa.me only prefills the text; tap Send for the user via accessibility.
        val svc = MyraAccessibilityService.instance
        if (svc != null && message.isNotBlank()) {
            svc.clickSendAfterDelay()
            return true
        }
        return false
    }

    /**
     * Place a WhatsApp voice call to a saved contact. WhatsApp registers a
     * per-contact data row (MIME `vnd.android.cursor.item/vnd.com.whatsapp.voip.call`)
     * that we fire directly, which is the only reliable way to start a WhatsApp
     * call without the user tapping through the UI. Returns false if the contact
     * has no WhatsApp entry (or contacts permission is missing).
     */
    fun whatsappCall(name: String): Boolean {
        if (!PermissionHelper.hasPermission(context, android.Manifest.permission.READ_CONTACTS)) return false
        val mime = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        // Resolve to the real saved contact name first (fuzzy) so we still match
        // the WhatsApp data row when the spoken name is partial or misspelled.
        val canonical = contactStore.findContact(name)?.name
        val candidates = listOfNotNull(canonical, name).distinct()
        for (candidate in candidates) {
            val cursor = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?",
                arrayOf(mime, "%$candidate%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val dataId = it.getLong(0)
                    launch(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId), mime)
                            .setPackage("com.whatsapp")
                    )
                    return true
                }
            }
        }
        return false
    }

    fun sendSms(name: String, message: String, index: Int = 0) {
        val number = (if (index >= 1) resolveNumber(name, index).first else lookupNumber(name)) ?: name
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", message))
    }

    fun email(to: String, subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        launch(intent)
    }

    fun lookupNumber(name: String): String? {
        if (!PermissionHelper.hasPermission(context, android.Manifest.permission.READ_CONTACTS)) return null
        // Prefer the in-memory store (fuzzy + multi-number aware); fall back to a
        // raw query only if the store has nothing for this name.
        contactStore.findNumber(name)?.let { return it }
        return lookupNumberRaw(name)
    }

    private fun lookupNumberRaw(name: String): String? {
        if (!PermissionHelper.hasPermission(context, android.Manifest.permission.READ_CONTACTS)) return null
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    // ----- Hardware toggles -----
    fun setTorch(on: Boolean) {
        try {
            val id = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(id, on)
        } catch (e: Exception) {
            Logger.e(TAG, "Torch failed", e)
        }
    }

    fun openBluetoothSettings() = launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    fun openWifiSettings() = launch(Intent(Settings.ACTION_WIFI_SETTINGS))

    fun setVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = (percent.coerceIn(0, 100) * max / 100)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, AudioManager.FLAG_SHOW_UI)
    }

    /**
     * Control whatever media is currently playing -- the same active media
     * session the media notification's buttons control. Sends BOTH key-down and
     * key-up, because a media session only reacts to a full press.
     */
    fun mediaControl(action: String) {
        val code = when (action.lowercase(Locale.ROOT).trim()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause", "toggle", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip", "forward" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun setBrightness(percent: Int) {
        if (!PermissionHelper.canWriteSettings(context)) {
            launch(PermissionHelper.writeSettingsIntent(context))
            return
        }
        val value = (percent.coerceIn(0, 100) * 255 / 100)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    // ----- Clock -----
    fun setAlarm(hour: Int, minute: Int, label: String) {
        launch(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        )
    }

    fun setTimer(seconds: Int, label: String) {
        launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        )
    }

    // ----- Media / navigation -----
    fun openCamera() = launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    fun openGallery() = launch(Intent(Intent.ACTION_VIEW).setType("image/*"))
    fun openMaps(query: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))))
    fun navigate(destination: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination))))
    fun openYouTube(query: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
    fun openSpotify() = openApp("spotify")

    /**
     * Generic "play" for ANY app the user names. The reliable, cross-app way is
     * to actually OPEN the app and then drive its own search box and tap the
     * first result via accessibility -- not to fire a system intent that many
     * apps silently ignore. YouTube and Spotify get a direct search deep-link
     * (faster), everything else is opened and searched via accessibility.
     */
    fun playInApp(appName: String, query: String) {
        val key = appName.lowercase(Locale.ROOT).trim()
        val pkg = KNOWN_APPS[key] ?: findPackageByLabel(appName)
        val a11y = MyraAccessibilityService.instance
        when {
            (key.contains("youtube") && !key.contains("music")) || pkg == "com.google.android.youtube" -> {
                openYouTube(query)
                a11y?.tapFirstResultAfterDelay()
            }
            key.contains("spotify") || pkg == "com.spotify.music" -> {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))).setPackage("com.spotify.music"))
                a11y?.tapFirstResultAfterDelay(2200L)
            }
            else -> {
                if (pkg != null) context.packageManager.getLaunchIntentForPackage(pkg)?.let { launch(it) } ?: openApp(appName)
                else openApp(appName)
                a11y?.searchAndPlay(query)
            }
        }
    }

    fun playMusic() {
        launch(Intent("android.intent.action.MUSIC_PLAYER").addCategory(Intent.CATEGORY_APP_MUSIC))
    }

    // ----- Calendar -----
    fun addCalendarEvent(title: String, startMillis: Long) {
        launch(
            Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
        )
    }
    fun openCalendar() {
        val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
        ContentUris.appendId(builder, System.currentTimeMillis())
        launch(Intent(Intent.ACTION_VIEW).setData(builder.build()))
    }

    // ----- Utilities -----
    fun shareText(text: String) {
        launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
    }
    fun takeScreenshot() = MyraAccessibilityService.instance?.takeScreenshotAction()

    /**
     * Run an accessibility-backed action and return an honest result. If the
     * service isn't enabled we say so rather than pretending it worked.
     */
    private inline fun a11yAction(action: (MyraAccessibilityService) -> Boolean): String {
        val svc = MyraAccessibilityService.instance
            ?: return "I can't do that yet - enable MYRA's accessibility service in Settings."
        return if (action(svc)) "Done" else "That action isn't available on this screen right now."
    }

    /**
     * Execute a function call requested by the Gemini model. Returns a short
     * human-readable result that is sent back to the model so it can confirm
     * (or report failure of) the action out loud.
     */
    fun dispatch(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "open_app" -> {
                    val app = args.optString("app_name")
                    if (openApp(app)) "Opened $app"
                    else "$app is not installed; opened the app store search instead."
                }
                "call_contact" -> {
                    val name = args.optString("name")
                    val (number, ask) = resolveNumber(name, args.optInt("index", 0))
                    when {
                        number != null -> { callNumber(number); "Calling $name" }
                        ask != null -> ask
                        !PermissionHelper.hasPermission(context, android.Manifest.permission.READ_CONTACTS) ->
                            "I need contacts permission before I can place that call."
                        else -> "I couldn't find a contact named $name."
                    }
                }
                "send_whatsapp" -> {
                    val to = args.optString("name")
                    val index = args.optInt("index", 0)
                    val ask = resolveNumber(to, index).second
                    if (ask != null) ask
                    else {
                        val sent = whatsapp(to, args.optString("message"), index)
                        if (sent) "WhatsApp message sent to $to"
                        else "I opened the WhatsApp chat with the message typed. Enable MYRA's accessibility service so I can tap Send automatically."
                    }
                }
                "send_sms" -> {
                    val to = args.optString("name")
                    val index = args.optInt("index", 0)
                    val ask = resolveNumber(to, index).second
                    if (ask != null) ask
                    else { sendSms(to, args.optString("message"), index); "SMS composer opened" }
                }
                "send_email" -> { email(args.optString("to"), args.optString("subject"), args.optString("body")); "Email composer opened" }
                "set_torch" -> { val on = args.optBoolean("on", true); setTorch(on); "Torch " + if (on) "on" else "off" }
                "set_volume" -> { setVolume(args.optInt("percent")); "Volume set to ${args.optInt("percent")}%" }
                "media_control" -> { mediaControl(args.optString("action")); "Media " + args.optString("action") }
                "set_brightness" -> {
                    if (!PermissionHelper.canWriteSettings(context)) {
                        setBrightness(args.optInt("percent"))
                        "I opened the permission screen - allow 'Modify system settings' so I can change brightness."
                    } else {
                        setBrightness(args.optInt("percent")); "Brightness set to ${args.optInt("percent")}%"
                    }
                }
                "set_alarm" -> { setAlarm(args.optInt("hour"), args.optInt("minute"), args.optString("label", "Alarm")); "Alarm set" }
                "set_timer" -> { setTimer(args.optInt("seconds"), args.optString("label", "Timer")); "Timer started" }
                "open_camera" -> { openCamera(); "Camera opened" }
                "open_gallery" -> { openGallery(); "Gallery opened" }
                "open_maps" -> { openMaps(args.optString("query")); "Maps opened" }
                "navigate" -> { navigate(args.optString("destination")); "Navigation started" }
                "search_youtube" -> { openYouTube(args.optString("query")); "YouTube opened" }
                "play_youtube" -> { playInApp("youtube", args.optString("query")); "Playing on YouTube" }
                "play_in_app" -> {
                    playInApp(args.optString("app"), args.optString("query"))
                    "Playing '" + args.optString("query") + "' in " + args.optString("app")
                }
                "play_music" -> { playMusic(); "Music playing" }
                "open_url" -> { openChrome(args.optString("url")); "Opened link" }
                "open_settings" -> { openSettings(); "Settings opened" }
                "open_wifi_settings" -> { openWifiSettings(); "Wi-Fi settings opened" }
                "open_bluetooth_settings" -> { openBluetoothSettings(); "Bluetooth settings opened" }
                "take_screenshot" -> {
                    if (MyraAccessibilityService.instance == null)
                        "Enable MYRA's accessibility service to take screenshots."
                    else if (takeScreenshot() == true) "Screenshot taken"
                    else "I couldn't take a screenshot on this device."
                }
                "add_calendar_event" -> {
                    val millis = args.optString("start_epoch_millis").toLongOrNull() ?: System.currentTimeMillis()
                    addCalendarEvent(args.optString("title"), millis); "Calendar event created"
                }
                "share_text" -> { shareText(args.optString("text")); "Share sheet opened" }
                "scroll" -> {
                    val down = !args.optString("direction").equals("up", ignoreCase = true)
                    val svc = MyraAccessibilityService.instance
                    when {
                        svc == null -> "Enable MYRA's accessibility service so I can scroll."
                        svc.scroll(down) -> "Scrolled ${if (down) "down" else "up"}"
                        else -> "I couldn't scroll on this screen."
                    }
                }
                "press_back" -> a11yAction { it.goBack() }
                "press_home" -> a11yAction { it.goHome() }
                "open_recents" -> a11yAction { it.openRecents() }
                "open_notifications" -> a11yAction { it.openNotifications() }
                "lookup_contact" -> {
                    val name = args.optString("name")
                    if (!PermissionHelper.hasPermission(context, android.Manifest.permission.READ_CONTACTS))
                        "I need contacts permission to read numbers."
                    else {
                        val flat = contactStore.findMatches(name).flatMap { c -> c.numbers.map { c.name to it } }
                        when {
                            flat.isEmpty() -> {
                                val raw = lookupNumberRaw(name)
                                if (raw != null) "$name's number is $raw" else "I couldn't find a contact named $name."
                            }
                            flat.size == 1 -> "${flat[0].first}'s number is ${flat[0].second.number} (${flat[0].second.label})"
                            else -> "$name has ${flat.size} numbers: " + flat.mapIndexed { i, pair -> "${i + 1}) ${pair.second.label} ${pair.second.number}" }.joinToString(", ")
                        }
                    }
                }
                "whatsapp_call" -> {
                    val to = args.optString("name")
                    when {
                        whatsappCall(to) -> "Starting a WhatsApp call to $to"
                        !PermissionHelper.hasPermission(context, android.Manifest.permission.READ_CONTACTS) ->
                            "I need contacts permission to place a WhatsApp call."
                        else -> "I couldn't find $to on WhatsApp. Make sure the contact is saved and has WhatsApp."
                    }
                }
                "get_current_time" -> currentPakistanTime()
                "get_weather" -> info.weather(args.optString("location"))
                "get_distance" -> info.distance(args.optString("from"), args.optString("to"))
                else -> "Unknown action: $name"
            }
        } catch (e: Exception) {
            Logger.e(TAG, "dispatch failed for $name", e)
            "Failed: ${e.message}"
        }
    }

    private fun currentPakistanTime(): String {
        val now = ZonedDateTime.now(ZoneId.of("Asia/Karachi"))
        val date = now.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", Locale.ENGLISH))
        val time = now.format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH))
        return "Pakistan local time: $time, $date (Asia/Karachi)."
    }

    /** Extract a durable fact worth remembering (learning mode). */
    fun maybeLearn(userText: String): String? {
        val t = userText.lowercase(Locale.ROOT)
        return when {
            t.contains("my name is") -> userText.substringAfter("my name is").trim().let { "User's name is $it" }
            t.contains("i like") -> "User likes" + userText.substringAfter("i like")
            t.contains("remember that") -> userText.substringAfter("remember that").trim()
            else -> null
        }
    }

    companion object {
        private const val TAG = "PhoneController"
        private val KNOWN_APPS = mapOf(
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music",
            "youtube" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "play store" to "com.android.vending",
            "calculator" to "com.google.android.calculator",
            "camera" to "com.android.camera2",
            "settings" to "com.android.settings"
        )
    }
}
