# Demucs Studio (Android) - Clean-Room Reconstruction Specification

**Specification Version:** 1.0.0-PROD  
**Target Architecture:** Native Android (Kotlin + Jetpack Compose)  
**Hardware Acceleration Target:** Qualcomm Snapdragon 8 Gen 2 / Gen 3 (Hexagon HTP NPU, Adreno GPU) + Low-RAM CPU Fallback  
**Classification:** Complete Clean-Room Reconstruction Specification  

---

## TABLE OF CONTENTS
1. [MODULE 1: Product Overview, Tech Stack & File Architecture](#module-1-product-overview-tech-stack--file-architecture)
2. [MODULE 2: Data Architecture, Database Schema & State Management](#module-2-data-architecture-database-schema--state-management)
3. [MODULE 3: Backend API Contracts, Auth & Business Logic](#module-3-backend-api-contracts-auth--business-logic)
4. [MODULE 4: Design System, Tokens & Shared UI Library](#module-4-design-system-tokens--shared-ui-library)
5. [MODULE 5: Core DSP, Neural Engine & Audio Pipeline Implementation](#module-5-core-dsp-neural-engine--audio-pipeline-implementation)
6. [MODULE 6: UI Layer, Verification Checklist & QA Audit](#module-6-ui-layer-verification-checklist--qa-audit)

---

## MODULE 1: Product Overview, Tech Stack & File Architecture

### 1. System Overview & Core Mechanics

#### 1.1 Purpose of the Application
**Demucs Studio** is a fully standalone, native Android on-device Digital Audio Workstation (DAW) and AI stem isolation utility. It separates full-mix stereo music tracks into isolated Vocals and Instrumental/Karaoke audio stems with zero cloud dependencies, zero data egress, and zero telemetry tracking. The application runs native neural inference locally using Facebook Research's Hybrid Transformer Demucs (HT-Demucs v4) model compiled to ONNX Runtime with Qualcomm QNN Execution Provider integration (Hexagon HTP NPU, Adreno OpenCL GPU, and strict low-RAM sequential CPU).

#### 1.2 Target Platform & Hardware Constraints
- **Target OS:** Android 14+ (API 34 to API 36; backward-compatible to Android 7.0 / API 24).
- **Primary Chipset Architecture:** ARM64-v8a (Qualcomm Snapdragon 8 Gen 1 / Gen 2 / Gen 3, Snapdragon X Elite, and equivalent flagship SoCs).
- **Hardware Acceleration Providers:**
  1. `QNN HTP FP16`: Qualcomm Neural Processing SDK communicating with Hexagon Tensor Processor (HTP) via `libcdsprpc.so` (FastRPC).
  2. `QNN GPU FP16`: Qualcomm Adreno GPU executing FP16 OpenCL kernels via `libOpenCL.so`.
  3. `CPU Low-RAM Safe`: Multi-threaded ARM NEON CPU running sequential basic optimization without weight pre-packing or BFCArena pre-allocations to prevent OOM termination.
- **Memory Footprint Mandate:** Peak native heap consumption must remain under 700 MB at all times. Standard ONNX runtime configurations consume upwards of 3.8 GB causing instant Android low-memory killer (LMK) termination. The application enforces a `largeHeap="true"` manifest flag coupled with immediate unpinned native buffer releases.

#### 1.3 Core Domain Rules & Zero-Mock Invariants
1. **Zero Synthetic / Demo Audio Policy:** Under no circumstances does the application generate synthetic sine waves, white noise, mock audio tracks, or pre-rendered waveforms. The application remains permanently locked in an uninitialized empty state until the user picks a valid local file via Android's Storage Access Framework (SAF).
2. **Deterministic Audio Decoding & Normalization:** All input audio (regardless of source sample rate, bit depth, or format: MP3, AAC, FLAC, OGG, WAV) is decoded via Android `MediaCodec` into raw 32-bit float stereo, resampled to exactly 44,100 Hz using linear phase interpolation, and written as 16-bit signed Little-Endian PCM (`source_44100_stereo_*.pcm`).
3. **Hann-Windowed 10-Second Chunking Pipeline:** Audio inference operates on fixed chunks of exactly $343,980\text{ samples}$ ($\approx 7.80\text{ seconds}$ at 44.1 kHz). A $25\%$ overlap crossfade window ($85,995\text{ frames}$ overlap, $257,985\text{ frames}$ hop) is applied with a symmetric linear ramp to prevent inter-chunk boundary clicks, transient phasing, or spectral smearing.
4. **Clean Residual Karaoke Subtraction:** Because `htdemucs_ft_vocals_fp16weights.onnx` has trained weights exclusively on Output Stem Index 3 (Vocals) while Indices 0, 1, and 2 (Drums, Bass, Other) contain untrained random noise generators, the Karaoke stem is synthesized via bit-perfect residual subtraction directly against the original decoded PCM:
   $$\text{Karaoke}[n] = \text{clamp}\Big(\text{Original}[n] - \text{Vocals}[n], -1.0, 1.0\Big)$$
   Planar vs. interleaved indexing mismatches and double-windowing are strictly prohibited.
5. **Real-Time Dual-Track Mixer Engine:** Playback does not rely on Android `MediaPlayer` or ExoPlayer. Playback is driven by a custom single-instance `AudioTrack` pushing 2048-frame buffers ($\approx 46.4\text{ ms}$) in a high-priority coroutine loop. Software mixing reads directly from two uncompressed PCM `RandomAccessFile` streams on disk with atomic volatile faders (`vocalGain`, `karaokeGain`), atomic mute/solo logic, and true RMS calculation for VU meters.

---

### 2. Exact Technology Stack & Dependency Blueprint

#### 2.1 Toolchain & Compilers
- **Build System:** Gradle 9.1.1 (Kotlin DSL: `.gradle.kts`)
- **Android Gradle Plugin (AGP):** `com.android.application:9.1.1`
- **Kotlin Standard Library:** `2.2.10`
- **Kotlin Symbol Processing (KSP):** `2.3.5-2.2.10`
- **JVM Target:** Java 11 bytecode compatibility
- **UI Toolkit:** Jetpack Compose (Compose BOM `2024.09.00`) with Material Design 3

#### 2.2 Complete `gradle/libs.versions.toml`
```toml
[versions]
agp = "9.1.1"
kotlin = "2.2.10"
googleDevtoolsKsp = "2.3.5"
coreKtx = "1.18.0"
lifecycleRuntimeKtx = "2.8.7"
lifecycleViewmodelCompose = "2.8.7"
lifecycleRuntimeCompose = "2.8.7"
activityCompose = "1.10.1"
composeBom = "2024.09.00"
navigationCompose = "2.8.9"
roomRuntime = "2.7.0"
roomKtx = "2.7.0"
roomCompiler = "2.7.0"
kotlinxCoroutinesCore = "1.10.2"
kotlinxCoroutinesAndroid = "1.10.2"
kotlinxCoroutinesTest = "1.10.2"
okhttp = "4.10.0"
loggingInterceptor = "4.10.0"
retrofit = "2.12.0"
converterMoshi = "2.12.0"
moshiKotlin = "1.15.2"
moshiKotlinCodegen = "1.15.2"
datastorePreferences = "1.1.7"
secretsGradlePlugin = "2.0.1"
googleServices = "4.5.0"
firebaseBom = "34.17.0"
onnxruntime = "1.20.0"
qnnRuntime = "2.27.0"
junit = "4.13.2"
junitVersion = "1.3.0"
espressoCore = "3.7.0"
robolectric = "4.16.1"
core = "1.6.1"
runner = "1.6.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleViewmodelCompose" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleRuntimeCompose" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material-icons-core = { group = "androidx.compose.material", name = "material-icons-core" }
androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "roomRuntime" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "roomKtx" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "roomCompiler" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "kotlinxCoroutinesCore" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutinesAndroid" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
logging-interceptor = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "loggingInterceptor" }
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
converter-moshi = { group = "com.squareup.retrofit2", name = "converter-moshi", version.ref = "converterMoshi" }
moshi-kotlin = { group = "com.squareup.moshi", name = "moshi-kotlin", version.ref = "moshiKotlin" }
moshi-kotlin-codegen = { group = "com.squareup.moshi", name = "moshi-kotlin-codegen", version.ref = "moshiKotlinCodegen" }
firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version.ref = "firebaseBom" }
firebase-ai = { group = "com.google.firebase", name = "firebase-ai" }
firebase-appcheck-recaptcha = { group = "com.google.firebase", name = "firebase-appcheck-recaptcha" }
firebase-appcheck-debug = { group = "com.google.firebase", name = "firebase-appcheck-debug" }

# Native Neural Acceleration (QNN & ONNX)
onnxruntime-android-qnn = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android-qnn", version = "1.20.0" }
qualcomm-qnn-runtime = { group = "com.qualcomm.qti", name = "qnn-runtime", version = "2.27.0" }

# Testing
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutinesTest" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
google-devtools-ksp = { id = "com.google.devtools.ksp", version.ref = "googleDevtoolsKsp" }
secrets = { id = "com.google.android.libraries.mapsplatform.secrets-gradle-plugin", version.ref = "secretsGradlePlugin" }
google-services = { id = "com.google.gms.google-services", version.ref = "googleServices" }
```

#### 2.3 Gradle Application Configuration (`app/build.gradle.kts`)
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.secrets)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio.demucsstemstudio.kpvzx"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose M3
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // OkHttp & Moshi
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)

    // Qualcomm QNN Execution Provider & ONNX Runtime Native Binaries
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.20.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.27.0")

    // Firebase (Platform Compatibility)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.appcheck.recaptcha)
    implementation(libs.firebase.appcheck.debug)
}
```

---

### 3. Environment & Configuration Specifications

#### 3.1 `.env.example` Specification
```properties
# GEMINI_API_KEY: Optional backend API token for server-side audio assistance.
# Format: AIzaSy[A-Za-z0-9_-]{33}
# Injection: Secrets panel in AI Studio UI -> BuildConfig.GEMINI_API_KEY
GEMINI_API_KEY=

# FIREBASE_APPCHECK_DEBUG_TOKEN: UUID token for testing AppCheck protected endpoints.
FIREBASE_APPCHECK_DEBUG_TOKEN=
```

#### 3.2 Platform Metadata (`metadata.json`)
```json
{
  "name": "Demucs Studio",
  "description": "Demucs v4 FT ONNX vocal and karaoke stem separator with synchronized DAW-style dual waveforms, low-latency playback, and lossless WAV export.",
  "requestFramePermissions": [],
  "majorCapabilities": ["MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API"]
}
```

#### 3.3 Android Manifest Hardware & Security Contract (`AndroidManifest.xml`)
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:largeHeap="true"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.MyApplication">

        <!-- Qualcomm Snapdragon FastRPC & OpenCL native library declarations for QNN HTP NPU & Adreno GPU -->
        <uses-native-library android:name="libcdsprpc.so" android:required="false" />
        <uses-native-library android:name="libOpenCL.so" android:required="false" />

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.MyApplication">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

---

### 4. Complete Directory & File Tree

```
/
├── .env.example                                      # Specification template for secrets and debug API tokens
├── metadata.json                                     # AI Studio platform identity and capability manifest
├── settings.gradle.kts                               # Root project name definition ("Demucs Studio") and repository config
├── build.gradle.kts                                  # Project-level Gradle build configuration
├── gradle/
│   └── libs.versions.toml                            # Centralized version catalog for dependencies and AGP plugins
└── app/
    ├── build.gradle.kts                              # App-level build file specifying SDK versions, plugins, and dependencies
    └── src/
        └── main/
            ├── AndroidManifest.xml                   # Manifest with largeHeap, permissions, FastRPC native libs, and FileProvider
            ├── res/
            │   ├── values/
            │   │   └── strings.xml                   # String resources for app labels, actions, and track titles
            │   └── xml/
            │       ├── backup_rules.xml              # Android backup exclusion configuration
            │       ├── data_extraction_rules.xml     # Cloud vs device data extraction policies
            │       └── file_paths.xml                # FileProvider paths exposing cacheDir/exports/ to system shares
            └── java/
                └── com/
                    └── example/
                        ├── MainActivity.kt           # Edge-to-edge ComponentActivity hosting DemucsStudioScreen
                        ├── audio/
                        │   ├── decoder/
                        │   │   └── AudioDecoder.kt   # MediaCodec/MediaExtractor 44.1kHz stereo resampler and 1200-peak generator
                        │   ├── demucs/
                        │   │   └── DemucsOnnxSeparationEngine.kt # QNN/CPU ONNX inference engine with 10s chunk overlap-add
                        │   ├── export/
                        │   │   └── WavAudioExporter.kt # 16-bit PCM to RIFF/WAVE lossless file serializer and share intent dispatcher
                        │   ├── model/
                        │   │   └── AudioModels.kt    # Immutable state models, enums (StemType, ModelPreset, ExecutionBackend)
                        │   └── player/
                        │       └── PcmSoftwareMixerEngine.kt # Low-latency dual-stream AudioTrack software mixer with live VU RMS
                        └── ui/
                            ├── AudioSeparationViewModel.kt # Central StateFlow coordinator managing lifecycle, SAF, and mixer state
                            ├── DemucsStudioScreen.kt # High-density DAW workstation Compose screen with dual waveforms and transport
                            ├── components/
                            │   ├── ModelStatusCard.kt # ONNX preset selector, QNN hardware backend toggle, and HTTP download manager
                            │   ├── StudioDialogs.kt  # Modal dialogs for real-time ONNX telemetry HUD and WAV export format selection
                            │   ├── TransportControlBar.kt # Global scrub timeline, timecode display, play/pause, mode toggles, and export
                            │   └── WaveformTrackLane.kt # Canvas waveform renderer, interactive scrub gestures, VU meter, and fader lane
                            └── theme/
                                ├── Color.kt          # Professional cyberpunk DAW palette (StudioBackground, VocalCyan, KaraokeAmber)
                                ├── Theme.kt          # Material 3 DarkColorScheme wrapping Studio colors
                                └── Type.kt           # Monospace and tabular typography specifications for audio measurements
```

---

## MODULE 2: Data Architecture, Database Schema & State Management

### 1. Database & Storage Architecture

The application uses an **Offline-First Hybrid Local Storage Architecture**:
- Structured session metadata is persisted in SQLite / Room.
- Raw continuous audio signals bypass SQLite and are written as uncompressed Little-Endian PCM binaries in `context.cacheDir` and `context.filesDir`.

```sql
-- TABLE: audio_sessions
CREATE TABLE IF NOT EXISTS `audio_sessions` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `title` TEXT NOT NULL,
    `source_uri` TEXT NOT NULL,
    `duration_ms` INTEGER NOT NULL,
    `sample_rate` INTEGER NOT NULL DEFAULT 44100,
    `channels` INTEGER NOT NULL DEFAULT 2,
    `total_frames` INTEGER NOT NULL,
    `source_pcm_path` TEXT NOT NULL,
    `original_peaks_blob` BLOB NOT NULL,
    `created_at_timestamp` INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS `index_audio_sessions_created_at` ON `audio_sessions` (`created_at_timestamp` DESC);

-- TABLE: separated_stems
CREATE TABLE IF NOT EXISTS `separated_stems` (
    `id` TEXT NOT NULL PRIMARY KEY,
    `session_id` TEXT NOT NULL,
    `stem_type` TEXT NOT NULL CHECK(`stem_type` IN ('VOCAL', 'KARAOKE', 'ORIGINAL')),
    `title` TEXT NOT NULL,
    `pcm_file_path` TEXT NOT NULL,
    `waveform_peaks_blob` BLOB NOT NULL,
    `model_preset_id` TEXT NOT NULL,
    `backend_used` TEXT NOT NULL,
    `inference_duration_ms` INTEGER NOT NULL,
    `created_at_timestamp` INTEGER NOT NULL,
    FOREIGN KEY (`session_id`) REFERENCES `audio_sessions` (`id`) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS `index_separated_stems_session_id` ON `separated_stems` (`session_id`);

-- TABLE: installed_models
CREATE TABLE IF NOT EXISTS `installed_models` (
    `preset_id` TEXT NOT NULL PRIMARY KEY,
    `file_name` TEXT NOT NULL UNIQUE,
    `file_path` TEXT NOT NULL,
    `file_size_bytes` INTEGER NOT NULL,
    `sha256_hash` TEXT,
    `is_verified` INTEGER NOT NULL DEFAULT 0,
    `installed_at_timestamp` INTEGER NOT NULL
);
```

### 2. StateFlow Observable Hierarchy
```
AudioSeparationViewModel
├── modelState: StateFlow<ModelState>
├── separationProgress: StateFlow<SeparationProgress>
├── currentBackend: StateFlow<ExecutionBackend>
├── isPlaying: StateFlow<Boolean>
├── playbackProgress: StateFlow<Float>
├── currentPositionMs: StateFlow<Long>
├── vocalVuLevel: StateFlow<Float>
├── karaokeVuLevel: StateFlow<Float>
├── currentMetadata: StateFlow<AudioMetadata?>
├── originalPeaks: StateFlow<FloatArray>
├── vocalTrack: StateFlow<StemTrackState?>
├── karaokeTrack: StateFlow<StemTrackState?>
├── playbackMode: StateFlow<PlaybackMode>
├── isDecoding: StateFlow<Boolean>
└── decodeProgress: StateFlow<Float>
```

---

## MODULE 3: Backend API Contracts, Auth & Business Logic

### 1. Authentication & Security Boundary
- **Zero-Trust Local Sandbox:** All processing is executed within Linux kernel UID boundaries (`u0_a...`). No cloud upload, no analytics, no external network calls except direct model weights streaming from HuggingFace.
- **Storage Access Framework (SAF):** User audio files are selected via `ActivityResultContracts.GetContent()` with scoped URI permissions.
- **FileProvider Sharing:** Exported lossless WAV files in `cache/exports/` are shared using cryptographically isolated content URIs with `FLAG_GRANT_READ_URI_PERMISSION`.

### 2. Core Service Methods
1. `AudioDecoder.decodeToStereo44100(uri, title, onProgress)`: Decodes and linearly resamples input audio to 44.1 kHz stereo PCM and generates 1200-point peak envelopes.
2. `DemucsOnnxSeparationEngine.downloadModel(preset, onProgress)`: Streams ONNX model weights from HuggingFace directly to disk with minimum size verification (> 50 MB).
3. `DemucsOnnxSeparationEngine.separate(pcmFile, metadata)`: Executes 10-second Hann-windowed chunk inference through `OrtSession` using Qualcomm QNN HTP/GPU or low-RAM CPU with clean residual subtraction.
4. `PcmSoftwareMixerEngine.play() / pause() / seekToFraction()`: Real-time dual-track software mixer feeding a dedicated 2048-frame `AudioTrack` stream with atomic volatile fader gains and live RMS calculation.
5. `WavAudioExporter.exportStems(title, type, vocalTrack, karaokeTrack)`: Serializes PCM audio to lossless 16-bit 44.1 kHz RIFF WAVE files with canonical 44-byte headers.

---

## MODULE 4: Design System, Tokens & Shared UI Library

### 1. Palette Specification (`Color.kt`)
- `StudioBackground`: `#0C0E14` (Base container background)
- `StudioSurface`: `#141722` (TopAppBar, inactive cards)
- `StudioSurfaceElevated`: `#1C2130` (Dialogs, bottom transport bar, active cards)
- `StudioCardBorder`: `#283042` (Component borders)
- `VocalCyan`: `#00E5FF` (Vocals waveform, primary action buttons, playhead)
- `VocalCyanMuted`: `#00838F` (Secondary vocal accents)
- `KaraokeAmber`: `#FF9100` (Karaoke waveform, warning accents)
- `StudioLedGreen`: `#00E676` (Model status verified, active playback LED, VU meter)
- `StudioMuteRed`: `#FF334B` (Mute button active state, error banners)
- `StudioSoloYellow`: `#FFEA00` (Solo button active state)
- `StudioTextPrimary`: `#F0F4FC` (Headers, active timecode)
- `StudioTextSecondary`: `#94A3B8` (Subtitles, sample rate metadata)
- `StudioTextMuted`: `#64748B` (Inactive labels)

---

## MODULE 5: Core DSP, Neural Engine & Audio Pipeline Implementation

*(Refer to source files in `app/src/main/java/com/example/audio/` for complete implementations of `AudioDecoder.kt`, `DemucsOnnxSeparationEngine.kt`, `PcmSoftwareMixerEngine.kt`, and `WavAudioExporter.kt`).*

### Summary of Engine Math:
- **Chunk Size ($L$):** 343,980 samples ($\approx 7.80$ seconds at 44.1 kHz).
- **Overlap Size ($O$):** 85,995 samples ($25\%$ linear crossfade window).
- **Hop Size ($H$):** 257,985 samples ($\approx 5.85$ seconds).
- **Residual Subtraction Formula:**
  $$\text{Karaoke}[n] = \text{clamp}\Big(\text{Original}[n] - \text{Vocals}[n], -1.0, 1.0\Big)$$
- **RMS VU Calculation:**
  $$\text{RMS} = \text{clamp}\left(\frac{\sqrt{\frac{1}{N}\sum_{i=1}^N x_i^2}}{32768.0} \cdot \text{Gain}, 0.0, 1.0\right)$$

---

## MODULE 6: UI Layer, Verification Checklist & QA Audit

### 1. 30-Second Verification Audit Command
To verify that no synthetic mock logic exists anywhere in the codebase:
```bash
grep -rnE "(delay\(|Random|Math\.sin|mock|demo|simulate)" app/src/main/java/
```
**Acceptance Criteria:**
- `Random`: Exactly 0 occurrences.
- `Math.sin`: Exactly 0 occurrences (no synthetic tones).
- `delay(`: Exactly 0 occurrences in DSP engine layers.
- Silent fallbacks in catch blocks: Exactly 0 occurrences.

### 2. Execution Flow Verification
1. App opens in clean uninitialized state: "ACTION REQUIRED: LOAD AUDIO FILE".
2. Model downloaded via OkHttp: file verified > 50 MB.
3. User selects real audio via SAF: decoded to 44.1 kHz stereo PCM, 1200 peaks rendered.
4. Separation runs: live ONNX proof telemetry HUD shows node names and shapes.
5. Playback: synchronized dual-waveform playback with real-time VU meters and faders.
6. Export: generates standard 16-bit 44.1 kHz RIFF WAV files for system sharing.

---

## MODULE 7: Automated CI/CD Release Pipeline & Keystore Setup

### 1. Generating a Production Release Keystore
Run the standard Java `keytool` utility locally to create a 2048-bit RSA keystore:
```bash
keytool -genkeypair \
  -v \
  -keystore release-keystore.jks \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "<STRONG_STORE_PASSWORD>" \
  -keypass "<STRONG_KEY_PASSWORD>" \
  -dname "CN=DemucsStudioRelease, OU=MobileAudio, O=DemucsStudio, L=SanFrancisco, ST=California, C=US"
```

### 2. Base64-Encoding & GitHub Repository Secrets Configuration
Encode the generated `.jks` file to a single-line base64 string:
- **Linux:**
  ```bash
  base64 -w 0 release-keystore.jks > keystore_base64.txt
  ```
- **macOS:**
  ```bash
  base64 -i release-keystore.jks | tr -d '\n' > keystore_base64.txt
  ```

In your GitHub repository, navigate to **Settings > Secrets and variables > Actions > New repository secret** and define the following four secrets:
| Secret Name | Value | Description |
| :--- | :--- | :--- |
| `SIGNING_KEY_STORE_BASE64` | Content of `keystore_base64.txt` | Base64-encoded binary content of `release-keystore.jks` |
| `KEYSTORE_PASSWORD` | `<STRONG_STORE_PASSWORD>` | Keystore store password |
| `KEY_ALIAS` | `upload` | Key alias defined during keystore generation |
| `KEY_PASSWORD` | `<STRONG_KEY_PASSWORD>` | Private key password (often same as store password) |

*Note: If these secrets are not configured, the GitHub Actions pipeline automatically detects their absence and generates a self-signed fallback keystore so initial test workflow runs complete successfully without failure.*

### 3. Triggering a Release
To publish an official versioned release with APK, AAB, and SHA256 checksums:
```bash
git tag v1.0.0
git push origin v1.0.0
```
Or trigger manually via the GitHub Actions UI under the **Actions** tab by selecting **Android Production Release > Run workflow** with your desired version tag (e.g., `v1.0.0`).

