package com.tellopilot

/**
 * Immutable snapshot of the drone telemetry, parsed from the state string the
 * Tello broadcasts on UDP 8890.
 *
 * Example raw line:
 * `pitch:0;roll:0;yaw:0;vgx:0;vgy:0;vgz:0;templ:65;temph:68;tof:10;h:0;bat:87;baro:103.40;time:0;agx:-3.00;agy:1.00;agz:-999.00;`
 */
data class TelloState(
    val pitch: Int = 0,
    val roll: Int = 0,
    val yaw: Int = 0,
    val tofCm: Int = 0,      // distance to ground reported by the ToF sensor
    val heightCm: Int = 0,   // relative height (h)
    val batteryPct: Int = 0, // bat
    val baro: Double = 0.0,  // barometer altitude
    val temperatureC: Int = 0,
    val raw: String = ""
) {
    companion object {
        /**
         * Parses a Tello state line. Returns null if the line is empty / malformed.
         * Robust to missing fields: any field that is absent keeps its default.
         */
        fun parse(line: String): TelloState? {
            if (line.isBlank()) return null
            val map = HashMap<String, String>()
            for (token in line.trim().split(";")) {
                val kv = token.split(":", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) {
                    map[kv[0].trim()] = kv[1].trim()
                }
            }
            if (map.isEmpty()) return null

            fun int(key: String): Int = map[key]?.toIntOrNull() ?: 0
            fun dbl(key: String): Double = map[key]?.toDoubleOrNull() ?: 0.0

            // temperature: average of templ/temph when both present
            val templ = map["templ"]?.toIntOrNull()
            val temph = map["temph"]?.toIntOrNull()
            val temp = when {
                templ != null && temph != null -> (templ + temph) / 2
                templ != null -> templ
                temph != null -> temph
                else -> 0
            }

            return TelloState(
                pitch = int("pitch"),
                roll = int("roll"),
                yaw = int("yaw"),
                tofCm = int("tof"),
                heightCm = int("h"),
                batteryPct = int("bat"),
                baro = dbl("baro"),
                temperatureC = temp,
                raw = line
            )
        }
    }
}
