# Trips — design & implementation plan

A "Trip" becomes a first-class entity that aggregates the outputs of the Travel Planner: N real `Outfit` objects + extras + per-day forecast + the AI considerations / vibes / goal used to generate it. The user navigates to a **Trip viewer** to browse the trip, edit individual day outfits (via the existing OutfitComposer, now trip-aware), bulk-refine all outfits with one AI call, and delete the trip (with or without cascading the outfits).

## User-facing changes
- **Travel tab** stops showing a flat list of travel-tagged outfits as the primary surface. Instead, it lists **Trip cards** at the top (destination, dates, day-count, a thumbnail row from the first few outfit items). Below that, an "Other travel outfits" section catches any travel-tagged Outfit that has no `tripId` (legacy data; eventually empty for new users).
- **Planner generate** auto-creates a `Trip` + N `Outfit`s and navigates the user straight into the **Trip viewer**. No inline packing-list preview anymore.
- **Trip viewer** (fullscreen, follows the existing `plannerMode: Boolean` overlay pattern, no Jetpack Navigation):
  - Header: editable trip name, destination, date range, forecast strip, delete (with confirmation that asks "Delete trip + outfits" or "Keep outfits").
  - Per-day outfit cards (reusing the existing `TravelOutfitListCard` look), each captioned "Day N — {weather}". Tap → opens the existing OutfitComposer pre-seeded with trip context.
  - Extras card (existing `ExtraItemsCard`).
  - Bulk-refine bar at the bottom: text field + preset chips ("brighter clothes", "pack lighter", "more formal evenings", "skip jacket") + AI button. One Gemini call updates every outfit in the trip in one shot.
- **OutfitComposer**, when opened from a trip-day card, knows it is editing day N of trip T:
  - Header shows "Day N of {tripName}".
  - Weather mode is pre-seeded to MANUAL from the day's forecast.
  - AI refine/alternatives prompts include a TripContext block (trip name, goal, vibes, considerations, day index, day forecast).
  - Saving updates the existing Outfit; `tripId` is preserved; `trip.outfitIds[dayIndex]` stays correct.

## Scoping decisions (confirmed)
- **Storage**: Drive folder `LibreLookAI/_trips/`, one JSON file per trip (`{tripId}.json`). Easier per-trip CRUD than a monolithic index.
- **Generate flow**: auto-create the trip and navigate immediately.
- **Delete**: confirmation dialog with two affirmative options ("Delete trip + outfits" / "Keep outfits").
- **Bulk refine**: single Gemini call, returns updated `slotAssignments` for every outfit in the trip.

## Data model
**New** `data/model/Trip.kt`:
```kotlin
data class Trip(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val destination: String = "",
    val startDate: String = "",                       // ISO yyyy-MM-dd
    val days: Int = 0,
    val forecast: List<DayForecast> = emptyList(),
    val isHistoricalForecast: Boolean = false,
    val historicalReferenceYear: Int? = null,
    val considerations: AiConsiderations = AiConsiderations(),
    val vibes: Set<String> = emptySet(),
    val goal: String = "",
    val outfitIds: List<String> = emptyList(),        // ordered by day
    val extraItems: List<String> = emptyList(),
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
```

**Modified** `data/model/Outfit.kt`:
- Add `val tripId: String? = null` (serialized, no `@Transient`).

## Persistence
**Modified** `data/drive/DriveRepository.kt`:
- `const val TRIPS_FOLDER_NAME = "_trips"`
- Add `_trips` to `NON_CLOSET_SUBFOLDER_NAMES` so it doesn't appear as a location.
- `suspend fun getOrCreateTripsFolder(rootFolderId: String): String`
- `suspend fun listTripFiles(tripsFolderId: String): List<DriveFileMeta>` (id + name)
- `suspend fun loadTripJson(fileId: String): String?`
- `suspend fun saveTripJson(tripsFolderId: String, tripId: String, json: String): String` — upsert; filename is `{tripId}.json`; returns Drive file ID.
- `suspend fun deleteTripJson(fileId: String)`

Trips are global (not per-closet). Their referenced `Outfit`s still live in a closet's `_outfits.json`; we save them to the **active** closet at trip-creation time.

## ViewModels
**New** `travel/TripsViewModel.kt`:
- `state.trips: List<Trip>`, `state.isLoading`, `state.error`
- `state.tripFolderId: String?` cached
- `fun loadTrips(rootFolderId)` — called from MainActivity on Drive ready.
- `fun createTripFromPlan(packingList: PackingList, planner: TravelUiState, prefs: UserPreferences, activeLocationFolderId: String, outfitsViewModel: OutfitsViewModel): String` — converts `PackingOutfit`s to real `Outfit`s with `tripId` set, persists outfits via `OutfitsViewModel.addOutfits(...)` (new helper) into the active closet, writes the trip JSON, returns the new tripId. Emits a `navigateTo: SharedFlow<String>` (the new tripId) so the screen can route into the viewer.
- `fun renameTrip(tripId, newName)`
- `fun deleteTrip(tripId, cascadeOutfits: Boolean, outfitsViewModel: OutfitsViewModel)`
- `fun replaceOutfitAtDay(tripId, dayIndex, newOutfitId)` — when composer save reassigns
- `fun refineAllOutfits(tripId, instruction, prefs, images, outfitsViewModel)` — one Gemini call (`GeminiRepository.refineTripOutfits(...)`), apply updated `itemIds` to each Outfit, persist.

**Modified** `outfit/OutfitsViewModel.kt`:
- `openComposer(...)` gains `tripContext: TripContext? = null`. Stored in `OutfitsUiState.composerTripContext`.
  ```kotlin
  data class TripContext(
      val tripId: String,
      val tripName: String,
      val dayIndex: Int,
      val dayForecast: DayForecast?,
      val considerations: AiConsiderations,
      val vibes: Set<String>,
      val goal: String,
  )
  ```
- When `tripContext != null`:
  - Pre-seed `composerWeatherMode = MANUAL`, derive `composerManualSeason / composerManualTempC / composerManualPrecip` from `dayForecast` + `startDate`.
  - Pre-seed `composerVibes = tripContext.vibes`.
  - Composer save (existing path) preserves `tripId` on the produced `Outfit`.
  - AI refine/alternatives prompt builders include a TripContext block.
- New helper `fun addOutfits(outfits: List<Outfit>, folderId: String)` to bulk-write outfits (used by `TripsViewModel.createTripFromPlan`).

**Modified** `travel/TravelViewModel.kt`:
- `generate()` success no longer just stashes a `PackingList` in state. Instead it calls a callback supplied by the screen which delegates to `TripsViewModel.createTripFromPlan`, then emits `navigateToTrip(tripId)`. Inline packing-list rendering inside `TravelPlannerContent` is removed.

## UI
**Modified** `travel/TravelScreen.kt`:
- `TravelOutfitsView` becomes a list of Trip cards (new `TripCard`) + an "Other travel outfits" section for orphan travel-tagged outfits (current `TravelOutfitListCard`).
- Tap on a Trip card → opens Trip viewer (state lifted into MainActivity).
- `TravelPlannerContent`: remove inline `PackingOutfitCard`/Extras/Refinement rendering. After generate, MainActivity flips state to open the Trip viewer for the new trip.

**New** `travel/TripViewerScreen.kt`:
- Composed of: editable header, forecast strip, day cards, extras card, bulk-refine bar, delete button.
- Day-card tap → `stylesViewModel.openComposer(..., tripContext = …)`.
- Delete → AlertDialog with two buttons (cascade vs. keep).

**Modified** `MainActivity.kt`:
- Instantiate `TripsViewModel`; call `loadTrips` on Drive ready (mirror `ShoppingClosetViewModel.loadItems()`).
- Add `var tripViewerTripId: String? by rememberSaveable { mutableStateOf(null) }`.
- Pipe state into `TravelScreen`. Hide nav chrome when `tripViewerTripId != null` (same trick as `travelPlannerMode`).
- Subscribe to `tripsViewModel.navigateToTrip` to flip the state when the planner generates a new trip.

## Gemini
**Modified** `gemini/PromptStore.kt`:
- Add `tripContextBlock(tripContext)` helper. Inject into the existing composer refine/alternatives prompt builders when present.

**Modified** `gemini/GeminiRepository.kt`:
- New `suspend fun refineTripOutfits(trip, currentOutfits, instruction, prefs, images): List<List<String>>?` — one JSON-mode call returning per-outfit item-id lists. Logged via `TokenUsageRepository.recordUsage(UsageCategory.TRIP_BULK_REFINE)` (new category, also added to `insights/UsageScreen`).
- `CreditPack.COST_TRIP_BULK_REFINE` + pricing entry in `firebase/functions/src/pricing.ts → DEFAULT_PRICING` (deploy out of scope; runtime falls back to default).

## Migration / behavior
- Existing travel-tagged outfits without `tripId` are still loaded as Outfits; they render under "Other travel outfits". Nothing is auto-migrated.
- The active closet (`activeLocationId`) is where new trip-outfits are saved. If no active closet, refuse to generate with a clear error.

## Strings
Add to `res/values/strings.xml` and **mirror in every** `values-*/strings.xml`:
- `trip_viewer_title`
- `trip_day_header` ("Day %1$d")
- `trip_default_name` ("%1$s — %2$s")
- `trip_section_other_outfits` ("Other travel outfits")
- `trip_delete`
- `trip_delete_confirm_title`
- `trip_delete_with_outfits`
- `trip_delete_keep_outfits`
- `trip_rename`
- `trip_modify_outfit`
- `trip_bulk_refine_hint`
- `trip_bulk_refine_action`
- `trip_bulk_refine_brighter`
- `trip_bulk_refine_lighter`
- `trip_bulk_refine_formal`
- `trip_bulk_refine_skip_jacket`
- `trip_empty_hint`
- `trip_extras_section` (reuse `travel_extra_items` if equivalent)

## CLAUDE.md / CLAUDE_ARCHIVE.md
- CLAUDE.md "Package layout": mention `travel/TripsViewModel`, `TripViewerScreen`.
- CLAUDE.md "Where to find things": pointer to archive § "Trips".
- CLAUDE_ARCHIVE.md new section "Trips": persistence (`_trips/{tripId}.json`), TripContext flow into composer, bulk-refine prompt, delete cascade.

## Execution order
1. Data model (`Trip`, `Outfit.tripId`).
2. DriveRepository CRUD + `NON_CLOSET_SUBFOLDER_NAMES`.
3. `TripsViewModel` (load + persist + create/delete/rename, no AI yet).
4. `OutfitsViewModel.openComposer` + `addOutfits` (no UI yet; just plumbing).
5. Gemini prompts (TripContext block, bulk-refine call).
6. `TripViewerScreen` (header, day cards, extras, bulk-refine bar, delete dialog).
7. `TravelScreen` refactor (trip cards + Other section).
8. `TravelViewModel` + `MainActivity` glue (auto-create trip on generate, navigate, chrome hiding).
9. Strings: all locales.
10. Docs: CLAUDE.md + CLAUDE_ARCHIVE.md.
11. Build & verify (`./gradlew assembleDebug`, fix warnings).
