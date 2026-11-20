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
- **ClearKey (DASH):** add Kodi-style prop lines next to the channel (before or after `#EXTINF`):
  ```
  #KODIPROP:inputstream.adaptive.manifest_type=mpd
  #KODIPROP:inputstream.adaptive.license_type=clearkey
  #KODIPROP:inputstream.adaptive.license_key=<kid_hex32>:<key_hex32>
  #EXTINF:-1,Channel name
  https://example.com/stream.mpd
  ```
  `license_key` is either `kid:key` hex pairs (several separated by `,`) or a JSON object
  `{"keys":[{"kty":"oct","kid":"<base64>","k":"<base64>"}],"type":"temporary"}` (base64 or base64url).
  Keys are served locally to ExoPlayer; no license server is used. Other `license_type`s are reported as unsupported.
  HLS AES-128 needs nothing extra (ExoPlayer reads `#EXT-X-KEY`).

## Playlist format
- **Transport:** `http://` and `https://` only. URLs are validated (scheme, host, port, query, no whitespace);
  entries with a bad URL are skipped and logged (logcat tag `OpenIPTV`, with the playlist line number).
  Redirects are followed, including http<->https (max 10, loops detected, every hop re-validated).
  TLS certificates and host names are always verified; a bad certificate is reported as a TLS error.
- **https / http mode (`HTTP_MODE`, default `fallback`):** build with `-PHTTP_MODE=strict|fallback|force` (or env `HTTP_MODE`).
  - `fallback`: try `https://` first; if the secure connection itself fails (TLS/certificate error, connection refused,
    timeout) retry the same URL over `http://`. HTTP status errors (403, 404...) never trigger it. A host that failed once
    goes straight to http for the rest of that playback.
  - `strict`: URLs exactly as written, no downgrade.
  - `force`: rewrite every `https://` stream URL to `http://` up front (`:443` dropped).
  Both `fallback` and `force` weaken TLS: anyone who can block port 443 can push a `fallback` request onto plain http, and
  http traffic and tokens are readable on the network. The playlist download is never affected.
- **Stream types:** HLS (`.m3u8`), DASH (`.mpd`), live PHP/TS endpoints (`...live.php?...&extension=ts`), and URLs with no
  extension. Type comes from `manifest_type`, then the path extension, then `extension=`; if still unknown, the first bytes
  of the response (Content-Type, `#EXTM3U`, `<MPD`, TS sync bytes) decide.
- **Request headers:** `#EXTVLCOPT:http-user-agent=` -> `User-Agent`; `#EXTVLCOPT:http-referrer=` (also `http-referer`,
  `http-user-referer`) -> `Referer`. Sent on the manifest and on every segment request.
- **Header / entry attributes:** `#EXTM3U url-tvg="..."` (also `x-tvg-url`) and per entry `tvg-id`, `group-title`,
  `tvg-logo`, `catchup`, `catchup-days`, `catchup-source` are parsed into `PlaylistHeader` / `Channel`
  (all other attributes are kept in `Channel.attributes`). The EPG and catch-up values are parsed but not yet used by the UI.
- **Tests:** `gradle :app:testDebugUnitTest` (JVM unit tests; the HTTP tests use a local server, no internet needed).
- Permissions: `INTERNET` only.
