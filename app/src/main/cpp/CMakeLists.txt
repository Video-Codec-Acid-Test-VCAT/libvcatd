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

# =========================
# Per-ABI install roots
# =========================
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(DAV1D_INSTALL_DIR "${DAV1D_INSTALL_ARM64_V8A}")
    set(VVDEC_INSTALL_DIR "${VVDEC_INSTALL_ARM64_V8A}")   # <— pass from Gradle
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(DAV1D_INSTALL_DIR "${DAV1D_INSTALL_ARMEABI_V7A}")
    set(VVDEC_INSTALL_DIR "${VVDEC_INSTALL_ARMEABI_V7A}")  # vvdec not built for 32-bit; skip
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

message(STATUS "ABI='${ANDROID_ABI}'")
message(STATUS "  DAV1D_INSTALL_DIR='${DAV1D_INSTALL_DIR}'")
message(STATUS "  VVDEC_INSTALL_DIR='${VVDEC_INSTALL_DIR}'")

# =========================
# dav1d (required)
# =========================
set(DAV1D_INCLUDE_DIR "${DAV1D_INSTALL_DIR}/include")
set(DAV1D_LIB_DIR     "${DAV1D_INSTALL_DIR}/lib")
set(DAV1D_STATIC      "${DAV1D_LIB_DIR}/libdav1d.a")
if(NOT EXISTS "${DAV1D_STATIC}")
    message(FATAL_ERROR "Static dav1d not found: ${DAV1D_STATIC}")
endif()

# =========================
# vvdec (required)
# =========================
set(VVDEC_INCLUDE_DIR "${VVDEC_INSTALL_DIR}/include")
set(VVDEC_LIB_DIR     "${VVDEC_INSTALL_DIR}/lib")
set(VVDEC_STATIC      "${VVDEC_LIB_DIR}/libvvdec.a")


if(NOT EXISTS "${VVDEC_STATIC}")
    message(FATAL_ERROR "Static vvdec not found: ${VVDEC_STATIC}")
endif()

# =========================
# Sources
# =========================
set(VCAT_JNI_SOURCES
        dav1d_jni.cc
        vvdec_jni.cc
)

add_library(vcat_jni SHARED ${VCAT_JNI_SOURCES})

# Includes
target_include_directories(vcat_jni PRIVATE "${DAV1D_INCLUDE_DIR}")
target_include_directories(vcat_jni PRIVATE "${VVDEC_INCLUDE_DIR}")

# NDK/system libs
find_library(log_lib     log)
find_library(android_lib android)
find_library(z_lib       z)

# Link static STL to avoid shipping libc++_shared.so
target_link_libraries(vcat_jni
        PRIVATE
        "${DAV1D_STATIC}"
        "${VVDEC_STATIC}"
        ${log_lib}
        ${android_lib}
        ${z_lib}
        c++_static
)

target_compile_definitions(vcat_jni PRIVATE VCAT_ENABLE_VVDEC=1)

# 32-bit often needs libatomic
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    find_library(atomic_lib atomic)
    if(atomic_lib)
        target_link_libraries(vcat_jni PRIVATE ${atomic_lib})
    endif()
endif()

# Catch missing symbols at link time (fail fast)
target_link_options(vcat_jni PRIVATE -Wl,--no-undefined)
