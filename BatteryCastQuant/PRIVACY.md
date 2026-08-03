# Privacy

BatteryCast Quant is designed for offline operation.

## Stored locally

- Battery percentage and supported battery sensor fields.
- Charging, screen, power-save, thermal, network-type, and broad usage-regime context.
- Optional aggregated foreground/screen-use duration and broad Android app category durations.
- Optional upcoming calendar event title and times while shown as targets.
- Forecast records and later actual battery outcomes for accuracy evaluation.

## Never collected

- Message or notification contents.
- Browser history.
- Typed text.
- Personal documents.
- Connected Bluetooth-device identities.
- Precise per-app battery-consumption claims.

## Network and third parties

The manifest does not request `android.permission.INTERNET`. The app includes no analytics, advertising, crash-upload, cloud database, remote model, or account SDK.

## User control

Usage access, Calendar, Bluetooth state, and notification permissions are optional. The app provides local CSV export and complete local-data deletion. Database and preferences are excluded from cloud backup and device transfer.
