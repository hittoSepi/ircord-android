# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**IRCord Android** is the Android client for the IRCord end-to-end encrypted chat application. It uses a hybrid architecture with Kotlin/Jetpack Compose for the UI and C++ (NDK) for cryptographic operations and voice handling.

**Architecture**: Client-server relay model — the server never sees plaintext messages.

## Technology Stack

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Language | Kotlin / C++20 | Kotlin for UI, C++ for shared crypto/voice |
| UI Framework | Jetpack Compose | Modern declarative UI |
| Async I/O | Kotlin Coroutines + OkHttp | Android-native networking |
| Serialization | Protobuf Lite | Interop with server and desktop client |
| E2E Crypto | libsignal-protocol-c + libsodium | Signal Protocol (X3DH + Double Ratchet) |
| Database | Room (SQLite) | Android Jetpack persistence |
| Dependency Injection | Hilt | Standard Android DI |
| Audio I/O | Oboe (NDK) | Low-latency Android audio API |
| Build | Gradle + CMake/NDK | Android standard + native builds |

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test

# Clean and rebuild
./gradlew clean assembleDebug
```

## Project Structure

```
ircord-android/
├── app/src/main/
│   ├── java/fi/ircord/android/
│   │   ├── MainActivity.kt           # Single Activity entry point
│   │   ├── IrcordApp.kt              # Application class with Hilt
│   │   │
│   │   ├── di/                        # Hilt modules
│   │   │   ├── AppModule.kt
│   │   │   ├── DatabaseModule.kt
│   │   │   └── NetworkModule.kt
│   │   │
│   │   ├── data/
│   │   │   ├── local/                 # Room database
│   │   │   │   ├── IrcordDatabase.kt
│   │   │   │   ├── dao/
│   │   │   │   └── entity/
│   │   │   ├── remote/                # Network layer
│   │   │   │   ├── IrcordSocket.kt
│   │   │   │   ├── FrameCodec.kt
│   │   │   │   └── ProtobufExt.kt     # Protobuf helpers
│   │   │   └── repository/
│   │   │       ├── CryptoRepository.kt     # NEW: Signal Protocol ops
│   │   │       ├── MessageRepository.kt
│   │   │       └── ChannelRepository.kt
│   │   │
│   │   ├── native/                    # JNI bridge to C++
│   │   │   ├── NativeCrypto.kt        # Signal Protocol JNI
│   │   │   └── NativeStore.kt         # Room-backed store for native
│   │   │
│   │   ├── ui/
│   │   │   ├── theme/
│   │   │   │   ├── Theme.kt           # Material3 theme
│   │   │   │   ├── ThemeViewModel.kt  # NEW: Theme state management
│   │   │   │   ├── Color.kt           # Light/Dark colors (Tokyo Night)
│   │   │   │   └── ...
│   │   │   ├── screen/
│   │   │   │   ├── chat/
│   │   │   │   ├── channels/
│   │   │   │   ├── settings/
│   │   │   │   │   ├── SettingsScreen.kt
│   │   │   │   │   ├── SettingsViewModel.kt
│   │   │   │   │   └── ThemeSelectorDialog.kt  # NEW: Theme picker
│   │   │   │   └── ...
│   │   │   └── navigation/
│   │   │
│   │   └── service/                   # Foreground services
│   │       ├── IrcordService.kt
│   │       └── VoiceService.kt
│   │
│   ├── cpp/                           # NDK native code
│   │   ├── CMakeLists.txt
│   │   ├── crypto/
│   │   │   ├── crypto_engine.hpp/.cpp # Signal Protocol implementation
│   │   │   ├── identity.cpp
│   │   │   ├── signal_store.cpp
│   │   │   └── group_session.cpp
│   │   └── jni/
│   │       └── jni_bridge.cpp         # JNI bindings
│   │
│   └── proto/                         # Shared protobuf schemas
│       └── ircord.proto
```

## Key Features

### 1. Signal Protocol Encryption (NDK)

The native layer (`cpp/`) implements:
- **X3DH** (Extended Triple Diffie-Hellman) for session establishment
- **Double Ratchet** for ongoing 1:1 message encryption
- **Sender Keys** for group/channel encryption
- **Argon2id + XChaCha20-Poly1305** for identity key encryption at rest

```kotlin
// Usage from Kotlin
val cryptoRepository: CryptoRepository

// Initialize with passphrase
val success = cryptoRepository.initialize(userId, passphrase)

// Encrypt (returns null if no session - request key bundle)
val ciphertext = cryptoRepository.encrypt(recipientId, plaintext)

// Decrypt
val plaintext = cryptoRepository.decrypt(senderId, recipientId, ciphertext, type)
```

### 2. Theme Support

Three theme modes available in Settings → Appearance → Theme:
- **System default** (default) - Follows phone's theme
- **Light** - Always light theme
- **Dark** - Tokyo Night dark theme

Managed by `ThemeViewModel` and applied in `MainActivity`.

### 3. Wire Protocol

Length-prefixed Protobuf frames over TLS/TCP:
```
┌──────────────┬────────────────────────┐
│  4 bytes     │  N bytes               │
│  (uint32 BE) │  Protobuf Envelope     │
└──────────────┴────────────────────────┘
```

## Security Model

- Server sees: IP addresses, who messages whom, timestamps
- Server does NOT see: message content, file contents, voice audio (P2P)
- Auth: Ed25519 identity key challenge-response (not password-based)
- E2E: Signal Protocol (X3DH initial + Double Ratchet for ongoing)
- At-rest: Identity keys encrypted with Argon2id + XChaCha20-Poly1305
- Screen capture: Configurable FLAG_SECURE
- Root detection: Warn but don't block (friend group context)

## Thread Model

```
Main Thread (UI)
  └─ Compose recomposition, user input

IO Dispatcher (Kotlin Coroutines)
  ├─ TLS socket I/O
  ├─ Room database operations
  └─ Network framing

Default Dispatcher
  └─ Signal Protocol ops (1-10ms, not blocking UI)

NDK Thread Pool (C++)
  ├─ Crypto operations
  └─ Voice engine

Audio Thread (Oboe callback, realtime)
  ├─ Audio capture
  └─ Audio playback
```

Critical rules:
1. **UI Thread never blocks** — all I/O in coroutines
2. **JNI calls are expensive** (~1-5µs per call), batch when possible
3. **Audio thread is realtime** — no allocations, no locks, no JNI

## Language Notes

The architecture documentation files in `docs/` are in **Finnish**. Key terms:
- *kanava* = channel
- *viesti* = message
- *käyttäjä* = user
- *palvelin* = server
- *asiakasohjelma/client* = client
- *lähetä* = send
- *vastaanota* = receive
