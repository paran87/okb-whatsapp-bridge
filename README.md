# OKB WhatsApp Bridge

Android application (`com.okb.whatsappbridge`) for a dedicated Android phone. It watches the
notifications of **authorized WhatsApp groups** in the background, stores every message locally,
and uploads it to the OKB backend.

```
WhatsApp group ─▶ Android notification ─▶ NotificationListenerService ─▶ group filter
              ─▶ Room database (source of truth) ─▶ WorkManager ─▶ OKB backend
```

The app's screen does **not** need to be open. Once Notification Access is granted and Background
Monitoring is on, Android delivers WhatsApp notifications to the bridge. This works when the UI is
closed, the screen is off, the phone is locked, or another app is in the foreground.

> **Important Android limitation.** The bridge uses Android's `NotificationListenerService` to
> monitor notifications while running in the background, but Android and device manufacturers can
> still restrict background apps. The bridge is designed for reliable background operation using
> Android-supported mechanisms. It does not bypass Android restrictions and cannot guarantee that
> the operating system will never stop it. The [Battery optimization](#battery-optimization) and
> [Troubleshooting](#troubleshooting) sections explain how to configure a phone for the best results.

---

## Contents

1. [Installation](#installation)
2. [Notification Access](#notification-access)
3. [Background Monitoring](#background-monitoring)
4. [Battery optimization](#battery-optimization)
5. [WhatsApp groups](#whatsapp-groups)
6. [Backend](#backend)
7. [Using the app](#using-the-app)
8. [Troubleshooting](#troubleshooting)
9. [Physical-device acceptance tests](#physical-device-acceptance-tests)
10. [Architecture](#architecture)
11. [Building and testing](#building-and-testing)
12. [Known limitations](#known-limitations)

---

## Installation

Requirements: Android 8.0 (API 26) or newer, plus WhatsApp or WhatsApp Business installed and
signed in on the same phone.

1. Copy `app-debug.apk` to the phone, or install it from a computer:
   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. If you install by opening the APK on the phone, allow your browser or file manager to
   "Install unknown apps" when Android asks.
3. Open **OKB WhatsApp Bridge**. The dashboard shows a setup checklist:
   1. Grant Notification Access.
   2. Authorize at least one WhatsApp group.
   3. Configure the backend URL.
   4. Turn on Background Monitoring.

## Notification Access

Background monitoring needs Notification Access, which only the operator can grant. The app never
grants it to itself.

**Settings → Notification Access → OKB WhatsApp Bridge → Allow**

The exact menu path depends on the manufacturer. It is often under *Settings → Apps → Special app
access → Notification access* or *Settings → Notifications → Device & app notifications*. The
**Open Android Settings** button (Dashboard / Settings / Diagnostics) opens the right screen.

**Android 13 and newer, side-loaded APK.** If the switch is greyed out ("Restricted setting"), go
to *Settings → Apps → OKB WhatsApp Bridge → ⋮ (top-right) → Allow restricted settings*. Then return
to Notification Access and enable the bridge. The **App info** button in Settings opens that page.

The app shows the actual state at all times:

| Display | Meaning |
|---|---|
| `● ACTIVE` | Access granted and Android has connected the listener |
| `● GRANTED` (amber) | Access granted, but Android has not connected the listener yet |
| `○ NOT ENABLED` (red) | Access not granted. Monitoring cannot work |

## Background Monitoring

The **Background Monitoring** switch on the Dashboard and in Settings controls capture:

- **ON** – new WhatsApp notifications from authorized groups are processed and stored, even when
  the app UI is closed.
- **OFF** – new WhatsApp notifications are not processed (a confirmation dialog appears first).
  Messages already in the queue still upload unless **Synchronization** is paused as well.

The switch setting is stored in the local database, so it survives app restarts, device reboots
and app updates.

The status line reflects the actual system state, not just the switch position:

| Status | Meaning |
|---|---|
| `● ACTIVE` | Switch on, Notification Access granted, and the listener is connected by Android |
| `○ PAUSED` | Switch off |
| `● NOT RECEIVING` | Switch on, but Notification Access is missing |
| `● WAITING FOR ANDROID` | Switch on and access granted, but Android has not (re)connected the listener |

**After a reboot.** Android reconnects the listener by itself as long as Notification Access is
still granted. Because of Android Direct Boot, the phone has to be **unlocked once** (PIN/pattern)
after a restart before any app, including the bridge, can run. If Android ever revokes access
(rare, but some manufacturers do this after updates), the dashboard shows
`⚠ Background monitoring may be inactive`. If alert notifications are allowed, the 15-minute
health check also posts a system notification. In that case, re-enable access in Settings.

## Battery optimization

For reliable background operation, configure the phone so Android does not aggressively restrict
OKB WhatsApp Bridge:

1. **Settings → Battery Optimization → Open Battery Settings**, choose *All apps*, then set
   *OKB WhatsApp Bridge* to **Don't optimize**.
2. **App info → Battery** (button *App battery usage*): select **Unrestricted** (Android 12+).
3. Disable any manufacturer "sleeping apps" or "auto-optimize" feature for the bridge (see
   [Troubleshooting](#troubleshooting)).

The app does not change these settings itself. It only opens the right screens. **Diagnostics**
shows the current battery optimization state, background restriction, App Standby bucket and power
saver mode.

Normal monitoring does **not** need the screen to stay on, the app to stay visible, or the CPU to
stay awake. The bridge only runs when Android delivers a notification or WorkManager runs a job.

## WhatsApp groups

Only groups that are **explicitly authorized** are captured. Private chats are never captured.

- **Groups → Authorize a WhatsApp group**: type the group name as WhatsApp shows it, then press
  **Add**. Case and extra spaces are ignored.
- Groups that the bridge has seen in notifications are listed automatically, **unchecked**. Tick
  the checkbox to authorize one. Only the group *name* is stored for unauthorized groups, never
  their messages.
- Unticking a group stops capture. Removing a group keeps the messages already captured.

WhatsApp must show notifications for the monitored groups:

- do **not** mute the monitored groups in WhatsApp;
- keep WhatsApp notifications enabled in Android (Settings → *WhatsApp notifications* button);
- avoid actively using WhatsApp Web/Desktop with the same account, because WhatsApp may silence
  the phone's notifications while you are active there;
- if a chat is open on screen in WhatsApp, WhatsApp shows no notification for it, so the bridge
  cannot see those messages.

## Backend

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/health` | Connectivity check |
| `POST` | `/api/v1/devices/register` | Register this phone |
| `POST` | `/api/v1/messages` | Upload one captured message |

Every request carries `Authorization: Bearer <device token>` (if a token is configured) and
`X-OKB-Device-Id`. Message uploads also carry `Idempotency-Key: <fingerprint>`.

Example `POST /api/v1/messages` body:

```json
{
  "deviceId": "OKB-ANDROID-A82F19",
  "clientMessageId": "6f0d3c1e-6a4e-4c47-9a53-0b3a5d0f2c11",
  "fingerprint": "3b1f…64 hex chars…",
  "groupName": "OKB Monitoring",
  "senderName": "Juan Santos",
  "messageText": "Flooding observed at Barangay San Jose",
  "timestamp": "2026-10-04T08:42:00+08:00",
  "timestampMillis": 1791074520000,
  "mediaType": "TEXT",
  "mediaStatus": "NONE",
  "sourcePackage": "com.whatsapp",
  "capturedAt": "2026-10-04T08:42:01+08:00"
}
```

`mediaType` is one of `TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, LOCATION, STICKER, UNKNOWN`.
`mediaStatus` is `NONE` for text and `UNAVAILABLE` for media: phase 1 never extracts media files,
but the text (for example a photo caption) is still uploaded.

The bridge treats any **2xx** response as "received" and only then marks the message `UPLOADED`.
The backend should deduplicate on `fingerprint`, because a retry after a lost response sends the
same message again.

### Configuring the app

**Settings → Backend Configuration**

- **Backend URL**, for example `https://okb.example.org`. A path prefix such as
  `https://example.org/okb` is supported. Release builds require HTTPS. Debug builds also allow
  `http://` for LAN testing, with a warning.
- **Device Token**: stored encrypted with an Android Keystore key (AES-256-GCM). It is never
  logged and never shown in full (only `••••••••abcd`). Leave the field blank to keep the stored
  token.
- **Save** checks the connection immediately. **Test Backend** runs `GET /api/v1/health`.

**Settings → Device Configuration**

- **Device ID**: generated once (`OKB-ANDROID-XXXXXX`) and stored encrypted.
- **Device Name**: a free-text label sent at registration.
- **Register device**: sends `POST /api/v1/devices/register`.

### Reference backend (development / acceptance testing)

`backend/server.js` is a dependency-free Node.js (≥18) server that implements the three endpoints,
validates payloads, deduplicates by fingerprint (also across restarts), and stores data in
`backend/data/`.

```bash
cd backend
OKB_DEVICE_TOKENS=choose-a-long-random-token node server.js          # listens on 0.0.0.0:8080
npm test                                                             # backend tests
curl -H "Authorization: Bearer choose-a-long-random-token" \
     "http://<server-ip>:8080/api/v1/messages?limit=5"              # inspect received messages
```

In the app, set Backend URL `http://<server-ip>:8080` (debug build only) and the same token. For
anything beyond a test LAN, put the server behind an HTTPS reverse proxy.

## Using the app

| Screen | Content |
|---|---|
| **Dashboard** | Background Monitoring switch, Notification Access, WhatsApp, Backend, Sync Worker and Database status; messages today, pending uploads, last message; setup checklist and warnings |
| **Messages** | Latest 300 stored messages with upload status (filter by Pending / Retrying / Failed / Uploaded) |
| **Groups** | Allowlist: add, authorize/de-authorize, remove |
| **Sync** | Queue counts, upload worker state, periodic health check, last success/failure, **Sync now**, **Retry failed**, pause synchronization |
| **Settings** | Background Monitoring, Notification Access, Battery Optimization, WhatsApp Status, Backend Configuration, Device Configuration, Synchronization, Health Alerts, Diagnostics, About |
| **Diagnostics** | Listener, monitoring, WhatsApp, Room, WorkManager, backend and battery health; activity timestamps; **Test Backend**, **Open Notification Settings**, **Open Battery Settings**, **View Logs**, troubleshooting guide |

Every number and status comes from the local database, WorkManager, or Android system services.
Nothing is simulated.

| Active (dark) | Access missing (dark) | Paused (light) | Diagnostics |
|---|---|---|---|
| ![](docs/screenshots/dashboard-active-dark.png) | ![](docs/screenshots/dashboard-no-access-dark.png) | ![](docs/screenshots/dashboard-paused-light.png) | ![](docs/screenshots/diagnostics-dark.png) |

*Rendered from sample state by `ScreenRenderTest` (Robolectric). The numbers shown are test data.*

## Troubleshooting

**No notifications / "Last WhatsApp notification: Never"**
- Check that Notification Access shows `● ACTIVE`.
- Send a test message to an authorized group **from another phone**. WhatsApp does not notify you
  about your own messages.
- Check that the group is not muted in WhatsApp and that WhatsApp notifications are enabled in
  Android.
- Make sure WhatsApp Web/Desktop is not actively in use with this account.

**Notifications arrive but nothing is captured**
- Check that the group is authorized (ticked) under **Groups**. If it appears unticked there, tick
  it.
- Check that Background Monitoring is ON.

**Listener disabled / "Waiting for Android" / ⚠ Background monitoring may be inactive**
- Open Notification Access settings, switch OKB WhatsApp Bridge **off and on again**.
- After a reboot, unlock the phone once.
- On Android 13+, a side-loaded APK may need *App info → ⋮ → Allow restricted settings*.

**Battery restrictions**
- Diagnostics shows `Restricted` or `Optimized`: follow [Battery optimization](#battery-optimization).
- Manufacturer-specific controls:
  - *Auto-start / Auto-launch* (Xiaomi, Huawei, Oppo, Vivo, Realme…): allow it for the bridge.
  - *Background activity*: allow it.
  - *Battery saver*: capture continues, but uploads may be deferred.
  - Samsung *Sleeping / Deep sleeping apps*: remove the bridge from those lists and add it to
    *Never sleeping apps*.
  - Some launchers let you lock the app in Recents so that task killers leave it alone.
  - See <https://dontkillmyapp.com> for model-specific steps.

**Backend unavailable**
- Messages stay on the phone as `PENDING_UPLOAD` / `RETRYING`. Nothing is lost.
- WorkManager retries with exponential backoff (starting at 30 s) whenever a network connection is
  available. The 15-minute health check also re-queues uploads, so a long backoff never delays
  recovery by more than about 15 minutes (subject to Android's scheduling).
- Use **Diagnostics → Test Backend** to see the error (DNS, TLS, HTTP status).

**Pending uploads not decreasing**
- **Sync** screen: check that synchronization is not paused and that the backend is configured.
- `FAILED` messages (HTTP 4xx such as 400/401/403) are not retried immediately. Fix the cause
  (often the token), then press **Retry failed**. Saving the backend configuration also re-queues
  them. Reconciliation retries a FAILED message automatically up to 20 times.
- **Diagnostics → View Logs** lists sync results and errors (never message content or tokens).

## Physical-device acceptance tests

The emulator is **not** enough for final validation. Run these tests on the dedicated phone, with a
second phone that sends WhatsApp messages to the monitored group. Useful commands while testing:

```bash
adb logcat -s OKB/Listener OKB/Capture OKB/Sync OKB/Health OKB/Boot
curl -H "Authorization: Bearer <token>" "http://<server-ip>:8080/api/v1/messages?limit=5"
```

**A. UI closed (critical)**
1. Open the bridge, enable monitoring, grant Notification Access, authorize the group, configure
   the backend.
2. Close the bridge: swipe it away from Recents.
3. Lock the phone and wait at least 1 minute.
4. From the second phone, send a message to the group.
5. Expected: the reference backend logs `message stored …` and `GET /api/v1/messages` shows the
   text. Reopening the app shows the message as `UPLOADED`.

**B. Offline / backend unavailable**
The bridge phone needs internet to *receive* WhatsApp messages at all, so the meaningful offline
test makes the **backend** unreachable while WhatsApp still works:
1. Enable monitoring. 2. Stop the reference backend (or block it at the router).
3. Send a message. 4. **Messages** shows it as `Pending` or `Retrying`; Sync shows the error.
5. Start the backend again. 6. Without opening the app, the message reaches the backend within a
   few minutes (WorkManager backoff, at most about 15 minutes via the health check) and the status
   becomes `Uploaded`.
Variant: turn on airplane mode for several minutes; WhatsApp delivers the message when the
connection returns, and the bridge then captures and uploads it.

**C. Reboot**
1. Enable monitoring. 2. Restart the phone. 3. Unlock it once. 4. Diagnostics → Notification
   Listener shows `Active` (do not open it if you are testing recovery with the UI closed).
5. Send a message. 6. Check that it reaches the backend.
If Android required re-enabling Notification Access after the reboot, write that down: it is a
device/OS behaviour that the app reports and must not bypass.

**D. Battery**
Leave the phone locked with the screen off for several hours while messages arrive. Check that they
are captured and that Android's battery usage screen shows negligible usage for the bridge. No
foreground service or wake lock is used.

## Architecture

```
app/src/main/java/com/okb/whatsappbridge/
├── OkbBridgeApplication.kt          WorkManager configuration, schedules the periodic health check
├── AppContainer.kt                  manual dependency injection (no Activity needed)
├── MainActivity.kt                  operations UI only
├── service/
│   ├── WhatsAppNotificationListenerService.kt   primary background mechanism
│   ├── NotificationSnapshotExtractor.kt         StatusBarNotification → plain snapshot
│   ├── NotificationProcessor.kt                 serial, off-main-thread capture
│   ├── BootReceiver.kt                          BOOT_COMPLETED / MY_PACKAGE_REPLACED recovery
│   ├── AndroidHealthAlertNotifier.kt            "monitoring may be inactive" alert
│   └── ListenerConnectionState.kt               real listener bind state
├── whatsapp/                        pure Kotlin, unit-tested
│   ├── WhatsAppNotificationFilter.kt  package check, system-notification ignore list, GroupAllowlist
│   ├── WhatsAppNotificationParser.kt  defensive parsing of several WhatsApp layouts
│   ├── SystemNotificationRules.kt     expandable ignore list
│   └── MediaTypeDetector.kt
├── domain/  model · repository interfaces · usecase
│   ├── ProcessNotificationUseCase   filter → parse → allowlist → fingerprint → Room → schedule upload
│   ├── SyncMessagesUseCase          drains the persistent queue, classifies errors
│   ├── HealthCheckUseCase           15-minute watchdog (verify, never loop)
│   └── BackendUseCases              health check, device registration
├── data/
│   ├── local/   Room: messages, upload_queue, monitored_groups, bridge_settings, event_log
│   ├── remote/  OkHttp + kotlinx.serialization client
│   └── repository/
├── worker/  MessageUploadWorker, ReconciliationWorker, BridgeWorkerFactory, WorkManagerUploadScheduler
├── ui/      Jetpack Compose + Material 3 (dashboard, messages, groups, sync, settings, diagnostics, onboarding)
└── util/    security (Keystore secret store, redactor), logging, fingerprint, system status/intents
```

Key design decisions:

- **Persist before network.** The flow is always notification → Room (message + queue row in one
  transaction) → WorkManager. It is never notification → network.
- **No keep-alive tricks.** There are no infinite loops, timers, or foreground service. The
  listener reacts to Android callbacks. Uploads are one-time WorkManager jobs
  (`NetworkType.CONNECTED`, exponential backoff). A 15-minute periodic job (the minimum Android
  allows) reconciles the queue and checks health. A foreground service (for example a future
  `MediaUploadForegroundService` for large media) is deliberately not implemented, because nothing
  in phase 1 needs one.
- **Duplicate prevention.** WhatsApp re-posts the whole conversation with every new message. Each
  message gets a SHA-256 fingerprint of (group, sender, text, message time) with a unique index.
  The fingerprint is also sent as the idempotency key so the backend can deduplicate retries.
- **Upload state machine.** `PENDING_UPLOAD → UPLOADING → UPLOADED`. Transient errors (offline,
  timeout, 408/425/429/5xx) give `RETRYING` and a WorkManager retry. Other 4xx errors and
  configuration errors give `FAILED`, retried by reconciliation (up to 20 times) or manually.
  Uploads interrupted by process death are recovered as `RETRYING`.
- **Recovery after restart.** If Notification Access is still granted, Android rebinds the
  listener. When the listener connects, it also processes WhatsApp notifications still in the
  shade (deduplicated). WorkManager restores its jobs. `BootReceiver` re-checks the queue.
- **Security.** The device token and device ID are encrypted with an Android Keystore AES-GCM key.
  Backups and device-transfer are disabled, so neither captured messages nor credentials leave the
  phone except through the authenticated upload. Logs never contain tokens or message content.
  Release builds allow HTTPS only.
- **Not used:** AccessibilityService, UI scraping, simulated taps, WhatsApp's private database, or
  any attempt to bypass WhatsApp or Android security.

## Building and testing

The environment needs JDK 17+ and the Android SDK (platform 35, build-tools 35). Set the SDK path in
`local.properties` (`sdk.dir=…`) or with `ANDROID_HOME`.

Windows:

```
gradlew.bat assembleDebug
gradlew.bat test
gradlew.bat lint
```

macOS / Linux:

```
./gradlew assembleDebug test lint
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Unit tests (`app/src/test`, JVM + Robolectric) cover the notification parser, WhatsApp package
detection, the system-notification ignore list, group filtering, media detection, message
fingerprinting and duplicate detection, the Room DAOs and upload queue, settings persistence across
a database reopen, the HTTP API (MockWebServer), sync retry/backoff classification, the upload
worker's `Result.retry()`/`success()`, the health check, device identity and redaction. There is
also an end-to-end pipeline test: a real Android `MessagingStyle` notification is wrapped in a
`StatusBarNotification` and pushed through extractor → filter/parser → Room → sync → HTTP, including
an offline-then-online scenario.

Backend tests: `cd backend && npm test`.

**CI.** `.github/workflows/ci.yml` runs on every pull request and on pushes to `main`. The
Android job runs `./gradlew assembleDebug test lint` on JDK 21 and uploads the debug APK, test
reports, lint report and rendered screenshots as artifacts. The backend job runs `npm test` on
Node 20.

## Phase 2 — WhatsApp media acquisition + Cloudflare R2

Phase 2 extends the bridge so that when an authorized group notification indicates a photo, video,
document, audio or sticker, the system records a **media attachment**, acquires the original file
**only when Android legitimately provides it**, stores it locally, hashes it, and uploads it to the
OKB backend, which stores it in Cloudflare R2. Phase 1 text/caption capture is unchanged and never
depends on media.

### What the notification actually contains (important)

A WhatsApp notification does **not** contain the original photo/video file. The only legitimate
original-media reference a notification can carry is a content URI set by the sender app via
`MessagingStyle.Message.setData(mimeType, uri)` (`getDataUri()` / `getDataMimeType()`). WhatsApp
**rarely populates this for group media**, and even when present the URI may not be readable by a
notification listener. Notification icons and `BigPicture` thumbnails are **not** the original media
and are never uploaded as such.

Therefore the realistic outcome on most devices is a clean **media-unavailable** state. The bridge:

- reads **only** a legitimately-provided `dataUri` through the public `ContentResolver`;
- **never** reads WhatsApp's private storage, scrapes WhatsApp Web, automates the UI, uses an
  `AccessibilityService`, or requires root;
- requests **no storage permission** (the primary strategy needs none);
- when no legitimate file is available, records `acquisitionStatus = UNAVAILABLE` with an
  operator-readable reason, keeps the text/caption, and does not invent a workaround.

> This is a deliberate architecture: media acquisition can be improved later (e.g. a future
> MediaStore-based strategy on devices that expose shared media) without changing the database,
> queue or backend contract.

### Media pipeline

```
Authorized group notification (media indicator)
      ↓
MediaAttachment row (DETECTED)
      ↓
Acquire ONLY a legitimately-provided notification URI  ──► none ──► UNAVAILABLE (text still kept)
      ↓ (file provided)
Stream-copy into app storage + SHA-256 (one pass, bounded memory)   → AVAILABLE
      ↓
Persistent media upload queue (WorkManager)
      ↓
media/intent (authenticated)  ──► duplicate ──► mark UPLOADED (no transfer)
      ↓ (upload URL)
Stream PUT to the URL (presigned R2, or dev local-sink)
      ↓
media/complete  → mark UPLOADED (R2 object key + ETag recorded)
      ↓
Cloudflare R2 object
```

### Backend media architecture: presigned direct-to-R2 (chosen)

Two approaches were evaluated:

| | A. Backend proxies bytes | **B. Presigned direct-to-R2 (chosen)** |
|---|---|---|
| R2 credentials | on backend only | on backend only |
| Android memory for large video | backend must stream too | device streams file from disk; backend untouched |
| Large video support | limited by backend resources | up to R2's 5 GiB single-PUT (multipart later) |
| Retry | re-send through backend | re-intent + re-PUT, idempotent on a content-addressed key |
| Security | token to backend | short-lived, key- and content-type-scoped URL; **no R2 keys on device** |
| Complexity | higher backend load | small SigV4 presigner |

**B** is used. The device authenticates to the OKB backend (same device-token auth as Phase 1),
calls `media/intent`, receives a **short-lived, key-scoped** upload URL, streams the file straight to
R2, then calls `media/complete`. **R2 Access Key / Secret never reach the APK.**

In development (no R2 configured) the backend returns a **signed local-sink URL on itself** so the
entire pipeline is testable without any R2 credentials. The Android contract is identical either way.

Multipart upload is not implemented in this phase (a single streamed PUT covers WhatsApp media sizes
well under R2's 5 GiB single-PUT limit); the upload is behind a `MediaUploader` abstraction so
multipart can be added later without touching the database or queue.

### New media API endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/media/intent` | Get an upload URL (presigned R2 or dev local-sink), or a `duplicate` verdict |
| `PUT` | the returned `uploadUrl` | Stream the file bytes (presigned R2, or dev `/api/v1/media/blob`) |
| `POST` | `/api/v1/media/complete` | Confirm the object; record R2 key + ETag |
| `GET` | `/api/v1/media/:id` | Media metadata |
| `GET` | `/api/v1/media?limit=50` | Operator verification list |

All Phase 1 message endpoints are unchanged and remain backward compatible.

The object key is deterministic and content-addressed (no secrets):

```
whatsapp/{deviceId}/{yyyy}/{MM}/{dd}/{sha256}.{ext}
```

Deduplication happens at three levels: locally (reuse an already-uploaded row with the same SHA-256),
at `intent` (the backend reports `duplicate` for a known object key), and implicitly at R2 (same
content → same key → same object).

### Configure Cloudflare R2 (exact steps)

1. In the Cloudflare dashboard, open **R2** and **Create bucket** (e.g. `okb-media`). Keep it
   **private** (do not enable public access).
2. Note your **Account ID** (R2 → Overview, or the dashboard URL).
3. **R2 → Manage R2 API Tokens → Create API token**:
   - Permission: **Object Read & Write** (the minimum this app needs);
   - scope it to the single bucket (`okb-media`);
   - create it and copy the **Access Key ID** and **Secret Access Key** (shown once).
4. On the backend host, copy `backend/.env.example` to `backend/.env` and fill in:
   ```
   R2_ACCOUNT_ID=your-account-id
   R2_BUCKET_NAME=okb-media
   R2_ACCESS_KEY_ID=your-access-key-id
   R2_SECRET_ACCESS_KEY=your-secret-access-key
   OKB_DEVICE_TOKENS=your-long-random-device-token
   ```
   Never commit `.env`. The device token is stored encrypted on the phone (Android Keystore).
5. Start the backend: `cd backend && node --env-file=.env server.js` (Node ≥ 20; on Node 18 export
   the vars instead). `GET /api/v1/health` reports `"media":"r2"` when R2 is active, or
   `"media":"local-sink"` in development.

**Verify R2 actually works** (recommended before relying on it — this exercises the SigV4 presigner
and your real credentials):

```bash
cd backend
# with the R2_* vars set in this shell, or: node --env-file=.env scripts/verify-r2.js
npm run verify:r2
```

It presigns a PUT and uploads a tiny test object directly to your bucket. `✅ R2 upload OK` means the
credentials, bucket permission and presigner are all correct (you can delete the test object). To test
the whole server path instead: `node scripts/verify-r2.js --via-backend http://localhost:8080 [--token …]`.

With R2 unset, the backend runs the **DEV local-sink** (stores media under `DATA_DIR/media`) so you
can test end-to-end before configuring R2. `OKB_ALLOW_NO_AUTH=1` remains **DEVELOPMENT ONLY**.

### Install the updated APK

Same as Phase 1:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The database migrates in place (v1 → v2); existing messages, groups and settings are preserved. No
new permission prompt appears on install — media acquisition uses no storage permission. Android may
show a brief "Uploading media…" notification while a large upload runs (a foreground data-sync task).

### Media settings and screens

- **Settings → Media Capture**: toggle media capture on/off, and "delete local copy after upload"
  (a local file is deleted only *after* a confirmed upload; otherwise kept 30 days).
- **Dashboard**: Media Today, Pending Media Uploads, Uploaded Media, Failed Media, Storage Used,
  Last Media Capture, Last Media Upload, plus a Media Worker status line.
- **Messages**: each message shows a media chip (`IMAGE`, `VIDEO`, `… · UNAVAILABLE`, etc.); tap a
  message to open the **media detail** screen (group, sender, caption, media type, file size,
  acquisition status, upload status, SHA-256, R2 object key, timestamps, last error). A preview is
  only ever shown when an accessible local file exists — never a fake preview.
- **Sync**: media queue counts and **Sync media now** / **Retry failed media**.
- **Diagnostics → Media**: acquisition capability, media permissions (none required), storage used,
  largest queued file, free space, last capture/upload, with plain-language explanations such as
  "Media detected, but Android did not provide an accessible media file."

### Phase 2 acceptance-test procedure (physical device)

Run on the dedicated phone with a second phone sending to an authorized group. Start the backend
(local-sink is fine for testing) and watch it plus logcat:

```
adb logcat -s OKB/Media OKB/MediaSync OKB/Capture OKB/Listener
curl -H "Authorization: Bearer <token>" "http://<server-ip>:8080/api/v1/media?limit=5"
```

1. **Text** — send "Phase 2 text test". Expect: captured, stored, uploaded; Phase 1 behavior intact.
2. **Photo** — send one photo. Expect: notification detected, media type `IMAGE`, caption preserved.
   If Android provides a legitimate URI → local file created, SHA-256 computed, queued, uploaded to
   R2/local-sink. If not → `mediaStatus = UNAVAILABLE` with a clear reason; text/caption still
   captured. (On most devices the latter is expected — this is correct, not a bug.)
3. **Photo with caption** — "Flooding observed at bridge" + photo. Expect: media record and caption
   linked to the same message; no text lost.
4. **Video** — short video. Expect: detected; if acquirable, streamed without a memory crash, queued,
   upload/retry works.
5. **App closed** — swipe the app away, lock the phone, send a photo. Expect: the listener captures
   it and media processing + upload happen in the background.
6. **Offline** — stop the backend, send a photo. Expect: media stays queued (`Pending`/`Retrying`),
   no data loss. Restart the backend → WorkManager retries → uploaded.
7. **Duplicate** — send the exact same media twice. Expect: SHA-256 detects the duplicate; R2 does
   not store a second object (intent returns `duplicate`).

> Do not claim any of these passed until executed on the physical device.

## Known limitations

- The bridge only sees what WhatsApp puts in its notifications. Messages in muted groups, in a chat
  that is open on screen, or that arrive while WhatsApp suppresses phone notifications (for example
  when WhatsApp Web is active) are not visible. Very long messages may be truncated by WhatsApp in
  the notification.
- WhatsApp's notification layout is not a public API. The parser handles several known layouts
  defensively, but a future WhatsApp update could need parser changes. Diagnostics/logs show when
  notifications arrive but are not captured.
- Group matching is by group **name**. Renaming a group in WhatsApp requires updating the allowlist.
  Two different groups with the same name cannot be distinguished.
- Media files (photos, video, audio, documents) are not extracted in phase 1. Only their type and
  text/caption are captured (`mediaStatus = UNAVAILABLE`).
- **Media files are usually not acquirable.** WhatsApp does not attach the original photo/video to
  group notifications, so on most devices media is recorded as `UNAVAILABLE` (type + caption only).
  The bridge only acquires media Android legitimately hands it via a notification URI; it never reads
  WhatsApp's private storage. A future phase may add a MediaStore-based strategy where the OS exposes
  shared media.
- Multipart/resumable upload is not implemented yet; a single streamed PUT is used (ample for
  WhatsApp media sizes). The `MediaUploader` abstraction allows adding multipart later.
- After a reboot, the phone must be unlocked once before monitoring resumes (Android Direct Boot).
  Some manufacturers may additionally require auto-start permission or may revoke Notification
  Access after system updates. The app reports this but cannot override it.
- Message timestamps come from WhatsApp's notification data. If the notification carries no
  per-message time, the notification time is used.
