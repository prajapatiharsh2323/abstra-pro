# Goldie Screenshot Pipeline — Tracking

> Live status file. AI sessions have no memory across runs: read this first,
> do the work listed under **Next steps**, then update this file and commit + push.

## Current status

| Field | Value |
|---|---|
| Phase | 1 — make goldie runnable |
| State | Phase 0 complete and pushed; Phase 1 not started |
| Last commit | see `git log -1` (chore(goldie): Phase 0) |
| Studio | not running |
| Blockers | none |

## START HERE (next session)

Read `IMPLEMENTATION-PLAN.md`, then execute Phase 1 in order:

1. **1a** — fix `goldie/goldie.config.ts`: replace `const APP_ROOT = "D:/abstra-pro";` with a
   portable path, e.g. `import { resolve } from "node:path"; const APP_ROOT = resolve(import.meta.dirname, "..");`
   (Node v22.23.3 supports native TS import, so `import.meta.dirname` is available). Run
   `npx -y goldie@0 doctor` afterwards (with env vars below) to confirm the config loads.
2. **1b** — install ffmpeg: `winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements`,
   then locate `ffmpeg.exe` under `$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Gyan.FFmpeg*` and
   prepend its `bin` dir to PATH in every subsequent command (new shells do not pick it up otherwise).
3. **1c** — install system image + create AVD + boot:
   ```powershell
   $sdk = "C:\Android\android-sdk"
   & "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "system-images;android-35;google_apis;x86_64"
   "no" | & "$sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n goldie-pixel -d pixel_9_pro -k "system-images;android-35;google_apis;x86_64"
   Start-Process -FilePath "$sdk\emulator\emulator.exe" -ArgumentList "-avd","goldie-pixel","-no-snapshot-save"
   ```
   Wait until `adb -s emulator-5554 shell getprop sys.boot_completed` prints `1`.
4. **1d** — env-prefixed doctor, then commit + push (Phase 1 summary into `.docs/summaries/`):
   ```powershell
   $env:GOLDIE_CONFIG = "C:\Users\RDP\abstra-pro\goldie\goldie.config.ts"
   $env:PATH = "<ffmpeg bin>;C:\Android\android-sdk\platform-tools;$env:PATH"
   npx -y goldie@0 doctor
   ```

## Environment facts (this machine)

- Repo: `C:\Users\RDP\abstra-pro` — native Android app `dev.abstra.pro` v2.0.0 (no iOS target → Google Play only)
- Release APK: `app/build/outputs/apk/release/app-release.apk` (signed ✓)
- goldie config: `goldie/goldie.config.ts` — `GOLDIE_CONFIG` env var must prefix every goldie command
- Android SDK: `C:\Android\android-sdk` (ANDROID_HOME/ANDROID_SDK_ROOT set; `adb` NOT on PATH → prepend `C:\Android\android-sdk\platform-tools` per command)
- Emulator accel: WHPX available ✓
- Node v22.23.3 ✓, ffmpeg: MISSING (install task pending)
- AVD: none yet — create `goldie-pixel` with `-d pixel_9_pro` on `system-images;android-35;google_apis;x86_64`
- Git identity: repo-local `Harsh Prajapati <harshprajapaticoding@gmail.com>` (decision: machine has no global identity)
- Remote: `https://github.com/prajapatiharsh2323/abstra-pro.git`, branch `main`

## Phase checklist

- [x] Phase 0 — git identity, commit skill files, `.docs/` scaffold, fetch goldie (pushed)
- [ ] Phase 1a — make `appRoot` portable in `goldie/goldie.config.ts` (was `D:/abstra-pro`, broken here)
- [ ] Phase 1b — install ffmpeg (winget Gyan.FFmpeg)
- [ ] Phase 1c — install system image + create `goldie-pixel` AVD, boot emulator
- [ ] Phase 1d — `goldie doctor` green, commit + push
- [ ] Phase 2a — update `goldie/seed-library.ps1` to embed USLT lyrics, seed emulator
- [ ] Phase 2b — smoke-test app, verify permission-dialog wording, patch 8 flows
- [ ] Phase 3 — `capture` → `frame` → `manifest`, inspect 5 stills, iterate
- [ ] Phase 4 — studio on :4321, background `preview`, `verify`, final commit + push

## Next steps (exact commands)

See **START HERE** above — Phase 1a–1d. After each phase: update this file,
write a `.docs/summaries/NN-*.md`, commit and push.

## Known risks / watch items

- R1: flows expect permission button text `While using the app`; real dialog on API 35 may say `Allow`. Verify with `goldie/dump-ui.ps1`, patch flows if needed.
- R2: `.lrc` next to mp3 is hidden by scoped storage on API 35 → lyrics scene would be empty. Mitigation: embed USLT lyrics into seeded mp3s (planned in Phase 2a).
- Commit + push after every phase; summaries in `.docs/summaries/`.
