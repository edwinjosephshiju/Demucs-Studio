# Demucs v4 FT ONNX Stem Separator & Karaoke Studio

A high-performance Android audio stem separation studio powered by the Demucs v4 fine-tuned ONNX model (`htdemucs_ft.onnx`). The app enables real-time and offline vocal vs. karaoke (instrumental) separation, low-latency playback via a single-stream phase-locked software mixer, interactive DAW-style waveforms with scrubbing, channel mute/solo controls, and lossless audio export.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural decisions have been refined and confirmed:

- **Single AudioTrack Software Mixer**: Replaced dual AudioTracks with a single hardware AudioTrack sink fed by a high-priority software mixer loop. This guarantees 100% sample-accurate phase alignment and eliminates comb-filtering audio artifacts when playing both stems.
- **Off-Heap Disk Streaming**: PCM buffers are streamed directly to temporary cache files and accessed via `MappedByteBuffer` or `RandomAccessFile`. Only downsampled amplitude peaks (`FloatArray(2000)`) are stored in memory for Compose Canvas waveform rendering, keeping JVM heap usage strictly under 50 MB.
- **44.1kHz Stereo Normalization**: Integrated linear/sinc resampling and channel conversion pipeline so any input format (MP3, 48kHz M4A, Mono) correctly feeds the strict 44.1kHz stereo Demucs tensor input.
- **XNNPACK & Multi-threaded CPU**: Configured ONNX Runtime Mobile with XNNPACK Execution Provider and intra-op thread pooling (4 threads), avoiding NNAPI graph partitioning failures on hybrid time-spectrogram architectures.
- **On-Demand Hugging Face Model Manager**: The model weights (`htdemucs_ft.onnx`) are downloaded on-demand with progress tracking, resumability, and SHA-256 verification directly into `context.filesDir`, with sideloading support from local storage.

---

## 1. Overview & Core Concept

- **What It Does**: Transforms any song (MP3, WAV, FLAC, M4A) into studio-quality separated Vocal and Karaoke backing tracks directly on-device using the Demucs v4 Fine-Tuned ONNX model.
- **Target Audience / Persona**: Vocalists, karaoke performers, music producers, remixers, and content creators needing instant vocal isolation and karaoke backing track creation without cloud server latency or privacy compromises.
- **Key Value**: Low-latency, privacy-first local processing, zero-lag synchronized playback with real-time stem mixing, and broadcast-ready WAV stem export.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Model Setup & Download**:
   - Clear banner showing model status (Ready, Downloading with real-time byte count progress & speed, or Import Local ONNX file). Minimum 50 MB file size verification.

2. **Audio Import & Preprocessing**:
   - Strict file-picker entry gate: The workspace remains locked in an uninitialized state until a valid local audio file is selected via the Android Storage Access Framework (SAF) and a verified ONNX model file is present on disk.
   - Automatic background decode and 44.1kHz stereo normalization with real-time peak envelope extraction from decoded PCM.

3. **On-Device Stem Separation**:
   - High-throughput chunked inference (10-second sliding windows with Hann overlap-add crossfade).
   - Real-time progress HUD showing current chunk, total chunks, percentage, and ETA seconds.
   - Cancel button to abort separation safely and reclaim temp resources.

4. **DAW Dual-Track Studio Playback**:
   - **Vocal Lane (Electric Cyan `#00E5FF`)**: Waveform, Mute button, Solo button, Gain fader (`0..150%`), and live RMS peak meter.
   - **Karaoke Lane (Warm Amber `#FF9100`)**: Waveform, Mute button, Solo button, Gain fader (`0..150%`), and live RMS peak meter.
   - **Transport Dock**: Play/Pause, Replay, Timecode (`00:00.00 / 03:45.00`), Seek scrub bar, and Quick Mode Presets (Both / Vocals Only / Karaoke Only).

5. **Stem Export**:
   - Export modal for saving: Vocals (WAV), Karaoke (WAV), Both Stems (ZIP/folder), or Current Custom Mix (WAV).
   - Direct integration with MediaStore / Android Share Sheet.

### Visual Identity & Theme

- **Aesthetic Direction**: Cyberpunk DAW Hardware Studio (Obsidian `#0F1117`, Studio Slate `#181B22`, Neon Cyan `#00E5FF`, Neon Amber `#FF9100`, LED Green `#00E676`).
- **Typography**: Digital monospace font for timecodes, rates, and chunk stats; crisp high-contrast sans for labels.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Single-AudioTrack Software Mixer for Phase-Locked Playback**
  - *Chosen Approach*: Jetpack Compose custom waveform canvas coupled with a single low-latency `AudioTrack` fed by a real-time PCM software mixer loop reading from memory-mapped stem files.
  - *Why*: Because the Karaoke track is computed via residual subtraction (`Original - Vocals`), sample-accurate phase alignment is mandatory. Summing the Vocal and Karaoke streams in a single software mixer loop eliminates inter-player clock drift and provides zero-latency mute, solo, and fader response.

- **Decision 2: Inference Pipeline, Chunking & Memory Optimization**
  - *Chosen Approach*: ONNX Runtime Mobile configured with XNNPACK and multi-threaded CPU execution, processing 10-second overlapping windows (44.1kHz stereo) with linear cross-fading / Hann window overlap-add, streaming results directly to disk.
  - *Why*: Avoids NNAPI partitioning fallbacks on complex hybrid spectrogram/time-domain graphs and keeps JVM heap usage under 50 MB by writing separated chunks directly to temporary cache files instead of holding full-song float arrays in RAM.

- **Decision 3: Audio Decoding, Resampling & WAV Rendering**
  - *Chosen Approach*: Android `MediaExtractor` + `MediaCodec` decoding to raw PCM, normalized through a 44.1kHz stereo resampler, processed through the separation engine, and written to disk as standard 16-bit 44.1kHz RIFF WAV files.
  - *Why*: Hardware-accelerated decoding with guaranteed tensor shape compatibility (`[1, 2, N]`) regardless of whether the source file is 44.1kHz MP3 or 48kHz M4A.

---

## 4. Technical Architecture & Data Strategy

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                    Jetpack Compose UI                        │
│                                                              │
│  ┌────────────────────────┐      ┌────────────────────────┐  │
│  │   Track 1: Vocals      │      │   Track 2: Karaoke     │  │
│  │  [Waveform Visualizer] │      │  [Waveform Visualizer] │  │
│  │  [Mute] [Solo] [Vol]   │      │  [Mute] [Solo] [Vol]   │  │
│  └────────────────────────┘      └────────────────────────┘  │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ Transport Controls: Play/Pause, Seek, Timecode, Export │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬───────────────────────────────┘
                               │ UI State & Actions
                               ▼
┌──────────────────────────────────────────────────────────────┐
│               AudioSeparationViewModel                       │
│  - Playback State Flow (Timecode, Playing, Volume, Mutes)    │
│  - Model Download & Verification State Flow                  │
│  - Separation Progress Flow (Phase, Percent, ETA)            │
└──────────────┬───────────────────────────────┬───────────────┘
               │                               │
               ▼                               ▼
┌──────────────────────────────┐ ┌─────────────────────────────┐
│    PcmSoftwareMixerEngine    │ │ DemucsOnnxSeparationEngine  │
│  - MappedByteBuffer Readers  │ │ - MediaCodec + 44.1kHz Sync │
│  - Sample-Accurate Mix Loop  │ │ - Overlap-Add Chunk Streamer│
│  - Real-time Gain/Mute/Solo  │ │ - ONNX Runtime (XNNPACK)    │
│  - RMS/Peak VU Meter Feed    │ │ - Residual Karaoke Compute  │
└──────────────┬───────────────┘ └─────────────┬───────────────┘
               │                               │
               ▼                               ▼
┌──────────────────────────────┐ ┌─────────────────────────────┐
│    Single AudioTrack Sink    │ │ Disk Cache & WAV Exporter   │
│   (Phase-Locked Hardware)    │ │ (Memory-Mapped 16-bit WAV)  │
└──────────────────────────────┘ └─────────────────────────────┘
```

### Data Model & State Strategy

- **`ModelState`**: Tracks `htdemucs_ft.onnx` readiness (`NOT_DOWNLOADED`, `DOWNLOADING(progress, bytes)`, `VERIFYING`, `READY(filePath)`, `ERROR`).
- **`AudioTrackState`**: Metadata for loaded source audio (`uri`, `title`, `durationMs`, `originalSampleRate`, `channels`, `decodedPcmFile`).
- **`StemTrack`**: Holds stem type (`VOCAL`, `KARAOKE`), `isMuted`, `isSoloed`, `volumeGain` (`0.0f..1.5f`), downsampled `waveformPeaks: FloatArray` (for Compose Canvas rendering), `currentVuLevel: Float`, and `pcmFile: File` reference (avoiding in-heap full PCM arrays).
- **`SeparationProgress`**: Current pipeline stage (`IDLE`, `DECODING_AND_RESAMPLING`, `INFERRING_CHUNKS`, `WRITING_WAV`, `COMPLETED`, `CANCELLED`, `ERROR`), `progress: Float` (`0f..1f`), `currentChunk: Int`, `totalChunks: Int`, and `etaSeconds: Long`.
- **`ExportOptions`**: Stem selection (`VOCAL_ONLY`, `KARAOKE_ONLY`, `BOTH_STEMS`, `MIXED_MASTER`), format (`WAV_PCM_16BIT_44100`), and target destination URI.

---

## 5. Implementation Status & Verification

- [x] **Project Metadata & Theme Setup**: Renamed app to `Demucs Studio`, updated `strings.xml`, `settings.gradle.kts`, unique `applicationId`, and crafted dark Cyberpunk DAW theme.
- [x] **Adaptive Launcher Icon**: Custom vector foreground with soundwave split node and pro-audio slate background.
- [x] **Dependencies Configured**: Integrated `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` with XNNPACK support.
- [x] **Audio Decoders & 44.1kHz Resampler**: Hardware-accelerated `MediaCodec` + `MediaExtractor` decoding MP3, WAV, M4A to strict 44.1kHz stereo PCM.
- [x] **Demucs v4 FT ONNX Separation Engine**: On-demand Hugging Face model downloader (`htdemucs_ft.onnx`), local `.onnx` sideloading, chunked sliding window with Hann overlap-add, and model-level 2-track vocal & residual karaoke extraction.
- [x] **Single-Stream Software Audio Mixer**: Single hardware `AudioTrack` with real-time software sum loop reading disk-mapped stem PCM, eliminating clock drift and comb filtering with instant mute/solo.
- [x] **Interactive Waveform Visualizer & DAW Track Lanes**: Real-time dual waveform Canvas with touch-scrub gestures, tactile Mute/Solo LED buttons, gain sliders, and peak LED meters.
- [x] **Lossless WAV Exporter**: 16-bit 44.1kHz WAV generation and system share sheet integration for Vocals, Karaoke, Both Stems, or Studio Master Mix.
- [x] **Zero-Mock Production Mandate Verified**: Purged all synthetic demo generators, fake delay timers, and DSP catch-fallbacks. Enforced strict 50MB model verification and Storage Access Framework gatekeeping.
- [x] **Automated Test Suite Verified**: Standard JVM and Robolectric tests passing cleanly (`:app:testDebugUnitTest BUILD SUCCESSFUL`).
- [x] **Demucs v4 FT Graph Reshape Alignment Fixed**: Fixed `/model/Reshape_17` shape mismatch (`{1, 4, -1, 343980}`) by strictly aligning chunk frame size to Demucs $7.8\text{s}$ segment size (`DEMUCS_SEGMENT_FRAMES = 343980`), zero-padding input tensors, and implementing linear crossfade overlap-add.
- [x] **Vocal Leakage & Separation Fidelity Fixed**: Resolved poor separation by applying Demucs standard mono mean/std variance normalization (`(x - mean) / std`) and scaling stems by `std`, and switched Karaoke from phase-corrupting residual subtraction to direct model stem summation (`Drums + Bass + Other`), eliminating vocal bleed.


