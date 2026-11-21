# Open IPTV
Minimal Android TV / phone IPTV player. Opens straight into a channel, no menus.

- **Playlist:** fixed at build time. Set the GitHub repo variable `M3U_URL`, or build with `-PM3U_URL=...`, or edit `DEFAULT_M3U` in `app/build.gradle.kts`.
- **Channels:** numbered 1..N in playlist order. Launch resumes the last channel.
- **Remote:** Up/Down or CH+/CH- change channel, digits type a number, Left/OK opens the channel menu (Back closes it).
- **From other apps:** intent action `com.example.openiptv.PLAY` with extra `number` or `name`, or deep link `openiptv://channel/5` / `openiptv://channel/vtv3`.
- **Streams:** HLS, DASH and TS over `http://` / `https://`. The type is taken from `manifest_type`, the URL, or the first bytes of the response.
- **https:** tried first; if the secure connection itself fails (TLS error, refused, timeout) the same URL is retried over `http://`. This weakens TLS. The playlist download never falls back.
- **Playlist tags:** `#EXTVLCOPT:http-user-agent=` and `http-referrer=` become request headers. `#KODIPROP:inputstream.adaptive.license_type=clearkey` with `license_key=<kid_hex32>:<key_hex32>` (or a JSON key object) plays ClearKey DASH. EPG and catch-up attributes are parsed but unused.
