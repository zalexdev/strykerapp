STRYKER_RELEASE_STORE_FILE=/path/to/keystore.jks
STRYKER_RELEASE_STORE_PASSWORD=...
STRYKER_RELEASE_KEY_ALIAS=...
STRYKER_RELEASE_KEY_PASSWORD=...# StrykerOSS

> A free and open-source mobile pentest suite for Android. Authorized testing only.

StrykerOSS bundles a curated set of network, wireless and web security tools into a single rooted-Android application, exposing them through a unified, modern UI. It runs an Alpine `chroot` under `/data/local/stryker/release` so heavyweight tools (Nmap, Metasploit, Nuclei, Hydra, SearchSploit, etc.) execute natively on the device. A built-in terminal (drawer → **Terminal**, or the **Stryker Terminal** launcher icon) drops straight into that chroot — no external shell app required.

- **Package**: `com.zalexdev.stryker`
- **Version**: 4.5R
- **Min SDK**: 24 (Android 7.0) · **Target SDK**: 28
- **License**: [GNU GPL v3.0](LICENSE) (bundled third-party components keep their own licenses — see in-app *About → Open-source licenses*)
- **Project site**: [zalexdev.com](https://zalexdev.com)
- **Source**: [github.com/zalexdev/strykerapp](https://github.com/zalexdev/strykerapp)

---

## Capabilities

| Module | Description |
|---|---|
| **Dashboard** | Live overview of the chroot, USB adapters, mounted state and quick actions. |
| **WiFi networks** | Scan, deauth, handshake capture, WPS attacks (Pixie Dust, common pins, custom pins) via external monitor-mode adapters. |
| **Handshakes** | Local handshake storage with rename, share, export to OnlineHashCrack and on-device cracking via Hashcat. |
| **MAC changer** | Inline + dedicated MAC randomizer with persistent profiles. |
| **Router scan** | Bulk router enumeration and credential discovery (RouterScan v2, Hydra, custom auth lists). |
| **WhisperPair (BLE)** | Fast Pair device discovery, CVE-2025-36911 vulnerability check and full exploit chain (RAW/RETROACTIVE/EXTENDED_RESPONSE), post-pair account-key write and HFP audio capture/passthrough. |
| **Local network** | Nmap host discovery, port scans, OS fingerprinting, per-device exploit dispatch with a live terminal. |
| **Nmap** | Direct Nmap interface with custom scripts, NSE, and exported reports. |
| **Web scanner (Nuclei)** | Multi-target Nuclei scans with severity-grouped findings and per-finding evidence. |
| **Arsenal** | Custom exploit / scanner database with template arguments (`{IP}`, `{PORT}`, `{MAC}`, `{GW}`, `{MASK}`). |
| **HID Attacks** | DuckyScript-compatible USB HID injection — pure-Java parser (Hak5 v1 + v3 superset), 7 bundled keyboard layouts (US/GB/DE/FR/ES/IT/RU), live execution log and bundled sample payloads. |
| **USB Arsenal** | USB-gadget profile manager — toggle HID keyboard/mouse, mass-storage, RNDIS/ECM/ACM functions on the fly, customise VID/PID/serial, mount `.img`/`.iso` images as removable disks. |
| **Metasploit** | Native MSF console inside the chroot with sessions, payload generation and module browser. |
| **GeoMac** | OSM-based map of captured BSSIDs / handshakes with WiGLE-style export (KML/CSV). |
| **VNC desktop** | Stand-up an in-chroot XFCE/Xfce-VNC session and view it locally. |
| **Core manager** | Mount / unmount / repair the chroot, manage installed components. |

---

## Requirements

- **Rooted Android device** (Magisk or KernelSU recommended).
- **~1 GB free internal storage** for the chroot, bundled tools and signatures.
- **External monitor-mode USB Wi-Fi adapter** for handshake capture and deauthentication (Atheros AR9271 / Realtek 88XXAU recommended).
- **Gadget-capable kernel (optional)** for HID Attacks and USB Arsenal. Required kernel options:
  - `CONFIG_USB_CONFIGFS=y`
  - `CONFIG_USB_CONFIGFS_F_HID=y`
  - `CONFIG_USB_CONFIGFS_MASS_STORAGE=y` (for mass-storage profiles)
  - `CONFIG_USB_CONFIGFS_RNDIS=y` / `CONFIG_USB_CONFIGFS_ECM=y` (for network profiles)
  - kernel ≥ 3.19, `/sys/class/udc/` populated
  - NetHunter / KernelSU-Next kernels and most modern OEM kernels meet these requirements out of the box.

---

## Build

Standard Android Gradle build. Java 8 sources, ndk-build for native code, R8 minification for release.

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (minified + R8)
./gradlew assembleRelease

# Install on a connected device
./gradlew installDebug

# Lint
./gradlew lint
```

Output APKs land in `app/build/outputs/apk/`.

### Release signing

Configure these in `~/.gradle/gradle.properties` (or pass via `-P` / environment):

```properties
STRYKER_RELEASE_STORE_FILE=/path/to/keystore.jks
STRYKER_RELEASE_STORE_PASSWORD=...
STRYKER_RELEASE_KEY_ALIAS=...
STRYKER_RELEASE_KEY_PASSWORD=...
```

If the variables are not set, the release build is left unsigned so CI / contributors can still produce an APK.

---

## Installation (end users)

1. Install the APK on a **rooted** device (`adb install StrykerOSS-4.5R.apk` or sideload).
2. On first launch the in-app installer (`AppIntroActivity`) will:
   - Request root (`su`).
   - Request runtime permissions (storage, location, notifications, Bluetooth, audio).
   - Download and unpack the Alpine `chroot` core (`core.tar.gz`).
   - Mount the chroot at `/data/local/stryker/release`.
   - Install optional components (Metasploit, Nuclei, Hydra, SearchSploit).
3. Open the built-in terminal (drawer → **Terminal**) for a shell straight into the chroot.
4. Plug in a supported USB Wi-Fi adapter for monitor-mode features.

---

## Project layout

```
app/
├── src/main/java/com/zalexdev/stryker/
│   ├── MainActivity.java            # Single-Activity host, drawer navigation
│   ├── about/                       # About / info page
│   ├── appintro/                    # First-launch installer & slides
│   ├── arsenal/                     # Custom exploit/scanner database
│   ├── coremanger/                  # Chroot manage / repair UI
│   ├── custom/                      # POJO domain models
│   ├── dashboard/                   # Home dashboard
│   ├── geomac/                      # OSM map for captured BSSIDs
│   ├── handshakes/                  # Handshake browser + cracking
│   ├── hid/                         # HID Attacks: DuckyScript engine, keymaps, executor, UI
│   ├── hydra/                       # Hydra integration
│   ├── localnetwork/                # LAN scan + exploit dispatch
│   ├── macchanger/                  # MAC randomizer
│   ├── metasploit/                  # MSF integration
│   ├── nmap/                        # Nmap UI
│   ├── nuclei/                      # Web vuln scanner
│   ├── routerscan/                  # Router enumeration
│   ├── searchsploit/                # ExploitDB browser
│   ├── settings/                    # User settings
│   ├── usbarsenal/                  # USB Arsenal: gadget profiles, configfs orchestration
│   ├── utils/                       # Core helpers, process wrappers
│   ├── vnc/                         # In-chroot VNC desktop
│   ├── wifi/                        # WiFi scan / attack
│   └── wpair/                       # WhisperPair (BLE Fast Pair) module
├── src/main/jni/                    # Native code (ndk-build)
├── src/main/assets/                 # Chroot scripts, wordlists, busybox
└── src/main/res/                    # Layouts, drawables, strings, themes
```

---

## Contributing

PRs and issues are welcome at [github.com/zalexdev/strykerapp](https://github.com/zalexdev/strykerapp).

When adding a feature:

- Keep modules self-contained under `com.zalexdev.stryker.<module>`.
- Reuse `Core.java` helpers for SharedPreferences, SQLite, asset extraction and root process execution rather than re-rolling them.
- Funnel root commands through `Core.generateSuProcess()` (direct `su` or chroot dispatch).
- Match the existing Material 3 design language — `MaterialCardView`, `MaterialButton`, dashboard accent colors, monospace terminals.
- Keep `targetSdk = 28` unless you are ready to migrate all storage / permission code paths.

---

## License

StrykerOSS is free software: you can redistribute it and/or modify it under the
terms of the **GNU General Public License v3.0** as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version.
The full text is in [`LICENSE`](LICENSE).

```
StrykerOSS — a mobile pentest suite for Android.
Copyright (C) 2021-2026 zalexdev

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

Bundled third-party components (terminal emulator, SDL/Xorg, custom-tabs, wordlists,
PoCs, etc.) are distributed under their own GPLv3-compatible licenses — see
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and the in-app
*About → Open-source licenses* screen for attributions.

---

## Disclaimer

StrykerOSS is provided **for authorized security testing, education and research only**. You are responsible for complying with all applicable laws and obtaining explicit permission before testing any system or device you do not own. The authors accept no liability for misuse.
