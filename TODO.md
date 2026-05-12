INVESTIGATION
=============

similarity search needs improvement

size filter? default sizes in wardrobe? size tag?

Implement new feature: We are working on the refinancing/monetization aspect of the app: I want people to be able to buy coins that I then use to pay gemini cloud costs. would that work with RevenueCat (handles the money/receipts) + Firebase (database and secure Gemini API routing)? In addition, I still want to support the bring your own key option though.

fix usability issue on wardrobe screen: moving item to different closet and then switching to that closet - it won't immediately appear on wardrobe after moving to different closet. it took almost a minute to appear. Can this be sped up?

consistency: the cross to cancel taking a picture in wardrobe is in the lower right. change the cross in similarity search to use the same button and move it from top left also to lower right ??? rotate button color??

security/function relevant parts of a prompts should not be appear in settings. e.g. "Place the clothing item on a pure, solid neon green background (Hex #00FF00).

should travel planner be a tab under outfits?

TODO
====


create human readable release notes between now and v1.6.1 and release version 1.7.0, git tag commit and upload to testers in firebase

[bug] on outfits and wardrobe item view make fab "delete" red, aswell as data/repair & sync + cutout

travel screen create tabs: create travel outfits and another one with travels that shows the travel outfits. tag outfits with travel.

Storage:
- I noticed .jpgs from camera are just 124kB but .pngs are 450kB. Since this is on users drive folders we have to be cautious about size. Let's target 1GB - to allow for 2000-4000 wardrobe items.

Wardrobe:
- on wardrobe item screen make delete button red
- Add item search / filtering by text for all, once with some local AI based text search and once with Gemini. This is for all outfits, wardrobe.

Feedback:
add way to send feedback via firebase under settings -> feedback
add a feedback tab under settings move debug setting under this tab


IN PROGRESS
===========


Repair & Sync:
- [bug] On repair & sync Preview screen buttons are outside of bottom of screen. Fix like you did in in several other cases for e.g. duplicate search.
- On repair & sync - it displays me 195 items marked as similar. These are exactly the items I already have imported. Why is that?

Try-on:
- Try-on details, clicking on item shows the item in big & shows button to go to this item in wardrobe.
- Add same filtering options like on outfits screen.
- Add a + FAB like on outfits but this time to create new try-on that shows the create try-on page.
- [bug] When a try-on is displayed fix the name: Instead of Save to google drive, give it a non-technical save name







DONE
=====
when suggesting an outfit from existing outfits - show the resulting outfit exactly like when you click on an outfit in the outfit detail view not in the outfit create view. enable sliding left right through options.

clicking on an outfit currently shows the wardrobe items of an outfit in a grid. I want a more natural look. the items should not be aligned with a grid, have transparent background. they should be partially overlapping with a slight shadow cast for each item and big enough to fill as much of the drawable screen content together as possible. this probably means removing the grid and drawing them on a canvas

Outfits:
- [bug] on outfit detail screen make everything fit the screen, show individual wardrobe items not only one below the other but sensible aranged such that they fill the full area, ensure that buttons are within the screen though and tags and items are not overlapping
- add option to create tag with AI under FAB (similar like in wardrobe)
- Rework outfit screen - we need a more intuitive UI: The goal of a user is to create an outfit for a use case that the user currently wants. So the user needs to be able to set the topic and all relevant parameters, a user likely wants to prompt the input or quicker just click through stuff. Hide the general preferences show only the current preferences/topic that are most relevant for what the user wants. Make a suggestion how an improved UI / UX would look like.


Outfits:
- Enable tag specific to outfits, i.e. birthday outfit, travel outfit, ...
- Clicking on an outfit shows it full screen like like on wardrobe with all items nicely arange.
- Once an outfit is shown full-screen allow for left/right sliding through outfits (similar to wardrobe)

Localization:
- [bug] Go through all text that is displayed on the screen and check if it is proper localized. This includes all buttons, all info messages, FAB,... Ensure that when switching languages localization remains sensible.  This does not hold for Wardrobe item names, nor outfit and shopping title names that are generated with AI already in the user specified language already. Examples that are not localized: 
	- On repair & sync Preview screen text is not localized. 
	- Try-on details, clicking on item shows the item in big:  screen not localized

in outfit detail view FAB add option that uses gemini to determine tags from title and selected item tags etc 

Camera:
- when taking pictures, the make it possible to change the position of the cross hair by just tapping on a different part of the screen
- enable pinch zooming when taking picture

add repair background in wardrobe item and a batch way in settings/data to fill the black background surrounding cutout images also cropping the image

green border in cut out, how to get rid of it and make the other black stuff fully transparent

overlay closet in wardrobe on top right of each item in grid use the same style color as on Outfits also on when viewing an item on wardrobe in detail use those colors for tags and make those tags not be contained in a transparent box but show each one separately

remove the long press on wardrobe item functionality - add the the missing function create outfit to FAB

when scrolling through wardrobe, outfits, shopping list the scroll bar that is shown make it wider and add way that clicking and dragging the scrollbar moves

moving an item from one closet to another did change order / time

show imported time below name of item in wardrobe item view. scale item such that it (initially is not overlapping with tags.

offline check is not reliable offline/online status - listen to android. if previously online but it now goes offline also go offline in the app and ensure that the red offline bar is shown. also in wardrobe some the buttons for importing from gallery or picture do still exist.

Fix those issues in wardrobe view:
2. in shopping view similarity search is also not localized and looking at match full screen buttons at the bottom are outside of the screen
3. when adding item to wardrobe and duplicate is found "Possible duplicate" it is not localized
4. clicking on a similar item "Similarity" & "Show in wardrobe" are not localized and button is too far at bottom of the screen (partially outside the screen)
5. when refining cutout the buttons Skip (use Gemini) and Use this cutout are only partially visible at the bottom of the screen. Ensure they are fully visibile. In addition they are not localized. Also "Refine cutout", "Drag the crosshair onto the item you want to keep." is not localized

Fix the following bugs in shopping helper:
1. when in offline mode don't allow delete
2. similarity search not localized and looking at match full screen buttons at the bottom are outside of the screen

Fix the following bugs in Outfits view:
1. when creating try-on on "Try on me" remove the top right button that shows previous try-ons we now have a dedicated tab for that
2. only have single + button and integrate the functionality to create manual outfit, suggest existing outfit and create new outfit with AI in the create outfit view that wardrobe is already using

when importing images/photo/url add option to do background removal via cheap local tflite model. let the model mark the background display the starting point as cross hair that the user can change

1. shopping -> similarity search when taking picture it displays the closet hide that. once picture is taken it jumps to shopping list but should stay on similarity search tab
2. shopping -> similarity search when looking at matching picture the show in wardrobe and add to shopping list buttons are to far at the bottom (half outside the screen)
3. fix bug in similarity search not all closets are being indexed initally I noticed only the default one is 
4. find via photo in wardrobe -> result screen should not show button add to shopping list (only for shopping -> similarity search)

shopping "wardrobe" is slow to load and does not have the same functionality as wardrobe, make shopping "wardrobe" have the same features as wardrobe and make it use the same function
on repair & sync make preview all images that will be imported in a grid similar to how they are show in wardrobe view with them all selected. only the selected ones will be modified. support manual (un)selection with the common features of (un)select all, showing counts..

how can we unify tags? I see tags like "Long-sleeve T-shirt", "Long-sleeved t-shirt", "Long-sleeve shirt" that likely mean the same. Or "grey", "Gray"

Similarity search on shopping screen, similarity search when importing and similarity search when finding an item on wardrobe seem to not work in the exact same way. The one on the shopping screen seems to work while the others don't.

Fix those bugs
1. similarity search buttons when displaying full matching image, button at bottom is does not fit on screen it is only half visible (happens in both shopping -> similarity search and in wardrobe when adding item by picture)
2. when refining cutout the buttons Skip (use Gemini) and Use this cutout are only partially visible at the bottom of the screen. Ensure they are fully visibile.
3. fix crash when importing item from camera. Crash happend when image was rotated.
4. similarity search not localized: "Possible duplicate" "We found X item(s) in your wardrobe that look very similar to this photo.", "Similarity", Buttons: "Cancel" "Import Anyway"

ONLY on wardrobe cutout background FAB add an option to clear alpha. this is off by default. when selected it runs before applying any of the other steps and clears alpha (opaque). Alos fix localization on this options screen. It is currently not localized do it for all the options.

when scrolling through wardrobe, outfits, shopping list add a scrollbar when scrolling

when viewing an item in wardrobe add option to move to different closet under FAB

in find by image when clicking on a match do not show the button add to shopping list

in duplicate detection when importing an image when clicking on a match do not show the buttons show in wardrobe and add to shopping list

Fix closet usability:
1. the closet filter on top can take up too much space if the closet name is too long. limit its width such that at least the wardrobe name fits there. if font size is too large also limit the wardrobe name on the left (ensure that it does not wrap).
2. when starting the app set closet filter to all and do not save the default closet filter. at the same time make the default closet in settings data/closets an independent option that is persistet but it does not have an effect on the closet filter just on to which location the user is importing by default
3. when creating outfits allow selecting closets from which outfits are generated but go with the default one
when importing move progress bar as overlay on top of each image that are currently being processed.

when viewing an item in detail. show the name of the item on top. below show tags separately. then modify the rotate button to become an edit button with same style as the + button on wardrobe and put the options: rotate, detect tags, remove bg as buttons

in the header the sorting menu is not localized and the entire filter menu is not localized. fix that 

update design

The url based import does not work (pasted link from Amazon) Can we open a web browser with that page and have the user select the image to be used or some other means of preview?

Similarity search seems to be no longer working. I see orange t-shirts being matched for blue ones with histogram getting a high score >0.9. Could it be that we are computing the histogram over background pixels - are we doing something like this?

// Only process pixels that are fully opaque
if (Color.alpha(pixel) > 0) { 
    // Calculate HSV and add to bin
}

In addition please plot a bounding box for the similarity search debug mode so it is clear what pixels are being considered.
on shopping page when viewing an item show detect tags, remove background and edit tags like when viewing an item on wardrobe

except for camera always keep the app in portrait mode

Whenever AI is used to generate or suggest an outfit or for try-on use the AI spinning wheel in case gemini reports back a progress use this to show progress 0..100%

Fix camera functions:
1. ensure that in all processes, images are smaller than max(width,height)<1280. In particular before uploading anything to Gemini. Ensure also that images are cropped to minimize number of pixels that need to be sent to gemini to reduce token usage. Also ensure that output of gemini cut outs is smaller than max(width,height)<1280
2. always show crosshair in camera

when importing an item, after bg removal with gemini not the entire item is visible. Parts are cut off. In particular t-shirts are not fully shown arms are cut. When shoes are bg-removed sometimes only one shoe remains but a pair is the expected behavior. In addition favor clean catalog images if items can be clearly identified.Modify the prompt

Improve useability on wardrobe and shopping screen: When importing a new item from camera, gallery or URL, just show a single + button on wardrobe and shopping screen that opens the camera. Once the camera is open the + button modifies into an X button (for cancel) and the more buttons for import via gallery or URL are being displayed. This is to unify the interface to outfits and limit the amount of overlapping buttons with the grid of items. it also makes offline mode easier. It is now sufficient to just hide the + button.

firebase app analytics to understand what features are being used (zoomed in which buttons are pressed, things tapped in which order)

add threshold for tuning bg removal algorithm under settings -> AI tab

On wardrobe screen when viewing an item: I noticed that on screens with a large font size there is a large transparent rectangle that is hiding most of the item.    

move costs statistics over to insights as another tab Costs. In addition to actual token use / cost add also counts and graphs of counts for how many items were imported etc

new feature: keep track of used gemini tokens. store those internally in app and sync to drive. display total token usage, daily token usage and weekly token usage under settings / credits. add additional separation by use (bg removal, tagging, try-on, ...). In addition to tokens also show actual costs in EUR based on gemini rates

Fix those issues in wardrobe view:
1. when refining cutout the buttons Skip (use Gemini) and Use this cutout are only partially visible at the bottom of the screen. Ensure they are fully visibile. In addition they are not localized
2. when finding image the buttons at the bottom (Show in Wardrobe)are only partially visible at the bottom of the screen. Ensure they are fully visibile. In addition they are not localized
3. when finding image and clicking on a match swipe left & right no longer shows the previous next match
4. when importing into wardrobe for local background removal ensure that image is centered. In general ensure that images are cropped.
5. when taking a picture and pressing the back button closes the camera / cancels
6. when taking a picture in wardrobe similar items should be shown in the same way like in find item view

Continue work on Shopping helper.

1. In wardrobe one can currently import an item by taking a picture and by selecting an image from gallery. Add another option to fetch an item from a shopping site. To this end the user pastes a URL to a shopping page e.g. amazon and we need to detect and fetch the product image from that page
2. Create a shopping closet that can contain potential items a user wants to shop. If a user indeed shops an item it can be moved over to one of his other closets. Do not display the shopping closet on wardrobe but show this shopping list as tab under Shopping. Have the same function as in wardrobe there but only every show items from the shopping list.
3. Similarity search keep the debug code but disable it by default. Add button on match list and when match is displayed large go to the matching item in wardrobe. Add another button to import to shopping closet (if user finds no similar one also in both cases)
4. When selecting one or multiple items in wardrobe and my intention is to get rid of those items and perhaps replace those add way to suggest alternatives I could buy


Rework shopping helper by adding tools that are currently scattered across the app:
1. Move current shopping helper under a tab Similarity Finder
2. move the identify gaps on this Screen as a separate tab and drop previous screen
3. Move wardrobe statistics over
4. Move statistics from calender over 

Fix those issues in shopping view:
1. enable try-on me on shopping view.
2. display shopping closet name like on wardrobe view
3. remove sorting
4. tapping on an item should show the item in big like happens in wardrobe
5. allow creation of outfit

on try-on details the delete button is partially outside the screen and when clicking X in upper left it does not immediately go back to the try-on tab on outfits

when clicking on any screen button go to that screen with the default tab selected

in settings add AI tab where all gemini prompts that are currently being used are being displayed and can be edited/overriden. add a reset to defaults on this page. add a setting as in what to consider by default when suggesting a style.

Integrate similarity search on several places:
1. when taking picture on wardrobe item import: when picture is taken check for similar ones automatically (make this optional via settings). if similar items are found (configurable threshold in settings) then let user see them and confirm if import should continue. for similarity comparison remove background of the photo in the same way as it is done for similarity search
2. shopping helper (as it is now)
3. in wardrobe view add option to go to item by taking picture, let the user see alternatives; when user selects one go to that item
4. in sync & repair, for each found image run similarity search. if above a certain threshold mark the image with 'similar'. Clicking on 'similar' then shows the similar ones. make the user confirm that there are no similar ones then. like in 1. remove background
5. in wardrobe import from folder: add same logic like in 4. sync & repair: preview images that would be imported and let user select and have similarity search

sync & repair: don't always clear cache make that optional

similarity search is still not reliable. Let's debug: Show the image once raw, once with background removed (white) and show the matching images with the same background removal applied. Also show the histograms. Note we are using efficientnet now to capture further textures. allow for swiping left / right to show next match

travel packing. For each suggested style add option to add to styles. also add option to move all items to "Travel" location.

why is the initial loading time on app start so long in wardrobe screen? can you speed this up and make the app work fully offline in "view only" and "manual" mode not enabling any of the AI suggestions at the same time?

add a try-on tab under outfits, that shows all previously worn outfits

Shopping helper
1. Move the calendar screen as tab into insights
2. Create a shopping helper screen and move the similarity search from insights there aswell as the gap analysis

similarity search always includes all wardrobes (cross-closet snapshot fed into every similarity-search call site)

similarity search on wardrobe: when tapping a found item don't open that item but scroll to the position where the item is in the grid view (and switch closet if the match lives elsewhere)

Fix the bug in similarity search: Taking a picture with the new debug screens it became apparent that the processed image still shows the background. So the segmentation is not happening and the background is not filled with white color for the newly taken photo. Likewise the processed item from wardrobe does not have a white background hinting at the segmentation / background removal not being applied in the same way.

move similarity threshold setting to top of AI tab

Implement a new feature: I want a local AI / machine learning model that is super fast to detect for a picture that is taken in the app the cutouts that do exist in my cache that look similar.

visualize statistics on wardrobe, e.g. how many t-shirts etc and by category
in wardrobe view display counts of items after applying filter

unify the try on feature from outfit or from multiple items in wardrobe screen: 1. create a new view that first shows those items and let's the user to add / remove items and then try on. explain that all those will be worn in the try-on. 2. show the try-on image make it zoomable 3. if the user likes it save the try-on in a dedicated folder on google drive together with the selected items. 4. add way to view previous try-ons together with items

currently there are different ways to create new styles, one in wardrobe view when selecting one or more items then manual / suggest with AI. One when selecting a multiple style to combine them. One in travel. Unify them in the following way. When items are selected have one button to create new style... this style opens a window that gives the user a list of options: 1. weather (automatic current weather - show state; or self selected by e.g. season / temperature /precipitation) 2. current preferences (e.g. casual, sporty, ...) 3. showing all wardrobe items that are currently selected but allow adding more items from wardrobe 4. number and kind of items to combine (e.g. top, bottom, footwear). 5. user preference prompt from settings that can be overridden. 6. add button to enhance with AI with feedback via prompt keeping context

editing styles on any screen should use the new style creation composable  

on settings screen move gemini api key to credits

rename style / Stile Screen to outfits as style is a different concept / meaning. Rename classes in code too.

ensure that when repairing, retagging, background removal batch jobs images only the resized images are used and if larger images are there that they are resized in the same way max(width,height)<1024 and cropped to minimize number of pixels that need to be sent & gemini token usage

Implement new feature: Wear on me: Idea is to view a selected style or wardrobe item on the person in the profile. To this end add an option to upload 3 pictures to ones profile: From front, from side and from back. Limit image size as usual. Store this on drive under a special name / folder such that these are not being mistaken for a wardrobe image. Then add option in wardrobe and style menu when a style or wardrobe item(s) are selected to "see this on myself" use again nanobanana to put on any style/wardrobe items.

fix this bug: what is shown on camera screen is not exactly the image being photgraphed, image might be larger. Align such that only the part shown on the screen is used also resize image to max(width,height)<1280 keeping aspect ratio before storing or uploading to google drive or processing with gemini

bug: when starting the app ensure that it has background processing permission and point the user to settings if not. Recheck then and don't let the user to do any interactions without that. This is to ensure that there is no data loss when importing / converting / tagging. Explain that to the user. For any gemini image tagging / background removal add checks for that setting to ensure consistency.

ensure that when the app is doing jobs that it is not being killed

when clicking on amazon and shop style button nothing happens, browser is installed. It was working before we addedd affiliate links. add debugging and instructions for logcat to check

offline check is not reliable offline/online status - listen to android. if previously online but it now goes offline also go offline in the app. there are still options visible while offline that should not: 1. style view - one cannot edit. 2. style view long press one cannot delete. 3. wardrobe one cannot create outfil manually and one cannot delete. 4. wardrobe on individual item view one cannot delete, move to, compose with AI, create outfit manually, rotate. 5. calendar when viewing one cannot wear again today or edit. 6. localization is wrong when in offline mode it all switches to english then. use the correct locale from settings.

in style view add edit button in lower area on the left: "edit". clicking this shows the style editing view.

bump version to 1.1.0 and release to firebase

bug localization: 1. the filters on top are not localized neither Filter themes like Seasonality etc nor the actual tags. 2. the buttom menu still has english 3. when the app is starting then tags in wardrobe screen are first displayed in english even if language is German

fix this bug: I am long clicking on a style. then select delete. Confirm. However the style doesn't disappear. delete button is still visible.

fix the bug: newly photographed items don't have a closet label visible in wardrobe

fix this bug: after restarting app default closet is unselected in settings / data. Why is that?

add an "about" under setting, that shows the software version of the app. make it have the version number but also add the git hash to enable debugging. Also describe the purpose of the app and that it is open source & free. That it is using AI heavily underneath (gemini) which has some API cost. List the cost per action also mention how one can create a key in AI Studio and where to put it (bring your own key BYOK) and that for ease of use payment via coins for users not intending to use that is enabled.

In calendar view when clicking on a day with a style image. show the style in big. add option to wear again today or edit

when for a suggested style the wardrobe list is shown, it is confusing to see the entire wardrobe list. We need a different view where the suggested style is being shown: a "style editing view". Use this style view also for the previous AI suggested style screen. This new view shows the images and description of the style. Clicking on an image shows the wardrobe view filtering for items of similar category. The current item is selected. One can select a different one of the list (a single one no multi-selection). In addition add a button to suggest 10 alternatives based on AI with gemini making Gemini aware of all the items currently in the style to find matching alternatives. Once style editing is confirmed add option to wear that style so users don't have to find the style again.

in wardrobe and styles to location filter add "All" to display items from all locations 

style recommendation needs more than 4 items, e.g. issue outer wear is combined with t-shirts or shoes.

long pressing a style makes it possible to select multiple styles, do the interface similar to wardrobe where one can select one or all, delete them. Also add AI gemini option to combine 2 or more styles into one.

when clicking on an image of a wardrobe item in the style list view, show the image in big incl tags.

put rotate button on wardrobe to lower right and don't call AI but just rotate the image instantly. don't show an AI progress wheel.

add option to rotate image in wardrobe view. this rotates original and cutout. also ensure that deleting image in wardrobe deletes side json, original and cutout.

bug: on wardrobe screen the top left X button does not close the item view (and returns to wardrobe in current location). 

pressing the wardrobe button at the bottom while in wardrobe item view does not do anything. return to current wardrobe page 

on repair & sync it takes very long just to scan about 100 files/images/jsons. Can you perhaps check for the size of the .json (e.g. 2 bytes would just be {} - which is empty) for speedup?

on several text input fields e.g. when giving feedback to gemini suggested styles, the keyboard appears but the text input field is not visible/under the keyboard. make it always visible (e.g. scrolling up) and have the submit key visible too so users don't have to push the android back button
when taking pictures where the rotation option is add a button to retake picture and one to close the camera to cancel

found another issue in sync & repair. Images that have wrong filenames and are subsequently imported are not tagged   
  perhaps also not cutout?                                                                                              
                                                                                                                        
add debug code to repair and sync that logs all actions to logcat. after running repair and sync all files that don't have an original file but just a cutout have empty tags (corresponding .json just contains "{}"). Hence ALWAYS run tagging based on the cutout image AND check if the json has more than just {} otherwise add also re-tag those. In addition, currently when the screen goes blank (android timeout) the app closes while in repair and sync procedure so the repair is not finished. fix that.

Fix this error when importing via taking picture in wardrobe: After taking picture I see that original image is there, cutout is being generated, but .json with tag file is not there but I get an error message on the screen saying /data/user/0/com.librelookai/files/wardrobe/drivhash_orginal.jpg: The source file doesn't exist

instead of saving metadata in a single file save the metadata (tags, name etc) in a .json file matching the basename of the item in wardrobe.                        


make all expensive/dangerous options dark red like batch processing of tag rescan, background removal and deleting

Over all pages unify the task/menu bar to have the same layout style font and colors

as filenames use the google drive hash name not capture_1775904019990_original.jpg or the like but 1IFsRFyABSERQj82JlB2TIkxq1QH4HUuo_original.jpg and 1IFsRFyABSERQj82JlB2TIkxq1QH4HUuo_cutout.png. Ensure that this is consistent and free of race conditions when taking pictures, picking pictures from gallery, importing pictures from drive, moving pictures to different locations and after processing them with gemini removing background or updating tags. In addition, when moving pictures to different locations move both the original and the cutout file. modify the filename migration to convert from capture_*_original.jpg, capture_*_cutout.png and to the new format to do so scan through all files in the google drive folder, rename and update metadata jsons accordingly.


make the location filter the first item in the filter bar where one can filter by other tags. In addition display tiny text with the location on top of each image in the grid


add small location text to wardrobe and add filter option on wardrobe and outfit page

when taking pictures to add items to wardrobe consider image orientation (landscape/portrait) add option to rotate image 90 degrees and save (including original and cutout)

after restarting the app I see several items from the wardrobe with and without background, when creating files it should be easily recognizable from the filename if the image is original or a cutout and in the app only cutout images shall ever be shown, say if no migration path is possible

in wardrobe it takes too long to always click + then camera to import. it should be 1-click to get to camera, multiple clicks to select from gallery 

add an option in wardrobe to select all items (matching filter criteria) - make it possible to unselect all too

currently there is a race condition one take take multiple pictures quickly one after the other which triggers processing but they are not consistently updated in meta data *.json's. make the tagging/background removal async such that users don't have to wait and add a progress bar while still imports are running and ensure consistency

