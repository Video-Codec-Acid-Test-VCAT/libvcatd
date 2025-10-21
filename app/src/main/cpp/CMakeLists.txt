cmake_minimum_required(VERSION 3.22)
project(libvcat_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# tighter visibility (optional)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

if(NOT ANDROID)
    message(FATAL_ERROR "Android/NDK build only")
endif()

# Read per-ABI install dir from -D args (gradle passes these)
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(DAV1D_INSTALL_DIR "${DAV1D_INSTALL_ARM64_V8A}")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(DAV1D_INSTALL_DIR "${DAV1D_INSTALL_ARMEABI_V7A}")
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

# Don't hard-fail at configure time; Gradle will build dav1d first before compile/link.
message(STATUS "ANDROID_ABI='${ANDROID_ABI}' DAV1D_INSTALL_DIR='${DAV1D_INSTALL_DIR}'")

set(DAV1D_INCLUDE_DIR "${DAV1D_INSTALL_DIR}/include")
set(DAV1D_LIB_DIR     "${DAV1D_INSTALL_DIR}/lib")

# Prefer find_library; if not found at configure time, fall back to the expected path.
find_library(DAV1D_STATIC NAMES dav1d PATHS "${DAV1D_LIB_DIR}" NO_DEFAULT_PATH)
if(NOT DAV1D_STATIC)
    # Don't check existence here; it may be created later by the Meson task
    set(DAV1D_STATIC "${DAV1D_LIB_DIR}/libdav1d.a")
endif()

add_library(vcat_jni SHARED
        dav1d_jni.cc
)

# Headers (path may not exist yet during configure; that's OK)
target_include_directories(vcat_jni PRIVATE "${DAV1D_INCLUDE_DIR}")

# NDK/system libs
find_library(log-lib     log)
find_library(android-lib android)
find_library(z-lib       z)
find_library(cpp-lib     c++_shared)

target_link_libraries(vcat_jni
        "${DAV1D_STATIC}"
        "${log-lib}"
        "${android-lib}"
        "${z-lib}"
        "${cpp-lib}"
)

# 32-bit often needs libatomic
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    find_library(atomic-lib atomic)
    if(atomic-lib)
        target_link_libraries(vcat_jni "${atomic-lib}")
    endif()
endif()
