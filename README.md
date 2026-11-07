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
| **Reset charging** button: switches the charging input off for 4 s and back on (same effect as the restart) | **Yes** |
| Turn charging OFF / ON buttons | **Yes** |
| Auto-fix: watchdog presses "Reset" for you (max 3× per plug-in) | **Yes** |
| Quick Settings tile "Fix charging" (edit tiles → drag it in) | **Yes** |

### Why root?
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
