# OpenAudioStreamExtractor

An Android app that extracts the **live audio stream URL** from webradio websites.
It bundles **GeckoView** (Firefox engine) with **uBlock Origin**, and uses several extraction
engines with fallback.

## Features

- Four extraction engines, each toggleable in Settings:
  1. **Known APIs** – the URL itself, Icecast/Shoutcast status endpoints, Radio.co ids, radio-browser.info.
  2. **Static parser** – fetches the page and scans HTML, same-site scripts and iframes (`<audio>`, `og:audio`, JSON-LD, PLS/M3U/HLS links).
  3. **Network interception** – loads the page in GeckoView and watches audio/ICY/playlist responses through a bundled WebExtension.
  4. **DOM injection** – like the above, plus dismisses consent banners, presses play (muted) and inspects media elements.
- Run modes: **Sequential** (default order), **Parallel race**, **Custom order**.
- Every candidate is **validated**: headers probed, PLS/M3U/XSPF/HLS resolved, HTML/video/segments/ads rejected, codec and bitrate detected, then **ranked**. The best match is highlighted; others are listed.
- Input by pasting a URL, the Android **share sheet**, or the built-in **Browser** tab with an "Extract this page" button.
- **Library**: save, rename, delete, search, favorites, export/import (M3U, PLS, JSON), and re-check dead streams.
- uBlock Origin and "I don't care about cookies" are bundled and enabled by default in GeckoView (Browser tab and the Gecko engines).
- Streams that only play with a `Referer` header (e.g. radiofg.com) are detected and flagged in the result.
- No analytics, no telemetry, no accounts.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 37.2).

```sh
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # debug APKs (per-ABI + universal)
```

Release signing reads `keystore.properties` in the repo root (git-ignored) or
`~/.android-keystores/oase-keystore.properties`, with keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

```sh
./gradlew assembleRelease
```

APKs are split per ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`) plus a universal APK. GeckoView is large; prefer the ABI-specific APK.

## Known limitations

- Sites that require a login, geo-restriction, or a very custom play button may defeat the DOM engine; use the Browser tab, start playback, then tap Extract.
- DRM-protected or fully blob/MediaSource-based players do not expose a plain stream URL.
- Use this app only on content you are allowed to access.

## License

GPL-3.0-or-later. Bundles [uBlock Origin](https://github.com/gorhill/uBlock) (GPL-3.0),
[I don't care about cookies](https://github.com/OhMyGuus/I-Dont-Care-About-Cookies) (GPL-3.0) and
[GeckoView](https://mozilla.github.io/geckoview/) (MPL-2.0).
