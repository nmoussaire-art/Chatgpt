# Permissions and privacy

**All battery observations and forecasts remain on your device.**

That sentence appears in the app, and this document explains exactly what backs it.

---

## What leaves your phone

Nothing.

BatteryCast Quant holds **no `INTERNET` permission**. This is not a policy the app follows; it is a
capability the process does not have. A request to any network fails at the operating-system level.
The manifest goes further and includes an explicit removal directive:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
```

so that if any dependency ever declares the permission, the manifest merger strips it instead of
silently granting it. A Gradle task (`verifyNoNetworkPermission`), a unit test and an instrumented
test all fail the build if this is ever undone.

You can verify it yourself on any built APK:

```bash
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

There are also:

- no analytics SDKs
- no advertising SDKs
- no crash-reporting SDKs that upload device data
- no user accounts
- no cloud database, sync or remote model
- no remote configuration

`ProductionPurityTest` asserts that none of the common vendors appear anywhere in the build file.

Cloud backup and device-to-device transfer are **disabled** (`allowBackup="false"` plus explicit
exclusion rules), so the observation database is not copied off the device by the system either.

---

## What is collected

Only what is needed to forecast a battery, all of it stored locally in a Room database on the
device.

**Battery state**: percentage, charging status, plug type, health, voltage, temperature, charge
counter, instantaneous and average current, remaining energy — whichever of these the device
actually reports.

**Device context**: whether the screen was interactive, whether power saving was on, the *transport
type* of the active network (Wi-Fi / cellular / offline), thermal status, Bluetooth radio on/off,
time of day, day of week, and a reconstruction of recent screen-on time from the app's own stored
observations.

**Optionally, with usage access**: how much foreground time occurred in the last half hour, and
which *broad category* dominated it — video, game, maps, social, and so on.

## What is never collected

- Message, email or notification contents
- Browsing history
- Typed text
- Documents, photos or files
- Contacts
- Location, at any precision — the app requests no location permission of any kind
- Wi-Fi network names, addresses or any network traffic
- **Which apps you used.** Package names are read to look up a category and are discarded
  immediately; only the category is ever stored.

---

## Permissions, one at a time

### `INTERNET` — not requested

Actively removed, as above.

### `RECEIVE_BOOT_COMPLETED` — normal, granted at install

Re-arms the periodic observation work after a restart or an app update. Without it the history
gains a hole after every reboot, and battery history with holes produces worse forecasts.

Also used to take one reading immediately after boot, which is what lets the app mark the boot
boundary correctly instead of differencing a charge counter across it.

### `ACCESS_NETWORK_STATE` — normal, granted at install

Reads the **transport type** of the active network and nothing else: Wi-Fi, cellular, ethernet, or
offline. Cellular data typically costs noticeably more battery than Wi-Fi, and the model treats
them as different states.

No SSID, no addresses, no traffic, and no ability to use the network.

### `POST_NOTIFICATIONS` — runtime, optional

Used for threshold alerts. Both notification channels are **low importance and silent** — an alert
that vibrates your phone to tell you the battery might run out is its own small irony.

Deny it and everything in the app still works; you simply are not interrupted.

Alerts fire only when a probability **crosses** a threshold, never while it merely sits below one,
with hysteresis (it must recover to 90 % before the same alert can fire again) and a cooldown
(3 hours for survival alerts, 6 for unusual drain). Nothing fires at all until the model is mature
enough for the probability to mean something.

### `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` — normal

Used **only** for a precision session that you start yourself. That service:

- never starts on its own;
- shows an ongoing notification for its entire life, stating that it uses slightly more power;
- is hard-capped at 20 minutes and stops itself;
- is `START_NOT_STICKY`, so the system will not silently restart it;
- holds no wake lock.

Routine background collection uses WorkManager and no service at all.

### `READ_CALENDAR` — runtime, optional

Buys exactly one thing: the ability to say "your flight is at 8 PM" instead of making you type a
time.

Reads only the **title, start and end** of events in the next 48 hours. All-day events are skipped
because they carry no useful target time. Nothing is stored — when you pick an event, only the
timestamp and the label you saw are kept — and nothing is uploaded, because the app cannot upload
anything.

Deny it and the app uses the quick targets (bedtime, midnight, in two hours, custom) instead. Every
forecast works identically.

### `PACKAGE_USAGE_STATS` — special access, optional, granted only in system settings

The strongest permission the app can ask for, so it asks for the least it can make use of.

With it granted, BatteryCast can distinguish "navigation-like" from "gaming-like" usage rather than
calling both "heavy use", which makes the scenario comparisons more useful.

What it will **not** do with it:

> BatteryCast never claims that a specific app used a specific amount of battery.

Android does not give third-party apps a per-app energy figure that can be defended, so the app
does not pretend to have one. The strongest statement it will make is a coincidence in time:

> *"High screen usage and extended video-app activity coincided with increased battery drain."*

and never:

> ~~"YouTube consumed exactly 12.4% of your battery"~~

Deny it and the whole app still works; regime classification simply stays at the broader
categories, which is exactly what it does before you have granted anything.

### `WAKE_LOCK` — appears in the merged manifest, not requested by this app

WorkManager declares this permission, and it appears in the built APK for that reason. It is what
lets a background worker finish once the system has started it. **No BatteryCast code acquires a
wake lock**, and the app never keeps the device awake to take a measurement — which is why samples
during Doze may be spaced further apart than the nominal 15 minutes, and why the model is built to
handle irregular spacing.

---

## Your data

**Export.** Settings → Your data → Export CSV writes every stored observation to a file in the
app's cache directory and offers it through the system share sheet. Unmeasured fields are written
as empty cells, never as zero, so the export says exactly what the model saw. The file exists only
where you send it.

**Delete.** Settings → Your data → Delete all data removes every observation, everything the model
has learned about your phone, every recorded forecast, and all notification state. Collection
starts again from nothing.

**Retention.** Observations older than 28 days are pruned automatically. Twenty-eight days is
enough for day-of-week personalisation without unbounded growth.

---

## Verifying these claims

Every claim in this document is checked by the build:

| Claim | Checked by |
|---|---|
| No `INTERNET` permission | `verifyNoNetworkPermission` (Gradle), `ProductionPurityTest`, `PermissionAndOfflineTest` on device |
| No analytics or crash-reporting SDKs | `ProductionPurityTest` |
| No location or contacts permission | `PermissionAndOfflineTest` on device |
| The app requests only the permissions listed here | `PermissionAndOfflineTest` on device |
| The forecast works with every optional permission denied | `PermissionAndOfflineTest` on device |
| Usage insights return *nothing* rather than zero when denied | `PermissionAndOfflineTest` on device |
| The database ships empty | `ObservationPersistenceTest` on device |
| No sample or fake data in production sources | `verifyNoSampleData` (Gradle), `ProductionPurityTest` |
