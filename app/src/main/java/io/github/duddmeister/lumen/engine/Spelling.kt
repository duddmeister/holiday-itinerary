package io.github.duddmeister.lumen.engine

/** Which English Lumen spells in: picks the dictionary and names the space bar. */
enum class Spelling(val id: String, val locale: String, val title: String, val language: String) {
    AMERICAN("us", "en_US", "American", "English (US)"),
    BRITISH("uk", "en_GB", "British", "English (UK)");

    companion object {
        /** Regions that spell the American way; the rest of the English-speaking world spells the British way. */
        private val AMERICAN_REGIONS = setOf("US", "PR", "PH", "LR", "UM", "VI", "GU", "AS", "MP", "MH", "FM", "PW")

        fun of(id: String?) = entries.firstOrNull { it.id == id }

        /** The spelling for a phone set to [country] (an ISO 3166 region code). */
        fun forRegion(country: String?): Spelling =
            if (country.isNullOrEmpty() || country.uppercase() in AMERICAN_REGIONS) AMERICAN else BRITISH
    }
}
