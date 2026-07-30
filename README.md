# LoopGuard

**An offline follow-through manager for the responsibilities that live between apps.**

The school that has not replied. The insurance approval that is still "processing". The
documents your accountant is waiting for. The thing someone promised you three weeks ago.
These do not belong in a to-do list, because half of them are not yours to do — they are
yours to *chase*.

LoopGuard keeps those two things apart, scores what is actually about to cost you money or
time, and writes the follow-up message for you.

- **Version:** 2.0.0 (version code 2)
- **Application ID:** `com.loopguard.app`
- **Minimum Android:** 8.0 (API 26) · **Targets:** Android 15 (API 35)
- **Network access:** none. The app does not declare `android.permission.INTERNET`.

---

## Install

1. Download `LoopGuard-v2.0.0.apk` from the [latest release](../../releases/latest).
2. Tap it. Android will ask you to allow installing from your browser or file manager — allow it.
3. Open LoopGuard.

**Upgrading from version 1.0 requires one manual step — see [Migrating from 1.0](#migrating-from-10).**

---

## What is in 2.0

Version 1.0 was a single HTML file inside a WebView. Version 2.0 is a native Kotlin and
Jetpack Compose application on a real database. Everything 1.0 could do, 2.0 still does.

### The core idea, sharpened

| | |
|---|---|
| **Waiting on me / waiting on them** | Still the spine of the app, now with a one-tap handover that records who took responsibility and when. |
| **Priority scoring** | Rewritten and, more importantly, **explained**. Every loop shows the factors that produced its number — impact, deadline, silence, whose move it is, how often you have already chased — with the points each one contributed. |
| **Focus queue** | The three loops most likely to cost you something, with a plain-language reason for each. |
| **Follow-up generation** | Four tones — Friendly, Professional, Firm, Final notice — that use the loop's own history: how long it has been silent, how many times you have chased, the reference number, the deadline that passed. |

### New in 2.0

- **Natural-language capture.** Type `Chase @Rebecca about the school place by friday !! #school`
  and LoopGuard fills in the contact, the date, the urgency, the tag and the category — and
  tells you what it understood, so it can never silently guess wrong.
- **Voice capture** through the system recogniser, feeding the same parser.
- **Share-to-capture.** Share text from your email or messaging app into LoopGuard and it
  becomes a parsed loop.
- **24 templates** across school, medical, property, finance, government and work. Each one
  carries a realistic deadline, the right responsibility side, and a prompt for the context
  you will wish you had written down.
- **Smart, restrained reminders.** One daily digest at a time you choose, plus at most two
  critical nudges — with **Snooze 1 day** and **Mark done** buttons directly on the
  notification. A follow-through app that nags about everything gets muted within a week.
- **Snooze and wake.** Push a loop out of the queue for a day, a week or a month; it returns
  on its own, and the snooze is on the record.
- **Search, filters and tags** across title, person, organisation, notes and reference number.
- **Reference numbers** — policy, claim, application, invoice — quoted automatically in
  follow-up messages, which is what actually gets an organisation to find your case.
- **Insights** that answer real questions: where your load is clustering, who has gone
  quiet longest, how long you take to close things.
- **Full timeline** on every loop, kept when you complete it, so you can prove what happened
  and when.
- **Material 3** throughout, with light and dark themes and optional Android 12+ wallpaper
  colours. Bottom navigation and bottom-anchored actions for one-handed use.
- **Undo** on complete, snooze and delete.
- **Backup, restore and export** to a real file through the Android file picker, plus
  paste-in text import.

### Accessibility

Content descriptions on every icon and on the priority rings (which announce the score and
band rather than just a number), semantic grouping on statistics, full support for system
font scaling, a 4.5:1 minimum contrast target in both themes, and no colour-only signals —
every state that uses colour also carries text.

---

## Migrating from 1.0

**LoopGuard 1.0 was distributed as a debug-signed APK.** Android refuses to replace an app
with a build signed by a different key, and a debug key must never be used to sign a public
release. That means version 2.0 cannot install directly over version 1.0 — this one time,
you have to uninstall first.

The signatures, for the record:

| Version | Signer | SHA-256 begins |
|---|---|---|
| 1.0 | `CN=Android Debug` | `fe:d0:4c:1d:f1:67:a1:a2` |
| 2.0 | `CN=LoopGuard` | `9e:c0:24:2f:93:43:5e:96` |

**Nothing is lost if you export first:**

1. In **LoopGuard 1.0**, open the **Loops** tab and tap **Backup**. Share the text to
   yourself — email, notes, WhatsApp, anywhere you can copy it back from.
2. Uninstall LoopGuard 1.0.
3. Install LoopGuard 2.0.
4. Go to **Settings → Backup and restore → Paste text**, paste, choose **Replace everything**,
   and import.

Every loop, date, impact, note, completed item and history entry comes across, including the
`waiting on me` / `waiting on them` split. The importer reads the 1.0 format directly.

**This is a one-time break.** Version 2.0 and everything after it are signed with the same
release key, so 2.1, 3.0 and beyond will install straight over the top with your data intact.

---

## Backups

`Settings → Backup and restore` gives you:

- **Save file** — writes a `.json` backup wherever you choose through the Android file picker.
- **Share** — sends the backup text to any app.
- **Restore file** / **Paste text** — with a choice between *merge* (keeps what you have,
  skips anything already imported) and *replace* (a true restore).

The backup format is a deliberate superset of the 1.0 format: it keeps the original
`loops` / `done` / `history` keys and adds the new fields alongside them. A 2.0 backup can
still be read by 1.0, and a 1.0 backup is read by 2.0 without conversion.

---

## Building it yourself

```bash
git clone https://github.com/nmoussaire-art/Chatgpt.git
cd Chatgpt
./gradlew testDebugUnitTest      # 48 unit tests
./gradlew :app:assembleRelease   # signed if keystore.properties is present
```

Requires JDK 17+ and the Android SDK (compileSdk 35, build-tools 35.0.0).

Release signing is described in [docs/SIGNING.md](docs/SIGNING.md). The private key and its
passwords are **never** committed — `.gitignore` blocks `*.jks`, `*.keystore` and
`keystore.properties`, and CI reads them from GitHub Secrets.

### Project layout

```
app/src/main/java/com/loopguard/app/
├── data/          Room entities, DAO, repository, settings, backup format
├── domain/        Priority engine, follow-up composer, quick-capture parser, templates
├── notify/        WorkManager digest, notification actions, boot rescheduling
└── ui/            Compose screens, theme, view model
app/src/test/      48 unit tests, including Room-backed end-to-end flow tests
```

---

## Testing

48 unit tests run on every push. They cover the priority engine's bounds and explanations,
the natural-language date and contact parser, all four follow-up tones (including the
"no contact name" and "no deadline" edge cases), the backup format in both directions, and
Room-backed walkthroughs of every flow: create, edit, change responsibility, log an action,
follow up, snooze, complete, reopen, delete and restore, import 1.0 data, and survive a
database close and reopen.

```bash
./gradlew testDebugUnitTest
```

---

## Privacy

There is no account, no server, no analytics and no subscription. The app does not declare
internet permission, so it is not capable of sending your data anywhere even in principle.
Everything is in a local database that leaves the device only when you explicitly export it.

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md).
