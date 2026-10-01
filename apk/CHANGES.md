# ADCB Tracker

Source code lives in `android/` (Kotlin + Jetpack Compose). Build with
`cd android && ./gradlew assembleRelease` (needs a `keystore.properties` with the signing key).

## v2.4.0 (2026-10-01)
- **Billing cycle**: Home and Insights follow the card statement period instead of calendar
  months. Default start day is the 24th; change it in Settings › Billing cycle (1 = calendar months).
- **Interactive spending calendar** (Insights): tap any day to open a sheet with that day's total,
  category split and every transaction; arrows step to the previous/next day.
- **Daily spending**: Home shows Today / Yesterday / This week tiles and a list of the last 14 days
  (tap a day for details). Transactions are grouped by day with each day's total.
- Daily pace chart is touch-interactive; cycle history bars jump to that cycle.
- Merchant parsing fix from v2.3.3-fix (ADCB's "Available limit" wording) carried over.

## v2.3.3-fix
ADCB changed its SMS wording from `... CITY-AE. Avl.Cr.limit is AED...` to
`... CITY-AE. Available limit AED...`; the merchant regex required `Avl.` and so every new
alert was saved as "Unknown Merchant". Fixed by ending the merchant at the first ". ".
