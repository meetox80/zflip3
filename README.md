<h1 align="center">zflip3</h1>

<p align="center">Magisk modules for running a GSI on the Galaxy Z Flip3 (SM-F711B) without losing the cover screen.</p>

<p align="center">
  <a href="#modules"><b>Modules</b></a> &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="#building"><b>Building</b></a> &nbsp;&nbsp;|&nbsp;&nbsp;
  <a href="#license"><b>License</b></a>
</p>

<br>

A GSI doesn't know about the cover screen, so when you fold the phone Android moves onto the 512x260 panel as if it were a tiny phone. `flip3_cover` keeps Android on the main screen and runs its own launcher on the cover, written from scratch to look and work like the One UI 7 one. `flip3_smsready` fixes incoming texts on Samsung's RIL.

## Modules

| Folder | Magisk id | What it does | State |
|---|---|---|---|
| [cover](cover) | `flip3_cover` | Cover screen: clock, widgets, notifications, music, quick panel | Working |
| [sms](sms) | `flip3_smsready` | Fixes incoming SMS never reaching Android | Working |
| [ims](ims) | `flip3_ims` | Port of Samsung's IMS service for VoLTE | Unfinished, don't use it for real calls |

Download the zips from [Releases](https://github.com/meetox80/zflip3/releases), install them in the Magisk app and reboot.

Tested on:

- SM-F711B, vendor from the F711BXXSJJZH3 stock firmware (One UI 7)
- Infinity-X 3.12 GSI, Android 16, `treble_arm64-ab-VANILLA`
- Magisk

## Building

You need Linux on x86_64 with curl, unzip, python3, [zig](https://ziglang.org), e2fsprogs (for `debugfs`), lz4 and android-tools (for `simg2img` and `lpunpack`). The JDK and the Android build tools are downloaded into `tools/` by the setup script.

```sh
scripts/setup-toolchain.sh
scripts/extract-stock.sh path/to/AP_F711B....tar.md5
scripts/build-cover.sh
scripts/build-sms.sh
scripts/build-ims.sh
```

The zips end up in `dist/`.

`extract-stock.sh` takes the AP file, a `super.img` or a `system.img` and copies the fonts, images and binaries the modules need into `stock/`. I used F711BXXSJJZH3. The scripts stop with an error if something they need isn't where they expect it. The SMS module doesn't need the firmware at all.

The first build creates a signing key in `signing/`. Keep it: the cover module reinstalls its app on boot, and that fails if the new APK is signed with a different key.

## License

Copyright &copy; 2026 kotwicha. Licensed under the [GNU General Public License v3.0](LICENSE).

The fonts, images and binaries the build takes from Samsung's firmware belong to their owners and aren't covered by this license. Samsung, Galaxy and One UI are trademarks of Samsung Electronics. This project isn't affiliated with Samsung.

<br>

<p align="center"><sub>made by <a href="https://github.com/meetox80">kotwicha</a></sub></p>
