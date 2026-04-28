CLAUDE progressing hint
=======================
Read CLAUDE.md and only the files listed in the 'Active Task' section.
in settings add AI tab where all gemini prompts that are currently being used are being displayed and can be edited/overriden. add a reset to defaults on this page. add a setting as in what to consider by default when suggesting a style.

For context you were working on this:
This is what you have already suggested and done:

Before executing the implementation udpate CLAUDE.md with decisions/active tasks

TODO
====


Features:
---------

AI (stars) spinning wheel in create outfit

create human readable release notes between now and c40489226c3a4a01dd4033e959d83cee1d7b8ebb and release version 1.3.0 and upload to testers in firebase

Implement new feature: We are working on the refinancing/monetization aspect of the app: I want people to be able to buy coins that I then use to pay gemini cloud costs. would that work with RevenueCat (handles the money/receipts) + Firebase (database and secure Gemini API routing)? In addition, I still want to support the bring your own key option though.

update design

size filter? default sizes in wardrobe? size tag?

Bugs:
-----

when importing into wardrobe for local background removal ensure that image is centered. In general ensure that images are cropped.

ensure that in all processes, images are smaller than max(width,height)<1280. In particular before uploading anything to Gemini.

on try-on details the delete button is partially outside the screen and when clicking X in upper left it does not immediately go back to the try-on tab on outfits

fix usability issue on wardrobe screen: moving item to different closet and then switching to that closet - it won't immediately appear on wardrobe after moving to different closet. it took almost a minute to appear. Can this be sped up?

offline check is not reliable offline/online status - listen to android. if previously online but it now goes offline also go offline in the app and ensure that the red offline bar is shown. also in wardrobe some the buttons for importing from gallery or picture do still exist.

security/function relevant parts of a prompts should not be appear in settings. e.g. "Place the clothing item on a pure, solid neon green background (Hex #00FF00).

bug or feature: persistance is working reliably now. What I noticed is that the closet selector in the header is being remebered too 



IN PROGRESS
===========
firebase app analytics to understand what features are being used (zoomed in which buttons are pressed, things tapped in which order)

when importing images/photo/url add option to do background removal via cheap local tflite model. let the model mark the background display the starting point as cross hair that the user can change

add way to send feedback via firebase under settings -> feedback

1. shopping -> similarity search when taking picture it displays the closet hide that. once picture is taken it jumps to shopping list but should stay on similarity search tab
2. shopping -> similarity search when looking at matching picture the show in wardrobe and add to shopping list buttons are to far at the bottom (half outside the screen)
3. fix bug in similarity search not all closets are being indexed initally I noticed only the default one is 
4. find via photo in wardrobe -> result screen should not show button add to shopping list (only for shopping -> similarity search)
shopping "wardrobe" is slow to load and does not have the same functionality as wardrobe, make shopping "wardrobe" have the same features as wardrobe and make it use the same function

add threshold for tuning bg removal algorithm under settings -> AI tab

add a feedback tab under settings move debug setting under this tab

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


on repair & sync make preview all images that will be imported in a grid similar to how they are show in wardrobe view with them all selected. only the selected ones will be modified. support manual (un)selection with the common features of (un)select all, showing counts..


how can we unify tags? I see tags like "Long-sleeve T-shirt", "Long-sleeved t-shirt", "Long-sleeve shirt" that likely mean the same. Or "grey", "Gray"




FIXED
=====
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

ensure that when repairing, retagging, background removal batch jobs images only the resized images are used and if larger images are there that they are resized in the same way max(width,height)<1280 

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

