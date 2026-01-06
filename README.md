<p align="center">
  <img src="https://github.com/jonathannah/vcat/blob/main/app/src/main/res/drawable/vcat_logo_tnsp_with_tm.png" alt="VCAT Logo" width="260"> 
</p>

<h1 align="center">VCAT™ — Video Codec Acid Test™</h1>

# libvcat — Core Media Library for VCAT

## About libvcat
**libvcat** is the core media library powering **[VCAT (Video Codec Acid Test)](https://github.com/jonathannah/vcat)**.  
It provides the foundational components required for decoding, parsing, and interacting with video data in the VCAT ecosystem.

While **VCAT** delivers the full benchmarking application, **libvcat** houses the lower-level logic — decoders, parsers, and supporting utilities — that make VCAT’s playback and telemetry possible.

## Key Capabilities
- **Decoder integrations**  
  Bridges for bundled software decoders (e.g., **dav1d** for AV1, optional **vvdec** for VVC) and helpers for system hardware decoders.

- **Parser and extractor utilities**  
  Bitstream and metadata parsers used for playback analysis and telemetry.

- **Native/JNI glue**  
  Lightweight native code supporting decoding, performance metrics, and frame-level instrumentation.

- **ExoPlayer integration layer**  
  Provides hooks for integrating with VCAT’s ExoPlayer pipeline while maintaining modular separation from the app layer.

## Build Instructions

### Common (Android / NDK toolchain)
- **JDK 17+**
- **Android SDK** + **Android NDK** (Clang toolchain; r25c or newer recommended)
- **Python 3.8+** (build tooling)
- **pkg-config**
- **Ninja** (build backend)

### dav1d (AV1) — built with Meson
- **Meson ≥ 1.2**
- **Ninja**
- **Clang/Clang++** from the Android NDK
- **For x86/x86_64 targets only:** **nasm ≥ 2.14** (required for dav1d’s x86 ASM)
- **For ARM/ARM64 (NEON):** no nasm required; NEON ASM is assembled by Clang/LLVM
- (Cross-compiling) a Meson **cross file** configured for your NDK/ABI

> Notes
> - On macOS/Linux, install `meson`, `ninja`, `pkg-config`, and (if building x86) `nasm` via your package manager.
> - On ARM-only Android builds, `nasm` is not needed; keep it only if you also build x86/x86_64.
> - libvcat’s Gradle/NDK configuration wires these tools into the build; ensure they’re on your PATH.

### Build libvcat
From the **libvcat/** project root:
Release
```bash
./gradlew clean assembleRelease
```
Debug
```bash
./gradlew clean assembleDebug
```
To publish to local Maven
```bash
./gradlew publishToMavenLocal
```
To use a custom version of libvcat, follow thge steps to publish to local Maven, then in VCAT, use 'File->Sync Project with Gradle Files' to get your local libvcat.

### Feedback
- [Use the discord channel for VCAT conversations](https://discord.gg/36XQYATF)

### Bugs
- Open issues on VCAT or libvcat github projects.  If unsure which to use, use VCAT.
- Include: **steps to reproduce**, **expected vs actual behavior**, **timestamp & timezone**, **browser/app version**, and **screenshots**.

## Relationship to VCAT
| Component | Role |
|-----------|------|
| **VCAT (app)** | Benchmarking UI, telemetry collection, test orchestration, reporting |
| **libvcat** | Core media stack: decoder adapters (e.g., dav1d AV1, optional vvdec VVC), parsers/extractors, JNI/native glue, capability probes |

This separation keeps the app lightweight and lets media-layer work (decoders, parsing, performance hooks) evolve independently from UI and workflow code.

## Project Status
- Actively maintained alongside VCAT
- Current focus:
  - Performance and memory improvements in decoder paths
  - Expanded codec coverage (HEVC, VP9, VVC) and extractor utilities
  - Enhanced metrics hooks for long-running playback/telemetry

Contributions via issues and PRs are welcome.

## Developer Integration Notes
- Building VCAT from source automatically includes **libvcat** pulling the pinned artifact from Maven Central.  
- If libvcat is rebuilt independently, developers can either publish to `mavenLocal` or point VCAT’s `settings.gradle` at the local libvcat project path.
- No external dependency setup is required; VCAT resolves libvcat dependency using local libvcat artifacts if available, otherwise from Maven Central.

## Disclaimer of Suitability
VCAT is provided for general benchmarking and evaluation purposes only. RoncaTech makes no representations or guarantees that VCAT is suitable for any particular purpose, environment, or workflow. You are solely responsible for determining whether VCAT meets your needs. Under no circumstances should reliance on VCAT substitute for your own testing, validation, or professional judgment.

## Limitation of Liability
TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT WILL RONCATECH, LLC OR ITS AFFILIATES, CONTRIBUTORS, OR SUPPLIERS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES, INCLUDING BUT NOT LIMITED TO LOSS OF PROFITS, REVENUE, DATA, OR USE, ARISING OUT OF OR IN CONNECTION WITH YOUR USE OF VCAT, EVEN IF RONCATECH HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.  

You agree that your sole and exclusive remedy for any claim under or related to VCAT will be to discontinue use of the software.

## Patent Notice (No Patent Rights Granted)
VCAT and libvcat are distributed under GPL-3.0-or-later. Nothing in this README, the source code, or the license grants you any rights under third-party patents, including without limitation patents essential to implement or use media codecs and container formats (e.g., AVC/H.264, HEVC/H.265, VVC/H.266, MPEG-2, AAC, etc.).  
- You are solely responsible for determining whether your use, distribution, or deployment of VCAT/libvcat requires patent licenses from any third party (including patent pools or individual patent holders) and for obtaining any such licenses.  
- Contributions to this project may include a limited patent grant from contributors as specified by GPL-3.0-or-later, but no additional patent rights are provided, and no rights are granted on behalf of any third party.  
- Use of bundled or integrated decoders/parsers does not imply or provide patent clearance for any jurisdiction. Your compliance with all applicable intellectual property laws remains your responsibility.

## License

VCAT is licensed under **GPL-3.0-or-later**.  
See: https://www.gnu.org/licenses/gpl-3.0.html

Use of the VCAT logo and artwork is permitted when discussing, documenting, demonstrating, or promoting VCAT itself.  Any other usage requires prior written permission from RoncaTech LLC.

Contact: legal@roncatech.com • https://roncatech.com/legal
