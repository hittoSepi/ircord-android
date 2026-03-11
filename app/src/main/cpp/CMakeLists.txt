cmake_minimum_required(VERSION 3.22.1)
project(ircord-native)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Android NDK already provides OpenSSL (libcrypto)
# We need to fetch libsignal-protocol-c and use libsodium

include(FetchContent)

# Fetch libsignal-protocol-c
FetchContent_Declare(
    libsignal-protocol-c
    GIT_REPOSITORY https://github.com/signalapp/libsignal-protocol-c.git
    GIT_TAG v2.3.3
    GIT_SHALLOW TRUE
)

# Configure libsignal-protocol-c build for Android
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(libsignal-protocol-c)

# NOTE: libsodium is not used - using OpenSSL from NDK instead

# Native crypto library
add_library(ircord-native SHARED
    jni/jni_bridge.cpp
    jni/jni_voice_bridge.cpp
    crypto/crypto_engine.cpp
    crypto/identity.cpp
    crypto/signal_store.cpp
    crypto/group_session.cpp
    voice/voice_engine.cpp
)

target_include_directories(ircord-native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${libsignal-protocol-c_SOURCE_DIR}/src
)

# Link libraries
target_link_libraries(ircord-native
    signal-protocol-c
    log         # Android log
)

# Compiler flags for Android
target_compile_options(ircord-native PRIVATE
    -Wall
    -Wextra
    -fexceptions
    -frtti
)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(ircord-native PRIVATE -g -O0)
else()
    target_compile_options(ircord-native PRIVATE -O3 -fvisibility=hidden -flto)
    target_link_options(ircord-native PRIVATE -flto)
endif()
