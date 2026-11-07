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
| **Fix charging now** button, no-root mode: uses Shizuku (ADB "shell" rights) to limit USB power on/off, reset the USB-C port, or swap the power role, measuring the current after each try and remembering what works | No (needs Shizuku running) |
| **Fix charging now** button, root mode: switches the charging input off for 4 s and back on (same effect as the restart) | Yes |
| Turn charging OFF / ON buttons | **Yes** |
| Auto-fix: watchdog presses "Fix" for you (max 3× per plug-in) | Root or Shizuku |
| Quick Settings tile "Fix charging" (edit tiles → drag it in) | Root or Shizuku |

### No-root mode (Shizuku)
The Android "shell" user (what a PC gets over USB debugging) holds `MANAGE_USB`, so it
can command the USB-C port controller. [Shizuku](https://shizuku.rikka.app/) gives an
app that shell level on-device via Wireless debugging (no PC). It must be started again
after every reboot. Whether the port commands actually make the charger renegotiate
depends on the phone's USB driver, which is why the app verifies each one by measuring
the current.

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
