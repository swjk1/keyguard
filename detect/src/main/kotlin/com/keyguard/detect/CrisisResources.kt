package com.keyguard.detect

/**
 * Region-appropriate crisis helplines.
 *
 * The first build hardcoded `tel:988`, which only works in the US and Canada. For a product
 * aimed at children in distress, a dead crisis number is the worst failure mode available,
 * so there is deliberately **no path that returns nothing**: an unrecognised region falls
 * back to a directory that covers the rest of the world.
 *
 * Lives in `:detect` rather than the app so it stays free of Android dependencies and can be
 * unit-tested, and so the eventual iOS port shares the same table.
 */
object CrisisResources {

    /**
     * @param dial a `tel:` URI, or null when only a web resource is known. A phone number is
     *   preferred: it works without data and is one tap from panic.
     * @param web an https URI, always present, so there is always somewhere to send someone.
     * @param label how to describe the resource to the user.
     */
    data class Resource(
        val dial: String?,
        val web: String,
        val label: String,
    ) {
        /** What the action button should open. */
        val primaryUri: String get() = dial ?: web
    }

    /**
     * Global fallback. Covers regions not listed below, which is most of the world — being
     * routed to a directory is far better than being routed to a disconnected number.
     */
    val FALLBACK = Resource(
        dial = null,
        web = "https://findahelpline.com",
        label = "Find a helpline near you",
    )

    /**
     * Keyed by ISO 3166-1 alpha-2 region. Deliberately short and verifiable rather than
     * exhaustive-but-unchecked: a wrong number here is worse than no number, because it
     * looks authoritative. Extend only with numbers that have been confirmed.
     */
    private val byRegion: Map<String, Resource> = mapOf(
        // 988 covers the US (2022) and Canada (2023).
        "US" to Resource("tel:988", "https://988lifeline.org", "988 Suicide & Crisis Lifeline"),
        "CA" to Resource("tel:988", "https://988.ca", "988 Suicide Crisis Helpline"),
        "GB" to Resource("tel:116123", "https://www.samaritans.org", "Samaritans"),
        "IE" to Resource("tel:116123", "https://www.samaritans.org/ireland", "Samaritans Ireland"),
        "AU" to Resource("tel:131114", "https://www.lifeline.org.au", "Lifeline Australia"),
        "NZ" to Resource("tel:1737", "https://1737.org.nz", "Need to Talk 1737"),
        "IN" to Resource("tel:9152987821", "https://icallhelpline.org", "iCall"),
        "ZA" to Resource("tel:0800567567", "https://www.sadag.org", "SADAG"),
        // 113 is the Dutch national suicide prevention line.
        "NL" to Resource("tel:113", "https://www.113.nl", "113 Zelfmoordpreventie"),
        // 116 123 is an EU-harmonised emotional-support number in several member states.
        "DE" to Resource("tel:08001110111", "https://www.telefonseelsorge.de", "TelefonSeelsorge"),
        "FR" to Resource("tel:3114", "https://3114.fr", "3114"),
        "BE" to Resource("tel:1813", "https://www.zelfmoord1813.be", "Zelfmoordlijn 1813"),
        "ES" to Resource("tel:024", "https://www.telefonoesperanza.com", "Línea 024"),
        "IT" to Resource("tel:800860022", "https://www.telefonoamico.it", "Telefono Amico"),
        "SE" to Resource("tel:90101", "https://mind.se", "Mind Självmordslinjen"),
        "NO" to Resource("tel:116123", "https://mentalhelse.no", "Mental Helse"),
        "DK" to Resource("tel:70201201", "https://www.livslinien.dk", "Livslinien"),
        "FI" to Resource("tel:0925250111", "https://mieli.fi", "MIELI"),
        "PL" to Resource("tel:116123", "https://www.116123.edu.pl", "Kryzysowy Telefon Zaufania"),
        "BR" to Resource("tel:188", "https://www.cvv.org.br", "CVV"),
        "MX" to Resource("tel:8009112000", "https://www.gob.mx/salud", "Línea de la Vida"),
        "JP" to Resource("tel:0570064556", "https://www.since2011.net/yorisoi", "Yorisoi Hotline"),
        "SG" to Resource("tel:1767", "https://www.sos.org.sg", "Samaritans of Singapore"),
        "HK" to Resource("tel:28960000", "https://www.samaritans.org.hk", "Samaritans Hong Kong"),
    )

    /**
     * Resolves a resource for [regionCode] (an ISO 3166-1 alpha-2 code, case-insensitive).
     * Never returns null; an unknown or blank region yields [FALLBACK].
     */
    fun forRegion(regionCode: String?): Resource {
        val key = regionCode?.trim()?.uppercase().orEmpty()
        if (key.length != 2) return FALLBACK
        return byRegion[key] ?: FALLBACK
    }

    /** Regions with a confirmed entry. Exposed for tests and for a coverage check. */
    val supportedRegions: Set<String> get() = byRegion.keys
}
