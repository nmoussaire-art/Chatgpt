# Changelog

All notable changes to LoopGuard.

## [2.0.0] — 2026-07-29

Complete rewrite from a WebView shell to a native Kotlin / Jetpack Compose application.
Every feature of 1.0 is preserved; the data model, the interface and the reminder system
are new.

### Added

- **Explained priority.** Each loop shows the individual factors behind its score — impact,
  deadline pressure, silence, whose move it is, follow-up fatigue, pinning — with the points
  each contributed, and a plain-language headline.
- **Natural-language quick capture.** Parses relative dates (`tomorrow`, `in 3 days`,
  `next monday`, `end of month`), absolute dates (`15/03`, `15 May`), contacts (`@Rebecca`),
  tags (`#school`), reference numbers (`ref ADM-2291`) and urgency (`!!`, `urgent`), then
  reports what it understood.
- **Voice capture** via the system speech recogniser.
- **Share-to-capture**: LoopGuard accepts shared text from any app as a new loop.
- **Four follow-up tones** — Friendly, Professional, Firm, Final notice — built from the
  loop's real history, with an automatically suggested tone based on how many times you
  have already chased.
- **24 templates** across school, medical, property, finance, government/admin and work.
- **Daily digest notifications** through WorkManager, at a time you choose, with
  *Snooze 1 day* and *Mark done* actions on the notification itself. Capped at three
  notifications a day.
- **Snooze** for 1, 3, 7, 14 or 30 days, with a "wake" action and a timeline record.
- **Search and filtering** by text, responsibility side, category and tag.
- **Reference numbers**, **organisations** and **tags** as first-class fields.
- **Pinning** to force a loop to the top of the queue.
- **Insights**: responsibility split, busiest area, who has been silent longest, average
  time to close, and current pressure.
- **Undo** for complete, snooze and delete, including full timeline restoration.
- **Backup to a file** through the Android file picker, plus restore from file or pasted text,
  with a merge-or-replace choice.
- **Onboarding** explaining the two kinds of unfinished work, the scoring, and the privacy
  model, with an option to start from three worked examples.
- **Dark mode** and optional Android 12+ dynamic colour.
- **Accessibility**: content descriptions throughout, screen-reader friendly priority rings,
  font-scale support, no colour-only status signals.
- 48 unit tests, including Room-backed end-to-end flow tests.
- GitHub Actions workflow that runs the tests and produces a signed release APK.

### Changed

- **Storage** moved from browser `localStorage` to a Room (SQLite) database, with a separate
  timeline table so history is queryable rather than an array of string pairs.
- **Priority formula** rebuilt to be bounded, explainable and to weigh follow-up fatigue.
- **Interface** rebuilt in Material 3 with bottom navigation, a persistent focus queue and
  bottom-anchored actions for one-handed use.
- **Backup format** extended to schema 2 while remaining readable by 1.0 (the `loops`,
  `done` and `history` keys are unchanged; new fields sit alongside them).
- **Reminders** replaced the previous absence of any reminder system.
- Application no longer requests or holds any network capability.
- `versionCode` 1 → 2, `versionName` 1.0.0 → 2.0.0.

### Fixed

- Loop dates no longer drift when the app is left open across midnight; the current date is
  re-read on every resume.
- The "silence" measure no longer counts against loops you own yourself.
- Impact, dates and malformed fields in imported backups are validated and clamped rather
  than trusted.

### Migration

LoopGuard 1.0 was distributed as a **debug-signed** APK, so 2.0 cannot install over it —
Android rejects an update signed with a different key. Export from 1.0 (**Loops → Backup**),
uninstall, install 2.0, then **Settings → Backup and restore → Paste text**. All loops,
dates, notes and history transfer. This is a one-time break; all future releases update
normally.

### Known limitations

- No screenshots are included in this repository: the build environment is a headless
  container with no hardware virtualisation, so the app could not be launched to capture
  them. The interface is verified by compilation and by logic tests, not by a device run.
- R8 code shrinking is deliberately disabled for release builds, which makes the APK larger
  (~11.9 MB) but removes a class of runtime failure that could not be caught without a
  device to test on.
- Voice capture depends on a system speech recogniser being present; the button degrades
  gracefully when there is none.

## [1.0.0] — 2026

Initial release. Single-file HTML application in a WebView shell: open loops split by
responsibility, a risk score, a focus queue, timelines, follow-up drafting and a text backup.
