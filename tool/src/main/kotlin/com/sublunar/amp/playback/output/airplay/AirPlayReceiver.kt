package com.sublunar.amp.playback.output.airplay

/**
 * An AirPlay speaker found on the network, as its `_raop._tcp` record
 * describes it.
 *
 * [id] is its whole instance name, lower-cased: a computer serving several
 * AirPlay speakers gives them all one hex device id, the part before the
 * `@` ([deviceId], the MAC on every device seen), and tells them apart by
 * [name], what follows it.
 */
data class AirPlayReceiver(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val txt: Map<String, String>,
) {
    val deviceId: String get() = id.substringBefore('@')

    /** Kinds of metadata it shows: text, a cover, a progress bar (`md`). */
    val showsText: Boolean get() = "0" in values("md")
    val showsArtwork: Boolean get() = "1" in values("md")
    val showsProgress: Boolean get() = "2" in values("md")

    /**
     * Played to over AirPlay 2 once paired with the code it shows (see
     * [AirPlayPairing]): an Apple TV, a Mac, any receiver that says it wants
     * pairing and offers the kind that shows a code. Every other plays AirPlay 1.
     */
    val pairsWithCode: Boolean get() = codePairing(txt)

    /**
     * Played to over AirPlay 2 with a pairing made afresh each time and no
     * code (see [AirPlayPairing.transient]): a HomePod.
     */
    val pairsWithoutCode: Boolean get() = transientPairing(txt)

    /** Whether it takes the start of MFi authentication, which some speakers want before a stream (et=4). */
    val takesAuthSetup: Boolean get() = "4" in values("et")

    /**
     * A speaker rather than a screen, as far as its announcement says: Apple's
     * own by model (HomePod, AirPort Express), any other by its features leaving
     * out video (bit 0 of `ft`). An Apple TV, or one that says neither, isn't.
     */
    val isSpeaker: Boolean get() {
        val model = txt["am"].orEmpty()
        if (model.startsWith("AppleTV")) return false
        if (model.startsWith("AudioAccessory") || model.startsWith("AirPort")) return true
        val features = txt["ft"]?.substringBefore(',')?.trim()?.removePrefix("0x")?.toLongOrNull(16) ?: return false
        return features and VIDEO_FEATURE == 0L
    }

    private fun values(key: String): List<String> = txt[key].orEmpty().split(',').map { it.trim() }

    companion object {
        /**
         * The speaker an mDNS [service] describes, or null when Amp can't play
         * to it — see [whyNot].
         */
        fun from(service: Mdns.Service): AirPlayReceiver? {
            if (whyNot(service) != null) return null
            val label = service.instance.substringBefore("._raop.")
            return AirPlayReceiver(
                id = label.lowercase(),
                name = label.substringAfter('@'),
                address = service.address,
                port = service.port,
                txt = service.txt,
            )
        }

        /**
         * Why Amp can't play to [service], or null when it can: plain AirPlay 1,
         * Apple Lossless over UDP, with nothing to unlock first — or an Apple
         * TV, which pairs with its code. Any other speaker that wants a
         * password, a PIN, pairing or encryption is left off the list rather
         * than shown and failing.
         */
        fun whyNot(service: Mdns.Service): String? {
            val label = service.instance.substringBefore("._raop.")
            val txt = service.txt
            fun has(key: String, value: String) = txt[key].orEmpty().split(',').any { it.trim().equals(value, ignoreCase = true) }

            val hex = label.substringBefore('@', missingDelimiterValue = "")
            if (hex.isEmpty() || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return "no device id"
            if (txt.containsKey("tp") && !has("tp", "UDP")) return "no UDP transport"
            if (txt.containsKey("cn") && !has("cn", "1")) return "no Apple Lossless"
            val model = txt["am"].orEmpty()
            // One that pairs with the code on its screen plays AirPlay 2; only
            // one set to a password is out.
            if (codePairing(txt) || transientPairing(txt)) {
                val passworded = txt["pw"].equals("true", ignoreCase = true) || parseFlags(txt["sf"] ?: txt["flags"]) and PASSWORD_REQUIRED != 0L
                return if (passworded) "needs a password" else null
            }
            // Unencrypted (0) must be on offer; with no list, only a speaker
            // that insists on the old key (ek=1) is out.
            if (txt.containsKey("et") && !has("et", "0")) return "needs encryption"
            if (!txt.containsKey("et") && txt["ek"] == "1") return "needs encryption"
            if (txt["pw"].equals("true", ignoreCase = true)) return "needs a password"
            val flags = parseFlags(txt["sf"] ?: txt["flags"])
            if (flags and PIN_REQUIRED != 0L) return "needs a PIN"
            if (flags and PASSWORD_REQUIRED != 0L) return "needs a password"
            if (flags and PAIRING_REQUIRED != 0L) return "needs pairing"
            // A HomePod from OS 27 that doesn't offer pairing without a code turns AirPlay 1 away.
            if (model.startsWith("AudioAccessory") && osMajor(txt) >= 27) return "an Apple device on OS ${osMajor(txt)}"
            // Macs no longer take AirPlay 1 at all.
            if (MAC_MODEL.matches(model)) return "a Mac"
            if (txt["ch"]?.let { it != "2" } == true || txt["sr"]?.let { it != "44100" } == true ||
                txt["ss"]?.let { it != "16" } == true
            ) {
                return "not CD audio"
            }
            return null
        }

        private const val VIDEO_FEATURE = 0x1L
        private const val PIN_REQUIRED = 0x8L
        private const val PASSWORD_REQUIRED = 0x80L
        private const val PAIRING_REQUIRED = 0x200L
        /** Every Mac's model name: Mac14,7, MacBookPro18,3, iMac21,1, Macmini9,1, MacPro7,1… */
        private val MAC_MODEL = Regex("""^i?Mac[A-Za-z]*\d+,\d+$""")

        private fun parseFlags(value: String?): Long {
            val v = value?.trim() ?: return 0
            return if (v.startsWith("0x", ignoreCase = true)) v.substring(2).toLongOrNull(16) ?: 0 else v.toLongOrNull() ?: 0
        }

        /**
         * Pairing it asks for (sf 0x200), of the HomeKit kind that puts a code
         * on its screen (feature bit 46), or an Apple TV, which always can. A
         * HomePod has no screen to show one on.
         */
        private fun codePairing(txt: Map<String, String>): Boolean {
            val model = txt["am"].orEmpty()
            if (model.startsWith("AppleTV")) return true
            if (model.startsWith("AudioAccessory")) return false
            val wantsPairing = parseFlags(txt["sf"] ?: txt["flags"]) and PAIRING_REQUIRED != 0L
            return wantsPairing && features(txt) and HOMEKIT_PAIRING != 0L
        }

        /** A HomePod: AirPlay 2, paired for each connection with no code (feature bits 43 or 48 offer it). */
        private fun transientPairing(txt: Map<String, String>): Boolean =
            txt["am"].orEmpty().startsWith("AudioAccessory") && features(txt) and (SYSTEM_PAIRING or COREUTILS_PAIRING) != 0L

        /** The 64 feature bits of `ft`, written as one hex number or as two (low word, high word). */
        private fun features(txt: Map<String, String>): Long {
            val parts = txt["ft"].orEmpty().split(',').map { it.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: 0L }
            return (parts.getOrElse(0) { 0L } and 0xffffffffL) or (parts.getOrElse(1) { 0L } shl 32)
        }

        private const val HOMEKIT_PAIRING = 1L shl 46
        private const val SYSTEM_PAIRING = 1L shl 43
        private const val COREUTILS_PAIRING = 1L shl 48

        private fun osMajor(txt: Map<String, String>): Int =
            (txt["ov"] ?: txt["osvers"])?.substringBefore('.')?.toIntOrNull() ?: 0
    }
}
