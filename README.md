<p align="center">
  <img src="app/src/main/res/drawable/vcat_logo_tnsp.png" alt="VCAT Logo" width="260">
</p>

<h1 align="center">VCAT — Video Codec Acid Test</h1>

---
# libvcat — Core Media Library for VCAT

## About libvcat
**libvcat** is the core media library powering **VCAT (Video Codec Acid Test)**.  
It provides the foundational components required for decoding, parsing, and interacting with video data in the VCAT ecosystem.

While **VCAT** delivers the full benchmarking application, **libvcat** houses the lower-level logic — decoders, parsers, and supporting utilities — that make VCAT’s playback and telemetry possible.

---

## Key Capabilities
- **Decoder integrations**  
  Bridges for bundled software decoders (e.g., **dav1d** for AV1, optional **vvdec** for VVC) and helpers for system hardware decoders.

- **Parser and extractor utilities**  
  Bitstream and metadata parsers used for playback analysis and telemetry.

- **Native/JNI glue**  
  Lightweight native code supporting decoding, performance metrics, and frame-level instrumentation.

- **ExoPlayer integration layer**  
  Provides hooks for integrating with VCAT’s ExoPlayer pipeline while maintaining modular separation from the app layer.

---

## Build Instructions

### 1. Prerequisites
- JDK 17 or newer  
- Android SDK and NDK (for native decoder components)  
- Gradle build environment (bundled with VCAT)

### 2. Build libvcat
From the **libvcat/** project root:
```bash
./gradlew clean assembleRelease
```

### 3) Model feedback form (web)
- Use OpenAI’s **Chat model feedback** form to report general model issues.
- Include: **steps to reproduce**, **expected vs actual behavior**, **timestamp & timezone**, **browser/app version**, and **screenshots**.

### 4) Community forum (optional, public)
- Post details on the **OpenAI Community** to compare notes and get guidance (not an official support ticket).
- Provide clear **repro steps**, **environment info**, and any **examples** that illustrate the issue.

## Relationship to VCAT
| Component | Role |
|-----------|------|
| **VCAT (app)** | Benchmarking UI, telemetry collection, test orchestration, reporting |
| **libvcat** | Core media stack: decoder adapters (e.g., dav1d AV1, optional vvdec VVC), parsers/extractors, JNI/native glue, capability probes |

This separation keeps the app lightweight and lets media-layer work (decoders, parsing, performance hooks) evolve independently from UI and workflow code.

---

## Project Status
- Actively maintained alongside VCAT
- Current focus:
  - Performance and memory improvements in decoder paths
  - Expanded codec coverage (HEVC, VP9, VVC) and extractor utilities
  - Enhanced metrics hooks for long-running playback/telemetry

Contributions via issues and PRs are welcome.

---

## Developer Integration Notes
- Building VCAT from source automatically includes **libvcat** as a project dependency when both live in the same repo tree.
- If libvcat is rebuilt independently, developers can either publish to `mavenLocal` or point VCAT’s `settings.gradle` at the local libvcat project path.
- No external dependency setup is required; VCAT links against the locally available libvcat build during development.


---

## License

VCAT is licensed under **GPL-3.0-or-later**.  
See: https://www.gnu.org/licenses/gpl-3.0.html

Use of the VCAT logo and artwork is permitted when discussing, documenting, demonstrating, or promoting VCAT itself.  Any other usage requires prior written permission from RoncaTech LLC.

Contact: legal@roncatech.com • https://roncatech.com/legal
