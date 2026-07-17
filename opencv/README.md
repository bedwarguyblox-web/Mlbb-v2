# This is a placeholder, not real OpenCV

This module exists so `:app` has something to compile `implementation project(':opencv')`
against out of the box — without it, the project wouldn't build at all until you'd
gone and found the real OpenCV Android SDK, even if all you want is **Manual
Draft**, which doesn't touch OpenCV at any point.

With this placeholder in place, the app builds and runs immediately. **Manual
Draft works exactly as intended.** **Live Overlay also starts and runs**, but
`HeroIconMatcher.match()` will never report a match (it's wired to always
report zero confidence), so the overlay will run with an empty recommendation
list — screen capture and the floating panel both work, hero detection just
doesn't, since that's the one part that genuinely needs real computer vision.

## To get real hero detection in Live Overlay

1. Download the OpenCV Android SDK from opencv.org/releases.
2. Unzip it somewhere next to this project.
3. In `settings.gradle`, change the `:opencv` project's directory to point at
   `<wherever you unzipped it>/OpenCV-android-sdk/sdk/java` instead of this
   folder.
4. Re-sync Gradle. `HeroIconMatcher` needs no code changes — it already calls
   the real OpenCV API shape, this module just mirrors it with no-ops.
