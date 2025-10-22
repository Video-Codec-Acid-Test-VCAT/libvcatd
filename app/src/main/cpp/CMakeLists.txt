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

# Per-ABI install dir passed from Gradle
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(DAV1D_INSTALL_DIR "${DAV1D_INSTALL_ARM64_V8A}")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(DAV1D_INSTALL_DIR "${DAV1D_INSTALL_ARMEABI_V7A}")
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

message(STATUS "ANDROID_ABI='${ANDROID_ABI}' DAV1D_INSTALL_DIR='${DAV1D_INSTALL_DIR}'")

set(DAV1D_INCLUDE_DIR "${DAV1D_INSTALL_DIR}/include")
set(DAV1D_LIB_DIR     "${DAV1D_INSTALL_DIR}/lib")

# Force static dav1d (avoid accidentally finding a .so)
set(DAV1D_STATIC "${DAV1D_LIB_DIR}/libdav1d.a")
if(NOT EXISTS "${DAV1D_STATIC}")
    message(FATAL_ERROR "Static dav1d not found: ${DAV1D_STATIC}")
endif()

add_library(vcat_jni SHARED
        dav1d_jni.cc
)

target_include_directories(vcat_jni PRIVATE "${DAV1D_INCLUDE_DIR}")

# NDK/system libs (use targets or names directly)
find_library(log_lib     log)
find_library(android_lib android)
find_library(z_lib       z)

# Link static STL to avoid shipping libc++_shared.so
target_link_libraries(vcat_jni
        PRIVATE
        "${DAV1D_STATIC}"
        ${log_lib}
        ${android_lib}
        ${z_lib}
        c++_static
)

# 32-bit often needs libatomic
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    find_library(atomic_lib atomic)
    if(atomic_lib)
        target_link_libraries(vcat_jni PRIVATE ${atomic_lib})
    endif()
endif()

# Catch missing symbols at link time (fail fast)
target_link_options(vcat_jni PRIVATE -Wl,--no-undefined)
