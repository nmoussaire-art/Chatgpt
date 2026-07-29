# Deadline Guardian

An offline Android app that photographs your receipts, warranty cards, medicine boxes
and documents, reads the dates on them, and warns you **while you can still act**.

The gap it fills: warranty trackers exist, but they are manual lists and they all miss
the deadline that actually costs you money — the **return window**. Shops print the date
you bought something and never print the date you lose the right to take it back.
Deadline Guardian computes that date and counts down to it.

Everything runs on the phone. No account, no server, no sync.

## What it does

One photo can start several clocks at once. A laptop receipt is not one deadline — it's
a 14-day return window *and* a two-year warranty, and they matter at very different times.

| Type | What gets tracked | Default warning |
|---|---|---|
| Receipt | Return window, warranty | 5 days / 30 days before |
| Warranty card | Warranty end | 30 days before |
| Medicine | Printed expiry | 14 days before |
| Food | Best-before | 3 days before |
| Document (passport, ID, insurance) | Expiry | 90 days before |
| Subscription | Next renewal | 7 days before |

The home screen leads with **money still recoverable** — the total of everything sitting
inside an open return window — because that is the number that makes you act.

## How the reading works

`app/src/main/java/com/deadlineguardian/engine/` holds the whole extraction pipeline,
and it is plain testable Kotlin with no Android dependencies.

- **`DateExtractor`** — finds dates in the formats receipts actually use: `14/03/2025`,
  `2025-03-14`, `14 mars 2025`, `EXP 03/2027`. Two-digit years, French and English month
  names, and month-only precision (an `EXP 03/2027` deadline resolves to 31 March).
  It then reads the words around each date to decide what it *means*, taking the
  **nearest preceding label** rather than the loudest one nearby — on a line like
  `Date: 01/06/2025  Garantie jusqu'au 01/06/2027` only proximity tells the two apart.
- **`TextSignals`** — stated policies (`retour sous 30 jours`, `garantie 2 ans`), the
  total amount, and the shop name. Durations are only accepted when they sit next to a
  warranty or returns word, because receipts are full of numbers that mean nothing.
- **`Classifier`** — keyword scoring to tell a receipt from a medicine box from a
  passport. Deliberately not an ML model: it needs no training data, it runs instantly,
  and its confidence drives whether the UI asks you to double-check.
- **`ScanAnalyzer`** — turns all of that into deadlines. A printed date always beats a
  computed one. When nothing is printed it falls back to your configured defaults, and
  **says so** — every proposed deadline shows its reasoning, and assumed ones are
  highlighted in amber so you can correct them before saving.

Two rules keep it trustworthy: it never guesses a warranty on groceries (inventing
deadlines trains you to ignore the app), and the review screen is not skippable, because
a wrong date you never saw is worse than no reminder at all.

## Privacy

Photos, extracted text and deadlines stay in the app's private storage. Text recognition
uses Google's ML Kit with the model **bundled inside the APK**, so scanning works in
airplane mode and no image or text is ever uploaded.

Stated plainly: ML Kit adds an `INTERNET` permission of its own and may report anonymous
statistics about its own usage to Google. It is never given your images or their
contents. Removing that permission was considered but not shipped, because it could not
be verified on a real device in this environment.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35).

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties

# Create a signing key (kept out of this repo on purpose — it is public)
keytool -genkeypair -v -keystore keystore/deadline-guardian.jks \
  -alias deadline -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass CHANGE_ME -keypass CHANGE_ME \
  -dname "CN=Deadline Guardian"

gradle testDebugUnitTest   # engine tests
gradle assembleRelease     # app/build/outputs/apk/release/
```

Signing credentials are read from Gradle properties (`dg.storeFile`, `dg.storePassword`,
`dg.keyAlias`, `dg.keyPassword`) and fall back to `keystore/deadline-guardian.jks`.

**Keep your keystore.** Android only allows an app to be updated in place by an APK
signed with the same key. If you lose it you can still build a new version, but you'll
have to uninstall the old app first, which deletes its data.

## Status

The extraction engine is covered by unit tests (`app/src/test/`) built from realistic
OCR fixtures — French and English receipts, a medicine box, a passport. The UI has not
been exercised on a physical device; there was no emulator available in the environment
it was built in.
