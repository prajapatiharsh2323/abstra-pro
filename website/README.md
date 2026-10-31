# Abstra Pro — Product Website

Single-page product site for **Abstra Pro** (Android music player). Built to extend the
app's visual identity — every color token and mockup recreation comes from the app's
own source (`app/src/main/java/dev/abstra/pro/ui/theme/Color.kt` and
`ui/components/*`).

## Stack

- **Next.js 15** (App Router, TypeScript, static export to `out/`)
- **Tailwind CSS v4** + CSS custom properties as design tokens
- **Framer Motion** — one shared reveal/variant system in `src/lib/motion.ts`
- **Lucide** icons, self-hosted Inter (variable) + Space Grotesk via Fontsource

## Tokens

The site's page identity is anchored on the app's **Midnight Room** theme
(`#1D1A2D` base, `#E5819E` primary, `#FCE4EA` headings). All seven app palettes
live verbatim in `src/lib/themes.ts` and power the interactive theme showcase via
`--m-*` CSS variables.

- `src/app/globals.css` — tokens, buttons, and all mockup primitives
  (phone frame, album-art gradients from `ThematicAlbumArt.kt`, segmented tabs from
  `TopSegmentedTabs.kt`, waveform from `WaveformVisualizer.kt`, vinyl centerpiece,
  10-band EQ sliders, mini player)
- `src/components/mockups/` — HTML/CSS recreations of real app screens
  (`HomeScreen.kt`, `FullPlayerScreen.kt`, `EqualizerScreen.kt`, `LyricsScreen.kt`)

Mockups contain only features that exist in the app. Album art is procedural
gradient artwork — the same approach the app uses for tracks without embedded art.

## Commands

```bash
npm install
npm run dev        # http://localhost:3000 (or -- --port 3100)
npm run build      # static export into out/
```

`scripts/inspect.js` / `inspect2.js` are dev-only Puppeteer helpers for visual QA
(screenshots land in `scripts/shots/`, gitignored).

## Content rules observed

- Download CTA → GitHub Releases (`prajapatiharsh2323/abstra-pro`)
- No fake statistics, testimonials, or store listings
- Verified facts only: Android 7.0+ (`minSdk 24`), no network permission, offline by design
