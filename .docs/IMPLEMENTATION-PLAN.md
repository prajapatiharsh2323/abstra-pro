# Implementation Plan — Google Play screenshots + preview (goldie)

Approved 2026-10-16. Target: Google Play only (native Android repo, no iOS target).
Device key: `pixel-10-pro` (AVD profile `pixel_9_pro` accepted by goldie — same 1280x2856 screen).

## Deliverables

- 5 framed screenshots, 1080x1920 PNG — `goldie/out/screenshots/pixel-10-pro/en-US/`
- 1 preview video, 1080x2400 MP4 (for YouTube; Play takes a link) — `goldie/out/previews/pixel-10-pro/en-US/`
- Studio at http://localhost:4321 — `GOLDIE_CONFIG=goldie/goldie.config.ts npx -y goldie@0 studio --no-open`
- `goldie verify` green for all assets

## Phases

0. Git identity + commit skill files (`.agents/`, `skills-lock.json`) + `.docs/` scaffold; fetch goldie.
1. Portable `appRoot`; install ffmpeg (winget Gyan.FFmpeg); install `system-images;android-35;google_apis;x86_64`; `avdmanager create avd -n goldie-pixel -d pixel_9_pro -k <image>`; boot emulator detached; doctor green.
2. Extend `seed-library.ps1` to embed USLT lyrics in the mp3s (scoped storage hides `.lrc` on API 35; app's jaudiotagger fallback reads embedded lyrics). Seed emulator. Smoke-test: install APK, launch fresh, `goldie/dump-ui.ps1` to read real permission-dialog text; patch the 8 store flows where selectors differ.
3. `capture` → `frame` → `manifest`; visually inspect every raw capture (empty states, keyboards, dialogs); repair + re-capture stale flows only.
4. Studio background + `preview` render background + `verify`; update `.docs/summaries/`; commit + push.

## Rules of engagement

- Every goldie command: `$env:GOLDIE_CONFIG = "C:\Users\RDP\abstra-pro\goldie\goldie.config.ts"` and `adb` on PATH.
- Commit + push after every phase (repo memory; sessions are ephemeral).
- Copy on tiles and store listing: existing in `goldie/goldie.config.ts` (reviewed, keep unless visual check demands edits).
- Scenes (order): home hero (offline badge), player, DSP, library, lyrics + 3-segment preview (home → browse → play/DSP).
