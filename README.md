# Open IPTV
Minimal Android TV / phone IPTV player. Opens straight into a channel, no menus.

- **Playlist:** fixed at build time (`M3U_URL`). Set the GitHub repo variable `M3U_URL`
  (Settings > Secrets and variables > Actions > Variables), or `-PM3U_URL=...`, or edit `DEFAULT_M3U` in `app/build.gradle.kts`.
- **Numbers:** every channel gets a number 1..N in playlist order.
- **Launch:** resumes the last channel (or #1).
- **Open a channel from another app / button mapper:**
  - `adb`-free intent: action `com.example.openiptv.PLAY`, package `com.example.openiptv`, extra `number` (int) or `name` (string)
  - deep link `openiptv://channel/5` or `openiptv://channel/vtv3`
- **Remote:** Up/Down or CH+/CH- change channel, digits type a number, Left (or OK) opens the channel menu: half the screen, 3 channels per row (1 2 3 / 4 5 6 / ...). Back, or Right from the last column, closes it.
- **ClearKey (DASH):** add a Kodi-style prop line before the channel URL:
  ```
  #KODIPROP:inputstream.adaptive.license_key=<kid_hex32>:<key_hex32>
  #EXTINF:-1,Channel name
  https://example.com/stream.mpd
  ```
  Multiple pairs are comma-separated. Keys are served locally to ExoPlayer; no license server is used.
  HLS AES-128 needs nothing extra (ExoPlayer reads `#EXT-X-KEY`).
- Permissions: `INTERNET` only.
