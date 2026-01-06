# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VCAT (Video Codec Acid Test) is an Android library providing ExoPlayer 3 (Media3) integration with custom video decoder plugins for AV1 (dav1d) and VVC/H.266 (vvdec) codecs. Licensed under GPLv3 with commercial licensing available.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Publish to Maven local (~/.m2/)
./gradlew publishToMavenLocal

# Run unit tests
./gradlew test

# Clean build
./gradlew clean build
```

### Build Options

```bash
# Use local codec sources instead of cloning
./gradlew -PDAV1D_SRC=/path/to/dav1d -PVVDEC_SRC=/path/to/vvdec build

# Override Python/CMake/Ninja paths
./gradlew -Ppython=/usr/bin/python3 -Pcmake=/opt/cmake -Pninja=/opt/ninja build

# Sign release artifacts
./gradlew -PsignRelease=true -Psigning.gnupg.keyName=<key> publishToMavenLocal
```

## Architecture

### Plugin System

The library uses a Service Provider Interface (SPI) pattern for decoder plugins:

- **VcatDecoderPlugin** - Interface defining codec plugins (getId, getMimeType, createVideoRenderer)
- **VcatDecoderManager** - Singleton registry for plugins (ConcurrentHashMap, last-registration-wins)
- **InternalDecoderLoader** - Auto-discovers and registers built-in plugins

### Decoder Implementations

Each decoder follows the same pattern:
1. **Plugin** (VcatDav1dPlugin/VcatVvcdecPlugin) - Implements VcatDecoderPlugin
2. **VideoRenderer** (Dav1dVideoRenderer/VvdecVideoRenderer) - Extends Media3 DecoderVideoRenderer
3. **Decoder** (Dav1dDecoder/VvdecDecoder) - Extends SimpleDecoder for frame processing
4. **Native bindings** (NativeDav1d/NativeVvdec) - JNI stubs to C++ code

### Native JNI Flow

Both decoders follow this lifecycle:
1. `nativeCreate(threadCount)` - Initialize decoder
2. `nativeQueueInput(data, pts)` - Queue compressed NAL units
3. `nativeDequeueFrame()` - Non-blocking dequeue returns native picture handle
4. `nativeRenderToSurface(handle, surface)` - Render to Android Surface
5. `nativeReleasePicture(handle)` - Release picture handle
6. `nativeFlush()` / `nativeSignalEof()` - Reset or signal end of stream

### MP4 Extractor

VcatMp4Extractor3 extends Media3's MP4 extractor to support:
- VVC codec (vvc1 sample entry, vvcC config box)
- Custom STSD atom parsing via NonStdDecoderStsdParser interface
- VideoConfiguration extraction for non-standard codecs

## Key Files

| Path | Purpose |
|------|---------|
| `app/build.gradle` | Native build config, meson/cmake orchestration |
| `app/src/main/cpp/dav1d_jni.cc` | AV1 JNI bridge (I420→RGBA conversion) |
| `app/src/main/cpp/vvdec_jni.cc` | VVC JNI bridge (YV12 output) |
| `app/src/main/java/.../decoder/VcatDecoderPlugin.java` | Plugin SPI interface |
| `app/src/main/java/.../decoder/VcatDecoderManager.java` | Plugin registry |
| `app/src/main/java/.../vvdec/VvcNalUnitUtil.java` | VVC NAL unit parsing utilities |

## Native Build Process

The build system handles native codec compilation:
1. Installs Python meson to local directory
2. Fetches dav1d (v1.5.1) and vvdec (v3.0.0) from git
3. Generates Android NDK cross-compilation toolchain files
4. Builds static libraries per ABI (arm64-v8a, armeabi-v7a for dav1d; arm64-v8a only for vvdec)
5. CMake builds JNI wrappers linking against static libs

## Dependencies

- Media3/ExoPlayer: 1.8.0
- Android NDK: 27.0.12077973
- Min SDK: 29, Target SDK: 35
- dav1d: v1.5.1
- vvdec: v3.0.0

## Publishing

- Group: `com.roncatech.vcat`
- Artifact: `libvcat`
- Output: AAR + sources JAR to Maven local
