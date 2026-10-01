# ADCB Tracker – merchant parsing fix (2026-10-01)

ADCB changed its SMS wording from `... at MERCHANT, CITY-AE. Avl.Cr.limit is AED...`
to `... at MERCHANT, CITY-AE. Available limit AED...`. The merchant regex only accepted
`Avl.` after the merchant, so every new alert was saved as "Unknown Merchant".

Patched regexes in `com.adcbtracker.parser.AdcbAlertParser`:

| Field | Old | New |
|---|---|---|
| MERCHANT_AT_RE | `(?i)\bat\s+([^.]+?)\.\s*(?:Avl\.\|$)` | `(?i)\bat\s+(.+?)\.(?:\s\|$)` |
| MERCHANT_BY_RE | `(?i)\bby\s+([^.]+?)\.\s*(?:Avl\.\|$)` | `(?i)\bby\s+(.+?)\.(?:\s\|$)` |
| AVL_CR_LIMIT_RE | `(?i)\bAvl\.?\s*Cr\.?\s*limit...` | `(?i)\b(?:Avl\.?\s*Cr\.?\|Available(?:\s+Cr\.?\|\s+credit)?)\s*limit...` |

Both the old and new SMS formats parse correctly; merchants containing dots (e.g. `AMAZON.AE`) now work too.
The APK is signed with a new key (the original debug key was not available), so the old app must be
uninstalled before installing this one; then run Settings → Import from SMS.
