#!/usr/bin/env python3
"""Health-check an M3U playlist and temporarily disable dead channels.

A channel is disabled by prefixing every line of its block with "#DISABLED# ",
so it becomes a comment and players skip it. Disabled channels are still
re-checked on every run and are re-enabled automatically once they work again.

To avoid disabling a channel because of a one-off blip (or because the runner
is in a region the stream blocks), a channel must fail `--threshold` runs in a
row. Failure counts are kept in a small JSON state file.

Usage:
    python3 check_channels.py m3u --state .github/channel-status.json
"""
import argparse, json, os, re, sys, urllib.error, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

PREFIX = "#DISABLED# "
TAG = "#DISABLED#"
DEFAULT_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120 Safari/537.36"


def parse(lines):
    """Return channel blocks: start/end line indexes, url, name, headers, disabled."""
    entries, start = [], None
    for i, raw in enumerate(lines):
        line = raw.strip()
        disabled = line.startswith(TAG)
        if disabled:
            line = line[len(TAG):].strip()
        if line.startswith("#EXTINF"):
            start, name, ua, ref = i, line.rsplit(",", 1)[-1].strip(), None, None
        elif start is not None and line.startswith("#"):
            m = re.match(r"#EXTVLCOPT:http-user-agent=(.*)", line)
            if m:
                ua = m.group(1).strip('"')
            m = re.match(r"#EXTVLCOPT:http-(?:referrer|referer|user-referer)=(.*)", line)
            if m:
                ref = m.group(1).strip('"')
        elif start is not None and re.match(r"https?://", line):
            entries.append(dict(start=start, end=i, url=line, name=name, ua=ua,
                                ref=ref, disabled=disabled))
            start = None
    return entries


def fetch(url, ua, ref, timeout, nbytes):
    headers = {"User-Agent": ua or DEFAULT_UA}
    if ref:
        headers["Referer"] = ref
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.headers.get("Content-Type", ""), r.read(nbytes), r.geturl()


def check_once(e, timeout):
    url, ua, ref = e["url"], e["ua"], e["ref"]
    try:
        ctype, body, final = fetch(url, ua, ref, timeout, 65536)
        text = body.decode("utf-8", "replace")
        if "<MPD" in text[:2000]:
            return True, "DASH manifest"
        if not text.lstrip().startswith("#EXTM3U"):
            if ctype.startswith(("audio/", "video/", "application/octet-stream")) or body[:1] == b"\x47":
                return True, "direct stream"
            return False, f"not a playlist ({ctype})"
        for _ in range(3):  # master -> variant -> media playlist
            refs = [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]
            if not refs:
                return False, "empty playlist"
            nxt = urllib.parse.urljoin(final, refs[0])
            if "#EXT-X-STREAM-INF" in text or nxt.split("?")[0].endswith(".m3u8"):
                ctype, body, final = fetch(nxt, ua, ref, timeout, 65536)
                text = body.decode("utf-8", "replace")
                if not text.lstrip().startswith("#EXTM3U"):
                    return False, "variant not a playlist"
                continue
            _, seg, _ = fetch(nxt, ua, ref, timeout, 2048)
            return (True, "segment readable") if seg else (False, "empty segment")
        return False, "too many playlist levels"
    except urllib.error.HTTPError as ex:
        return False, f"HTTP {ex.code}"
    except Exception as ex:
        return False, f"{type(ex).__name__}: {str(ex)[:80]}"


def check(e, timeout, attempts=2):
    result = (False, "no attempt")
    for _ in range(attempts):
        result = check_once(e, timeout)
        if result[0]:
            break
    return result


def apply(lines, entries, results, state, threshold):
    """Update `lines` and `state` in place. Returns (disabled, enabled) name lists."""
    disabled_now, enabled_now = [], []
    for e, (ok, detail) in zip(entries, results):
        key = e["url"]
        block = range(e["start"], e["end"] + 1)
        if ok:
            state.pop(key, None)
            if e["disabled"]:
                for i in block:
                    if lines[i].lstrip().startswith(TAG):
                        lines[i] = lines[i].replace(PREFIX, "", 1) if PREFIX in lines[i] else lines[i].replace(TAG, "", 1)
                enabled_now.append(e["name"])
        else:
            fails = state.get(key, {}).get("fails", 0) + 1
            state[key] = {"fails": fails, "name": e["name"], "detail": detail}
            if not e["disabled"] and fails >= threshold:
                for i in block:
                    if not lines[i].lstrip().startswith(TAG):
                        lines[i] = PREFIX + lines[i]
                disabled_now.append(e["name"])
    return disabled_now, enabled_now


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("playlist")
    ap.add_argument("--state", default=".github/channel-status.json")
    ap.add_argument("--workers", type=int, default=20)
    ap.add_argument("--timeout", type=int, default=12)
    ap.add_argument("--threshold", type=int, default=3,
                    help="consecutive failed runs before a channel is disabled")
    ap.add_argument("--max-fail-ratio", type=float, default=0.6,
                    help="if more than this share of channels fail, assume the runner "
                         "has a network problem and change nothing")
    a = ap.parse_args()

    with open(a.playlist, encoding="utf-8", newline="") as f:
        lines = f.read().split("\n")
    entries = parse(lines)

    try:
        with open(a.state, encoding="utf-8") as f:
            state = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        state = {}

    with ThreadPoolExecutor(a.workers) as pool:
        results = list(pool.map(lambda e: check(e, a.timeout), entries))

    failed = sum(1 for ok, _ in results if not ok)
    total = len(entries)
    summary = [f"Checked {total} channels: {total - failed} working, {failed} failing."]

    if total and failed / total > a.max_fail_ratio:
        summary.append(f"More than {a.max_fail_ratio:.0%} failed, so this looks like a runner "
                       "network problem. No changes made.")
        out = "\n".join(summary)
        print(out)
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
                f.write(out + "\n")
        return

    disabled_now, enabled_now = apply(lines, entries, results, state, a.threshold)
    still_off = sum(1 for e, (ok, _) in zip(entries, results) if e["disabled"] and not ok)

    if disabled_now:
        summary.append("**Disabled:** " + ", ".join(disabled_now))
    if enabled_now:
        summary.append("**Re-enabled:** " + ", ".join(enabled_now))
    if still_off:
        summary.append(f"{still_off} previously disabled channel(s) are still down.")
    pending = [v["name"] + f" ({v['fails']}/{a.threshold})" for k, v in state.items()
               if v["fails"] < a.threshold]
    if pending:
        summary.append("Failing but not disabled yet: " + ", ".join(pending))

    with open(a.playlist, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(lines))
    os.makedirs(os.path.dirname(a.state) or ".", exist_ok=True)
    with open(a.state, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2, ensure_ascii=False, sort_keys=True)
        f.write("\n")

    out = "\n\n".join(summary)
    print(out)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(out + "\n")


if __name__ == "__main__":
    main()
