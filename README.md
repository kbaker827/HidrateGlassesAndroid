# HidrateGlasses Android

An Android app that connects to HidrateSpark smart water bottles via BLE, syncs with the HidrateSpark
cloud API, and pushes a live hydration HUD to Rokid CXR-M glasses (e.g. Rokid Max / Max Pro).

---

## Features

- BLE connection to HidrateSpark bottles (service UUID `1BC5FFA0-0200-62AB-E411-F254E005DBD3`)
- OAuth2-authenticated sync with the HidrateSpark REST API
- Real-time display of water intake, daily goal, drink history, bottle temperature, and battery level
- Live hydration HUD rendered directly on the Rokid glasses via the CXR-M SDK custom-view API
- Drink-reminder toasts pushed to the glasses via `sendGlobalToastContent`
- Phone-side fallback notification when glasses are not connected

---

## Requirements

| Item | Version |
|------|---------|
| Android Studio | Hedgehog 2023.1.1+ |
| Kotlin | 1.9+ |
| minSdk | 26 (Android 8.0) |
| targetSdk | 35 |
| Gradle | 8.4+ |

---

## Setup

### 1. Clone and open

```bash
git clone <repo-url> HidrateGlassesAndroid
```

Open the project root in Android Studio and let Gradle sync complete.

### 2. HidrateSpark API credentials

Add your HidrateSpark OAuth2 credentials to `local.properties` (never commit this file):

```properties
hidrate.client_id=YOUR_CLIENT_ID
hidrate.client_secret=YOUR_CLIENT_SECRET
```

`RetrofitClient.kt` reads these at runtime via `BuildConfig` fields injected in
`app/build.gradle.kts`.

### 3. Rokid CXR-M SDK

#### Maven repository

The SDK is hosted on the Rokid Maven repository. It is added automatically by the root
`build.gradle.kts` `allprojects` block:

```kotlin
// build.gradle.kts (root)
allprojects {
    repositories {
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
    }
}
```

The dependency in `app/build.gradle.kts`:

```kotlin
implementation("com.rokid.cxr:client-m:1.0.8")
```

#### Client secret

Obtain your `ROKID_CLIENT_SECRET` from the [Rokid developer portal](https://developer.rokid.com).
The value must be provided **without hyphens**.

Add it to `local.properties` (never commit this file):

```properties
ROKID_CLIENT_SECRET=yoursecretwithouthyphens
```

`gradle.properties` contains a placeholder that documents the key name:

```properties
ROKID_CLIENT_SECRET=YOUR_SECRET_HERE
```

`app/build.gradle.kts` injects the value into `BuildConfig.ROKID_CLIENT_SECRET` at build time.

### 4. Required Android permissions

| Permission | Purpose |
|---|---|
| `BLUETOOTH_SCAN` | Discover BLE devices (API 31+) |
| `BLUETOOTH_CONNECT` | Connect to paired BLE devices (API 31+) |
| `BLUETOOTH_ADMIN` | Legacy BLE admin (API < 31) |
| `ACCESS_FINE_LOCATION` | Required by BLE scanner on API 26–30 |
| `INTERNET` | API sync |
| `FOREGROUND_SERVICE` | Keep BLE connection and HUD alive |
| `POST_NOTIFICATIONS` | Hydration reminder push notifications (API 33+) |
| `VIBRATE` | Notification vibration |
| `RECEIVE_BOOT_COMPLETED` | Restart service after reboot |

On first launch the app walks through permission requests using Accompanist Permissions.

### 5. Bottle BLE pairing

1. Power on your HidrateSpark bottle and ensure Bluetooth is enabled on the phone.
2. Open the app and tap **Scan** on the Home screen.
3. Select your bottle from the results dialog.
4. The GATT connection is established automatically; readings appear within a few seconds.

Bottle BLE details:

| Attribute | Value |
|---|---|
| Primary service UUID | `1BC5FFA0-0200-62AB-E411-F254E005DBD3` |
| Hydration characteristic | `1BC5FFA1-0200-62AB-E411-F254E005DBD3` |
| Temperature characteristic | `1BC5FFA2-0200-62AB-E411-F254E005DBD3` |
| Battery characteristic | `1BC5FFA3-0200-62AB-E411-F254E005DBD3` |
| Last drink timestamp | `1BC5FFA4-0200-62AB-E411-F254E005DBD3` |

### 6. Rokid glasses pairing and HUD

#### Pairing (initial connection)

1. Enable Bluetooth on the phone and power on the Rokid glasses.
2. In the app toggle **Rokid HUD** on the Home screen — `RokidGlassesManager.connect(device)`
   is called with the scanned `BluetoothDevice`.
3. The SDK fires `CxrStatus.BLUETOOTH_AVAILABLE` when the BLE link is ready; the hydration
   custom-view HUD is opened automatically at that point.

#### Reconnecting after app restart

If the glasses MAC address was persisted from a previous session, call:

```kotlin
rokidGlassesManager.reconnect(
    socketUuid      = "",          // pass empty string if not using RFCOMM UUID
    macAddress      = savedMac,
    snEncryptContent = savedSnContent
)
```

`RokidGlassesManager` calls `CxrApi.getInstance().connectBluetooth(...)` with
`BuildConfig.ROKID_CLIENT_SECRET`.

#### Custom-view HUD format

The HUD is a JSON layout opened once per connection via `CxrApi.openCustomView(json)`:

```json
{
  "type": "column", "padding": 16,
  "children": [
    {"type": "text",        "id": "title",     "text": "Hydration",         "textSize": 18, "bold": true, "color": "#FFFFFF"},
    {"type": "progressBar", "id": "ring",      "progress": 0, "max": 100,                  "color": "#4FC3F7"},
    {"type": "text",        "id": "intake",    "text": "0 / 0 oz",          "textSize": 28,              "color": "#FFFFFF"},
    {"type": "text",        "id": "lastDrink", "text": "Last drink: --",    "textSize": 14,              "color": "#B0BEC5"},
    {"type": "text",        "id": "temp",      "text": "Bottle temp: --°F", "textSize": 14,              "color": "#B0BEC5"},
    {"type": "text",        "id": "battery",   "text": "Battery: --%",      "textSize": 12,              "color": "#78909C"}
  ]
}
```

Individual fields are updated without re-sending the entire layout:

```kotlin
CxrApi.getInstance().updateCustomView(
    """[{"id":"intake","text":"32 / 64 oz"},{"id":"ring","progress":50}]"""
)
```

Drink reminders are sent as toasts:

```kotlin
CxrApi.getInstance().sendGlobalToastContent(1, "Time to drink water!", true)
```

### 7. Build and install

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

```
MainActivity
  └─ NavHost
       ├─ HomeScreen       ← HomeViewModel ← HydrationRepository
       ├─ HistoryScreen    ← HomeViewModel
       └─ SettingsScreen   ← HomeViewModel

HydrationRepository
  ├─ HidrateBLEManager       (BLE GATT stream from HidrateSpark bottle)
  └─ HidrateApiService       (Retrofit REST / OAuth2)

RokidOverlayService          (foreground service; observes HydrationRepository;
                              drives CxrApi HUD updates and reminder toasts)
RokidGlassesManager          (@Singleton; wraps CxrApi; manages BLE connection,
                              custom-view lifecycle, battery/screen listeners)
```

---

## License

Apache 2.0
