## LoopGuard 2.0.0

A complete rebuild of LoopGuard as a native Kotlin / Jetpack Compose application.
Every feature of 1.0 is preserved; the data model, the interface and the reminder
system are new.

**Download:** `LoopGuard-v2.0.0.apk` below. Tap it on your phone and allow installing
from your browser or file manager.

| | |
|---|---|
| Application ID | `com.loopguard.app` |
| Version | 2.0.0 (version code 2) |
| Minimum Android | 8.0 (API 26) |
| Targets | Android 15 (API 35) |
| Signature | `CN=LoopGuard`, SHA-256 `9E:C0:24:2F:93:43:5E:96:6A:60:1B:DD:04:84:05:08:BF:50:82:0F:42:F3:2F:98:41:43:22:1D:47:B9:E3:44` |
| Network permission | none |

### Upgrading from 1.0 — read this first

LoopGuard 1.0 was distributed as a **debug-signed** APK, so Android will not let 2.0
install over it. Export your data first and nothing is lost:

1. In LoopGuard 1.0: **Loops → Backup**, share the text to yourself.
2. Uninstall LoopGuard 1.0.
3. Install this APK.
4. **Settings → Backup and restore → Paste text**, choose **Replace everything**, import.

Every loop, date, note, completed item and history entry transfers. This is a one-time
break — all future versions update normally.

### Highlights

- Priority scores now **explain themselves**: every loop lists the factors and points
  behind its number.
- **Natural-language capture**: `Chase @Rebecca about the school place by friday !! #school`
  fills in the contact, date, urgency, tag and category — and shows what it understood.
- **Four follow-up tones** (Friendly, Professional, Firm, Final notice) written from the
  loop's real history, reference number and elapsed silence.
- **Voice capture**, **share-to-capture** and **24 templates** for school, medical,
  property, finance, government and work situations.
- **Restrained reminders**: one daily digest plus at most two critical nudges, with
  *Snooze* and *Mark done* on the notification itself.
- **Search, filters, tags, reference numbers, pinning, snooze, undo** and an insights
  view showing where your load sits and who has gone quiet.
- **Material 3** with light and dark themes and optional Android 12+ wallpaper colours.
- **Backup to a real file** through the Android file picker; the format stays readable
  by 1.0.

Full detail in [CHANGELOG.md](../blob/main/CHANGELOG.md).
