# VoLTE port (unfinished)

An attempt at running Samsung's own IMS stack, `com.sec.imsservice` from the stock firmware, on the GSI without patching the framework. Without IMS every call on the GSI falls back to 2G. I've stopped working on this. It's here because a good part of it works and someone might want to carry on, but **don't install it on a phone you rely on for calls**.

Where it got to on Play (Poland):

- Samsung's stack registers with Play's IMS
- Outgoing calls connect
- One incoming call worked with audio both ways and showed up as VoLTE/HD in the call log
- The next incoming call froze on the answer screen and locked up the whole phone until I forced a reboot. No logs survived. That's the bug I never fixed.
- On a second Play SIM without an ISIM, Android decided IMS wasn't available and turned it off. I never found out why.

There's no Wi-Fi calling.

It's set up for Play. `module/post-fs-data.sh` sets the CSC to `PRT` (Play) so Samsung's stack loads the right carrier profile. For any other carrier you'd have to change those properties to its sales code.

## How it's put together

`scripts/build-ims.sh` takes the stock APK and libraries from `stock/` and builds a module that installs them as a privileged app.

- The APK loses `sharedUserId=android.uid.system` and `persistent` and gets signed with the local key, so it runs under its own uid in `priv_app`. It gets a privapp permission allowlist and a hidden API exemption. Dropping `persistent` matters: a persistent app that crash-loops sets off RescueParty, which resets your settings.
- `scripts/ims/patch_dex.py` rewrites calls to Samsung-only framework methods, mostly `TelephonyManager` and `ServiceState` ones. It also drops the `ServiceManager.addService` calls an app uid isn't allowed to make, sends `SystemProperties.set` through a shim that logs instead of throwing, points the all-users handles at user 0 and fills in `Build.VERSION.SEM_INT` from the stock build.prop.
- Samsung's `imsmanager`, `vsimmanager`, `EpdgManager` and `rcsopenapi` jars are added to the APK as extra dex. `shim/java` has hand-written versions of the framework classes the stack actually uses (`SemSystemProperties`, `SemFloatingFeature`, `SemCscFeature` and a few more). `scripts/ims/gen_shim.py` generates empty ones for the rest, which only get touched off the VoLTE path. `missing-types.txt` lists every class the APK uses that the GSI doesn't have.
- `scripts/ims/patch_manifest.py` removes the protected broadcast and `uses-library` declarations, requests the permissions a system uid app gets for free, and registers `AbsentProvider` for content provider authorities the stack watches but that don't exist on the GSI.
- `imsd` (IPsec helper) and `multiclientd` (the stack needs it to reach rild, otherwise calls drop a second after answering because audio never starts) are the stock binaries. `native/imsdstart.c` and `native/multiclientstart.c` start them with the uid, groups and capabilities they expect. `service.sh` writes the IMS app's uid into multiclientd's table of allowed callers, and `check_multiclient.py` makes sure that table is where the build expects.
- `libsec-ims.so` reads `/proc/net/if_inet6`, which apps can't read anymore. `patch_native.py` points it at an fd that `ImsProcessNetwork` fills with the IMS network's addresses.
- Two static overlays point telephony at `com.sec.imsservice` and mark VoLTE as available. `post-fs-data.sh` adds the SELinux rules for the radio HALs, hwbinder and the imsd socket.

Same boot guard as the cover module: it disables itself after three boots that never finish, or when a boot hangs for four minutes.

## Sign-out tool

`signout/ImsSignout.java` registers with the carrier's IMS core using the SIM, with AKA and IPsec like a phone does, and then deregisters. I wrote it when I thought a registration left behind by the port was swallowing my texts. It wasn't (the real cause is in [sms](../sms)), but the tool works, and it's a small self-contained SIP REGISTER over IPsec if you ever need one.

```sh
scripts/build-signout.sh
adb push dist/imssignout.jar /data/local/tmp/
adb shell su -c 'CLASSPATH=/data/local/tmp/imssignout.jar app_process /system/bin ImsSignout'
```

It masks your IMSI, phone numbers and the auth values in what it prints.
