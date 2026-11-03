#
# Copyright 2021 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# LINT.IfChange
cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)
# LINT.ThenChange(../../../build.gradle.kts)

# FFmpeg 9.0 public headers use standard C++17 attributes.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

project(libffmpegJNI C CXX)

set(decoder_ffmpeg_main "${CMAKE_CURRENT_SOURCE_DIR}/..")
set(ffmpeg_include "${decoder_ffmpeg_main}/ffmpeg/include")
set(ffmpeg_binaries "${decoder_ffmpeg_main}/jniLibs/${ANDROID_ABI}")
set(renderer_sdk "${decoder_ffmpeg_main}/renderer-sdk/${ANDROID_ABI}")
set(libplacebo_include "${renderer_sdk}/include")
set(libplacebo_archive "${renderer_sdk}/lib/libplacebo.a")

set(supported_ffmpeg_abis "armeabi-v7a" "arm64-v8a")
list(FIND supported_ffmpeg_abis "${ANDROID_ABI}" supported_ffmpeg_abi_index)
if(supported_ffmpeg_abi_index EQUAL -1)
    message(FATAL_ERROR "Unsupported FFmpeg ABI: ${ANDROID_ABI}")
endif()

set(ffmpeg_lib_file_path ${ffmpeg_binaries}/libffmpeg.so)
if(NOT EXISTS "${ffmpeg_lib_file_path}")
    message(FATAL_ERROR
            "Missing FFmpeg library for ${ANDROID_ABI}: ${ffmpeg_lib_file_path}")
endif()
add_library(
        ffmpeg
        SHARED
        IMPORTED)
set_target_properties(
        ffmpeg PROPERTIES
        IMPORTED_LOCATION
        ${ffmpeg_lib_file_path})

if(NOT EXISTS "${libplacebo_include}/libplacebo/config.h")
    message(FATAL_ERROR
            "Missing libplacebo headers for ${ANDROID_ABI}: ${libplacebo_include}")
endif()
if(NOT EXISTS "${libplacebo_archive}")
    message(FATAL_ERROR
            "Missing libplacebo archive for ${ANDROID_ABI}: ${libplacebo_archive}")
endif()
add_library(libplacebo STATIC IMPORTED)
set_target_properties(
        libplacebo PROPERTIES
        IMPORTED_LOCATION
        ${libplacebo_archive})

find_library(android_log_lib log)
find_library(android_egl_lib EGL)
find_library(android_gles_lib GLESv3)
find_library(android_dl_lib dl)
find_library(android_math_lib m)

add_library(ffmpegJNI
            SHARED
            ffmain.cc
            ffcommon.cc
            ffaudio.cc
            ffvideo.cc
            ffvideo_frame.cc
            ffvideo_gles.cc
            ffvideo_libav.c
            ffvideo_placebo.cc)

target_include_directories(ffmpegJNI
                           PRIVATE ${ffmpeg_include}
                           PRIVATE ${libplacebo_include})

target_compile_options(ffmpegJNI
                       PRIVATE -Wall -Wextra -pthread)

target_compile_definitions(ffmpegJNI
                           PRIVATE PL_STATIC)

target_link_libraries(ffmpegJNI
                      PRIVATE android
                      PRIVATE ffmpeg
                      PRIVATE libplacebo
                      PRIVATE ${android_egl_lib}
                      PRIVATE ${android_gles_lib}
                      PRIVATE ${android_dl_lib}
                      PRIVATE ${android_math_lib}
                      PRIVATE ${android_log_lib})

target_link_options(ffmpegJNI
                    PRIVATE "-Wl,-rpath-link,${ffmpeg_binaries}"
                    PRIVATE "-Wl,--exclude-libs,libplacebo.a"
                    PRIVATE -pthread)

# Additional flags needed for "arm64-v8a" from NDK 23.1.7779620 and above.
# See https://github.com/google/ExoPlayer/issues/9933#issuecomment-1029775358.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-Bsymbolic")
endif()

