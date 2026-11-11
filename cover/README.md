# Cover screen

`flip3_cover` keeps Android off the cover screen and runs its own launcher there. The launcher is written from scratch to match the One UI 7 cover screen: same layouts, sizes, fonts and animation timings.

## What's on it

- Clock with the stock faces (horizontal, typo, big time, small, two analog ones) in the ten stock colors, plus date and battery
- Swipe left for notifications. New ones pop up on the cover. Tap one to read all of it, clear it, open the app on the main screen or send a quick reply
- Swipe right for widgets: music, next alarm, today's schedule and any app widget you add
- Music has the album art colors and the squiggly progress bar, and you can seek
- Pull down for the quick panel: Wi-Fi, sound mode, Bluetooth, airplane mode, flashlight and brightness
- Long press to edit: add, remove and reorder widgets, pick a clock face and color
- Volume keys work while folded, swapped so up is still up, with a volume bar on the cover
- Double tap or the power key wakes it, and it goes off after 15 seconds without a touch
- While the phone is locked the schedule and app widgets are hidden, and notification text follows your lock screen privacy setting

Notification access, calendar permission and widget binding are granted by the module, there's nothing to set up.

## How it works

Keeping Android off the panel takes four pieces. Drop any one of them and Android is back on the cover.

1. `module/system/vendor/etc/displayconfig/display_layout_configuration.xml` replaces the vendor layout. Stock makes the cover the default display when folded (device state 0), so the GSI moves its whole UI there. Mine keeps the main screen as the default in every state and only enables the cover when folded, in its own display group so it gets its own power state.
2. `overlay/` builds a static overlay that sets `config_localPrivateDisplayPorts` to the cover's port, 131. SystemUI puts its keyguard presentation (the clock with the peach dot) on every connected display, above anything an app can draw. A private display doesn't get it.
3. `service.sh` creates a fabricated overlay that turns off `config_localDisplaysMirrorContent`, so the main screen never gets mirrored onto the cover.
4. `CoverDaemon` runs as root through `app_process`, started by `coverd.sh`. It reads the lid switch, power key and cover touch events, turns on cover touch through `/sys/class/sec/tsp2/input/enabled` (the kernel leaves it off) and starts `CoverActivity` on the cover with `am start --display`. An app can't see a private display until one of its activities is on it, so the launch has to come from root.

The daemon also does what Android can't on this panel:

- Brightness. `cmd display set-brightness` and window brightness never reach the cover. The app writes the level to a file and the daemon writes it to `/sys/class/backlight/panel1-backlight/brightness` (0 to 510).
- Double tap to wake. Writing `aot_enable,1` to `/sys/class/sec/tsp2/cmd` makes the touch chip send `KEY_WAKEUP` while the panel is off. Android sees that key too and wakes the main screen, so the daemon puts it back to sleep.
- Volume keys. Android swaps them on rotation, not on the hinge. `native/coverkeys.c` grabs the key devices while folded, sends volume through its own uinput device with up and down swapped, and tells the daemon about power and volume presses so it can show the volume bar.
- Wi-Fi, Bluetooth, airplane mode and sleep, which the app asks for through a command file the daemon polls.

The panel is 512x260 and stock draws it at density 160. The GSI reports 480, so `CoverActivity` forces 160 or everything would be three times too big.

## Good to know

- The daemon logs to `/data/adb/modules/flip3_cover/coverd.log`.
- If Android stops booting with the module on, it disables itself after three boots that never finish, or when a boot hangs for four minutes.
