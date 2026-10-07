# Third-Party Notices

StrykerOSS is licensed under the **GNU General Public License v3.0** (see [`LICENSE`](LICENSE)).
It incorporates and/or bundles the third-party components listed below. Each component
remains under its own license; only the combined work is distributed under the GPLv3.
All listed licenses are GPLv3-compatible. The in-app *About → Open-source licenses*
screen carries the runtime attribution list.

> Corresponding source for every GPL-licensed binary distributed with the app is
> available from its upstream project (linked below) and on request.

---

## Bundled modules (source)

| Component | Origin | License | Notes |
|---|---|---|---|
| Terminal emulator (`terminal/`) | [NeoTerm](https://github.com/NeoTerm/NeoTerm) (© imkiva) built on [Termux](https://termux.com) terminal-emulator (© 2016–2017 Fredrik Fornwall) | **GPL-3.0** | Strong-copyleft core; the reason the combined work is GPLv3. |
| `NeoTermBridge/` | NeoTerm remote-execute bridge (© imkiva) | **GPL-3.0** | Package renamed `io.neoterm.bridge` → `com.stryker.terminal.bridge`. |
| `NeoLang/` | NeoLang config language (NeoTerm / Kiva) | **Apache-2.0** | Compatible one-way into GPLv3. |
| `Xorg/` | [libsdl-android / XServer XSDL](https://sourceforge.net/projects/libsdl-android/) (© 2009–2014 Sergii Pylypenko) | **zlib** | `GLSurfaceView_SDL.java` © 2008 The Android Open Source Project — **Apache-2.0**. |
| `chrome-tabs/` | [ChromeLikeTabSwitcher](https://github.com/michael-rapp/ChromeLikeTabSwitcher) (© 2016–2017 Michael Rapp) + android-util | **Apache-2.0** | |

Additional libraries referenced by the terminal module (EventBus, RecyclerView-FastScroll,
RecyclerTabLayout, SortedListAdapter/ModularAdapter, Color-O-Matic, etc.) are listed with
their licenses in the in-app *About → Open-source licenses* screen.

## Bundled binaries & data (APK assets)

| Asset | Origin | License | Notes |
|---|---|---|---|
| `busybox64` | [BusyBox](https://busybox.net) v1.36.1 (osm0sis build) | **GPL-2.0-only** | Shipped as a standalone executable, invoked via `exec` from shell scripts (mere aggregation / separate program). Not statically linked into app code. Corresponding source: busybox.net. |
| `bash` | [GNU Bash](https://www.gnu.org/software/bash/) 5.2.37 | **GPL-3.0-or-later** | Unmodified upstream source, signature-checked against GNU's; built by `images/tools/build-bash.sh`, which records the exact flags. |
| `sqlite3` | [SQLite](https://sqlite.org) 3.21.0 | **Public Domain** | |
| `devices.txt` | [linux-usb.org `usb.ids`](http://www.linux-usb.org/usb-ids.html) | **GPL-2.0+ / BSD-3 (dual)** | USB vendor/product database. |
| `checker.py` | CVE-2022-27255 PoC — [infobyte/cve-2022-27255](https://github.com/infobyte/cve-2022-27255) (© Martin Tartarelli, Octavio Gianatiempo) | upstream PoC | Attribution preserved in file header. |
| Fonts: `SourceCodePro.ttf`, `ZedMono*.ttf`, `UbuntuMono.ttf`, `eks_font.ttf` | Adobe Source Code Pro; be5invis Iosevka/Zed Mono; Canonical Ubuntu Mono; Google Noto | **OFL-1.1** / **UFL-1.0** | License texts retained; OFL Reserved Font Names respected. |

## Guest-core payload (`assets/rootless/stryker-guest-core.tar.enc`)

Unpacked into the Linux environment at first boot. Each tool runs as a separate program
inside the guest and is aggregated with, not linked into, Stryker.

| Path in tar | Origin | License |
|---|---|---|
| `CORE/SMB/mysmb.py`, `CORE/RDP/mysmb.py`, `exploits/mysmb.py` | derived from [impacket](https://github.com/fortra/impacket) (© SecureAuth / Fortra) | **Apache-2.0** |
| `CORE/Cameradar/credentials.json`, `CORE/Cameradar/routes` | [Ullaakut/cameradar](https://github.com/Ullaakut/cameradar) RTSP route and credential dictionaries | **MIT** |
| `CORE/PixieWps/pixie.py` | derived from OneShotPin 0.0.2 (© 2017 rofl0r, modified by drygdryg / kimocoder), with ideas from [OneShot-Extended](https://github.com/chkndrp/OneShot-Extended); substantially rewritten for Stryker | **GPL-3.0** |

Corresponding source and the full license text of each are available from the upstream
projects linked above and on request.

## Distributed by this project (GitHub releases and/or APK assets)

| Artifact | Origin | License | Notes |
|---|---|---|---|
| `qemu-system-aarch64` | [QEMU](https://www.qemu.org) — custom build (`--enable-libusb`, virtfs) | **GPL-2.0-only** | Not stock Debian QEMU. Corresponding source and build configuration on request. |
| `Image`, `initrd.img` | Debian kernel `6.12.94+deb13-arm64` | **GPL-2.0** | Unmodified Debian binary; source via `apt source linux`. |
| `libslirp.so` | [libslirp](https://gitlab.freedesktop.org/slirp/libslirp) | **BSD-3-Clause** | Binary redistribution requires the upstream copyright notice, reproduced in the release notes. |
| `rootfs.imgz`, `chroot64-debian.tar.gz` | Debian trixie arm64 | per-package | `/usr/share/doc/<package>/copyright` inside the image; see <https://www.debian.org/legal/licenses/>. |

## Runtime-downloaded tools (not distributed by this project)

Metasploit Framework, Nuclei, Hydra and SearchSploit are fetched at first launch from their
own upstream sources and remain under their respective upstream licenses.
