package com.ferousco.hydro.ui.i18n

import androidx.compose.runtime.compositionLocalOf

/** Languages Hydro speaks. The names are shown in their own language so anyone can find theirs. */
enum class AppLanguage(val code: String, val nativeName: String) {
    English("en", "English"),
    Pidgin("pcm", "Naija Pidgin"),
    Hausa("ha", "Hausa");

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: English
    }
}

/**
 * Every piece of text translated so far. Screens not listed here are still English; add a field here
 * and a value in each language to translate more. The Pidgin and Hausa text is a careful first pass
 * and should be read by native speakers before a public release.
 */
class Strings(
    // Tabs and map
    val tabMap: String, val tabTrends: String, val tabAsk: String, val tabAlerts: String,
    val searchPlaceholder: String,
    val walking: String, val running: String, val cycling: String, val outdoorWork: String,
    val commuting: String, val outdoors: String,
    // Now page
    val nearYou: String, val updated: String, val feelsLike: String, val coolestWindow: String,
    val heatIndex: String, val humidity: String, val wind: String, val uvIndex: String,
    val whatToDo: String, val tryAgain: String, val refresh: String,
    /** %s is the time the saved reading was taken. */
    val offlineBanner: String,
    /** Eight words from coldest to hottest. */
    val feelWords: List<String>,
    /** %s is a feel word in lower case. */
    val rightNow: String,
    val headlines: Map<String, String>,
    // Air quality and dust
    val airQuality: String, val modelledEstimate: String, val airFootnote: String,
    val airCategories: Map<String, String>,
    val dustTitleSeason: String, val dustTitleOther: String,
    val dustAdviceHeavy: String, val dustAdviceLight: String,
    // Trends
    val next24Hours: String, val howHotNear: String,
    /** %s is a place name. */
    val howHotIn: String,
    val heatThroughDay: String, val hottest: String, val coolest: String, val now: String,
    // Ask
    val askTitle: String, val askSubtitle: String, val askPlaceholder: String, val newChat: String,
    val askSuggestions: List<String>,
    // Settings
    val language: String, val languageNote: String
)

val EnglishStrings = Strings(
    tabMap = "Map", tabTrends = "Trends", tabAsk = "Ask", tabAlerts = "Alerts",
    searchPlaceholder = "Where to?",
    walking = "Walking", running = "Running", cycling = "Cycling", outdoorWork = "Outdoor work",
    commuting = "Commuting", outdoors = "Outdoors",
    nearYou = "Near you", updated = "Updated", feelsLike = "Feels like", coolestWindow = "Coolest window",
    heatIndex = "Heat index", humidity = "Humidity", wind = "Wind", uvIndex = "UV index",
    whatToDo = "What to do", tryAgain = "Try again", refresh = "Refresh conditions",
    offlineBanner = "No signal. Showing what we saw at %s.",
    feelWords = listOf("Freezing", "Cold", "Cool", "Mild", "Warm", "Hot", "Very hot", "Dangerously hot"),
    rightNow = "It's %s right now",
    headlines = emptyMap(),
    airQuality = "Air quality", modelledEstimate = "Modelled estimate",
    airFootnote = "Estimated from satellite and weather models, not a ground sensor. Nigeria has no " +
        "national air-monitoring network, so treat this as a guide.",
    airCategories = emptyMap(),
    dustTitleSeason = "Harmattan dust", dustTitleOther = "Dust in the air",
    dustAdviceHeavy = "Cover your nose and mouth outside, skip hard exercise, keep windows shut and drink water often.",
    dustAdviceLight = "Dust is up. Children, older people and anyone with asthma should limit time outside. Protect your eyes and lips.",
    next24Hours = "Next 24 hours", howHotNear = "How hot it will feel near you", howHotIn = "How hot it will feel in %s",
    heatThroughDay = "Heat through the day", hottest = "Hottest", coolest = "Coolest", now = "Now",
    askTitle = "Ask Hydro", askSubtitle = "Get answers based on today's heat, air and your plans.",
    askPlaceholder = "Ask about heat, air or your route", newChat = "New chat",
    askSuggestions = listOf(
        "Should I walk 12 km this afternoon?", "When is the coolest time today?",
        "What should I carry for a long walk?", "Is the air safe for a run right now?"
    ),
    language = "Language", languageNote = "Some parts of the app are still in English."
)

val PidginStrings = Strings(
    tabMap = "Map", tabTrends = "How e go be", tabAsk = "Ask", tabAlerts = "Alert",
    searchPlaceholder = "Where you dey go?",
    walking = "Waka", running = "Run", cycling = "Bicycle", outdoorWork = "Work outside",
    commuting = "Road to work", outdoors = "Outside",
    nearYou = "Where you dey", updated = "Updated", feelsLike = "E dey feel like", coolestWindow = "Cool time",
    heatIndex = "Heat level", humidity = "Humidity", wind = "Wind", uvIndex = "Sun strength (UV)",
    whatToDo = "Wetin to do", tryAgain = "Try again", refresh = "Refresh",
    offlineBanner = "No network. Na wetin we see for %s be dis.",
    feelWords = listOf("E dey freeze", "Cold", "Cool", "E dey okay", "Warm", "Hot", "Hot well well", "Hot wey dey dangerous"),
    rightNow = "Right now e dey %s",
    headlines = mapOf(
        "Avoid outdoor exertion" to "No do hard work for outside",
        "Limit outdoor exposure" to "No stay outside too long",
        "Heat builds later" to "Heat go increase later",
        "Protect against sun exposure" to "Cover yourself from sun",
        "Take heat precautions" to "Take care for di heat",
        "Refresh before relying on this reading" to "Refresh before you trust dis one"
    ),
    airQuality = "How di air be", modelledEstimate = "Na estimate",
    airFootnote = "Dem estimate am from satellite and weather model, e no be sensor for ground. Nigeria no get " +
        "national air-checking network, so use am as guide.",
    airCategories = mapOf(
        "Good" to "Good", "Moderate" to "Fair",
        "Unhealthy for sensitive groups" to "No good for sensitive people", "Unhealthy" to "No good",
        "Very unhealthy" to "E bad well well", "Hazardous" to "E dey dangerous"
    ),
    dustTitleSeason = "Harmattan dust", dustTitleOther = "Dust dey air",
    dustAdviceHeavy = "Cover your nose and mouth for outside, no do hard exercise, close window and drink water well well.",
    dustAdviceLight = "Dust don rise. Children, old people and people wey get asthma make dem no stay outside too long. Protect your eye and lip.",
    next24Hours = "Next 24 hours", howHotNear = "How hot e go feel near you", howHotIn = "How hot e go feel for %s",
    heatThroughDay = "Heat for di whole day", hottest = "Hottest", coolest = "Coolest", now = "Now",
    askTitle = "Ask Hydro", askSubtitle = "Get answer based on today heat, air and your plan.",
    askPlaceholder = "Ask about heat, air or your road", newChat = "New chat",
    askSuggestions = listOf(
        "Make I walk 12 km this afternoon?", "Which time today go cool pass?",
        "Wetin I go carry for long walk?", "Di air safe for me to run now?"
    ),
    language = "Language", languageNote = "Some parts still dey English."
)

val HausaStrings = Strings(
    tabMap = "Taswira", tabTrends = "Hasashe", tabAsk = "Tambaya", tabAlerts = "Gargadi",
    searchPlaceholder = "Ina za a je?",
    walking = "Tafiya a ƙafa", running = "Gudu", cycling = "Keke", outdoorWork = "Aiki a waje",
    commuting = "Zuwa aiki", outdoors = "A waje",
    nearYou = "Kusa da kai", updated = "An sabunta", feelsLike = "Yana ji kamar", coolestWindow = "Lokacin sanyi",
    heatIndex = "Matakin zafi", humidity = "Danshi", wind = "Iska", uvIndex = "Ƙarfin rana (UV)",
    whatToDo = "Abin da za a yi", tryAgain = "Sake gwadawa", refresh = "Sabunta",
    offlineBanner = "Babu sigina. Abin da muka gani da ƙarfe %s ne.",
    feelWords = listOf("Sanyi mai tsanani", "Sanyi", "Sanyi-sanyi", "Daidai", "Dumi", "Zafi", "Zafi sosai", "Zafi mai haɗari"),
    rightNow = "A yanzu: %s",
    headlines = mapOf(
        "Avoid outdoor exertion" to "A guji aiki mai wahala a waje",
        "Limit outdoor exposure" to "A rage zama a waje",
        "Heat builds later" to "Zafi zai ƙaru daga baya",
        "Protect against sun exposure" to "A kare kai daga rana",
        "Take heat precautions" to "A kula da zafi",
        "Refresh before relying on this reading" to "A sabunta kafin a dogara da wannan"
    ),
    airQuality = "Ingancin iska", modelledEstimate = "Kimantawa ce",
    airFootnote = "Kimantawa ce daga tauraron ɗan adam da samfurin yanayi, ba na'urar ƙasa ba. Najeriya ba ta da " +
        "tsarin sa ido kan iska na ƙasa, don haka a ɗauke ta a matsayin jagora.",
    airCategories = mapOf(
        "Good" to "Mai kyau", "Moderate" to "Matsakaici",
        "Unhealthy for sensitive groups" to "Ba shi da kyau ga masu rauni", "Unhealthy" to "Ba shi da kyau",
        "Very unhealthy" to "Mara kyau sosai", "Hazardous" to "Mai haɗari"
    ),
    dustTitleSeason = "Ƙurar Harmattan", dustTitleOther = "Ƙura a iska",
    dustAdviceHeavy = "A rufe hanci da baki a waje, a guji motsa jiki mai nauyi, a rufe tagogi, a sha ruwa akai-akai.",
    dustAdviceLight = "Ƙura ta ƙaru. Yara, tsofaffi da masu asma su rage zama a waje. A kare idanu da leɓɓa.",
    next24Hours = "Awa 24 masu zuwa", howHotNear = "Yadda zafi zai ji kusa da kai", howHotIn = "Yadda zafi zai ji a %s",
    heatThroughDay = "Zafi cikin yini", hottest = "Mafi zafi", coolest = "Mafi sanyi", now = "Yanzu",
    askTitle = "Tambayi Hydro", askSubtitle = "Samu amsoshi bisa zafin yau, iska da shirinka.",
    askPlaceholder = "Tambaya game da zafi, iska ko hanyarka", newChat = "Sabuwar tattaunawa",
    askSuggestions = listOf(
        "Zan yi tafiya kilomita 12 yau da rana?", "Yaushe ne lokacin da ya fi sanyi yau?",
        "Me zan ɗauka don doguwar tafiya?", "Iska lafiya ce don gudu yanzu?"
    ),
    language = "Harshe", languageNote = "Wasu sassa har yanzu Turanci ne."
)

fun stringsFor(language: AppLanguage): Strings = when (language) {
    AppLanguage.English -> EnglishStrings
    AppLanguage.Pidgin -> PidginStrings
    AppLanguage.Hausa -> HausaStrings
}

/** The strings for the language the person chose; English until a screen provides another. */
val LocalStrings = compositionLocalOf { EnglishStrings }
