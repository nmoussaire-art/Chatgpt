# Privacy

OnTime Quant is a local application. There is no account, no login, no server, and no
analytics. This document states exactly what is stored, why, whether it leaves the device,
and how to get rid of it.

---

## The short version

| Question | Answer |
| --- | --- |
| Do I need an account? | No. There is nothing to sign in to. |
| Is my trip history uploaded? | No. It never leaves the device, and it is excluded from cloud backup. |
| Is my calendar uploaded? | No. Never, in whole or in part. |
| Do you record where I drive? | No. A trip stores four timestamps and three durations, not a path. |
| Is there analytics or crash reporting? | No. |
| Can I export everything? | Yes, as readable JSON. |
| Can I delete everything? | Yes, in one action. |

---

## What is stored, and why

Everything below lives in a single SQLite database on the device
(`ontime-quant.db`), plus a small DataStore preferences file.

### Places you saved

`SavedLocationEntity` — label, address, latitude, longitude, kind (Home / Work / other),
and optional parking and walking overrides.

*Why:* a forecast needs an origin and a destination. **These are the only coordinates the
app ever stores.**

### Journeys and appointments

`SavedJourneyEntity`, `AppointmentEntity` — an origin/destination pair, a title, the start
time as an absolute instant, **the appointment's own time zone**, the arrive-early buffer
and the confidence target.

*Why:* the journey id is the key under which route-specific learning accumulates. The zone
is stored explicitly so an appointment booked in another country keeps its correct wall
clock, and so a daylight-saving transition cannot silently move a deadline.

### Completed trips

`CompletedTripEntity` — for each finished journey:

- the deadline, the recommended departure, the actual departure, the actual arrival;
- predicted and actual road duration;
- preparation, parking and walking durations;
- the weather severity index and event pressure score that were in force;
- traffic regime, time-of-day bucket, day type, day of week;
- the model version that produced the forecast.

*Why:* this is the entire training set. Without it the app cannot learn that a route runs
long, cannot narrow its intervals, and cannot honestly report its own accuracy.

**What is deliberately not stored:** any location sample between departure and arrival. The
app does not need to know which road you took to learn that the journey took 23 minutes,
so it does not record it. There is no path, no breadcrumb trail, no periodic position log —
in any mode, including with background location granted.

### Learned model state

`ModelResidualEntity` (raw `log(actual/predicted)` values), `RouteBiasStateEntity` (an EWMA
cache, always rebuildable), and preparation / parking / walking observations.

*Why:* the empirical bootstrap and the robust scale estimator need the raw residuals, not
just a summary. Keeping them is what allows the model to be re-fitted, backtested and
audited.

### Forecast snapshots

`RouteForecastEntity`, `CandidateDepartureForecastEntity`, `PredictionSnapshotEntity`,
`WeatherSnapshotEntity`, `EventPressureSnapshotEntity` — the most recent forecast per
appointment, its curve, and the component breakdown that produced it.

*Why:* so the home screen has something to show instantly, and so a past forecast can be
reproduced exactly rather than approximately.

### Caches and housekeeping

`RouteCacheEntity` (routing responses, pruned after 24 hours) and
`NotificationStateEntity` (when the last notification was sent, so the cool-down survives
the process being killed).

---

## What leaves the device

Only when live providers are enabled — that is, when demo mode is off **and** an API key is
configured. In demo mode the app makes **no network calls at all**.

| Destination | What is sent | When |
| --- | --- | --- |
| Google Routes API | Origin and destination coordinates, and a departure timestamp — one request per five-minute departure slot | When you request or refresh a forecast |
| Google Places API | The text you type into destination search, and the coordinates of your chosen origin as a search bias | While you are searching |
| Open-Meteo | Destination coordinates, rounded to the provider's own resolution | When a forecast is generated |
| Ticketmaster Discovery | Destination coordinates and a time window | Only if an event key is configured |

That is the complete list. In particular:

- **Your calendar is never sent anywhere.** Events are read on the device and matched to
  destinations locally. If you track a calendar event, the destination *text* on that one
  event is sent to Places to be resolved into coordinates — the same as if you had typed it.
- **Your trip history is never sent anywhere.** No provider receives it, and no provider
  could use it.
- **Your learned model is never sent anywhere.** It exists only on your device.

---

## Calendar access

Read-only, and narrower than the permission allows.

- Only calendars you explicitly tick are queried. An empty selection reads **nothing**.
- Only these fields are read: title, start time, end time, time zone, location, whether a
  reminder exists.
- Descriptions, attendees, organisers, free/busy status, and recurrence details are never
  touched.
- All-day events are skipped entirely — they have no arrival deadline.

Implementation: `data/calendar/CalendarRepository.kt`. The projection passed to the content
resolver is the enforcement point, not a convention.

---

## Location access

Three levels, each useful, none required.

| Granted | What the app does |
| --- | --- |
| Nothing | You choose an origin from your saved places. Every screen still works. |
| Foreground | "Use my current location" works. Trip completion is confirmed by you with one tap. |
| Background | An arrival geofence is registered for the destination while a journey is in flight, and removed the moment the trip is recorded. |

Positions are requested **one at a time**, used immediately, and never written to the
database. There is no location listener running in the background, and no geofence except
during an active journey.

---

## Backup

Trip history is excluded from Android cloud backup and device-transfer
(`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`). Restoring a phone
therefore restores your preferences but not your movement history — you start the model
fresh rather than having a record of your journeys copied through Google's servers.

---

## Export

**Settings → Your data → Export my data** writes a readable JSON file containing every
place, journey, appointment, completed trip, learned bias summary and calibration result
the app holds, and offers it to a share target of your choice.

The export is deliberately complete. If the app stores it, the export contains it — that is
the only honest way to answer "what do you have on me?".

Implementation: `data/repository/DataExporter.kt`.

---

## Deletion

**Settings → Your data → Delete everything**, behind one confirmation dialog.

This clears every table and every preference. The demo journey is then reloaded so the app
remains usable rather than becoming an empty husk. There is no soft delete, no tombstone,
and nothing to revoke on a server, because there is no server.

Finer-grained control is also available:

- **Trip history → any trip → Exclude from learning** keeps the record but removes its
  influence on every future forecast.
- **Trip history → any trip → Delete** removes it entirely and re-derives the model state.
- **Settings → Learning → Learn how long you take to set off** turns off preparation-delay
  measurement; the component then contributes exactly zero rather than falling back to a
  prior.

---

## Notifications

Notification content is generated on the device and posted locally. Nothing is routed
through a push service, so no third party sees where you are going or when.

---

## A note on the preparation-delay model

The app measures the gap between the moment you accept a recommendation and the moment you
actually start moving, and includes it in the recommended departure time.

This is a timing measurement, and the app treats it as one. The copy reads *"Your recent
trips suggest that you usually begin moving about seven minutes after deciding to leave.
OnTime Quant has included this preparation time in today's recommendation."* — a statement
about the journey, not about you. It can be switched off in Settings, and switching it off
removes the component rather than substituting an estimate.

---

## Third-party privacy policies

When live providers are enabled you are also subject to:

- Google Maps Platform: <https://policies.google.com/privacy>
- Open-Meteo: <https://open-meteo.com/en/terms>
- Ticketmaster Developer: <https://developer.ticketmaster.com/support/terms-of-use/>
