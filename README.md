# Cull

Swipe-to-cull for Android. Works on photos on your phone and photos in Google Photos.

- **Swipe right = keep, left = bin.** Undo any time.
- **Similar photos are grouped.** Bursts and retakes (taken within 20 min and visually near-identical) appear as one card with the best shot starred. Tap the big photo to keep/unkeep it, tap thumbnails to look at each, then swipe right to keep your selection or left to bin the lot.
- **Best pick** = sharpness (Laplacian variance) + exposure, plus on-device face detection for eyes open / smiling when there are people in the shot. Nothing leaves the phone.
- **Review screen** before anything is deleted — tap any thumbnail to rescue it.
- Remembers what you've already kept so the next cull skips it.

## Download

Every push to `main` builds the APK on GitHub Actions and attaches it to the **latest** release:
`https://github.com/cvassilas/photocull-android/releases/tag/latest` → `Cull.apk`.

## How deletion works

| Source | What happens to rejects |
|---|---|
| On this phone | One system dialog moves them all to the phone's bin (recoverable for 30 days). |
| Google Photos | Google's API is read-only for third-party apps since March 2025, so Cull can't delete. The review screen lists rejects by date with a button to open Google Photos and a shareable list. |

If your phone photos are backed up to Google Photos, culling **On this phone** is the fastest route; check after your first run whether Google Photos also moved the cloud copies to its bin.

## One-time Google setup (only needed for the Google Photos source)

1. Go to <https://console.cloud.google.com/>, create a project (e.g. "Cull").
2. **APIs & Services → Library** → enable **Photos Picker API**.
3. **OAuth consent screen** → External, app name "Cull", add your Gmail as a **test user**. Add the scope `.../auth/photospicker.mediaitems.readonly`.
4. **Credentials → Create credentials → OAuth client ID → Android**
   - Package name: `com.cvassilas.cull`
   - SHA-1: `D6:BD:12:99:A8:23:9F:DA:EC:A7:86:42:AE:FB:E9:3A:E0:D9:0C:10`

No client ID goes in the code — Google matches the package name and signing key. The signing key (`app/cull.keystore`) is committed on purpose so every CI build has the same SHA-1; keep the repo private.

"Developer error 10" when signing in means step 4 doesn't match.

## Build locally

```
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`. Requires JDK 17 and the Android SDK (API 35).

## Code map

- `data/DeviceRepo.kt` — MediaStore queries, albums, reviewed-photo store
- `data/GooglePhotos.kt` — Play services OAuth + Photos Picker API (sessions, polling, media items)
- `analysis/Analyzer.kt` — dHash fingerprints, grouping, sharpness/exposure, ML Kit faces, best pick
- `ui/CullViewModel.kt` — deck state, decisions, undo, trash request
- `ui/HomeScreen.kt`, `ui/CullScreen.kt`, `ui/ReviewScreen.kt` — Compose UI
