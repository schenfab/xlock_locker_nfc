package com.fschenkel.xlocklocker

import android.content.Context

class BadgeRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class BadgeData(
        val uid: String,
        val techList: List<String>,
        val aids: List<String>,
        val exchanges: List<Pair<String, String>>  // ordered list of (commandHex, responseHex)
    )

    fun save(data: BadgeData) {
        prefs.edit()
            .putString(KEY_UID, data.uid)
            .putString(KEY_TECH_LIST, data.techList.joinToString("|"))
            .putString(KEY_AIDS, data.aids.joinToString("|"))
            .putString(KEY_EXCHANGES, exchangesToString(data.exchanges))
            .apply()
    }

    fun load(): BadgeData? {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        return BadgeData(
            uid = uid,
            techList = prefs.getString(KEY_TECH_LIST, "")!!
                .split("|").filter { it.isNotEmpty() },
            aids = prefs.getString(KEY_AIDS, "")!!
                .split("|").filter { it.isNotEmpty() },
            exchanges = stringToExchanges(prefs.getString(KEY_EXCHANGES, "") ?: "")
        )
    }

    fun lookupResponse(commandHex: String): ByteArray? {
        val upper = commandHex.uppercase()
        val exchanges = stringToExchanges(prefs.getString(KEY_EXCHANGES, "") ?: "")

        // Exact match
        exchanges.firstOrNull { it.first == upper }?.let { return it.second.hexToBytes() }

        // Prefix match on the 5-byte APDU header (CLA INS P1 P2 + Lc)
        val prefix = upper.take(10)
        exchanges.firstOrNull { it.first.startsWith(prefix) }?.let { return it.second.hexToBytes() }

        return null
    }

    fun isConfigured(): Boolean = prefs.contains(KEY_UID)

    fun clear() = prefs.edit().clear().apply()

    // Format: "CMD1:RESP1\nCMD2:RESP2\n..."
    private fun exchangesToString(exchanges: List<Pair<String, String>>): String =
        exchanges.joinToString("\n") { "${it.first}:${it.second}" }

    private fun stringToExchanges(s: String): List<Pair<String, String>> {
        if (s.isEmpty()) return emptyList()
        return s.lines()
            .filter { it.contains(":") }
            .map {
                val idx = it.indexOf(":")
                it.substring(0, idx) to it.substring(idx + 1)
            }
    }

    companion object {
        private const val PREFS_NAME = "badge_data"
        private const val KEY_UID = "uid"
        private const val KEY_TECH_LIST = "tech_list"
        private const val KEY_AIDS = "aids"
        private const val KEY_EXCHANGES = "exchanges"
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun String.hexToBytes(): ByteArray {
    val s = if (length % 2 != 0) "0$this" else this
    return ByteArray(s.length / 2) {
        ((s[it * 2].digitToInt(16) shl 4) + s[it * 2 + 1].digitToInt(16)).toByte()
    }
}
