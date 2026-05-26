package com.librelookai.wardrobe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.librelookai.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * JVM-side Compose UI flow test (runs under `./gradlew testDebugUnitTest`, no device).
 *
 * Exercises the wardrobe tag-filter flow end to end at the composable level:
 * open a category chip → toggle tag values → verify the hoisted callback fires and the
 * chip's active-count badge updates → clear all filters.
 *
 * `TagFilterBar` is `internal`, so this test lives in the same package to reach it without
 * widening visibility. State is hoisted into the test so we observe the real recomposition,
 * exactly as the host screen would.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagFilterBarTest {

    @get:Rule
    val rule = createComposeRule()

    // Resolve the localized "Clear" label from resources rather than hardcoding display text.
    private val clearLabel: String =
        RuntimeEnvironment.getApplication().getString(R.string.action_clear)

    @Test
    fun selectingAndClearingTags_drivesCallbackAndChipBadge() {
        var emitted: Map<String, Set<String>> = emptyMap()

        rule.setContent {
            var selected by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
            // "Brand" is an unmapped category label → rendered verbatim; tag values "nike"/"adidas"
            // are unmapped too, so assertions stay locale-independent.
            TagFilterBar(
                tagCategories = listOf(TagCategory("Brand", listOf("nike", "adidas"))),
                selectedTags = selected,
                onTagsChanged = { selected = it; emitted = it },
            )
        }

        // Initial state: bare chip, no active count, no Clear action.
        rule.onNodeWithText("Brand").assertIsDisplayed()
        rule.onNodeWithText(clearLabel).assertDoesNotExist()

        // Open the dropdown and toggle both values on.
        rule.onNodeWithText("Brand").performClick()
        rule.onNodeWithText("nike").performClick()
        rule.onNodeWithText("adidas").performClick()

        rule.runOnIdle {
            assertEquals(setOf("nike", "adidas"), emitted["Brand"])
        }

        // Chip badge reflects the active count and a Clear action has appeared.
        rule.onNodeWithText("Brand (2)").assertIsDisplayed()
        rule.onNodeWithText(clearLabel).assertIsDisplayed()

        // Clearing wipes every selection and removes the Clear action.
        rule.onNodeWithText(clearLabel).performClick()
        rule.runOnIdle {
            assertTrue(emitted.values.all { it.isEmpty() })
        }
        rule.onNodeWithText("Brand").assertIsDisplayed()
        rule.onNodeWithText(clearLabel).assertDoesNotExist()
    }
}
