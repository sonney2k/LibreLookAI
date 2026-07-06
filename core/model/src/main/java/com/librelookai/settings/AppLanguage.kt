package com.librelookai.settings
import java.util.Locale

object AppLanguage {
    const val ENGLISH        = "English"
    const val ENGLISH_GB     = "English (UK)"
    const val GERMAN         = "Deutsch"
    const val SPANISH        = "Español"
    const val SPANISH_LATAM  = "Español (Latinoamérica)"
    const val FRENCH         = "Français"
    const val ITALIAN        = "Italiano"
    const val PORTUGUESE_BR  = "Português (Brasil)"
    const val DUTCH          = "Nederlands"
    const val POLISH         = "Polski"
    const val ROMANIAN       = "Română"
    const val CZECH          = "Čeština"
    const val HUNGARIAN      = "Magyar"
    const val SWEDISH        = "Svenska"
    const val DANISH         = "Dansk"
    const val FINNISH        = "Suomi"
    const val NORWEGIAN      = "Norsk"
    const val GREEK          = "Ελληνικά"
    const val TURKISH        = "Türkçe"
    const val UKRAINIAN      = "Українська"
    const val ARABIC         = "العربية"
    const val HEBREW         = "עברית"
    const val URDU           = "اردو"
    const val HINDI          = "हिन्दी"
    const val THAI           = "ไทย"
    const val VIETNAMESE     = "Tiếng Việt"
    const val INDONESIAN     = "Bahasa Indonesia"
    const val MALAY          = "Bahasa Melayu"
    const val FILIPINO       = "Filipino"
    const val JAPANESE       = "日本語"
    const val KOREAN         = "한국어"
    const val CHINESE_TW     = "繁體中文"

    val options = listOf(
        ENGLISH, ENGLISH_GB,
        GERMAN,
        SPANISH, SPANISH_LATAM,
        FRENCH,
        ITALIAN,
        PORTUGUESE_BR,
        DUTCH,
        POLISH,
        ROMANIAN,
        CZECH,
        HUNGARIAN,
        SWEDISH, DANISH, FINNISH, NORWEGIAN,
        GREEK,
        TURKISH,
        UKRAINIAN,
        ARABIC, HEBREW, URDU,
        HINDI, THAI, VIETNAMESE,
        INDONESIAN, MALAY, FILIPINO,
        JAPANESE, KOREAN, CHINESE_TW,
    )

    fun toLocale(language: String): Locale = when (language) {
        ENGLISH_GB     -> Locale("en", "GB")
        GERMAN         -> Locale("de")
        SPANISH        -> Locale("es", "ES")
        SPANISH_LATAM  -> Locale.Builder().setLanguage("es").setRegion("419").build()
        FRENCH         -> Locale("fr")
        ITALIAN        -> Locale("it")
        PORTUGUESE_BR  -> Locale("pt", "BR")
        DUTCH          -> Locale("nl")
        POLISH         -> Locale("pl")
        ROMANIAN       -> Locale("ro")
        CZECH          -> Locale("cs")
        HUNGARIAN      -> Locale("hu")
        SWEDISH        -> Locale("sv")
        DANISH         -> Locale("da")
        FINNISH        -> Locale("fi")
        NORWEGIAN      -> Locale("no")
        GREEK          -> Locale("el")
        TURKISH        -> Locale("tr")
        UKRAINIAN      -> Locale("uk")
        ARABIC         -> Locale("ar")
        HEBREW         -> Locale("iw")
        URDU           -> Locale("ur")
        HINDI          -> Locale("hi")
        THAI           -> Locale("th")
        VIETNAMESE     -> Locale("vi")
        INDONESIAN     -> Locale("in")
        MALAY          -> Locale("ms")
        FILIPINO       -> Locale("fil")
        JAPANESE       -> Locale("ja")
        KOREAN         -> Locale("ko")
        CHINESE_TW     -> Locale("zh", "TW")
        else           -> Locale.ENGLISH
    }

    /** The language name Gemini understands (used in prompt instructions). */
    fun toGeminiName(language: String): String = when (language) {
        ENGLISH_GB     -> "British English"
        GERMAN         -> "German"
        SPANISH        -> "Spanish (Spain)"
        SPANISH_LATAM  -> "Latin American Spanish"
        FRENCH         -> "French"
        ITALIAN        -> "Italian"
        PORTUGUESE_BR  -> "Brazilian Portuguese"
        DUTCH          -> "Dutch"
        POLISH         -> "Polish"
        ROMANIAN       -> "Romanian"
        CZECH          -> "Czech"
        HUNGARIAN      -> "Hungarian"
        SWEDISH        -> "Swedish"
        DANISH         -> "Danish"
        FINNISH        -> "Finnish"
        NORWEGIAN      -> "Norwegian"
        GREEK          -> "Greek"
        TURKISH        -> "Turkish"
        UKRAINIAN      -> "Ukrainian"
        ARABIC         -> "Modern Standard Arabic"
        HEBREW         -> "Hebrew"
        URDU           -> "Urdu"
        HINDI          -> "Hindi"
        THAI           -> "Thai"
        VIETNAMESE     -> "Vietnamese"
        INDONESIAN     -> "Indonesian"
        MALAY          -> "Malay"
        FILIPINO       -> "Filipino"
        JAPANESE       -> "Japanese"
        KOREAN         -> "Korean"
        CHINESE_TW     -> "Traditional Chinese"
        else           -> "English"
    }

    /** ISO 639-1 code accepted by ML Kit's `TranslateLanguage` constants. */
    fun toBcp47(language: String): String = when (language) {
        ENGLISH_GB     -> "en"
        GERMAN         -> "de"
        SPANISH        -> "es"
        SPANISH_LATAM  -> "es"
        FRENCH         -> "fr"
        ITALIAN        -> "it"
        PORTUGUESE_BR  -> "pt"
        DUTCH          -> "nl"
        POLISH         -> "pl"
        ROMANIAN       -> "ro"
        CZECH          -> "cs"
        HUNGARIAN      -> "hu"
        SWEDISH        -> "sv"
        DANISH         -> "da"
        FINNISH        -> "fi"
        NORWEGIAN      -> "no"
        GREEK          -> "el"
        TURKISH        -> "tr"
        UKRAINIAN      -> "uk"
        ARABIC         -> "ar"
        HEBREW         -> "he"
        URDU           -> "ur"
        HINDI          -> "hi"
        THAI           -> "th"
        VIETNAMESE     -> "vi"
        INDONESIAN     -> "id"
        MALAY          -> "ms"
        FILIPINO       -> "fil"
        JAPANESE       -> "ja"
        KOREAN         -> "ko"
        CHINESE_TW     -> "zh"
        else           -> "en"
    }

    /** Best-fit language for the device's current locale; falls back to English. */
    fun fromSystemLocale(locale: Locale): String {
        val lang = locale.language.lowercase()
        val country = locale.country.uppercase()
        return when (lang) {
            "en"        -> if (country == "GB" || country == "IE" || country == "AU" || country == "NZ" || country == "ZA") ENGLISH_GB else ENGLISH
            "de"        -> GERMAN
            "es"        -> if (country.isNotEmpty() && country != "ES") SPANISH_LATAM else SPANISH
            "fr"        -> FRENCH
            "it"        -> ITALIAN
            "pt"        -> PORTUGUESE_BR
            "nl"        -> DUTCH
            "pl"        -> POLISH
            "ro"        -> ROMANIAN
            "cs"        -> CZECH
            "hu"        -> HUNGARIAN
            "sv"        -> SWEDISH
            "da"        -> DANISH
            "fi"        -> FINNISH
            "no", "nb", "nn" -> NORWEGIAN
            "el"        -> GREEK
            "tr"        -> TURKISH
            "uk"        -> UKRAINIAN
            "ar"        -> ARABIC
            "he", "iw"  -> HEBREW
            "ur"        -> URDU
            "hi"        -> HINDI
            "th"        -> THAI
            "vi"        -> VIETNAMESE
            "id", "in"  -> INDONESIAN
            "ms"        -> MALAY
            "fil", "tl" -> FILIPINO
            "ja"        -> JAPANESE
            "ko"        -> KOREAN
            "zh"        -> CHINESE_TW
            else        -> ENGLISH
        }
    }
}
