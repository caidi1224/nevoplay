# Notes on DiPlay (a downstream xcertplay project)

Reference notes on [DiPlay](https://github.com/shihabal3amri/DiPlay) — what it does differently,
what this fork took from it, and what it deliberately did **not** take. Written after reading its
source against upstream xcertplay 1.3.1 and against this fork.

## What it is

DiPlay is a **downstream project based on xcertplay** (`README.md`: "Based on
[xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0"), aimed at BYD head units. GitHub
reports `isFork: false` because its history was re-rooted, not because the code is unrelated: the
`automotive` module still carries xcertplay's `versionCode`/`versionName`, and the package tree is
`com.shilapi.xcertplay`.

## Licence boundary — read this before copying anything

- Overall **GPL-3.0**, the same licence as this repository, so code can be borrowed **with
  attribution**.
- `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` is marked
  `SPDX-License-Identifier: AGPL-3.0-only` ("UI copy and visual language adapted from DiAuto"), and
  the static site under `site/` is likewise AGPL-3.0. `shared/src/main/java/com/shilapi/xcertplay/hud/`
  shares a copy with DiAuto. **Do not move any of that into this GPL-3.0 tree.**
- Everything under `shared/src/main/java/com/shilapi/xcertplay/{media,network,airplay,mfi,transport}`
  and the non-UI parts of `orchestration/` carry no AGPL marker and continue xcertplay's structure;
  those are the files referenced below.

## Borrowed

| Area | What we took | Why |
| --- | --- | --- |
| Diagnostics | `TouchLatencyProbe` (touch → next frame), `readMaxMs` / `processMaxUs` split in the receive path, `rx` vs **`shown`** fps, `kbps`, `recoveries`, and the 2 s idle cap on arrival gaps | The stutter is only reproducible while dragging on the map; measured touch→frame latency is the only number that speaks to the user's complaint directly. Splitting socket wait from local processing separates "the phone sent nothing" from "our receive thread was held up", and the idle cap stops a static screen from looking like a stall. |
| Log persistence | 8-generation rotation instead of truncating at the size cap, plus a redactor that runs **before** anything reaches storage | A freeze usually ends with a force-stop; a log that is truncated at the cap can destroy exactly the evidence needed. The redactor also keeps Wi-Fi passphrases and long payload dumps out of the file that gets shared. |
| Wi-Fi P2P | Group **identity** (only reclaim a group this app owns, tracked by a persisted `owned_ssid`) and, after `removeGroup`, **poll until the group is actually gone** before creating a new one | Our reconnect path retried `createGroup` while the framework still held the old group; the observed result was `Wi-Fi P2P is busy` 110 times over 124 s. Waiting for the removal to take effect is what the framework actually requires. |
| Screen-stream state | Replaying the active stream set when the state listener is re-installed | If a screen stream is already active and the listener is replaced, the host never learns it — the picture stays frozen with no indication. This is the most plausible code-level explanation for the freeze after saving settings. |
| Audio | `MAX_QUEUED_PACKETS` 64 → 192, polling the decoder every 10 ms even when no packet arrives, and rebuilding the music buffer after starvation | 64 AAC packets is ≈1.49 s, and our start threshold was ≈23 ms: a normal Wi-Fi gap underruns the track and the burst that follows is dropped. Their own changelog still lists occasional audio cutouts, so this is mitigation, not a fix. |
| Lockdown recovery | Recognising `InvalidHostID` as a rejected pair record, and only clearing a *saved* record (DiPlay `842b647`) | iOS answers `InvalidHostID` when it no longer knows the host identity in the record; the old code rethrew that as a hard failure. Clearing a record this run had just created could also throw away a good pairing for an unrelated error. |
| Audio receive buffer | A 512 KiB `SO_RCVBUF` on the RTP socket, with the granted size logged (part of DiPlay `e0aa67a`) | The platform default holds a handful of packets, so a Wi-Fi scheduling hiccup drops frames no decoder-side change can bring back. The log line says whether the request was actually honoured. |
| Video decoder ladder | Configure the tuned format, then a minimal one, then the software decoder by name (DiPlay `260a5a0`) | A vendor decoder can answer `BAD_VALUE` to the tuned keys (input size, priority, colour metadata, low latency); with a single attempt one rejected configure left the decoder restarting for every frame that followed. Our tuned rung also keeps the SPS-derived colour keys, which their minimal rung drops. |
| Day/night | `nightModeOrNull`: `UI_MODE_NIGHT_UNDEFINED` keeps the palette already on screen (DiPlay `c1195aa`) | Undefined is not daylight. Reading it as light flipped this panel to the light theme mid-drive. Their companion change polls `resources.configuration` because BYD firmware updates it without delivering `onConfigurationChanged`; **not** taken here - this fork has its own vehicle-callback latches and no evidence of that failure yet. |
| Wireless handoff | Keep the session when a frame has already been rendered, instead of the 45 s teardown (DiPlay `885dffb`, its `WirelessConnectionProof.hasRenderedFrame`) | Some iPhone/firmware combinations never open the type-130 tunnel yet render video happily; the teardown is what the user sees as a reconnect loop. Narrower than upstream `de9647f`, which continues unconditionally. Ours records the proof in the controller and latches a replayable listener on the sink, so a session adopted from the background still counts. |

## Deliberately not borrowed

| Not taken | Reason |
| --- | --- |
| Their decoder latency policy (drop the backlog when a queued frame is older than 250 ms) | 250 ms is above the 150–202 ms output-age spikes this fork measured, so it would never fire here. They also **removed** the `maxOutputAgeMs` observation that produced that evidence; our stats line keeps it. |
| Their `lowLatency` handling | Behaviourally identical to ours, and `c2.qti.hevc.decoder` does not advertise `FEATURE_LowLatency`, so neither project has a lever there. |
| Non-blocking queue that drops frames on overflow | It changes what the user sees and is unproven for our spikes; kept as a documented option, not a default. |
| Their link-local IPv6 policy (prefer scoped link-local, refuse an unscoped one) | The opposite of this fork's decision (IPv4 first, never advertise link-local). Both fix "the phone cannot reach the address" under different conditions; ours was chosen after a device log showed only `fe80::` being offered. Treat theirs as a comparison experiment, not a replacement. |
| Their hotspot interface selection | It still excludes the primary interface by name, which is the bug this fork already fixed: on this head unit the interface carrying the hotspot *is* the active network. |
| Their `render` flag without the `BUFFER_FLAG_CODEC_CONFIG` guard | Looks like a regression: codec-config buffers would be released for render and would pollute the `shown` fps counter. |
| Their listener callback inside `synchronized(screenStateLock)` | Deadlock risk if a callback re-enters the sink. Replay from a snapshot, outside the lock. |
| Their offline MFi identity, packaged into the APK | An experimental accessory identity recovered from third-party firmware, with an extractable private key, by their own description. This fork authenticates through a real CH341-attached coprocessor and should keep doing so. |

## Things the comparison surfaced in *our* code

- **MFi startup wait.** `MfiRuntime.MFI_STARTUP_PROBE_TIMEOUT_MILLIS = 15_000` is handed to
  `MfiDeviceScanner`, which retries `0x10`/`0x11` every 20 ms until that deadline and only returns
  early on success. That is the 15.96 s observed in a device log, and 26 s is the same budget burned
  twice around an availability poll. DiPlay did not touch this path at all.
- **`ch341MfiResetGpio` is `null`**, so the reset pulse that would deterministically select
  coprocessor address `0x11` never runs, and the address is selected by the board's luck.
- **`WirelessHotspotManager` band labels.** `3` is labelled "6 GHz", but in `WifiConfiguration.apBand`
  and `SoftApConfiguration` terms `3` is the 2.4/5 GHz automatic case and `4` is 6 GHz.
- **`TcpLiveness`** (keepalive 10/3/3 plus `TCP_USER_TIMEOUT` 20 s) has no counterpart here, and is
  worth investigating for the `SocketException: Software caused connection abort` session endings.

## Attribution

DiPlay is GPL-3.0, based on xcertplay. Where this fork adapts its approach, keep this file and the
upstream notices with the code. Nothing here is copied from its AGPL-3.0 files.
