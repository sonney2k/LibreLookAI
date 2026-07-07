package com.librelookai.wardrobe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.librelookai.core.designsystem.R
import com.librelookai.gemini.normalize
import com.librelookai.gemini.normalizeAesthetic
import com.librelookai.gemini.normalizeColor
import com.librelookai.gemini.normalizeEnumTag
import com.librelookai.gemini.normalizeMaterial
import com.librelookai.gemini.normalizePattern
import com.librelookai.gemini.normalizeType

enum class SortOption { DATE_DESC, DATE_ASC, POPULARITY, TYPE, CATEGORY }

@Composable
fun SortOption.displayLabel(): String = when (this) {
    SortOption.DATE_DESC  -> stringResource(R.string.wardrobe_sort_newest)
    SortOption.DATE_ASC   -> stringResource(R.string.wardrobe_sort_oldest)
    SortOption.POPULARITY -> stringResource(R.string.wardrobe_sort_most_worn)
    SortOption.TYPE       -> stringResource(R.string.wardrobe_sort_type)
    SortOption.CATEGORY   -> stringResource(R.string.wardrobe_sort_category)
}

data class TagCategory(val label: String, val tags: List<String>)

@Composable
fun tagCategoryDisplayLabel(key: String): String = when (key) {
    "Type"        -> stringResource(R.string.tag_type)
    "Category"    -> stringResource(R.string.tag_category)
    "Uses"        -> stringResource(R.string.tag_uses)
    "Colors"      -> stringResource(R.string.tag_colors)
    "Seasonality" -> stringResource(R.string.tag_seasonality)
    "Aesthetic"   -> stringResource(R.string.tag_aesthetic)
    "Fit"         -> stringResource(R.string.tag_fit)
    "Material"    -> stringResource(R.string.tag_material)
    "Pattern"     -> stringResource(R.string.tag_pattern)
    else          -> key
}

/** Returns the string-resource id for a canonical English tag value, or null when unmapped.
 *  Shared by the Composable display path and the non-Composable search path. */
fun tagValueResId(canonical: String): Int? = when (canonical.lowercase()) {
    "casual" -> R.string.tag_val_casual
    "formal" -> R.string.tag_val_formal
    "business" -> R.string.tag_val_business
    "sport" -> R.string.tag_val_sport
    "outdoor" -> R.string.tag_val_outdoor
    "beach" -> R.string.tag_val_beach
    "evening" -> R.string.tag_val_evening
    "spring" -> R.string.tag_val_spring
    "summer" -> R.string.tag_val_summer
    "fall" -> R.string.tag_val_fall
    "winter" -> R.string.tag_val_winter
    "minimalist" -> R.string.tag_val_minimalist
    "streetwear" -> R.string.tag_val_streetwear
    "preppy" -> R.string.tag_val_preppy
    "bohemian" -> R.string.tag_val_bohemian
    "classic" -> R.string.tag_val_classic
    "sporty" -> R.string.tag_val_sporty
    "romantic" -> R.string.tag_val_romantic
    "edgy" -> R.string.tag_val_edgy
    "business-casual" -> R.string.tag_val_business_casual
    "luxury" -> R.string.tag_val_luxury
    "slim" -> R.string.tag_val_slim
    "regular" -> R.string.tag_val_regular
    "relaxed" -> R.string.tag_val_relaxed
    "oversized" -> R.string.tag_val_oversized
    "tailored" -> R.string.tag_val_tailored
    "cotton" -> R.string.tag_val_cotton
    "denim" -> R.string.tag_val_denim
    "wool" -> R.string.tag_val_wool
    "leather" -> R.string.tag_val_leather
    "polyester" -> R.string.tag_val_polyester
    "linen" -> R.string.tag_val_linen
    "silk" -> R.string.tag_val_silk
    "knit" -> R.string.tag_val_knit
    "solid" -> R.string.tag_val_solid
    "stripes" -> R.string.tag_val_stripes
    "plaid" -> R.string.tag_val_plaid
    "floral" -> R.string.tag_val_floral
    "geometric" -> R.string.tag_val_geometric
    "animal-print" -> R.string.tag_val_animal_print
    "graphic" -> R.string.tag_val_graphic
    "camo" -> R.string.tag_val_camo
    "abstract" -> R.string.tag_val_abstract
    "tops" -> R.string.tag_val_tops
    "bottoms" -> R.string.tag_val_bottoms
    "outerwear" -> R.string.tag_val_outerwear
    "footwear" -> R.string.tag_val_footwear
    "accessories" -> R.string.tag_val_accessories
    "dress" -> R.string.tag_val_dress
    "suit" -> R.string.tag_val_suit
    "jumpsuit" -> R.string.tag_val_jumpsuit
    "black" -> R.string.tag_val_black
    "white" -> R.string.tag_val_white
    "grey" -> R.string.tag_val_grey
    "gray" -> R.string.tag_val_gray
    "charcoal" -> R.string.tag_val_charcoal
    "brown" -> R.string.tag_val_brown
    "beige" -> R.string.tag_val_beige
    "cream" -> R.string.tag_val_cream
    "ivory" -> R.string.tag_val_ivory
    "tan" -> R.string.tag_val_tan
    "camel" -> R.string.tag_val_camel
    "red" -> R.string.tag_val_red
    "burgundy" -> R.string.tag_val_burgundy
    "wine" -> R.string.tag_val_wine
    "coral" -> R.string.tag_val_coral
    "pink" -> R.string.tag_val_pink
    "blush" -> R.string.tag_val_blush
    "magenta" -> R.string.tag_val_magenta
    "fuchsia" -> R.string.tag_val_fuchsia
    "orange" -> R.string.tag_val_orange
    "rust" -> R.string.tag_val_rust
    "yellow" -> R.string.tag_val_yellow
    "mustard" -> R.string.tag_val_mustard
    "gold" -> R.string.tag_val_gold
    "green" -> R.string.tag_val_green
    "olive" -> R.string.tag_val_olive
    "khaki" -> R.string.tag_val_khaki
    "sage" -> R.string.tag_val_sage
    "mint" -> R.string.tag_val_mint
    "emerald" -> R.string.tag_val_emerald
    "forest", "forest green" -> R.string.tag_val_forest
    "teal" -> R.string.tag_val_teal
    "blue" -> R.string.tag_val_blue
    "navy" -> R.string.tag_val_navy
    "cobalt" -> R.string.tag_val_cobalt
    "sky", "sky blue" -> R.string.tag_val_sky
    "denim blue" -> R.string.tag_val_denim_blue
    "purple" -> R.string.tag_val_purple
    "lavender" -> R.string.tag_val_lavender
    "violet" -> R.string.tag_val_violet
    "lilac" -> R.string.tag_val_lilac
    "silver" -> R.string.tag_val_silver
    "multicolor", "multi-color", "multicolour" -> R.string.tag_val_multicolor
    "printed" -> R.string.tag_val_printed
    else -> null
}

/** Maps a stored English tag value to its localized display string. Unknown values pass through. */
@Composable
fun String.localizedTagValue(): String =
    tagValueResId(this)?.let { stringResource(it) } ?: this

fun List<DriveImage>.tagCategories(): List<TagCategory> {
    fun collect(vararg lists: List<String>) = lists.flatMap { it }.toSortedSet().toList()
    return listOfNotNull(
        TagCategory("Type",        mapNotNull { it.tags?.type?.normalizeType() }.filter { it.isNotEmpty() }.toSortedSet().toList()).takeIf { it.tags.isNotEmpty() },
        TagCategory("Category",    mapNotNull { it.tags?.category?.lowercase()?.trim() }.filter { it.isNotEmpty() }.toSortedSet().toList()).takeIf { it.tags.isNotEmpty() },
        TagCategory("Uses",        collect(flatMap { it.tags?.uses?.map { v -> v.normalizeEnumTag() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Colors",      flatMap { it.tags?.colors?.map { v -> v.normalizeColor() } ?: emptyList() }.distinct().sortedByColorOrder()).takeIf { it.tags.isNotEmpty() },
        TagCategory("Seasonality", collect(flatMap { it.tags?.seasonality?.map { v -> v.normalizeEnumTag() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Aesthetic",   collect(flatMap { it.tags?.aesthetic?.map { v -> v.normalizeAesthetic() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Fit",         collect(flatMap { it.tags?.fit?.map { v -> v.normalizeEnumTag() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Material",    collect(flatMap { it.tags?.material?.map { v -> v.normalizeMaterial() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
        TagCategory("Pattern",     collect(flatMap { it.tags?.pattern?.map { v -> v.normalizePattern() } ?: emptyList() })).takeIf { it.tags.isNotEmpty() },
    )
}

fun DriveImage.allTagStrings() = buildSet {
    tags?.normalize()?.let { t ->
        if (t.type.isNotEmpty()) add(t.type)
        if (t.category.isNotEmpty()) add(t.category)
        addAll(t.uses); addAll(t.colors); addAll(t.seasonality)
        addAll(t.aesthetic); addAll(t.fit); addAll(t.material); addAll(t.pattern)
    }
}

data class TagCount(val value: String, val count: Int)
data class TagCategoryCounts(val label: String, val counts: List<TagCount>)

fun List<DriveImage>.tagCategoryCounts(): List<TagCategoryCounts> {
    fun counted(values: List<String>): List<TagCount> =
        values.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .map { TagCount(it.key, it.value) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.value })

    return listOfNotNull(
        counted(mapNotNull { it.tags?.type?.normalizeType() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Type", it) },
        counted(mapNotNull { it.tags?.category?.lowercase()?.trim() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Category", it) },
        counted(flatMap { it.tags?.uses?.map { v -> v.normalizeEnumTag() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Uses", it) },
        counted(flatMap { it.tags?.colors?.map { v -> v.normalizeColor() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Colors", it) },
        counted(flatMap { it.tags?.seasonality?.map { v -> v.normalizeEnumTag() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Seasonality", it) },
        counted(flatMap { it.tags?.aesthetic?.map { v -> v.normalizeAesthetic() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Aesthetic", it) },
        counted(flatMap { it.tags?.fit?.map { v -> v.normalizeEnumTag() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Fit", it) },
        counted(flatMap { it.tags?.material?.map { v -> v.normalizeMaterial() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Material", it) },
        counted(flatMap { it.tags?.pattern?.map { v -> v.normalizePattern() } ?: emptyList() })
            .takeIf { it.isNotEmpty() }?.let { TagCategoryCounts("Pattern", it) },
    )
}

fun DriveImage.tagStringsForCategory(categoryLabel: String): Set<String> {
    val t = tags?.normalize() ?: return emptySet()
    return when (categoryLabel) {
        "Type"        -> if (t.type.isNotEmpty()) setOf(t.type) else emptySet()
        "Category"    -> if (t.category.isNotEmpty()) setOf(t.category) else emptySet()
        "Uses"        -> t.uses.toSet()
        "Colors"      -> t.colors.toSet()
        "Seasonality" -> t.seasonality.toSet()
        "Aesthetic"   -> t.aesthetic.toSet()
        "Fit"         -> t.fit.toSet()
        "Material"    -> t.material.toSet()
        "Pattern"     -> t.pattern.toSet()
        else          -> emptySet()
    }
}

