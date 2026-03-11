#include "crypto/crypto_engine.hpp"
#include <sodium.h>
#include <signal_protocol.h>
#include <curve.h>
#include <session_builder.h>
#include <session_cipher.h>
#include <key_helper.h>
#include <session_pre_key.h>
#include <protocol.h>
#include <sender_key.h>
#include <sender_key_state.h>
#include <group_session_builder.h>
#include <group_cipher.h>
#include <android/log.h>

#include <openssl/hmac.h>
#include <openssl/sha.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>

#define LOG_TAG "IRCordCrypto"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace ircord::crypto {

// ============================================================================
// Signal Crypto Provider (OpenSSL-backed)
// ============================================================================

static int signalRandomBytes(uint8_t* data, size_t len, void*) {
    randombytes_buf(data, len);
    return SG_SUCCESS;
}

static int signalHmacSha256Init(void** ctx, const uint8_t* key, size_t key_len, void*) {
    HMAC_CTX* hmac = HMAC_CTX_new();
    if (!hmac) return SG_ERR_NOMEM;
    HMAC_Init_ex(hmac, key, static_cast<int>(key_len), EVP_sha256(), nullptr);
    *ctx = hmac;
    return SG_SUCCESS;
}

static int signalHmacSha256Update(void* ctx, const uint8_t* data, size_t len, void*) {
    HMAC_Update(static_cast<HMAC_CTX*>(ctx), data, len);
    return SG_SUCCESS;
}

static int signalHmacSha256Final(void* ctx, signal_buffer** out, void*) {
    auto* hmac = static_cast<HMAC_CTX*>(ctx);
    uint8_t buf[32]; 
    unsigned int len = 32;
    HMAC_Final(hmac, buf, &len);
    *out = signal_buffer_create(buf, len);
    return *out ? SG_SUCCESS : SG_ERR_NOMEM;
}

static void signalHmacSha256Cleanup(void* ctx, void*) {
    HMAC_CTX_free(static_cast<HMAC_CTX*>(ctx));
}

static int signalSha512DigestInit(void** ctx, void*) {
    SHA512_CTX* sha = new SHA512_CTX;
    SHA512_Init(sha);
    *ctx = sha;
    return SG_SUCCESS;
}

static int signalSha512DigestUpdate(void* ctx, const uint8_t* data, size_t len, void*) {
    SHA512_Update(static_cast<SHA512_CTX*>(ctx), data, len);
    return SG_SUCCESS;
}

static int signalSha512DigestFinal(void* ctx, signal_buffer** out, void*) {
    auto* sha = static_cast<SHA512_CTX*>(ctx);
    uint8_t buf[64];
    SHA512_Final(buf, sha);
    *out = signal_buffer_create(buf, 64);
    return *out ? SG_SUCCESS : SG_ERR_NOMEM;
}

static void signalSha512DigestCleanup(void* ctx, void*) {
    delete static_cast<SHA512_CTX*>(ctx);
}

static int signalEncrypt(signal_buffer** out, int cipher,
                         const uint8_t* key, size_t key_len,
                         const uint8_t* iv, size_t iv_len,
                         const uint8_t* data, size_t data_len, void*) {
    const EVP_CIPHER* evp = nullptr;
    switch (cipher) {
    case SG_CIPHER_AES_CBC_PKCS5:
        evp = (key_len == 16) ? EVP_aes_128_cbc() : EVP_aes_256_cbc(); 
        break;
    case SG_CIPHER_AES_CTR_NOPADDING:
        evp = (key_len == 16) ? EVP_aes_128_ctr() : EVP_aes_256_ctr(); 
        break;
    default: return SG_ERR_UNKNOWN;
    }

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    EVP_EncryptInit_ex(ctx, evp, nullptr, key, iv);
    if (cipher == SG_CIPHER_AES_CTR_NOPADDING) EVP_CIPHER_CTX_set_padding(ctx, 0);

    std::vector<uint8_t> buf(data_len + EVP_CIPHER_block_size(evp));
    int len1 = 0, len2 = 0;
    EVP_EncryptUpdate(ctx, buf.data(), &len1, data, static_cast<int>(data_len));
    EVP_EncryptFinal_ex(ctx, buf.data() + len1, &len2);
    EVP_CIPHER_CTX_free(ctx);

    *out = signal_buffer_create(buf.data(), len1 + len2);
    return *out ? SG_SUCCESS : SG_ERR_NOMEM;
}

static int signalDecrypt(signal_buffer** out, int cipher,
                         const uint8_t* key, size_t key_len,
                         const uint8_t* iv, size_t iv_len,
                         const uint8_t* data, size_t data_len, void*) {
    const EVP_CIPHER* evp = nullptr;
    switch (cipher) {
    case SG_CIPHER_AES_CBC_PKCS5:
        evp = (key_len == 16) ? EVP_aes_128_cbc() : EVP_aes_256_cbc(); 
        break;
    case SG_CIPHER_AES_CTR_NOPADDING:
        evp = (key_len == 16) ? EVP_aes_128_ctr() : EVP_aes_256_ctr(); 
        break;
    default: return SG_ERR_UNKNOWN;
    }

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    EVP_DecryptInit_ex(ctx, evp, nullptr, key, iv);
    if (cipher == SG_CIPHER_AES_CTR_NOPADDING) EVP_CIPHER_CTX_set_padding(ctx, 0);

    std::vector<uint8_t> buf(data_len + EVP_CIPHER_block_size(evp));
    int len1 = 0, len2 = 0;
    EVP_DecryptUpdate(ctx, buf.data(), &len1, data, static_cast<int>(data_len));
    EVP_DecryptFinal_ex(ctx, buf.data() + len1, &len2);
    EVP_CIPHER_CTX_free(ctx);

    *out = signal_buffer_create(buf.data(), len1 + len2);
    return *out ? SG_SUCCESS : SG_ERR_NOMEM;
}

// ============================================================================
// Store Callbacks
// ============================================================================

static int sessionLoad(signal_buffer** record, signal_buffer** /*user_record*/,
                      const signal_protocol_address* addr, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    std::string address(addr->name, addr->name_len);
    address += ":" + std::to_string(addr->device_id);
    
    auto data = engine->store_->loadSession(address);
    if (data.empty()) return SG_ERR_NO_SESSION;
    
    *record = signal_buffer_create(data.data(), data.size());
    return *record ? SG_SUCCESS : SG_ERR_NOMEM;
}

static int sessionGetSubDeviceSessions(signal_int_list** sessions,
                                       const char* name, size_t name_len, void* ud) {
    signal_int_list_alloc(sessions);
    return SG_SUCCESS;
}

static int sessionStore(const signal_protocol_address* addr,
                       uint8_t* record, size_t record_len,
                       uint8_t* /*user_record*/, size_t /*user_record_len*/, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    std::string address(addr->name, addr->name_len);
    address += ":" + std::to_string(addr->device_id);
    
    engine->store_->saveSession(address, 
        std::vector<uint8_t>(record, record + record_len));
    return SG_SUCCESS;
}

static int sessionContains(const signal_protocol_address* addr, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    std::string address(addr->name, addr->name_len);
    address += ":" + std::to_string(addr->device_id);
    
    auto data = engine->store_->loadSession(address);
    return data.empty() ? 0 : 1;
}

static int sessionDelete(const signal_protocol_address* addr, void* ud) {
    return SG_SUCCESS;
}

static int sessionDeleteAll(const char* name, size_t name_len, void* ud) {
    return SG_SUCCESS;
}

static void sessionDestroy(void* ud) {}

static int preKeyLoad(signal_buffer** record, uint32_t pre_key_id, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    auto data = engine->store_->loadPreKey(pre_key_id);
    if (data.empty()) return SG_ERR_INVALID_KEY_ID;
    
    *record = signal_buffer_create(data.data(), data.size());
    return *record ? SG_SUCCESS : SG_ERR_NOMEM;
}

static int preKeyStore(uint32_t pre_key_id, uint8_t* record, size_t len, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    engine->store_->savePreKey(pre_key_id, 
        std::vector<uint8_t>(record, record + len));
    return SG_SUCCESS;
}

static int preKeyContains(uint32_t pre_key_id, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    auto data = engine->store_->loadPreKey(pre_key_id);
    return data.empty() ? 0 : 1;
}

static int preKeyRemove(uint32_t pre_key_id, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    engine->store_->removePreKey(pre_key_id);
    return SG_SUCCESS;
}

static void preKeyDestroy(void* ud) {}

static int signedPreKeyLoad(signal_buffer** record, uint32_t spk_id, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    auto data = engine->store_->loadSignedPreKey(spk_id);
    if (data.empty()) return SG_ERR_INVALID_KEY_ID;
    
    *record = signal_buffer_create(data.data(), data.size());
    return *record ? SG_SUCCESS : SG_ERR_NOMEM;
}

static int signedPreKeyStore(uint32_t spk_id, uint8_t* record, size_t len, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    engine->store_->saveSignedPreKey(spk_id,
        std::vector<uint8_t>(record, record + len));
    return SG_SUCCESS;
}

static int signedPreKeyContains(uint32_t spk_id, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    auto data = engine->store_->loadSignedPreKey(spk_id);
    return data.empty() ? 0 : 1;
}

static int signedPreKeyRemove(uint32_t spk_id, void* ud) {
    return SG_SUCCESS;
}

static void signedPreKeyDestroy(void* ud) {}

static int identityGetKeyPair(signal_buffer** pub_data, signal_buffer** priv_data, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    
    // Return X25519 identity key
    std::vector<uint8_t> pub(32);
    std::vector<uint8_t> priv(32);
    
    // For now, derive X25519 from Ed25519 or generate separately
    // In a full implementation, we'd store the X25519 identity key separately
    *pub_data = signal_buffer_create(pub.data(), pub.size());
    *priv_data = signal_buffer_create(priv.data(), priv.size());
    
    return (*pub_data && *priv_data) ? SG_SUCCESS : SG_ERR_NOMEM;
}

static int identityGetLocalRegistration(void* /*ud*/, uint32_t* registration_id) {
    *registration_id = 1;
    return SG_SUCCESS;
}

static int identitySaveIdentity(const signal_protocol_address* addr,
                               uint8_t* key_data, size_t key_len, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    std::string user_id(addr->name, addr->name_len);
    engine->store_->savePeerIdentity(user_id,
        std::vector<uint8_t>(key_data, key_data + key_len));
    return SG_SUCCESS;
}

static int identityIsTrusted(const signal_protocol_address* addr,
                            uint8_t* /*key_data*/, size_t /*key_len*/, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    std::string user_id(addr->name, addr->name_len);
    auto key = engine->store_->loadPeerIdentity(user_id);
    return key.empty() ? 0 : 1;  // Trust-on-first-use
}

static void identityDestroy(void* ud) {}

static int senderKeyStore(const signal_protocol_sender_key_name* name,
                         uint8_t* record, size_t len,
                         uint8_t* /*user_record*/, size_t /*user_record_len*/, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    std::string sender_key_id(name->sender.name, name->sender.name_len);
    sender_key_id += ":" + std::to_string(name->sender.device_id);
    sender_key_id += ":" + std::string(name->group_id, name->group_id_len);
    
    engine->store_->saveSenderKey(sender_key_id, 
        std::vector<uint8_t>(record, record + len));
    return SG_SUCCESS;
}

static int senderKeyLoad(signal_buffer** record, signal_buffer** /*user_record*/,
                        const signal_protocol_sender_key_name* name, void* ud) {
    auto* engine = static_cast<CryptoEngine*>(ud);
    std::string sender_key_id(name->sender.name, name->sender.name_len);
    sender_key_id += ":" + std::to_string(name->sender.device_id);
    sender_key_id += ":" + std::string(name->group_id, name->group_id_len);
    
    auto data = engine->store_->loadSenderKey(sender_key_id);
    if (data.empty()) return SG_ERR_INVALID_KEY_ID;
    
    *record = signal_buffer_create(data.data(), data.size());
    return *record ? SG_SUCCESS : SG_ERR_NOMEM;
}

static void senderKeyDestroy(void* ud) {}

// ============================================================================
// CryptoEngine Implementation
// ============================================================================

CryptoEngine::CryptoEngine() = default;

CryptoEngine::~CryptoEngine() {
    if (store_ctx_) {
        signal_protocol_store_context_destroy(store_ctx_);
        store_ctx_ = nullptr;
    }
    if (signal_ctx_) {
        signal_context_destroy(signal_ctx_);
        signal_ctx_ = nullptr;
    }
}

void CryptoEngine::setupSignalCrypto(signal_context* ctx) {
    signal_crypto_provider provider{};
    provider.random_func = signalRandomBytes;
    provider.hmac_sha256_init_func = signalHmacSha256Init;
    provider.hmac_sha256_update_func = signalHmacSha256Update;
    provider.hmac_sha256_final_func = signalHmacSha256Final;
    provider.hmac_sha256_cleanup_func = signalHmacSha256Cleanup;
    provider.sha512_digest_init_func = signalSha512DigestInit;
    provider.sha512_digest_update_func = signalSha512DigestUpdate;
    provider.sha512_digest_final_func = signalSha512DigestFinal;
    provider.sha512_digest_cleanup_func = signalSha512DigestCleanup;
    provider.encrypt_func = signalEncrypt;
    provider.decrypt_func = signalDecrypt;
    signal_context_set_crypto_provider(ctx, &provider);
}

bool CryptoEngine::init(IStore* store, const std::string& user_id, 
                        const std::string& passphrase) {
    store_ = store;
    user_id_ = user_id;
    
    // Initialize libsodium
    if (sodium_init() < 0) {
        LOGE("Failed to initialize libsodium");
        return false;
    }
    
    // Create signal context
    if (signal_context_create(&signal_ctx_, this) != SG_SUCCESS) {
        LOGE("Failed to create signal context");
        return false;
    }
    setupSignalCrypto(signal_ctx_);
    
    // Create store context
    if (signal_protocol_store_context_create(&store_ctx_, signal_ctx_) != SG_SUCCESS) {
        LOGE("Failed to create store context");
        return false;
    }
    
    // Setup store callbacks
    setupStores(store_ctx_, this);
    
    // Load or generate identity
    if (!loadOrGenerateIdentity(passphrase)) {
        LOGE("Failed to load/generate identity");
        return false;
    }
    
    // Generate signed pre-key (X25519)
    randombytes_buf(x25519_identity_.priv.data(), 32);
    crypto_scalarmult_curve25519_base(x25519_identity_.pub.data(), x25519_identity_.priv.data());
    
    X25519KeyPair spk_pair;
    randombytes_buf(spk_pair.priv.data(), 32);
    crypto_scalarmult_curve25519_base(spk_pair.pub.data(), spk_pair.priv.data());
    
    // Sign SPK with Ed25519
    std::vector<uint8_t> sig(crypto_sign_BYTES);
    unsigned long long sig_len;
    crypto_sign(sig.data(), &sig_len, spk_pair.pub.data(), 32, 
                ed25519_key_.priv.data());
    sig.resize(sig_len);
    
    spk_.id = 1;
    spk_.key_pair = spk_pair;
    spk_.signature = sig;
    next_opk_id_ = 1;
    
    // Store SPK
    signal_buffer* spk_record = signal_buffer_create(
        reinterpret_cast<const uint8_t*>(&spk_pair.priv), 32);
    signal_protocol_signed_pre_key_store_key(store_ctx_, spk_.id, spk_record);
    signal_buffer_free(spk_record);
    
    loaded_ = true;
    LOGD("CryptoEngine initialized for '%s'", user_id.c_str());
    return true;
}

void CryptoEngine::setupStores(signal_protocol_store_context* store_ctx, CryptoEngine* engine) {
    signal_protocol_session_store session_store = {
        .load_session_func = sessionLoad,
        .get_sub_device_sessions_func = sessionGetSubDeviceSessions,
        .store_session_func = sessionStore,
        .contains_session_func = sessionContains,
        .delete_session_func = sessionDelete,
        .delete_all_sessions_func = sessionDeleteAll,
        .destroy_func = sessionDestroy,
        .user_data = engine
    };
    
    signal_protocol_pre_key_store pre_key_store = {
        .load_pre_key_func = preKeyLoad,
        .store_pre_key_func = preKeyStore,
        .contains_pre_key_func = preKeyContains,
        .remove_pre_key_func = preKeyRemove,
        .destroy_func = preKeyDestroy,
        .user_data = engine
    };
    
    signal_protocol_signed_pre_key_store spk_store = {
        .load_signed_pre_key_func = signedPreKeyLoad,
        .store_signed_pre_key_func = signedPreKeyStore,
        .contains_signed_pre_key_func = signedPreKeyContains,
        .remove_signed_pre_key_func = signedPreKeyRemove,
        .destroy_func = signedPreKeyDestroy,
        .user_data = engine
    };
    
    signal_protocol_identity_key_store id_store = {
        .get_identity_key_pair_func = identityGetKeyPair,
        .get_local_registration_id_func = identityGetLocalRegistration,
        .save_identity_func = identitySaveIdentity,
        .is_trusted_identity_func = identityIsTrusted,
        .destroy_func = identityDestroy,
        .user_data = engine
    };
    
    signal_protocol_sender_key_store sk_store = {
        .store_sender_key_func = senderKeyStore,
        .load_sender_key_func = senderKeyLoad,
        .destroy_func = senderKeyDestroy,
        .user_data = engine
    };
    
    signal_protocol_store_context_set_session_store(store_ctx, &session_store);
    signal_protocol_store_context_set_pre_key_store(store_ctx, &pre_key_store);
    signal_protocol_store_context_set_signed_pre_key_store(store_ctx, &spk_store);
    signal_protocol_store_context_set_identity_key_store(store_ctx, &id_store);
    signal_protocol_store_context_set_sender_key_store(store_ctx, &sk_store);
}

// ============================================================================
// Identity Encryption (Argon2id + XChaCha20-Poly1305)
// ============================================================================

std::vector<uint8_t> CryptoEngine::encryptIdentityPriv(
    const std::array<uint8_t, 64>& priv_key,
    const std::string& passphrase,
    std::vector<uint8_t>& salt_out) {
    
    // Generate random salt (16 bytes)
    salt_out.resize(16);
    randombytes_buf(salt_out.data(), salt_out.size());
    
    // Derive key using Argon2id
    std::vector<uint8_t> key(32);
    if (crypto_pwhash(key.data(), key.size(),
                      passphrase.c_str(), passphrase.length(),
                      salt_out.data(),
                      crypto_pwhash_OPSLIMIT_INTERACTIVE,
                      crypto_pwhash_MEMLIMIT_INTERACTIVE,
                      crypto_pwhash_ALG_ARGON2ID13) != 0) {
        LOGE("Argon2id key derivation failed");
        return {};
    }
    
    // Generate nonce for XChaCha20-Poly1305
    std::vector<uint8_t> nonce(crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
    randombytes_buf(nonce.data(), nonce.size());
    
    // Encrypt
    std::vector<uint8_t> ciphertext(priv_key.size() + crypto_aead_xchacha20poly1305_ietf_ABYTES);
    unsigned long long ciphertext_len;
    
    crypto_aead_xchacha20poly1305_ietf_encrypt(
        ciphertext.data(), &ciphertext_len,
        priv_key.data(), priv_key.size(),
        nullptr, 0,  // no additional data
        nullptr,
        nonce.data(), key.data());
    
    ciphertext.resize(ciphertext_len);
    
    // Format: [salt (16 bytes)] [nonce (24 bytes)] [ciphertext]
    std::vector<uint8_t> result;
    result.insert(result.end(), salt_out.begin(), salt_out.end());
    result.insert(result.end(), nonce.begin(), nonce.end());
    result.insert(result.end(), ciphertext.begin(), ciphertext.end());
    
    return result;
}

bool CryptoEngine::decryptIdentityPriv(
    const std::vector<uint8_t>& ciphertext,
    const std::vector<uint8_t>& salt,
    const std::string& passphrase,
    std::array<uint8_t, 64>& priv_out) {
    
    if (ciphertext.size() < 16 + crypto_aead_xchacha20poly1305_ietf_NPUBBYTES + crypto_aead_xchacha20poly1305_ietf_ABYTES) {
        LOGE("Invalid ciphertext size");
        return false;
    }
    
    // Derive key using Argon2id
    std::vector<uint8_t> key(32);
    if (crypto_pwhash(key.data(), key.size(),
                      passphrase.c_str(), passphrase.length(),
                      salt.data(),
                      crypto_pwhash_OPSLIMIT_INTERACTIVE,
                      crypto_pwhash_MEMLIMIT_INTERACTIVE,
                      crypto_pwhash_ALG_ARGON2ID13) != 0) {
        LOGE("Argon2id key derivation failed");
        return false;
    }
    
    // Extract nonce and ciphertext
    size_t offset = salt.size();
    std::vector<uint8_t> nonce(ciphertext.begin() + offset, 
                               ciphertext.begin() + offset + crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
    offset += crypto_aead_xchacha20poly1305_ietf_NPUBBYTES;
    
    const uint8_t* ct_data = ciphertext.data() + offset;
    size_t ct_len = ciphertext.size() - offset;
    
    // Decrypt
    unsigned long long plaintext_len;
    std::vector<uint8_t> plaintext(64);
    
    if (crypto_aead_xchacha20poly1305_ietf_decrypt(
            plaintext.data(), &plaintext_len,
            nullptr,
            ct_data, ct_len,
            nullptr, 0,
            nonce.data(), key.data()) != 0) {
        LOGE("Decryption failed - wrong passphrase?");
        return false;
    }
    
    std::copy(plaintext.begin(), plaintext.begin() + 64, priv_out.begin());
    return true;
}

bool CryptoEngine::loadOrGenerateIdentity(const std::string& passphrase) {
    std::vector<uint8_t> pub_key, priv_key_enc, salt;
    if (store_->loadIdentity(user_id_, pub_key, priv_key_enc, salt)) {
        // Decrypt and load existing identity
        if (pub_key.size() == 32) {
            std::copy(pub_key.begin(), pub_key.end(), ed25519_key_.pub.begin());
            
            if (decryptIdentityPriv(priv_key_enc, salt, passphrase, ed25519_key_.priv)) {
                LOGD("Loaded existing identity");
                return true;
            }
            LOGE("Failed to decrypt identity - wrong passphrase?");
            return false;
        }
    }
    // Generate new identity
    return generateIdentity(passphrase);
}

bool CryptoEngine::generateIdentity(const std::string& passphrase) {
    crypto_sign_keypair(ed25519_key_.pub.data(), ed25519_key_.priv.data());
    
    // Encrypt private key
    std::vector<uint8_t> salt;
    auto encrypted = encryptIdentityPriv(ed25519_key_.priv, passphrase, salt);
    if (encrypted.empty()) {
        LOGE("Failed to encrypt identity");
        return false;
    }
    
    std::vector<uint8_t> pub(ed25519_key_.pub.begin(), ed25519_key_.pub.end());
    store_->saveIdentity(user_id_, pub, encrypted, salt);
    
    LOGD("Generated new identity for %s", user_id_.c_str());
    return true;
}

// ============================================================================
// Key Upload / Registration
// ============================================================================

std::vector<uint8_t> CryptoEngine::prepareKeyUpload(int num_opks) {
    // Build KeyUpload protobuf manually (simplified format)
    // Format: [4 bytes spk_id LE] [32 bytes spk_pub] [4 bytes sig_len LE] [sig bytes]
    //         [4 bytes opk_count LE] for each: [4 bytes id LE] [32 bytes pub]
    
    std::vector<uint8_t> data;
    auto append_u32 = [&data](uint32_t val) {
        data.push_back(val & 0xFF);
        data.push_back((val >> 8) & 0xFF);
        data.push_back((val >> 16) & 0xFF);
        data.push_back((val >> 24) & 0xFF);
    };
    
    // SPK ID
    append_u32(spk_.id);
    
    // SPK public
    data.insert(data.end(), spk_.key_pair.pub.begin(), spk_.key_pair.pub.end());
    
    // SPK signature
    append_u32(spk_.signature.size());
    data.insert(data.end(), spk_.signature.begin(), spk_.signature.end());
    
    // One-time prekeys
    append_u32(num_opks);
    
    for (int i = 0; i < num_opks; i++) {
        X25519KeyPair opk;
        randombytes_buf(opk.priv.data(), 32);
        crypto_scalarmult_curve25519_base(opk.pub.data(), opk.priv.data());
        
        uint32_t opk_id = next_opk_id_++;
        append_u32(opk_id);
        data.insert(data.end(), opk.pub.begin(), opk.pub.end());
        
        // Store private key for later use
        signal_buffer* key_record = signal_buffer_create(opk.priv.data(), 32);
        signal_protocol_pre_key_store_key(store_ctx_, opk_id, key_record);
        signal_buffer_free(key_record);
    }
    
    return data;
}

// ============================================================================
// Authentication
// ============================================================================

std::vector<uint8_t> CryptoEngine::signChallenge(const std::vector<uint8_t>& nonce) {
    // Sign nonce || user_id
    std::vector<uint8_t> message = nonce;
    message.insert(message.end(), user_id_.begin(), user_id_.end());
    
    std::vector<uint8_t> sig(crypto_sign_BYTES);
    unsigned long long sig_len;
    crypto_sign(sig.data(), &sig_len, message.data(), message.size(),
                ed25519_key_.priv.data());
    sig.resize(sig_len);
    return sig;
}

std::vector<uint8_t> CryptoEngine::identityPub() const {
    return std::vector<uint8_t>(ed25519_key_.pub.begin(), ed25519_key_.pub.end());
}

CryptoEngine::SpkInfo CryptoEngine::currentSpk() const {
    SpkInfo info;
    info.pub = std::vector<uint8_t>(spk_.key_pair.pub.begin(), spk_.key_pair.pub.end());
    info.sig = spk_.signature;
    info.id = spk_.id;
    return info;
}

// ============================================================================
// 1:1 Encryption (Signal Protocol Double Ratchet)
// ============================================================================

bool CryptoEngine::hasSession(const std::string& recipient_id) {
    signal_protocol_address addr{};
    addr.name = recipient_id.c_str();
    addr.name_len = recipient_id.size();
    addr.device_id = 1;
    
    return signal_protocol_session_contains_session(store_ctx_, &addr) == 1;
}

std::string CryptoEngine::getPendingPlaintext(const std::string& recipient_id) {
    auto it = pending_plaintexts_.find(recipient_id);
    if (it != pending_plaintexts_.end()) {
        return it->second;
    }
    return {};
}

void CryptoEngine::clearPendingPlaintext(const std::string& recipient_id) {
    pending_plaintexts_.erase(recipient_id);
}

std::vector<uint8_t> CryptoEngine::encrypt(const std::string& recipient_id,
                                            const std::string& plaintext) {
    signal_protocol_address addr{};
    addr.name = recipient_id.c_str();
    addr.name_len = recipient_id.size();
    addr.device_id = 1;
    
    // Check if we have a session
    bool has_session = signal_protocol_session_contains_session(store_ctx_, &addr) == 1;
    
    if (!has_session) {
        // Queue for later and request key bundle
        pending_plaintexts_[recipient_id] = plaintext;
        LOGD("No session for %s, queuing message", recipient_id.c_str());
        return {};  // Caller should request key bundle
    }
    
    // Create cipher
    session_cipher* cipher = nullptr;
    int rc = session_cipher_create(&cipher, store_ctx_, &addr, signal_ctx_);
    if (rc != SG_SUCCESS) {
        LOGE("session_cipher_create failed: %d", rc);
        return {};
    }
    
    // Encrypt
    ciphertext_message* encrypted = nullptr;
    rc = session_cipher_encrypt(cipher,
        reinterpret_cast<const uint8_t*>(plaintext.data()), plaintext.size(),
        &encrypted);
    session_cipher_free(cipher);
    
    if (rc != SG_SUCCESS) {
        LOGE("session_cipher_encrypt failed: %d", rc);
        return {};
    }
    
    signal_buffer* buf = ciphertext_message_get_serialized(encrypted);
    std::vector<uint8_t> result(signal_buffer_data(buf), 
                                 signal_buffer_data(buf) + signal_buffer_len(buf));
    
    uint32_t ct_type = ciphertext_message_get_type(encrypted);
    SIGNAL_UNREF(encrypted);
    
    LOGD("Encrypted message for %s, type=%d, len=%zu", 
         recipient_id.c_str(), ct_type, result.size());
    return result;
}

std::vector<uint8_t> CryptoEngine::encryptPending(const std::string& recipient_id) {
    auto it = pending_plaintexts_.find(recipient_id);
    if (it == pending_plaintexts_.end()) {
        return {};
    }
    
    auto result = encrypt(recipient_id, it->second);
    if (!result.empty()) {
        pending_plaintexts_.erase(it);
    }
    return result;
}

DecryptResult CryptoEngine::decrypt(const std::string& sender_id,
                                     const std::string& recipient_id,
                                     const std::vector<uint8_t>& ciphertext,
                                     int ciphertext_type,
                                     const std::vector<uint8_t>& skdm) {
    DecryptResult result;
    result.sender_id = sender_id;
    
    signal_protocol_address addr{};
    addr.name = sender_id.c_str();
    addr.name_len = sender_id.size();
    addr.device_id = 1;
    
    session_cipher* cipher = nullptr;
    int rc = session_cipher_create(&cipher, store_ctx_, &addr, signal_ctx_);
    if (rc != SG_SUCCESS) {
        LOGE("session_cipher_create failed: %d", rc);
        return result;
    }
    
    signal_buffer* plaintext_buf = nullptr;
    
    if (ciphertext_type == 3) {  // PRE_KEY_SIGNAL_MESSAGE
        pre_key_signal_message* msg = nullptr;
        rc = pre_key_signal_message_deserialize(&msg, ciphertext.data(), ciphertext.size(), signal_ctx_);
        if (rc == SG_SUCCESS) {
            rc = session_cipher_decrypt_pre_key_signal_message(cipher, msg, nullptr, &plaintext_buf);
            SIGNAL_UNREF(msg);
        }
    } else {  // SIGNAL_MESSAGE (type 2)
        signal_message* msg = nullptr;
        rc = signal_message_deserialize(&msg, ciphertext.data(), ciphertext.size(), signal_ctx_);
        if (rc == SG_SUCCESS) {
            rc = session_cipher_decrypt_signal_message(cipher, msg, nullptr, &plaintext_buf);
            SIGNAL_UNREF(msg);
        }
    }
    
    session_cipher_free(cipher);
    
    if (rc != SG_SUCCESS || !plaintext_buf) {
        LOGE("Decryption failed: %d", rc);
        return result;
    }
    
    result.plaintext.assign(
        reinterpret_cast<const char*>(signal_buffer_data(plaintext_buf)),
        signal_buffer_len(plaintext_buf));
    signal_buffer_free(plaintext_buf);
    result.success = true;
    
    LOGD("Decrypted message from %s, len=%zu", sender_id.c_str(), result.plaintext.size());
    return result;
}

// ============================================================================
// X3DH Key Bundle Processing
// ============================================================================

void CryptoEngine::onKeyBundle(const std::vector<uint8_t>& bundle_data,
                                const std::string& recipient_id) {
    // Parse bundle_data (KeyUpload format)
    if (bundle_data.size() < 4 + 32 + 4) {
        LOGE("Invalid key bundle");
        return;
    }
    
    size_t offset = 0;
    auto read_u32 = [&bundle_data, &offset]() -> uint32_t {
        uint32_t val = bundle_data[offset] | 
                      (bundle_data[offset + 1] << 8) |
                      (bundle_data[offset + 2] << 16) |
                      (bundle_data[offset + 3] << 24);
        offset += 4;
        return val;
    };
    
    uint32_t spk_id = read_u32();
    
    // SPK public
    ec_public_key* signed_pre_key = nullptr;
    curve_decode_point(&signed_pre_key, bundle_data.data() + offset, 32, signal_ctx_);
    offset += 32;
    
    // SPK signature
    uint32_t sig_len = read_u32();
    signal_buffer* spk_sig = signal_buffer_create(bundle_data.data() + offset, sig_len);
    offset += sig_len;
    
    // Identity key (we need to get this separately or it's in the bundle)
    // For now, assume we loaded it from peer identities
    auto peer_id_key = store_->loadPeerIdentity(recipient_id);
    ec_public_key* identity_key = nullptr;
    if (!peer_id_key.empty()) {
        curve_decode_point(&identity_key, peer_id_key.data(), peer_id_key.size(), signal_ctx_);
    }
    
    // One-time prekey (optional)
    ec_public_key* one_time_key = nullptr;
    if (offset + 4 <= bundle_data.size()) {
        uint32_t opk_count = read_u32();
        if (opk_count > 0 && offset + 4 + 32 <= bundle_data.size()) {
            uint32_t opk_id = read_u32();
            curve_decode_point(&one_time_key, bundle_data.data() + offset, 32, signal_ctx_);
        }
    }
    
    // Create pre-key bundle
    session_pre_key_bundle* pkb = nullptr;
    int rc = session_pre_key_bundle_create(&pkb,
        1,  // registration_id
        1,  // device_id
        one_time_key ? 1 : 0, one_time_key,  // OPK ID and key
        spk_id, signed_pre_key,
        signal_buffer_data(spk_sig), signal_buffer_len(spk_sig),
        identity_key);
    
    signal_buffer_free(spk_sig);
    
    if (rc != SG_SUCCESS) {
        LOGE("session_pre_key_bundle_create failed: %d", rc);
        SIGNAL_UNREF(identity_key);
        SIGNAL_UNREF(signed_pre_key);
        if (one_time_key) SIGNAL_UNREF(one_time_key);
        return;
    }
    
    // Build session
    signal_protocol_address addr{};
    addr.name = recipient_id.c_str();
    addr.name_len = recipient_id.size();
    addr.device_id = 1;
    
    session_builder* builder = nullptr;
    rc = session_builder_create(&builder, store_ctx_, &addr, signal_ctx_);
    if (rc == SG_SUCCESS) {
        rc = session_builder_process_pre_key_bundle(builder, pkb);
        session_builder_free(builder);
    }
    
    SIGNAL_UNREF(pkb);
    SIGNAL_UNREF(identity_key);
    SIGNAL_UNREF(signed_pre_key);
    if (one_time_key) SIGNAL_UNREF(one_time_key);
    
    if (rc != SG_SUCCESS) {
        LOGE("session_builder_process_pre_key_bundle failed: %d", rc);
        return;
    }
    
    LOGD("Established X3DH session with '%s'", recipient_id.c_str());
    
    // Try to send pending message
    encryptPending(recipient_id);
}

// ============================================================================
// Group Encryption (Sender Keys)
// ============================================================================

void CryptoEngine::initGroupSession(const std::string& channel_id,
                                     const std::vector<std::string>& members) {
    // Initialize our sender key for this channel
    auto& session = group_sessions_[channel_id];
    
    // Generate chain key and signing key
    session.chain_key.resize(32);
    randombytes_buf(session.chain_key.data(), session.chain_key.size());
    
    session.signature_key_priv.resize(32);
    randombytes_buf(session.signature_key_priv.data(), session.signature_key_priv.size());
    crypto_scalarmult_curve25519_base(session.signature_key_pub.data(), 
                                       session.signature_key_priv.data());
    
    session.iteration = 0;
    session.initialized = true;
    
    LOGD("Initialized group session for %s", channel_id.c_str());
}

std::vector<uint8_t> CryptoEngine::encryptGroup(const std::string& channel_id,
                                                 const std::string& plaintext) {
    auto it = group_sessions_.find(channel_id);
    if (it == group_sessions_.end() || !it->second.initialized) {
        LOGE("No group session for %s", channel_id.c_str());
        return {};
    }
    
    auto& session = it->second;
    
    // Use libsignal's group cipher
    signal_protocol_sender_key_name key_name{};
    key_name.group_id = channel_id.c_str();
    key_name.group_id_len = channel_id.size();
    key_name.sender.name = user_id_.c_str();
    key_name.sender.name_len = user_id_.size();
    key_name.sender.device_id = 1;
    
    group_cipher* cipher = nullptr;
    int rc = group_cipher_create(&cipher, store_ctx_, &key_name, signal_ctx_);
    if (rc != SG_SUCCESS) {
        LOGE("group_cipher_create failed: %d", rc);
        return {};
    }
    
    // We need to distribute the sender key first if this is the first message
    // For now, simplified implementation
    
    ciphertext_message* encrypted = nullptr;
    rc = group_cipher_encrypt(cipher,
        reinterpret_cast<const uint8_t*>(plaintext.data()), plaintext.size(),
        &encrypted);
    group_cipher_free(cipher);
    
    if (rc != SG_SUCCESS) {
        LOGE("group_cipher_encrypt failed: %d", rc);
        return {};
    }
    
    signal_buffer* buf = ciphertext_message_get_serialized(encrypted);
    std::vector<uint8_t> result(signal_buffer_data(buf),
                                 signal_buffer_data(buf) + signal_buffer_len(buf));
    SIGNAL_UNREF(encrypted);
    
    LOGD("Encrypted group message for %s, len=%zu", channel_id.c_str(), result.size());
    return result;
}

std::vector<uint8_t> CryptoEngine::decryptGroup(const std::string& sender_id,
                                                 const std::string& channel_id,
                                                 const std::vector<uint8_t>& ciphertext,
                                                 const std::vector<uint8_t>& skdm) {
    // Process SKDM if present
    if (!skdm.empty()) {
        processSenderKeyDistribution(sender_id, channel_id, skdm);
    }
    
    signal_protocol_sender_key_name key_name{};
    key_name.group_id = channel_id.c_str();
    key_name.group_id_len = channel_id.size();
    key_name.sender.name = sender_id.c_str();
    key_name.sender.name_len = sender_id.size();
    key_name.sender.device_id = 1;
    
    group_cipher* cipher = nullptr;
    int rc = group_cipher_create(&cipher, store_ctx_, &key_name, signal_ctx_);
    if (rc != SG_SUCCESS) {
        LOGE("group_cipher_create failed: %d", rc);
        return {};
    }
    
    sender_key_message* skm = nullptr;
    rc = sender_key_message_deserialize(&skm, ciphertext.data(), ciphertext.size(), signal_ctx_);
    if (rc != SG_SUCCESS) {
        LOGE("sender_key_message_deserialize failed: %d", rc);
        group_cipher_free(cipher);
        return {};
    }
    
    signal_buffer* plaintext = nullptr;
    rc = group_cipher_decrypt(cipher, skm, &plaintext);
    
    sender_key_message_destroy(skm);
    group_cipher_free(cipher);
    
    if (rc != SG_SUCCESS || !plaintext) {
        LOGE("group_cipher_decrypt failed: %d", rc);
        return {};
    }
    
    std::vector<uint8_t> result(signal_buffer_data(plaintext),
                                 signal_buffer_data(plaintext) + signal_buffer_len(plaintext));
    signal_buffer_free(plaintext);
    
    LOGD("Decrypted group message from %s in %s, len=%zu", 
         sender_id.c_str(), channel_id.c_str(), result.size());
    return result;
}

void CryptoEngine::processSenderKeyDistribution(const std::string& sender_id,
                                                 const std::string& channel_id,
                                                 const std::vector<uint8_t>& skdm) {
    sender_key_distribution_message* skdm_msg = nullptr;
    int rc = sender_key_distribution_message_deserialize(&skdm_msg, 
                                                         skdm.data(), skdm.size(), 
                                                         signal_ctx_);
    if (rc != SG_SUCCESS) {
        LOGE("Failed to deserialize SKDM: %d", rc);
        return;
    }
    
    signal_protocol_sender_key_name key_name{};
    key_name.group_id = channel_id.c_str();
    key_name.group_id_len = channel_id.size();
    key_name.sender.name = sender_id.c_str();
    key_name.sender.name_len = sender_id.size();
    key_name.sender.device_id = 1;
    
    group_session_builder* builder = nullptr;
    rc = group_session_builder_create(&builder, store_ctx_, signal_ctx_);
    if (rc == SG_SUCCESS) {
        rc = group_session_builder_process_sender_key_distribution_message(builder, 
                                                                           &key_name, 
                                                                           skdm_msg);
        group_session_builder_free(builder);
    }
    
    sender_key_distribution_message_destroy(skdm_msg);
    
    if (rc == SG_SUCCESS) {
        LOGD("Processed SKDM from %s for %s", sender_id.c_str(), channel_id.c_str());
    } else {
        LOGE("Failed to process SKDM: %d", rc);
    }
}

// ============================================================================
// Safety Number
// ============================================================================

std::string CryptoEngine::safetyNumber(const std::string& peer_id) {
    auto peer_key = store_->loadPeerIdentity(peer_id);
    if (peer_key.size() != 32) {
        return "(no key on file for " + peer_id + ")";
    }
    
    // Compute SHA-512 of concatenated public keys (sorted)
    std::array<uint8_t, 32> our_pub = ed25519_key_.pub;
    std::array<uint8_t, 32> their_pub;
    std::copy(peer_key.begin(), peer_key.end(), their_pub.begin());
    
    uint8_t hash[64];
    if (memcmp(our_pub.data(), their_pub.data(), 32) < 0) {
        SHA512_CTX ctx;
        SHA512_Init(&ctx);
        SHA512_Update(&ctx, our_pub.data(), 32);
        SHA512_Update(&ctx, their_pub.data(), 32);
        SHA512_Final(hash, &ctx);
    } else {
        SHA512_CTX ctx;
        SHA512_Init(&ctx);
        SHA512_Update(&ctx, their_pub.data(), 32);
        SHA512_Update(&ctx, our_pub.data(), 32);
        SHA512_Final(hash, &ctx);
    }
    
    // Convert to 60-digit number (30 bytes * 2 digits per byte = 60 digits)
    std::string number;
    for (int i = 0; i < 30; i++) {
        number += std::to_string(hash[i] % 10);
        number += std::to_string((hash[i] / 10) % 10);
        if (i < 29) {
            if ((i + 1) % 5 == 0) number += " ";
        }
    }
    return number;
}

} // namespace ircord::crypto
