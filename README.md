# SafeStreet (Neighborhood Safe Street)
### Worldwide Public Safety & Local Incident Awareness Platform with Wear OS Companion

[![Android Build](https://img.shields.io/badge/Android-Phone%20%26%20Wear%20OS-brightgreen.svg)](https://developer.android.com)
[![Firebase Spark](https://img.shields.io/badge/Firebase-Firestore%20%2B%20Hosting-orange.svg)](https://firebase.google.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-blue.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Compose%20%2B%20Wear%20Compose-purple.svg)](https://developer.android.com/jetpack/compose)

SafeStreet is an architectural implementation of the worldwide public safety and local incident awareness blueprint. It differentiates **authoritative open-data feeds** (911 CAD, Fire dispatches, Police calls, Global Disaster Alerts) from **time-bounded community observations**, maintaining strict provenance and privacy safeguards.

---

## 🌟 Key Features

### 1. Multi-Tier Provenance & Authority Architecture
Every displayed incident clearly belongs to an explicit provenance tier with clear latency transparency:
- **`OFFICIAL LIVE`**: Near-real-time CAD / dispatch feeds (e.g., Seattle Fire 911 calls, San Francisco Police & Fire dispatch, GDACS Global Disaster alerts).
- **`OFFICIAL DELAYED`**: Verified open-data reports with stated reporting latencies (e.g. Chicago Crime datasets, NYC calls-for-service).
- **`COMMUNITY CONFIRMED`**: Observable events verified by 2+ independent community accounts.
- **`COMMUNITY REPORT`**: Direct observable reports from local contributors with an **immutable 24-hour expiration policy** (`expiresAt = createdAt + 24h`).

### 2. Triple Timestamp Transparency
Every incident card exposes the 3 critical timestamps recommended in the research plan:
1. **Occurred Time** (e.g. *12m ago*)
2. **Official Source Updated Time** (e.g. *8m ago*)
3. **App Ingested Time** (e.g. *just now*)

### 3. Dual-Mode Wear OS Companion App
Designed for round and square smartwatches (Wear OS 3+ / 4 / 5):
- **Primary Connection (Bluetooth Data Layer)**: Synchronizes active nearby incidents and critical alerts from the phone over Google Play Services Wearable API (`/incidents`, `/alert`).
- **Secondary Connection (Standalone Internet Fallback)**: If Bluetooth is disconnected or the phone is out of range, the watch automatically switches to standalone Wi-Fi/LTE mode to query official public safety feeds directly!
- **1-Tap Wrist Reporting**: Quick observation reporting ("Fire", "Crash", "Police", "Hazard") directly from the wrist.
- **Emergency SOS**: High-visibility shortcut to emergency services dialer.
- **Haptic Alerts**: Distinctive vibration patterns for incoming high-priority emergency dispatches.

### 4. Privacy & Anti-Harassment Safeguards
- **No Private Individual Naming**: Policy and UI strictly enforce reporting of observable physical conditions only (no suspect accusations, phone numbers, or license plates).
- **Quantized Coordinates**: Community report locations are quantized to ~100m to prevent tracking private residences.
- **Community Moderation & Reporting**: Integrated confirmation ("I also observe this") and flagging ("Report abuse/inaccurate") mechanisms that automatically hide content with 3+ flags.

---

## 🏗️ Project Structure

```text
crime-app/
├── app/                  # Handheld Phone Application (Pixel 8 Pro / Android 26+)
│   ├── src/main/java/com/neighborhood/safestreet/
│   │   ├── data/api/     # Live OpenDataClient (Seattle Fire, SF Police, GDACS)
│   │   ├── data/firebase/# FirestoreCommunityRepository (24h expiry, auth)
│   │   ├── data/repository/ # Unified IncidentRepository
│   │   ├── wear/         # WearableSyncManager & WearableListenerService
│   │   └── ui/           # Jetpack Compose UI (RadarView, IncidentCard, Sheets)
│   └── google-services.json
│
├── wear/                 # Wear OS Smartwatch Companion Application
│   ├── src/main/java/com/neighborhood/safestreet/wear/
│   │   ├── WearDataManager.kt  # Dual-mode (Bluetooth + Standalone Internet Fallback)
│   │   ├── WearMainActivity.kt # Rotary ScalingLazyColumn UI
│   │   └── WearCompanionListenerService.kt
│   └── google-services.json
│
├── common/               # Shared Kotlin Multiplatform / JVM Module
│   └── src/main/java/com/neighborhood/safestreet/common/
│       ├── models/       # Incident, IncidentCategory, ProvenanceType, WearSyncPacket
│       └── serialization/# JsonHelper
│
├── firestore.rules       # Strict Firebase Security Rules enforcing 24h expiration & UGC bounds
├── firebase.json         # Firebase project configuration
└── scripts/              # Icon generation scripts
```

---

## 🚀 Deployed & Verified Targets

- **Handheld Device**: Google Pixel 8 Pro (`37220DLJG001ML`) via ADB
- **Wear OS Target**: `sdk_gwear_x86_64` (`emulator-5556`) running Android 14 / Wear OS 5
- **Firebase Project**: `neighborhood-safe-street` (Rules & Indexes deployed)
