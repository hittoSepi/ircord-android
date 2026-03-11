# IRCord Android Client — Arkkitehtuuri

**Stack:** Kotlin · Jetpack Compose · C++ (NDK) · Protobuf · Room · Hilt

---

## 1. Komponentit yhdellä silmäyksellä

```
┌───────────────────────────────────────────────────────────────────┐
│                     ircord-android                                 │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                    UI Layer (Compose)                        │ │
│  │   Screens · Components · Theme · Navigation                 │ │
│  └────────────────────────────┬────────────────────────────────┘ │
│                               │                                   │
│  ┌────────────────────────────┴────────────────────────────────┐ │
│  │              ViewModel Layer (Kotlin)                        │ │
│  │   ChatVM · VoiceVM · SettingsVM · ChannelListVM             │ │
│  │   ThemeViewModel (NEW)                                      │ │
│  └────────────────────────────┬────────────────────────────────┘ │
│                               │                                   │
│  ┌────────────────────────────┴────────────────────────────────┐ │
│  │            Repository / UseCase Layer (Kotlin)               │ │
│  │   MessageRepo · ChannelRepo · KeyRepo · VoiceRepo           │ │
│  │   CryptoRepository (NEW) · NativeStore (NEW)                │ │
│  └──────┬─────────────────┬─────────────────┬──────────────────┘ │
│         │                 │                 │                     │
│  ┌──────┴──────┐  ┌───────┴───────┐  ┌─────┴──────────────────┐ │
│  │  NetClient  │  │  LocalStore   │  │  Native Bridge (JNI)   │ │
│  │  (OkHttp /  │  │  (Room DB)    │  │  ┌──────────────────┐  │ │
│  │   raw TLS   │  │  + SharedPrefs│  │  │  CryptoEngine    │  │ │
│  │   socket)   │  │               │  │  │  (libsignal +    │  │ │
│  └─────────────┘  └───────────────┘  │  │   libsodium)     │  │ │
│                                       │  │  Argon2id +      │  │ │
│                                       │  │  XChaCha20-P1305 │  │ │
│                                       │  ├──────────────────┤  │ │
│                                       │  │  Group Cipher    │  │ │
│                                       │  │  (Sender Keys)   │  │ │
│                                       │  └──────────────────┘  │ │
│                                       └────────────────────────┘ │
└───────────────────────────────────────────────────────────────────┘
```

### Miksi Kotlin + NDK (C++) hybridimalli?

IRCord-serveri ja desktop-client ovat C++20. Signal Protocol -sessiot ja krypto-opsit
on kirjoitettu C++:lla ja jaetaan desktop-clientin kanssa. Android-client käyttää
**samaa natiivikerrosta** — JNI-bridge yhdistää Kotlin-UI:n C++-krypto-moottoriin.

| Kerros | Kieli | Rationale |
|--------|-------|-----------|
| UI + ViewModel | Kotlin | Compose on paras Android UI toolkit, lifecycle-tuki |
| Repo / UseCase | Kotlin | Coroutines, Room, Hilt — Android-ekosysteemi |
| Network framing | Kotlin | OkHttp/raw socket + Protobuf-lite, Android-natiivi TLS |
| Crypto (Signal) | C++ (NDK) | **Jaettu desktop-clientin kanssa**, libsignal-protocol-c |
| Voice | C++ (NDK) | Jaettu desktop-clientin kanssa, libdatachannel + Opus |
| Audio I/O | C++ (NDK) | Oboe (Googlen low-latency Android audio API) |

---

## 2. Thread Model

```
┌─────────────────────────────────────────────────────────────┐
│  Main Thread (UI)                                            │
│   Compose recomposition, user input, navigation              │
│   → EI koskaan blokkaa — kaikki raskas työ coroutineissa    │
└────────────────────┬────────────────────────────────────────┘
                     │ StateFlow / SharedFlow
         ┌───────────┼───────────────┬──────────────────┐
         ▼           ▼               ▼                  ▼
┌──────────────┐ ┌──────────┐ ┌───────────────┐ ┌────────────┐
│  IO Dispatcher│ │ Default  │ │  NDK Thread   │ │ Audio      │
│  (Coroutine) │ │Dispatcher│ │  Pool         │ │ Thread     │
│              │ │          │ │  (C++ side)   │ │ (Oboe      │
│ - TLS socket │ │ - Proto  │ │              │ │  callback, │
│ - Room DB    │ │   parse  │ │ - Signal ops │ │  realtime) │
│ - Reconnect  │ │ - State  │ │ - ICE/DTLS   │ │            │
│              │ │   update │ │ - Opus enc/  │ │ - Capture  │
│              │ │          │ │   decode     │ │ - Playback │
└──────────────┘ └──────────┘ └───────────────┘ └────────────┘
```

**Kriittiset säännöt:**

1. **UI Thread ei koskaan blokkaa** — kaikki I/O ja krypto coroutineissa
2. **NDK-kutsut ovat kalliita** — JNI boundary overhead ~1-5µs per call, batchaa kun mahdollista
3. **Audio thread (Oboe callback)** on realtime — ei allokointeja, ei lockkeja, ei JNI-kutsuja
4. **Signal Protocol -operaatiot** voivat kestää 1-10ms (X3DH erityisesti) — aina Default dispatcherissa

---

## 3. JNI Bridge — Native Crypto Layer

### Toteutetut C++ komponentit (src/main/cpp/)

```
ircord-android/app/src/main/cpp/
├── CMakeLists.txt                    # FetchContent: libsignal-protocol-c
├── crypto/
│   ├── crypto_engine.hpp/.cpp        # Täysi Signal Protocol -toteutus
│   │                                    - X3DH avaintenvaihto
│   │                                    - Double Ratchet (1:1 viestit)
│   │                                    - Sender Keys (ryhmäviestit)
│   │                                    - Argon2id + XChaCha20-Poly1305
│   ├── identity.cpp                  # Identity key management
│   ├── signal_store.cpp              # Signal Protocol store interface
│   └── group_session.cpp             # Group/Sender key sessions
└── jni/
    └── jni_bridge.cpp                # JNI-toteutus NativeCrypto:lle
```

### JNI Interface (Kotlin-puoli)

```kotlin
object NativeCrypto {
    init { System.loadLibrary("ircord-native") }

    // Identity & Registration
    external fun identityPub(): ByteArray?           // Ed25519 public key
    external fun currentSpk(): SpkInfo?              // Signed pre-key
    external fun prepareRegistration(numOpks: Int): ByteArray?  // KeyUpload

    // 1:1 Encryption (Double Ratchet)
    external fun encrypt(recipientId: String, plaintext: ByteArray): ByteArray?
    external fun decrypt(senderId: String, recipientId: String, 
                        ciphertext: ByteArray, type: Int): ByteArray?
    external fun onKeyBundle(recipientId: String, bundleData: ByteArray)
    external fun hasSession(recipientId: String): Boolean

    // Group Encryption (Sender Keys)
    external fun initGroupSession(channelId: String, members: Array<String>)
    external fun encryptGroup(channelId: String, plaintext: ByteArray): ByteArray?
    external fun decryptGroup(senderId: String, channelId: String, 
                             ciphertext: ByteArray, skdm: ByteArray?): ByteArray?
    external fun processSenderKeyDistribution(senderId: String, 
                                             channelId: String, skdm: ByteArray)

    // Safety Number & Auth
    external fun safetyNumber(peerId: String): String
    external fun signChallenge(nonce: ByteArray): ByteArray?

    // Storage interface (JNI callback)
    interface Store {
        fun saveIdentity(userId: String, pubKey: ByteArray, 
                        privKeyEncrypted: ByteArray, salt: ByteArray)
        fun loadIdentity(userId: String): ByteArray?
        fun saveSession(address: String, record: ByteArray)
        fun loadSession(address: String): ByteArray?
        fun savePreKey(id: Int, record: ByteArray)
        fun loadPreKey(id: Int): ByteArray?
        fun removePreKey(id: Int)
        fun saveSignedPreKey(id: Int, record: ByteArray)
        fun loadSignedPreKey(id: Int): ByteArray?
        fun savePeerIdentity(userId: String, pubKey: ByteArray)
        fun loadPeerIdentity(userId: String): ByteArray?
        fun saveSenderKey(senderKeyId: String, record: ByteArray)
        fun loadSenderKey(senderKeyId: String): ByteArray?
    }
}
```

### NativeStore — Room-backed JNI Store

`NativeStore` toteuttaa `NativeCrypto.Store` -rajapinnan ja välittää
tallennetukset Room-tietokantaan ja EncryptedSharedPreferences:iin.

```kotlin
class NativeStore(context: Context, database: IrcordDatabase) : NativeCrypto.Store {
    // Identity, sessions, pre-keys → SharedPreferences (salattu)
    // Peer identities → Room DB (PeerIdentityEntity)
}
```

---

## 4. Teema-järjestelmä (NEW)

### ThemeViewModel

```kotlin
@HiltViewModel
class ThemeViewModel @Inject constructor(userPreferences: UserPreferences) {
    val themeMode: StateFlow<String>     // THEME_SYSTEM / THEME_LIGHT / THEME_DARK
    val isDarkTheme: StateFlow<Boolean?> // null = follow system
}
```

### Käyttö MainActivity:ssä

```kotlin
class MainActivity : ComponentActivity() {
    private val themeViewModel: ThemeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        setContent {
            val isDarkTheme by themeViewModel.isDarkTheme.collectAsStateWithLifecycle()
            
            // isDarkTheme = null → seuraa järjestelmää
            // isDarkTheme = true/false → pakota teema
            IrcordTheme(darkTheme = isDarkTheme ?: isSystemInDarkTheme()) {
                // ...
            }
        }
    }
}
```

### Teema-asetukset

Asetukset → Ulkoasu → Teema:
- **Järjestelmän oletus** (oletus) — Seuraa puhelimen teema-asetusta
- **Vaalea** — Pakota vaalea teema
- **Tumma** — Pakota Tokyo Night tumma teema

Toteutus: `ThemeSelectorDialog.kt`, `SettingsScreen.kt`

---

## 5. Hakemistorakenne (Päivitetty)

```
ircord-android/
├── app/src/main/
│   ├── java/fi/ircord/android/
│   │   ├── MainActivity.kt              # Teeman soveltaminen
│   │   ├── IrcordApp.kt
│   │   │
│   │   ├── data/
│   │   │   ├── local/
│   │   │   │   ├── IrcordDatabase.kt    # v2: publicKey kenttä lisätty
│   │   │   │   ├── Migrations.kt        # NEW: MIGRATION_1_2
│   │   │   │   ├── dao/
│   │   │   │   └── entity/
│   │   │   │       └── PeerIdentityEntity.kt  # publicKey (Base64)
│   │   │   ├── remote/
│   │   │   │   └── ProtobufExt.kt       # NEW: Protobuf helpers
│   │   │   └── repository/
│   │   │       └── CryptoRepository.kt  # NEW: High-level crypto API
│   │   │
│   │   ├── native/                      # JNI bridge
│   │   │   ├── NativeCrypto.kt          # Päivitetty täysi toteutus
│   │   │   └── NativeStore.kt           # NEW: Room-backed store
│   │   │
│   │   ├── ui/
│   │   │   ├── theme/
│   │   │   │   ├── ThemeViewModel.kt    # NEW: Teeman hallinta
│   │   │   │   ├── Color.kt             # Light + Dark (Tokyo Night)
│   │   │   │   └── ...
│   │   │   └── screen/
│   │   │       └── settings/
│   │   │           ├── SettingsScreen.kt
│   │   │           └── ThemeSelectorDialog.kt  # NEW: Teeman valinta
│   │   │
│   │   └── di/
│   │       └── DatabaseModule.kt        # Päivitetty: NativeStore provider
│   │
│   ├── cpp/                             # NDK native code
│   │   ├── CMakeLists.txt               # libsignal-protocol-c FetchContent
│   │   ├── crypto/
│   │   │   ├── crypto_engine.hpp/.cpp   # Täysi Signal Protocol
│   │   │   └── ...
│   │   └── jni/
│   │       └── jni_bridge.cpp           # JNI bindings
│   │
│   └── proto/
│       └── ircord.proto                 # Jaettu .proto
```

---

## 6. Dataflow: Viestin lähetys (Päivitetty)

```
User painaa Send
  → ChatViewModel.sendMessage(text)
    → CryptoRepository.encrypt(recipientId, text)
      → NativeCrypto.encrypt(recipientId, bytes) [JNI]
        └── [C++ CryptoEngine::encrypt()]
            ├── Jos sessio olemassa → Double Ratchet encrypt
            └── Jos ei → return null (pyydä avainniput)
      ← Palaa ciphertext tai null
    
    Jos ciphertext == null:
      → Pyydä serveriltä recipientin KeyBundle
      → CryptoRepository.processKeyBundle(bundle)
        → NativeCrypto.onKeyBundle(recipientId, bundle) [JNI]
          └── [C++ X3DH session establishment]
      → Yritä encrypt uudelleen
    
    → Envelope(type=CHAT, payload=ciphertext)
    → IrcordSocket.send(envelope) [TLS write]
    → MessageRepository.insertLocal(msg, status=SENDING)
    → UI: MessageBubble shows ⏳

Server relay → recipient
  → IrcordSocket.receive() [suspend, IO dispatcher]
    → FrameCodec.decode() → Envelope
    → MessageHandler.dispatch(envelope)
      → CryptoRepository.decrypt(senderId, recipientId, ct, type)
        → NativeCrypto.decrypt(senderId, recipientId, ct, type) [JNI]
          └── [C++ CryptoEngine::decrypt()]
              ├── type=2 (SIGNAL_MESSAGE) → Double Ratchet decrypt
              └── type=3 (PREKEY_MESSAGE) → X3DH initial decrypt
      → MessageRepository.insert(decryptedMsg)
      → NotificationManager (jos app ei ole foreground)
      → ChatViewModel.messages StateFlow päivittyy
        → Compose recomposition → uusi viesti näkyy
```

---

## 7. Turvallisuus — Päivitetyt tiedot

| Uhka | Suojaus |
|------|---------|
| Root/jailbreak | Detect & warn, mutta älä estä (kaveriporukka) |
| Screen capture | FLAG_SECURE chat-näkymässä (konfiguroitava Settingsissä) |
| Identity key extraction | Argon2id + XChaCha20-Poly1305, Android Keystore hardware-backed |
| Session state | SharedPreferences + EncryptedSharedPreferences |
| Network intercept | Certificate pinning (OkHttp CertificatePinner) |
| Backup leak | `android:allowBackup="false"` |
| Clipboard snoop | Clear clipboard 30s after Safety Number copy |
| Push content leak | FCM: vain "wakeup" — ei viestin sisältöä |

### Identity Key Encryption

```cpp
// C++: crypto_engine.cpp
std::vector<uint8_t> CryptoEngine::encryptIdentityPriv(
    const std::array<uint8_t, 64>& priv_key,
    const std::string& passphrase,
    std::vector<uint8_t>& salt_out) {
    
    // 1. Argon2id key derivation (libsodium)
    crypto_pwhash(key, passphrase, salt, 
                  OPSLIMIT_INTERACTIVE, MEMLIMIT_INTERACTIVE,
                  crypto_pwhash_ALG_ARGON2ID13)
    
    // 2. XChaCha20-Poly1305 encryption
    crypto_aead_xchacha20poly1305_ietf_encrypt(
        plaintext=priv_key, key=derived_key, nonce=random)
}
```

---

## 8. Build & NDK Setup

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += "-DANDROID_STL=c++_shared"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

### NDK CMakeLists.txt

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(ircord-native)

set(CMAKE_CXX_STANDARD 20)

# Fetch libsignal-protocol-c
FetchContent_Declare(
    libsignal-protocol-c
    GIT_REPOSITORY https://github.com/signalapp/libsignal-protocol-c.git
    GIT_TAG v2.3.3
)
FetchContent_MakeAvailable(libsignal-protocol-c)

add_library(ircord-native SHARED
    jni/jni_bridge.cpp
    crypto/crypto_engine.cpp
    crypto/identity.cpp
    crypto/signal_store.cpp
    crypto/group_session.cpp
)

target_link_libraries(ircord-native
    signal-protocol-c    # Fetched
    crypto               # OpenSSL from NDK
    sodium               # libsodium
    log                  # Android log
)
```

---

## 9. Tietokantamuutokset

### Migration 1 → 2

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Lisätty publicKey kenttä PeerIdentityEntityyn
        // NativeStore tallentaa Base64-koodatun avaimen tähän
        db.execSQL("ALTER TABLE peer_identities ADD COLUMN public_key TEXT DEFAULT NULL")
    }
}
```

---

## 10. TODO / Tulevat parannukset

- [ ] VoiceEngine JNI-toteutus (libdatachannel + Opus + Oboe)
- [ ] FCM push-notifikaatiot offline-viesteille
- [ ] Certificate pinning tuotantoserverille
- [ ] SQLCipher Room-tietokannan salaamiseen
- [ ] Biometric auth identity-avaimelle
