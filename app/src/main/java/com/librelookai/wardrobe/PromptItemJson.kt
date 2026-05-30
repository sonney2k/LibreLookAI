package com.librelookai.wardrobe

import com.librelookai.settings.AiConsiderations

/**
 * Single source of truth for encoding a wardrobe item into the JSON shape every AI prompt feeds
 * the model (outfit prediction, the composer, gap analysis, replacements, travel packing).
 *
 * `id`, `type`, `category` and (optionally) `name` are always present; the remaining tag
 * dimensions are gated by the user's [AiConsiderations.itemTags] "expert" selection so power users
 * can trim the payload (cheaper, more focused) or keep the full nine-field set (default).
 */
fun DriveImage.toPromptJson(c: AiConsiderations, includeName: Boolean = true): String {
    val nameField = if (includeName) """"name":"$name",""" else ""
    val t = tags
    if (t == null) return """{"id":"$driveId",${nameField}"tags":null}"""

    fun arr(values: List<String>) = values.joinToString(",", "[", "]") { "\"$it\"" }

    val fields = buildList {
        add(""""type":"${t.type}"""")
        add(""""category":"${t.category}"""")
        if (c.includesItemTag("uses")) add(""""uses":${arr(t.uses)}""")
        if (c.includesItemTag("colors")) add(""""colors":${arr(t.colors)}""")
        if (c.includesItemTag("seasonality")) add(""""seasonality":${arr(t.seasonality)}""")
        if (c.includesItemTag("aesthetic")) add(""""aesthetic":${arr(t.aesthetic)}""")
        if (c.includesItemTag("fit")) add(""""fit":${arr(t.fit)}""")
        if (c.includesItemTag("material")) add(""""material":${arr(t.material)}""")
        if (c.includesItemTag("pattern")) add(""""pattern":${arr(t.pattern)}""")
    }
    return """{"id":"$driveId",${nameField}"tags":{${fields.joinToString(",")}}}"""
}
