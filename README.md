# Universal Wi-Fi TV Remote (Kotlin + Jetpack Compose)

Local-only Android remote for Android TV / Google TV, Samsung Tizen, LG webOS and Roku TV.
No account, no cloud backend: discovery, pairing and control all happen on your Wi-Fi network.

## ⚠️ Build and verification status (read this first)

| Item | Status |
|---|---|
| Source code for all four adapters, UI, storage, discovery | Written |
| Compiled with Gradle / Android Studio | **Not done.** The environment this was generated in had no Android SDK and blocked `dl.google.com`, `maven.google.com` and `services.gradle.org`. Expect to fix a few compile errors on first sync. |
| Debug APK | **Not produced.** Build it yourself (below). |
| Unit tests (`ProtocolTest`) | Written, **not run**. They are pure-JVM (protobuf framing, app id tables, capability table). |
| Tested on physical TVs | **No TV of any platform has been tested.** Nothing here has been run against hardware. |
| Simulator/emulator coverage | None. |
| Android TV adapter | Most complete (discovery, pairing, keys, deep-link launch), built first as requested, but still unverified. |
| Roku / webOS / Tizen adapters | Implemented to the same `TvAdapter` interface from public protocol documentation; unverified. |

Treat every cell in the compatibility matrix below as **"implemented, untested"** until you tick it off in the
verification checklist.

## Build

1. Install Android Studio (Ladybug or newer) with Android SDK 35.
2. Open this folder (`UniversalTvRemote/`). Let Gradle sync (it downloads the wrapper/AGP; if no wrapper is present,
   Android Studio will offer to create one, or run `gradle wrapper --gradle-version 8.10.2` once).
3. Run `./gradlew :app:assembleDebug`. The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.
4. Run unit tests with `./gradlew :app:testDebugUnitTest`.
5. Install on a phone: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The phone and TV must be on the **same Wi-Fi network/VLAN**. Guest networks and "AP/client isolation" block discovery.

## Architecture

```
core/        TvAdapter interface, RemoteKey, Feature, TvDevice, AppIds, Compatibility table
adapters/    AndroidTvAdapter, RokuAdapter, WebOsAdapter, TizenAdapter  (one per platform, same interface)
discovery/   mDNS (Android TV) + SSDP (Roku / LG / Samsung) + manual-IP probing
data/        TvStore: saved TVs, favorites and pairing credentials in EncryptedSharedPreferences (Android Keystore)
net/         Protobuf helpers, TLS for self-signed LAN TVs, Wake-on-LAN, sleeping-vs-unreachable classification
ui/          Compose: Remote, Apps and TVs tabs, pairing dialogs, status banner
```

Adding a platform means implementing `TvAdapter` and registering it in `AppViewModel`.
The UI disables controls that the connected adapter does not advertise via `features()`.

## Pairing guide

| Platform | What you do |
|---|---|
| Android TV / Google TV | TVs tab → Scan (or Add by IP) → Pair. The TV shows a 6-character code; type it in the phone. |
| Roku | No pairing. On the TV: Settings → System → Advanced system settings → **Control by mobile apps** → *Default* or *Permissive*. |
| LG webOS | Tap Pair; accept "Allow this device" on the TV. The TV returns a key which is stored securely. |
| Samsung Tizen | Tap Pair; choose **Allow** on the TV. If previously denied: TV Settings → General → External Device Manager → Device Connection Manager → Device List, and remove/allow the entry. |

If pairing is denied or times out, the app shows a retry dialog; pairing is never retried silently.

## Power-on over Wi-Fi

Only attempted where the platform supports it; otherwise the app says so and shows the setting to change.

| Platform | Method | TV setting (names vary by firmware) |
|---|---|---|
| Roku TV | ECP `PowerOn` | Settings → System → Power → Fast TV start |
| LG webOS | Wake-on-LAN | Settings → General → Devices → External Devices → TV On With Mobile → Turn on via Wi-Fi |
| Samsung | Wake-on-LAN | Settings → General → Network → Expert Settings → Power On with Mobile |
| Android TV | Wake-on-LAN, if a MAC is known | Wake on LAN / Wake on Wi-Fi; often Ethernet-only. Not available on many models |

The app distinguishes **Sleeping** (host or a control port still answers; display/control is off) from
**Not found** (nothing answers: wrong network, unplugged, IP changed). This is a heuristic; some TVs vanish from the
network entirely in deep standby and will show as Not found. When the TV is Not found, the app periodically rescans and
follows the TV if its IP changed.

## Compatibility matrix (implemented, UNTESTED)

✅ = implemented in code · ➖ = not available through the platform's protocol as used here · ⚠️ = partial/firmware-dependent

| Feature | Android TV / Google TV | Samsung Tizen | LG webOS | Roku TV |
|---|---|---|---|---|
| Local discovery | ✅ mDNS | ✅ SSDP + :8001 info | ✅ SSDP (name unknown) | ✅ SSDP + ECP info |
| Manual IP | ✅ | ✅ | ✅ | ✅ |
| Pairing | ✅ PIN on TV | ✅ Allow on TV (token) | ✅ Allow on TV (client key) | ➖ none (setting on TV) |
| Power off / toggle | ✅ | ✅ | ✅ | ✅ |
| Power on over Wi-Fi | ⚠️ WoL, model-dependent | ⚠️ WoL, 2016+ | ⚠️ WoL | ✅ ECP (Fast TV start) |
| Home / Back / Menu | ✅ | ✅ | ✅ | ✅ (Menu = Info) |
| Settings key | ✅ | ✅ (`KEY_TOOLS`) | ✅ (maps to Menu) | ➖ |
| Input / Source key | ✅ | ✅ | ✅ (input picker) | ✅ |
| D-pad + OK | ✅ | ✅ | ✅ | ✅ |
| Volume ± / Mute | ✅ | ✅ | ✅ | ✅ (TV only) |
| Channel ± | ✅ | ✅ | ✅ | ✅ (TV only) |
| Number pad | ✅ | ✅ | ✅ | ➖ |
| Play/Pause, FF/RW, Next/Prev | ✅ | ⚠️ no toggle key; Prev/Next missing | ⚠️ Prev/Next missing | ⚠️ Prev = instant replay; Next missing |
| Swipe touchpad | ⚠️ swipes become D-pad steps | ⚠️ D-pad steps | ✅ pointer socket | ⚠️ D-pad steps |
| Phone keyboard text entry | ⚠️ per-character keys (letters/digits only) | ⚠️ `SendInputString` | ✅ IME insertText | ✅ `Lit_` keys |
| Native press-and-hold | ✅ | ✅ | ➖ (repeat clicks) | ✅ keydown/keyup |
| One-tap YouTube/Netflix/Prime/Disney+/Spotify | ✅ deep links, cannot detect "missing" | ✅ app ids | ✅ app ids | ✅ channel ids |
| List installed apps | ➖ not exposed | ⚠️ many models don't answer | ✅ | ✅ |
| Detect "app not installed" | ➖ | ⚠️ only if list is returned | ✅ | ✅ |
| Direct HDMI 1–4 shortcuts | ➖ (Source key only) | ➖ (Source key only) | ✅ `switchInput` | ✅ `InputHDMIx` |

Model/firmware are recorded when the TV reports them (Roku, Samsung) and shown in the TVs tab; per-model feature
overrides are not populated yet. `Compatibility.base` in `core/Core.kt` is where they would go once hardware testing
finds real differences.

App ids/deep links in `AppIds` are community-documented values; verify them on your region/firmware.

## Known limitations of this first version

- Android TV text entry sends individual key events; the full IME batch-edit message is not implemented.
- Android TV cannot report whether a deep-linked app is installed; a missing app fails silently on the TV.
- Self-signed TLS is trusted only for connections to the TV you pick (`LocalTls`). Android TV pairing is authenticated
  by the PIN hash over both certificates; webOS/Tizen rely on the on-screen approval.
- No Android TV (device) build of the remote itself; this is a phone app.
- Hold-to-repeat on platforms without native press/release repeats clicks (~130 ms).

## Verification checklist (to run on real hardware)

Mark each as Physical (P) or Simulator (S) and record TV model + firmware. **None are done yet.**

Per platform (Android TV, Samsung, LG, Roku):
- [ ] Discovery finds the TV; manual IP works when discovery is blocked
- [ ] Pairing succeeds; credentials survive app restart (no re-pairing)
- [ ] **Denied pairing**: choose Deny / let it time out → retry dialog appears, no crash, nothing saved as paired
- [ ] Wrong PIN (Android TV) → can retry without restarting
- [ ] Every button in the matrix; press-and-hold on volume, channel, D-pad
- [ ] Keyboard text entry into a search field
- [ ] One-tap launch for each of the five apps; **launching a missing app** shows the "isn't installed" message (or silently no-ops on Android TV)
- [ ] Apps tab: list, pin, reorder, remove, per-TV favorites persist
- [ ] HDMI shortcuts (webOS/Roku)
- [ ] Power-on from standby with the TV setting enabled and disabled (message shown when it can't)
- [ ] **Reconnection**: toggle phone Wi-Fi, power-cycle TV, change TV IP via DHCP → app recovers by itself
- [ ] **Switching between two TVs** (ideally different platforms) from the Remote tab chips; buttons go to the right TV
- [ ] Dark mode, TalkBack labels, large font sizes
