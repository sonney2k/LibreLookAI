package com.librelookai

import com.google.gson.annotations.SerializedName

/** Result of a forecast or historical-climate fetch. */
data class ForecastResult(
    val resolvedName: String,
    val forecasts: List<DayForecast>,
    /** True when the data comes from the historical archive rather than the live forecast. */
    val isHistorical: Boolean,
    /** The actual year used when historical (may differ from the trip year). */
    val referenceYear: Int? = null,
)

data class DayForecast(
    val date: String,       // ISO "2026-04-10"
    val minTempC: Float,
    val maxTempC: Float,
    val weatherCode: Int,
)

data class PackingOutfit(
    val occasion: String = "",
    val itemIds: List<String> = emptyList(),
    val description: String = "",
)

data class PackingList(
    val destination: String = "",
    val days: Int = 0,
    val outfits: List<PackingOutfit> = emptyList(),
    @SerializedName("extraItems") val extraItems: List<String> = emptyList(),
    val reason: String = "",
)
