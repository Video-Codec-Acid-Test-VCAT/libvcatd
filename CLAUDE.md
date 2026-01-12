# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

libvcat is the core native Android media library (AAR) powering VCAT (Video Codec Acid Test). It provides software video decoders and MP4 parsing capabilities that integrate with ExoPlayer. The library bridges native codec implementations (dav1d for AV1, vvdec for VVC/H.266) to Android via JNI.

**Key specs:** Android API 29+, Target SDK 35, NDK 27.0.12077973, GPL-3.0-or-later

## Build Commands

```bash
# Build release AAR
./gradlew clean assembleRelease

# Build debug AAR
./gradlew clean assembleDebug

# Publish to local Maven (~/.m2)
./gradlew publishToMavenLocal

# Run unit tests
./gradlew test

# Run single test class
./gradlew test --tests "com.roncatech.libvcat.vvdec.VvcVideoCfgParserTest"
```

**Build requirements:** JDK 17+, Python 3.8+, Meson ≥1.2, Ninja, pkg-config. NASM ≥2.14 only needed for x86 builds (not ARM).

## Build System

The build is hybrid Gradle + CMake + Meson:

1. **Gradle** orchestrates the overall build and Maven publishing
2. **Meson** builds dav1d (AV1 decoder) - auto-cloned from VideoLAN @ v1.5.1
3. **CMake** builds vvdec (VVC decoder) - auto-cloned from Fraunhofer @ v3.0.0
4. **CMake** also builds the JNI layer that links both static decoder libraries into `libvcat_jni.so`

External dependencies are fetched automatically on first build. Static libraries are installed to `app/build/{dav1d,vvdec}/install-{abi}/`.

## Architecture

### Plugin-Based Decoder System

```
VcatDecoderPlugin (interface) ─── VcatDecoderManager (singleton registry)
        │
        ├── VcatDav1dPlugin (AV1)
        │       └── Dav1dDecoder → NativeDav1d → dav1d_jni.cc → libdav1d.a
        │
        └── VcatVvcdecPlugin (VVC/H.266)
                └── VvdecDecoder → NativeVvdec → vvdec_jni.cc → libvvdec.a
```

- **VcatDecoderPlugin**: SPI interface for codec implementations. Provides decoder identification, MIME type, profile support, and renderer creation.
- **VcatDecoderManager**: Thread-safe singleton that auto-discovers and registers decoder plugins at startup.
- **Native***: JNI binding classes with native methods for create/queue/dequeue/render operations.

### Package Structure

- `com.roncatech.libvcat.decoder` - Core decoder framework and plugin interfaces
- `com.roncatech.libvcat.dav1d` - AV1 decoder plugin (dav1d integration)
- `com.roncatech.libvcat.vvdec` - VVC/H.266 decoder plugin (vvdec integration)
- `com.roncatech.libvcat.extractor.mp4` - Custom MP4/fragmented MP4 parser (based on ExoPlayer's deprecated API)

### JNI Layer (`app/src/main/cpp/`)

Single shared library `libvcat_jni.so` wraps both decoders:
- `dav1d_jni.cc` - AV1 JNI bindings, renders RGBA8888 to Surface
- `vvdec_jni.cc` - VVC JNI bindings, renders YV12 to Surface

Both use a queue/dequeue pattern with PTS tracking for frame ordering.

## Key Technical Notes

- **Thread limits**: vvdec has known issues with ≥4 threads; recent commits limit thread count
- **Surface rendering**: dav1d outputs RGBA8888, vvdec outputs YV12
- **Plugin registration**: First registration wins in VcatDecoderManager
- **ExoPlayer integration**: Decoders implement ExoPlayer's Decoder interface; renderers extend DecoderVideoRenderer
- **ABIs**: arm64-v8a and armeabi-v7a only (no x86)

## Maven Coordinates

```
com.roncatech.vcat:libvcat:0.0.3.1
```

Publishing to local Maven happens automatically after assembleDebug/Release.
