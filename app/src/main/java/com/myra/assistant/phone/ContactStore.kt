package com.myra.assistant.phone

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import com.myra.assistant.util.Logger
import com.myra.assistant.util.PermissionHelper
import java.util.Locale

/**
 * Loads EVERY device contact once (with READ_CONTACTS permission) and keeps
 * them in memory for fast, fuzzy name -> number lookup.
 *
 * Two problems this solves:
 *  1) The old code ran a single `DISPLAY_NAME LIKE '%name%'` query at call time,
 *     which missed nicknames, partial names, and spelling/case differences.
 *  2) When one person had MORE THAN ONE number, that query always returned the
 *     first row, so the other number was unreachable (and renaming the contact
 *     changed nothing). Here we keep ALL numbers per contact -- grouped by
 *     contact id, with their Mobile/Home/Work labels -- so the caller can list
 *     them or pick one by position.
 */
class ContactStore(private val context: Context) {

    data class PhoneNumber(val number: String, val label: String)
    data class Contact(val id: Long, val name: String, val numbers: List<PhoneNumber>)

    @Volatile private var loaded = false
    private val contacts = ArrayList<Contact>()

    private class MutableContact(val id: Long, val name: String) {
        val numbers = ArrayList<PhoneNumber>()
    }

    /**
     * Read all contacts into memory. Safe to call repeatedly; only does real
     * work the first time unless [force] is set. Returns the contact count.
     */
    @Synchronized
    fun load(force: Boolean = false): Int {
        if (loaded && !force) return contacts.size
        if (!PermissionHelper.hasPermission(context, Manifest.permission.READ_CONTACTS)) return 0
        // Group by CONTACT_ID so one person's multiple numbers stay together AND
        // two different people who happen to share a name stay separate.
        val byId = LinkedHashMap<Long, MutableContact>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (c.moveToNext()) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                    val rawNumber = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                    if (name.isBlank() || rawNumber.isBlank()) continue
                    val clean = rawNumber.filter { it.isDigit() || it == '+' }
                    if (clean.isBlank()) continue
                    val id = if (idIdx >= 0) c.getLong(idIdx) else name.hashCode().toLong()
                    val type = if (typeIdx >= 0) c.getInt(typeIdx) else 0
                    val custom = if (labelIdx >= 0) c.getString(labelIdx) else null
                    val mc = byId.getOrPut(id) { MutableContact(id, name) }
                    if (mc.numbers.none { normalizeNumber(it.number) == normalizeNumber(clean) }) {
                        mc.numbers.add(PhoneNumber(clean, phoneTypeLabel(type, custom)))
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Contact load failed", e)
        }
        contacts.clear()
        byId.values.forEach { if (it.numbers.isNotEmpty()) contacts.add(Contact(it.id, it.name, it.numbers.toList())) }
        loaded = true
        Logger.i(TAG, "Loaded ${contacts.size} contacts")
        return contacts.size
    }

    fun isLoaded(): Boolean = loaded
    fun size(): Int = contacts.size

    fun all(): List<Contact> {
        if (!loaded) load()
        return contacts.toList()
    }

    /** Every contact matching the spoken name, best match first. */
    fun findMatches(query: String): List<Contact> {
        if (!loaded) load()
        val q = normalize(query)
        if (q.isBlank() || contacts.isEmpty()) return emptyList()
        val qTokens = q.split(' ').filter { it.isNotBlank() }
        return contacts
            .map { it to score(it.name, q, qTokens) }
            .filter { it.second >= 40 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** Best matching contact for a spoken name, or null if nothing is close. */
    fun findContact(query: String): Contact? = findMatches(query).firstOrNull()

    /** Best-matching phone number (optionally the Nth, 1-based) for a name. */
    fun findNumber(query: String, index: Int = 1): String? {
        val c = findContact(query) ?: return null
        if (c.numbers.isEmpty()) return null
        val i = (index - 1).coerceIn(0, c.numbers.size - 1)
        return c.numbers[i].number
    }

    private fun score(name: String, q: String, qTokens: List<String>): Int {
        val n = normalize(name)
        if (n.isBlank()) return 0
        return when {
            n == q -> 100
            n.startsWith(q) -> 85
            n.contains(q) -> 70
            q.contains(n) -> 65
            else -> {
                val nTokens = n.split(' ').filter { it.isNotBlank() }
                val overlap = qTokens.count { qt ->
                    nTokens.any { nt -> nt == qt || nt.startsWith(qt) || qt.startsWith(nt) }
                }
                if (overlap > 0) 30 + overlap * 10 else 0
            }
        }
    }

    private fun phoneTypeLabel(type: Int, custom: String?): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "Work mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> custom?.ifBlank { null } ?: "Other"
        else -> custom?.ifBlank { null } ?: "Other"
    }

    /** Lowercase, strip accents/punctuation, collapse spaces. */
    private fun normalize(s: String): String {
        val ascii = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return ascii.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Compare numbers by their last 10 digits so formatting differences match. */
    private fun normalizeNumber(s: String): String = s.filter { it.isDigit() }.takeLast(10)

    companion object { private const val TAG = "ContactStore" }
}
