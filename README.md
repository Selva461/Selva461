# Charge Fix (OnePlus "fake charging" helper)

Fixes / detects the bug where, with a non-original cable, the phone shows the
charging bolt but the battery actually keeps draining until you restart.

**Install:** download [`release/ChargeFix.apk`](release/ChargeFix.apk) on the phone and open it
(allow "install unknown apps" when asked).

## What it does

| Feature | Needs root? |
|---|---|
| Big red/green screen: **really charging** vs **fake charging**, using the real current (mA) flowing into the battery | No |
| Watchdog notification that alerts you when the phone is "charging" but draining for ~1 min | No |
| **Fix charging now** button, no-root mode: the app's own ADB connection (Wireless debugging, paired once) limits USB power on/off, resets the USB-C port, or swaps the power role, measuring the current after each try and remembering what works | No (Wi-Fi needed) |
| **Fix charging now** button, root mode: switches the charging input off for 4 s and back on (same effect as the restart) | Yes |
| Turn charging OFF / ON buttons | **Yes** |
| Auto-fix: watchdog presses "Fix" for you (max 3× per plug-in) | Root or no-root mode |
| Quick Settings tile "Fix charging" (edit tiles → drag it in) | Root or no-root mode |

### No-root mode (built in, no other app)
The Android "shell" user (what a PC gets over USB debugging) holds `MANAGE_USB`, so it
can command the USB-C port controller. Charge Fix contains its own ADB client
([libadb-android](https://github.com/MuntashirAkon/libadb-android)) and pairs with the
phone's Wireless debugging once. During that first connection it grants itself
`WRITE_SECURE_SETTINGS`, so for each fix it switches Wireless debugging **on**, connects
to itself, starts a short-lived helper (`app_process`, as the shell user) that runs the
USB command, then disconnects and switches Wireless debugging **off** again.

Whether the port commands make the charger renegotiate depends on the phone's USB
driver, which is why the app checks each one by measuring the current.

### Why root for the rest?
Android does not allow normal apps to control the charger chip. The only way to
switch charging off/on is writing to kernel switches such as
`/sys/class/oplus_chg/battery/mmi_charging_enable` or
`/sys/class/power_supply/battery/input_suspend`, which only root can do.
Without root the app still tells you for sure whether it's really charging, so
you know when to unplug and restart.

### Real fix
The cause is the cheap cable failing the fast-charge (SUPERVOOC/Warp) handshake.
A proper cable rated for your OnePlus charger (or a reputable USB-C 3A/5A cable)
stops it happening at all.

## Build
`./gradlew assembleRelease` (Android SDK 34). CI builds the APK on every push.
