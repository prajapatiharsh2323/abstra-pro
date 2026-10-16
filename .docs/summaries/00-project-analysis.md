# Summary 00 — Project analysis (pre-implementation)

**Date**: 2026-10-16 · **Phase**: before Phase 0 · **Status**: complete

## Repo

- Native Android app (Kotlin + Jetpack Compose), `dev.abstra.pro`, versionName 2.0.0 / versionCode 3, minSdk 24, targetSdk/compileSdk 35. No iOS target → **Google Play only**.
- 67 Kotlin sources. Media playback via media3/ExoPlayer (`PlaybackPlayer` abstraction), Room, jaudiotagger for metadata/lyrics, MediaStore-scanned library (`IS_MUSIC != 0`, `DURATION >= 10s`).
- Manifest permissions: `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE (≤32)`, `POST_NOTIFICATIONS`; explicitly **zero internet permission** (that's the marketing hook of scene 1).

## Existing goldie setup (committed in 35eb8db)

- `goldie/goldie.config.ts` — 5 screenshot scenes (home/player/dsp/library/lyrics) + 3-segment preview; Cotton Candy theme gradient, Montserrat, badge "100% offline"; store listing complete. **`appRoot` points at `D:/abstra-pro` (does not exist here) — Phase 1a fix.**
- `.argent/flows/store-0*.yaml` + `store-preview-0*.yaml` — 8 flows. Selectors verified against app source:
  - `While using the app` / `Allow` (permission dialogs — system text, unverified on this image → risk R1)
  - `What's your name?` + `Skip` (MainActivity.kt:297/317), `RECENTLY ADDED` (HomeScreen.kt:155), `Songs` (TopSegmentedTabs.kt:25), `id: Search` (SongsScreen.kt:139 desc), `Pause` (MiniPlayer.kt:133 desc), `Collapse Player` (FullPlayerScreen.kt:153 desc), `Audio Equalizer & Effects` (FullPlayerScreen.kt:202 desc), `DSP Audio Effects` (EqualizerScreen.kt:86), `Lyrics` (FullPlayerScreen.kt:451), `Synced Lyrics` (LyricsScreen.kt:135)
  - Preview segments correctly have no `launch:` (doctor check) + `executionPrerequisite`.
- `.argent/flags.json` — `video-watermark: false` (doctor check satisfied).
- `goldie/seed-library.ps1` — builds 12 synthetic mp3s (ffmpeg sine chords) + matching `.lrc` in `/sdcard/Music/Abstra Demo`; asserts MediaStore `is_music=1` indexing.
- `goldie/dump-ui.ps1` — adb uiautomator dump → text/content-desc + normalized coords (repo author derived flow coordinates this way; their normalization 1080x2410 matches 1280x2856 aspect).
- `.gitignore` already has `goldie/out/`.

## Environment findings

| Item | State |
|---|---|
| Node | v22.23.3 ✓ (native TS config import works) |
| ffmpeg/ffprobe | MISSING on PATH (winget/choco available) |
| Android SDK | `C:\Android\android-sdk`; platform-tools present but `adb` not on PATH |
| System images | none installed |
| AVDs | none; `pixel_9_pro` device profile available (no `pixel_10_pro` in this cmdline-tools) |
| Emulator | 37.2.12, WHPX accel OK |
| Release APK | exists + signed (apksigner verify OK) |
| Disk | ~26 GB free on C: |

## goldie internals confirmed (from source)

- Android device `pixel-10-pro`: accepts AVD profiles `pixel_10_pro` or `pixel_9_pro` (1280x2856); reuses running emulator by profile match, else boots first matching AVD (180s deadline).
- `prepare()`: pins SystemUI demo status bar (9:41, full battery/wifi), sets night mode; no keyboard pinning on Android (no typing in these flows, so safe).
- Each flow replays after `reinstall-app` with cleared data → permission dialogs always present.
- Doctor checks: adb, ffmpeg/ffprobe, argent driver, watermark flag, `ARGENT_SCREENSHOT_SCALE`, APK exists/release, AVD profile, flows exist, preview segments have no `launch:`.

## Risks

- **R1** permission dialog wording on API 35 image (`While using the app` vs `Allow`) — verified in Phase 2b, patch flows accordingly.
- **R2** `.lrc` unreadable under scoped storage on API 35 → embed lyrics in mp3 (USLT) so the app's jaudiotagger fallback (MusicViewModel `loadLyricsForSong` step 4) renders them — Phase 2a.
- Session memory loss → commit + push every phase; this file is the durable record.
