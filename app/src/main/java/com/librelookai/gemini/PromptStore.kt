package com.librelookai.gemini
import android.content.Context
import com.librelookai.R

/**
 * User-editable prompts for each Gemini call. Stored device-local in SharedPreferences.
 *
 * For **static** prompts (background removal, classify) the stored string is the full
 * prompt sent to Gemini — placeholders like `{LANGUAGE}` are substituted at call time.
 *
 * For **dynamic** prompts (style prediction, composition, packing, etc.) the stored
 * string is the role + instructions preamble. The builder inserts the dynamic data
 * blocks (wardrobe JSON, weather, user profile, …) and the fixed JSON response schema
 * around it, so response parsing always works even after edits.
 */
enum class PromptKey(
    val titleRes: Int,
    val descRes: Int,
    val default: String,
) {
    BG_REMOVAL(
        R.string.ai_prompt_bg_title,
        R.string.ai_prompt_bg_desc,
        """
        Extract the single primary clothing item from the image as a clean catalog-style product photo. The item must be shown in its entirety: every part fully visible inside the frame with comfortable margin on all sides — no cropping of sleeves, hems, collars, straps, laces, or any extremity. For garments with sleeves (t-shirts, shirts, jackets, sweaters, coats, dresses), lay the sleeves out flat and fully extended so both arms are completely shown end-to-end. For items that naturally come as a pair (shoes, boots, sandals, sneakers, gloves, earrings), always render BOTH pieces side by side, even if only one is visible in the source photo — reconstruct the missing piece as a mirrored counterpart. If the source photo clearly identifies a known product, prefer rendering it as a clean studio catalog image of that product (flat-lay or ghost-mannequin style) rather than copying photographic artifacts, hangers, mannequins, hands, or background clutter. Center the item, oriented upright (portrait). Place it on a pure, solid neon green background (Hex #00FF00). Macro product photography, soft even studio lighting, sharp focus, no text, no UI elements, no shadows, no gradients, no checkerboard patterns, no phones, no apps, no website interfaces.
        """.trimIndent(),
    ),
    CLASSIFY(
        R.string.ai_prompt_classify_title,
        R.string.ai_prompt_classify_desc,
        """
        Analyze this clothing item and return ONLY a JSON object (no markdown, no explanation) with these fields: "label" (a short descriptive name for the item in {LANGUAGE}, e.g. "Navy Chinos", "White Oxford Shirt", "Black Puffer Jacket"), "type" (concise item name; prefer terms from: T-shirt, Long-sleeve shirt, Polo shirt, Oxford shirt, Button-up shirt, Blouse, Tank top, Crop top, Sweater, Hoodie, Cardigan, Vest, Jacket, Blazer, Coat, Puffer jacket, Trench coat, Jeans, Chinos, Trousers, Shorts, Skirt, Dress, Jumpsuit, Suit jacket, Suit trousers, Sneakers, Boots, Loafers, Sandals, Heels, Belt, Bag, Hat, Scarf, Gloves, Sunglasses — use the same concise style for unlisted items), "category" (one of: tops, bottoms, outerwear, footwear, accessories, dress, suit), "uses" (array from: casual, formal, business, sport, outdoor, beach, evening), "colors" (array of main colors, lowercase English; choose from: black, white, cream, beige, tan, brown, khaki, camel, rust, orange, peach, coral, red, burgundy, pink, magenta, purple, lavender, lilac, blue, navy, sky, denim blue, teal, mint, green, olive, forest, yellow, mustard, gold, silver, gray, charcoal, multicolor — use "gray" not "grey"), "seasonality" (array from: spring, summer, fall, winter), "aesthetic" (array from: minimalist, streetwear, preppy, bohemian, classic, sporty, romantic, edgy, business-casual, luxury), "fit" (array from: slim, regular, relaxed, oversized, tailored), "material" (array of detected/inferred materials, e.g. cotton, denim, wool, leather, polyester, linen, silk, knit), "pattern" (array from: solid, stripes, plaid, floral, geometric, animal-print, graphic, camo, abstract). Use empty arrays for fields that cannot be determined.
        """.trimIndent(),
    ),
    PREDICTION(
        R.string.ai_prompt_prediction_title,
        R.string.ai_prompt_prediction_desc,
        """
        You are a personal fashion stylist AI. Choose exactly ONE existing style for the user to wear today.

        ## Instructions
        Pick the single best style ID from the styles list that fits:
        1. The current weather (temperature, conditions)
        2. The trending topics and cultural context of the user's location
        3. The user's personal preferences and profile
        4. The urban/rural character of the location
        """.trimIndent(),
    ),
    COMPOSITION(
        R.string.ai_prompt_composition_title,
        R.string.ai_prompt_composition_desc,
        """
        You are a personal fashion stylist AI. Compose a brand-new outfit by selecting items from the user's wardrobe.

        ## Instructions
        Select 4–6 item IDs from the wardrobe that form a complete, well-coordinated outfit. If required items are specified, build a cohesive outfit around them and add complementary pieces.

        Every outfit MUST include all four of these layers (pick exactly one item per layer, unless the wardrobe has none):
          • Base top    — t-shirt, shirt, blouse, or similar inner layer
          • Bottom      — trousers, jeans, shorts, skirt, or similar
          • Shoes       — any footwear
          • Outer layer — jacket, coat, blazer, hoodie, or cardigan (add this OVER the base top; do NOT substitute it for a base top or shoes)

        IMPORTANT outfit-structure rules:
        - Outerwear (jacket, coat, blazer, hoodie, cardigan) is an ADDITIONAL layer on top of a base top — never pick outerwear instead of a base top
        - Never build an outfit from only outerwear + shoes, or only outerwear + t-shirt with no bottom
        - Do NOT include two items of the same layer (e.g. two pairs of trousers, two jackets, two t-shirts)

        Choose items that:
        1. Are appropriate for the current weather
        2. Reflect the trending topics and cultural context
        3. Match the user's personal preferences
        4. Work together visually (complementary colors, consistent style category)
        5. Suit the urban/rural character of the location

        Also propose:
        - A short, evocative name for the outfit ("name")
        - A 1-2 sentence style description suitable as a caption ("description")
        """.trimIndent(),
    ),
    COMPOSER(
        R.string.ai_prompt_composer_title,
        R.string.ai_prompt_composer_desc,
        """
        You are a personal fashion stylist AI. Compose a cohesive outfit from the user's wardrobe that MUST include the required items below and meets the target layer counts.

        ## Instructions
        1. The itemIds list MUST include every required item id verbatim.
        2. Add complementary items from the wardrobe to meet the target composition counts as closely as possible.
        3. Items must work together visually (colors, style, formality).
        4. Respect the weather and desired vibe.
        5. Do not include two items of the same layer unless the target count requires it.
        """.trimIndent(),
    ),
    ALTERNATIVES(
        R.string.ai_prompt_alternatives_title,
        R.string.ai_prompt_alternatives_desc,
        """
        You are a personal fashion stylist AI. Suggest up to 10 alternative wardrobe items that could replace a specific item in an outfit.

        ## Instructions
        Find up to 10 items from the available wardrobe that would work as a good replacement.
        The replacement should:
        1. Serve the same general purpose/category as the item being replaced
        2. Coordinate well with the other outfit items (colors, style, aesthetic)
        3. Be suitable for the same occasions as the original item
        """.trimIndent(),
    ),
    PACKING(
        R.string.ai_prompt_packing_title,
        R.string.ai_prompt_packing_desc,
        """
        You are a travel packing assistant. Create a packing list from the user's wardrobe for their trip.

        ## Instructions
        Group the days into 2–5 occasion groups based on weather and activity type (e.g. 'Days 1–2: Sightseeing', 'Evenings', 'Rainy days').
        For each group, select item IDs from the wardrobe that form a complete, weather-appropriate outfit.
        Aim to reuse items across groups to keep the overall pack light.
        Also list any items the user should pack that are NOT in their wardrobe (e.g. umbrella, adapter, sunscreen).
        """.trimIndent(),
    ),
    GAP(
        R.string.ai_prompt_gap_title,
        R.string.ai_prompt_gap_desc,
        """
        You are a personal stylist and wardrobe analyst.

        ## Task
        Analyze the wardrobe above. Identify the top 3 missing foundational pieces that would:
        1. Unlock the highest number of new complete outfit combinations
        2. Complement the user's existing color palette and aesthetic
        3. Fill genuine category gaps (e.g. missing footwear type, missing layering piece)

        For each suggestion, estimate how many additional complete outfits it would enable.
        The 'reason' field must be phrased as a concrete, user-facing observation, e.g.:
          "You have 4 formal shirts and 3 blazers, but no formal footwear to complete those looks."
        """.trimIndent(),
    ),
}

object PromptStore {
    private const val PREFS_NAME = "librelookai_prompts"

    fun get(context: Context, key: PromptKey): String {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key.name, null)
        return if (stored.isNullOrBlank()) key.default else stored
    }

    fun set(context: Context, key: PromptKey, value: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == key.default.trim()) {
            prefs.remove(key.name)
        } else {
            prefs.putString(key.name, trimmed)
        }
        prefs.apply()
    }

    fun reset(context: Context, key: PromptKey) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(key.name).apply()
    }

    fun resetAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun isOverridden(context: Context, key: PromptKey): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(key.name)
}
