# Open IPTV
Minimal Android TV / phone IPTV player. Opens straight into a channel, no menus.

- **Playlist:** fixed at build time. Set the GitHub repo variable `M3U_URL`, or build with `-PM3U_URL=...`, or edit `DEFAULT_M3U` in `app/build.gradle.kts`.
- **Channels:** numbered 1..N in playlist order. Launch resumes the last channel.
- **Fallback sources:** entries with the same name (case, accents and spacing ignored; `+` is kept, so `On Sports` and `On Sports+` stay separate) are ONE channel. The first is the primary, the rest are backups, each with its own `#EXTVLCOPT` / `#KODIPROP` lines. If a source fails to play (playback error, or a TLS error while probing), the player switches to the next one and shows `(source 2/3)`; after the last one it shows the error. Changing channel starts again from the primary. Numbers count channels, not entries.

  ```
  #EXTINF:-1 group-title="VTV",VTV3
  https://primary.example/vtv3.m3u8
  #EXTINF:-1,VTV3
  https://backup.example/vtv3.m3u8
  ```
- **Remote:** Up/Down or CH+/CH- change channel, digits type a number, Left/OK opens the channel menu (Back closes it).
- **From other apps:** intent action `com.example.openiptv.PLAY` with extra `number` or `name`, or deep link `openiptv://channel/5` / `openiptv://channel/vtv3`.
- **Streams:** HLS, DASH and TS over `http://` / `https://`. The type is taken from `manifest_type`, the URL, or the first bytes of the response.
- **https:** tried first; if the secure connection itself fails (TLS error, refused, timeout) the same URL is retried over `http://`. This weakens TLS. The playlist download never falls back.
- **Playlist tags:** `#EXTVLCOPT:http-user-agent=` and `http-referrer=` become request headers. `#KODIPROP:inputstream.adaptive.license_type=clearkey` with `license_key=<kid_hex32>:<key_hex32>` (or a JSON key object) plays ClearKey DASH. EPG and catch-up attributes are parsed but unused.
