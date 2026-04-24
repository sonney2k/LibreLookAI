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

AI (stars) spinning wheel in create style

visualize statistics on wardrobe, e.g. how many t-shirts etc and by category

Modify gaps screen into shopping helper that has 3 features:
1. identify gaps (as before)
2. when shopping add feature to check if item would match wardrobe and with what it could be combined / shopping helper. to this end take picture like in wardrobe with same size and UI experience. remove bg and tag and find matching items like in style creation for wardrobe
3.  When selecting multiple items in wardrobe add option remove / replace item / alternatives suggestion

create human readable release notes between now and c40489226c3a4a01dd4033e959d83cee1d7b8ebb and release version 1.3.0 and upload to testers in firebase

on repair & sync make preview all images that will be imported in a grid similar to how they are show in wardrobe view with them all selected. only the selected ones will be modified. support manual (un)selection with the common features of (un)select all, showing counts..

in wardrobe view display counts of items after applying filter


in settings add AI tab where all gemini prompts that are currently being used are being displayed and can be edited/overriden. add a reset to defaults on this page. add a setting as in what to consider by default when suggesting a style.


add option to try on image from catalog

groessen filter


-------
Implement a new feature: I want a local AI / machine learning model that is super fast to detect for a picture that is taken in the app the cutouts that do exist in my cache that look similar.

To solve this on an Android device locally and with blazing speed, you don't actually want a traditional "Image Classification" model (which requires retraining every time you add a new piece of clothing to the cache).

Instead, you need an Image Retrieval (or Visual Search) architecture using Embeddings.

Here is exactly how this works and the best stack to use for Android.

The Core Concept: Image Embeddings
An embedding model doesn't output a label like "red shirt." Instead, it looks at an image and outputs a long list of numbers (a vector, usually 512 or 1024 numbers long) that represents the "visual fingerprint" of that image.

If two images look similar, their vectors will be mathematically close to each other.

The Recommended Android Stack
Use Google's MediaPipe. Specifically, the MediaPipe Tasks Vision API (ImageEmbedder). It is designed specifically for on-device, low-latency mobile ML.

The Model: Use a pre-trained MobileNetV3 embedding model (available in .tflite format). It is incredibly lightweight, designed for mobile CPUs, and processes images in milliseconds.

Step-by-Step Implementation
Phase 1: Indexing your Cache (Background Task)
You only do this once per cached item, or whenever a new cutout is downloaded to the cache.

Load the Cutout: Read the cached image into a Bitmap.

Generate Embedding: Pass the Bitmap to the MediaPipe ImageEmbedder. It will return a FloatArray (the vector).

Store It: Save this FloatArray in your local database (Room, SQLite, or even just an in-memory HashMap if it's only a few dozen images) alongside the image's ID or file path.

Phase 2: The Live Camera Picture (Real-Time)
When the user snaps a picture in the app:

Generate the Query Embedding: Pass the newly snapped picture through the exact same ImageEmbedder model to get its FloatArray vector.

Calculate Similarity: Loop through all the stored vectors in your cache. Calculate the Cosine Similarity between the live picture's vector and each cached vector.

Cosine similarity is a simple math formula that outputs a score between -1 and 1. A score closer to 1 means the images are visually identical.

Check the Threshold: Find the highest score. If it is above a specific threshold (e.g., 0.85), you have a match! You now know exactly which cached cutout it corresponds to.

Crucial Tip for Accuracy: The "Background" Problem
You mentioned your cache has "cutouts" (which implies no background), but the user is taking a live picture (which will have a background, like a bedroom wall or a body).

If you compare an image without a background to an image with a background, the embedding vectors might look too different, causing the match to fail.

How to fix this:
Before you pass the live camera picture to the ImageEmbedder, you should separate the clothing from the background. You can do this by chaining another lightning-fast MediaPipe tool called the Image Segmenter (specifically the Magic Touch or Selfie Segmentation models) to mask out the background of the live photo, turning it into a cutout before you extract the visual fingerprint.

Summary of your Android Pipeline:
Camera Input -> Bitmap

(Optional but recommended) MediaPipe Image Segmenter -> Removes background.

MediaPipe Image Embedder (MobileNet) -> Outputs FloatArray vector.

Kotlin Math (Cosine Similarity loop) -> Compares against cached vectors.

Result -> Highest score > Threshold = Match found.

Because MediaPipe runs purely on-device (utilizing the CPU, GPU, or Android NPU), this entire pipeline will execute in real-time (often well under 100 milliseconds), require zero internet connection, and respect user privacy.
-----




Implement new feature: We are working on the refinancing/monetization aspect of the app: I want people to be able to buy coins that I then use to pay gemini cloud costs. would that work with RevenueCat (handles the money/receipts) + Firebase (database and secure Gemini API routing)? In addition, I still want to support the bring your own key option though.

Bugs:
-----
fix usability issue on wardrobe screen: moving item to different closet and then switching to that closet - it won't immediately appear on wardrobe after moving to different closet. it took almost a minute to appear. Can this be sped up?

bug or feature: persistance is working reliably now. What I noticed is that the closet selector in the header is being remebered too 
  but on app start it seems to be set to the same value as the one in settings data. on startup make it always show     
  all.                                                                                                                  

offline check is not reliable offline/online status - listen to android. if previously online but it now goes offline also go offline in the app and ensure that the red offline bar is shown. also in wardrobe some the buttons for importing from gallery or picture do still exist.

ensure that in all processes, images are smaller than max(width,height)<1280. In particular before uploading anything to Gemini.

IN PROGRESS
===========

how can we unify tags? I see tags like "Long-sleeve T-shirt", "Long-sleeved t-shirt", "Long-sleeve shirt" that likely mean the same. Or "grey", "Gray"

why is the initial loading time on app start so long in wardrobe screen? can you speed this up and make the app work fully offline in "view only" and "manual" mode not enabling any of the AI suggestions at the same time?

travel packing. For each suggested style add option to add to styles. also add option to move all items to "Travel" location.


FIXED
=====
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

continue this
❯ found another issue in sync & repair. Images that have wrong filenames and are subsequently imported are not tagged   
  perhaps also not cutout?                                                                                              
                                                                                                                        
⏺ Let me look at the Repair & Sync code to understand the current flow for wrongly-named images.                      
                                                                                                                        
  Searched for 6 patterns (ctrl+o to expand)                                                                            
                                                                                         
⏺ Now let me read the full raw image processing section in detail.                                                      
                                                                                                                      
  Read 1 file (ctrl+o to expand)                                                                                        
                                                                                                                        
⏺ Now let me look at downloadToCache and uploadAsCutout to understand what happens to the local file during raw image   
  processing.                                                                                                           
                                                                                                                        
  Searched for 2 patterns, read 2 files (ctrl+o to expand)                                                              
                                                                                                                        
⏺ Now I can see the bug. Let me look at processQueue to confirm the correct ordering:                                   
                                                            
  Read 1 file (ctrl+o to expand)                                                                                        
                                                                                                                        
⏺ Found it. The bug is identical in both the orphaned originals block and the raw images block:                         
                                                                                                                        
  1. drive.deleteFile(item.driveId) runs first, which deletes localOriginal from the local cache ({item.driveId}.jpg)   
  2. Then localOriginal.copyTo(...) throws NoSuchFileException                                                          
  3. The outer runCatching catches it — tagging and sidecar write are never reached                                     
                                                                                                                        
  processQueuedImage does this correctly: upload original → copy files to cache → then delete raw. The Repair & Sync    
  blocks have the order wrong. Also, uploadImageWithName is called after deleteFile using the now-deleted localOriginal 
  file.                                                                                                                 
                                                            
  The fix mirrors processQueuedImage:                                                                                   
  
  Read 1 file (ctrl+o to expand)                                                                                        
                                                            
⏺ Now I'll apply the fix to both blocks — upload original to Drive first, then copy to local cache, then delete the old
  raw file:                       
                                                                                                                        
⏺ Update(app/src/main/java/com/librelookai/WardrobeViewModel.kt)                                                        
  ⎿  Added 4 lines, removed 1 line                                                                                      
      403                          Log.d(TAG, "Cutout file: ${cutoutFile.absolutePath}")                                
      404                          val cutoutDrive = uploadAsCutout(item.folderId, cutoutFile)
      405                          Log.d(TAG, "Cutout uploaded as ${cutoutDrive.id}")                                   
      406 +                        // Upload original to Drive with correct name before deleteFile, which also   
      407 +                        // removes the local cache file for item.driveId (= localOriginal).                  
      408                          val newOrigId = runCatching {
      407 -                            drive.deleteFile(item.driveId)                                            
      409                              drive.uploadImageWithName(
      410                                  item.folderId, localOriginal,
      411                                  "${cutoutDrive.id}${DriveRepository.ORIGINAL_SUFFIX}",
      412                              ).id
      413                          }.onFailure { Log.w(TAG, "Original re-upload failed: ${it.message}") }.getOrNu
           ll()
      414                          Log.d(TAG, "Original re-uploaded as $newOrigId")
      415 +                        // Cache both files locally before deleting the raw Drive file.               
      416                          val localCutout = File(drive.cacheDir, "${cutoutDrive.id}.png")
      417                          if (cutoutFile.absolutePath != localCutout.absolutePath) {
      418                              cutoutFile.copyTo(localCutout, overwrite = true)
     ...
      420                          localOriginal.copyTo(
      421                              File(drive.cacheDir, "${cutoutDrive.id}_original.jpg"), overwrite = true,
      422                          )
      423 +                        runCatching { drive.deleteFile(item.driveId) }                                
      424                          val tags = gemini.classifyClothing(localCutout, geminiLanguage)
      425                          Log.d(TAG, "Tags for ${cutoutDrive.id}: $tags")
      426                          drive.upsertSidecar(

⏺ Update(app/src/main/java/com/librelookai/WardrobeViewModel.kt)
  ⎿  Added 4 lines, removed 1 line
      448                          Log.d(TAG, "Cutout file: ${cutoutFile.absolutePath}")                                
      449                          val cutoutDrive = uploadAsCutout(item.folderId, cutoutFile)
      450                          Log.d(TAG, "Cutout uploaded as ${cutoutDrive.id}")                                   
      451 +                        // Upload original to Drive with correct name before deleteFile, which also   
      452 +                        // removes the local cache file for item.driveId (= localOriginal).                  
      453                          val newOrigId = runCatching {
      452 -                            drive.deleteFile(item.driveId)                                            
      454                              drive.uploadImageWithName(
      455                                  item.folderId, localOriginal,
      456                                  "${cutoutDrive.id}${DriveRepository.ORIGINAL_SUFFIX}",
      457                              ).id
      458                          }.onFailure { Log.w(TAG, "Original re-upload failed: ${it.message}") }.getOrNu
           ll()
      459                          Log.d(TAG, "Original re-uploaded as $newOrigId")
      460 +                        // Cache both files locally before deleting the raw Drive file.               
      461                          val localCutout = File(drive.cacheDir, "${cutoutDrive.id}.png")
      462                          if (cutoutFile.absolutePath != localCutout.absolutePath) {
      463                              cutoutFile.copyTo(localCutout, overwrite = true)
     ...
      465                          localOriginal.copyTo(
      466                              File(drive.cacheDir, "${cutoutDrive.id}_original.jpg"), overwrite = true,
      467                          )
      468 +                        runCatching { drive.deleteFile(item.driveId) }                                
      469                          val tags = gemini.classifyClothing(localCutout, geminiLanguage)
      470                          Log.d(TAG, "Tags for ${cutoutDrive.id}: $tags")
      471                          drive.upsertSidecar(



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

