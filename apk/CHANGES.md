# ADCB Tracker

Source code lives in `android/` (Kotlin + Jetpack Compose). Build with
`cd android && ./gradlew assembleRelease` (needs a `keystore.properties` with the signing key).

## v2.5.1 (2026-10-04)
- Fix: Daily pace readout lost its amounts at large font sizes; values now sit on their own line.
- Projection explanation folded behind an ⓘ (tap the title or amount to show it).
- Projection now also expects your typical big purchases (from the last 3 cycles) that haven't
  happened yet this cycle, so recurring large bills aren't left out.

## v2.5.0 (2026-10-04)
- New adaptive app icon (glass card + glowing trend line, themed-icon support).
- Tap any transaction anywhere (day sheet, Home, Insights, Expenses) to open an edit sheet:
  pick a category for just that transaction or for every transaction from the merchant, or delete it.
- Tap a category (Home/Insights) to list its transactions for the cycle; Home shows a
  "N transactions need a category" shortcut. Merchants tab has search and a chip picker.
- Statement projection: spent so far + usual daily spend × days left, where one-off purchases
  at or above the large-expense threshold are not extrapolated and the pace is blended with the
  last 3 cycles. The card explains the numbers.
- Cycle budget with "safe to spend per day"; available credit on Home.
- Large-expense phone notifications (toggle in Settings) and a home-screen widget.
- Fixed truncated "Previous" readout on the Daily pace chart, overlapping nav labels and the
  cramped projection row. "Transactions" tab renamed "Expenses".

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
