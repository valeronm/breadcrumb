package io.github.valeronm.breadcrumb.domain.recording

/** A Wi-Fi connection can be lost while another is joined, which leaves the phone on Wi-Fi. */
class WifiLinks {

    data class Lost(val frequencyMhz: Int?, val stillJoined: List<String>) {
        val offWifi: Boolean get() = stillJoined.isEmpty()
    }

    private val frequencies = LinkedHashMap<String, Int?>()

    fun onJoined(id: String) {
        frequencies.putIfAbsent(id, null)
    }

    fun frequencyChanged(id: String, mhz: Int): Boolean {
        if (frequencies[id] == mhz) return false
        frequencies[id] = mhz
        return true
    }

    fun onLost(id: String): Lost = Lost(frequencies.remove(id), frequencies.keys.toList())
}
