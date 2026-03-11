#include "crypto/crypto_engine.hpp"
#include <android/log.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>
#include <cstring>

// Signal Protocol headers
#include <signal_protocol.h>
#include <key_helper.h>
#include <protocol.h>
#include <session_builder.h>
#include <session_cipher.h>
#include <session_pre_key.h>
#include <session_state.h>
#include <curve.h>
#include <hkdf.h>

// Signal Protocol compatibility
#ifndef SIGNAL_UNREF
#define SIGNAL_UNREF(obj) signal_type_unref(obj)
#endif

#define LOG_TAG "IRCordCrypto"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace ircord::crypto {

// ============================================================================
// Global Signal Protocol Context
// ============================================================================

static signal_context* g_signal_ctx = nullptr;
static signal_protocol_store_context* g_store_ctx = nullptr;

// Crypto provider callbacks for libsignal-protocol-c
static int random_bytes(uint8_t* data, size_t len, void* user_data) {
    (void)user_data;
    if (RAND_bytes(data, static_cast<int>(len)) != 1) {
        return SG_ERR_UNKNOWN;
    }
    return 0;
}

static int hmac_sha256_init(void** hmac_context, const uint8_t* key, size_t key_len, void* user_data) {
    (void)user_data;
    EVP_MAC* mac = EVP_MAC_fetch(nullptr, "HMAC", nullptr);
    if (!mac) return SG_ERR_UNKNOWN;
    
    EVP_MAC_CTX* ctx = EVP_MAC_CTX_new(mac);
    EVP_MAC_free(mac);
    if (!ctx) return SG_ERR_UNKNOWN;
    
    OSSL_PARAM params[] = {
        OSSL_PARAM_construct_utf8_string("digest", const_cast<char*>("SHA256"), 0),
        OSSL_PARAM_construct_end()
    };
    
    if (EVP_MAC_init(ctx, key, key_len, params) != 1) {
        EVP_MAC_CTX_free(ctx);
        return SG_ERR_UNKNOWN;
    }
    
    *hmac_context = ctx;
    return 0;
}

static int hmac_sha256_update(void* hmac_context, const uint8_t* data, size_t data_len, void* user_data) {
    (void)user_data;
    if (EVP_MAC_update(static_cast<EVP_MAC_CTX*>(hmac_context), data, data_len) != 1) {
        return SG_ERR_UNKNOWN;
    }
    return 0;
}

static int hmac_sha256_final(void* hmac_context, signal_buffer** output, void* user_data) {
    (void)user_data;
    size_t out_len = 0;
    if (EVP_MAC_final(static_cast<EVP_MAC_CTX*>(hmac_context), nullptr, &out_len, 0) != 1) {
        return SG_ERR_UNKNOWN;
    }
    
    signal_buffer* buf = signal_buffer_alloc(out_len);
    if (!buf) return SG_ERR_NOMEM;
    
    if (EVP_MAC_final(static_cast<EVP_MAC_CTX*>(hmac_context), signal_buffer_data(buf), &out_len, out_len) != 1) {
        signal_buffer_free(buf);
        return SG_ERR_UNKNOWN;
    }
    
    *output = buf;
    return 0;
}

static void hmac_sha256_cleanup(void* hmac_context, void* user_data) {
    (void)user_data;
    EVP_MAC_CTX_free(static_cast<EVP_MAC_CTX*>(hmac_context));
}

static int sha512_digest(signal_buffer** output, const uint8_t* data, size_t data_len, void* user_data) {
    (void)user_data;
    signal_buffer* buf = signal_buffer_alloc(64);
    if (!buf) return SG_ERR_NOMEM;
    
    if (EVP_Digest(data, data_len, signal_buffer_data(buf), nullptr, EVP_sha512(), nullptr) != 1) {
        signal_buffer_free(buf);
        return SG_ERR_UNKNOWN;
    }
    
    *output = buf;
    return 0;
}

static int sha256_digest(signal_buffer** output, const uint8_t* data, size_t data_len, void* user_data) {
    (void)user_data;
    signal_buffer* buf = signal_buffer_alloc(32);
    if (!buf) return SG_ERR_NOMEM;
    
    if (EVP_Digest(data, data_len, signal_buffer_data(buf), nullptr, EVP_sha256(), nullptr) != 1) {
        signal_buffer_free(buf);
        return SG_ERR_UNKNOWN;
    }
    
    *output = buf;
    return 0;
}

// AES encryption for Signal Protocol
static int aes_cipher(int cipher_mode, const uint8_t* key, size_t key_len,
                      const uint8_t* iv, size_t iv_len,
                      const uint8_t* plaintext, size_t plaintext_len,
                      signal_buffer** output, void* user_data) {
    (void)user_data;
    (void)iv_len;
    
    const EVP_CIPHER* cipher = nullptr;
    if (key_len == 16) {
        cipher = (cipher_mode == SG_CIPHER_AES_CBC_PKCS5) ? EVP_aes_128_cbc() : EVP_aes_128_ctr();
    } else if (key_len == 24) {
        cipher = (cipher_mode == SG_CIPHER_AES_CBC_PKCS5) ? EVP_aes_192_cbc() : EVP_aes_192_ctr();
    } else if (key_len == 32) {
        cipher = (cipher_mode == SG_CIPHER_AES_CBC_PKCS5) ? EVP_aes_256_cbc() : EVP_aes_256_ctr();
    } else {
        return SG_ERR_UNKNOWN;
    }
    
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return SG_ERR_UNKNOWN;
    
    int mode = (cipher_mode == SG_CIPHER_AES_CBC_PKCS5) ? 1 : 1; // encrypt
    if (EVP_CipherInit_ex(ctx, cipher, nullptr, key, iv, mode) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return SG_ERR_UNKNOWN;
    }
    
    // Disable padding for CTR mode
    if (cipher_mode == SG_CIPHER_AES_CTR_NOPADDING) {
        EVP_CIPHER_CTX_set_padding(ctx, 0);
    }
    
    signal_buffer* buf = signal_buffer_alloc(plaintext_len + EVP_CIPHER_block_size(cipher));
    if (!buf) {
        EVP_CIPHER_CTX_free(ctx);
        return SG_ERR_NOMEM;
    }
    
    int out_len = 0;
    int final_len = 0;
    
    if (EVP_CipherUpdate(ctx, signal_buffer_data(buf), &out_len, plaintext, static_cast<int>(plaintext_len)) != 1) {
        signal_buffer_free(buf);
        EVP_CIPHER_CTX_free(ctx);
        return SG_ERR_UNKNOWN;
    }
    
    if (EVP_CipherFinal_ex(ctx, signal_buffer_data(buf) + out_len, &final_len) != 1) {
        signal_buffer_free(buf);
        EVP_CIPHER_CTX_free(ctx);
        return SG_ERR_UNKNOWN;
    }
    
    signal_buffer_set_len(buf, out_len + final_len);
    *output = buf;
    
    EVP_CIPHER_CTX_free(ctx);
    return 0;
}

static void aes_cleanup(void* user_data) {
    (void)user_data;
    // Nothing to cleanup for OpenSSL
}

// ============================================================================
// Signal Protocol Store Callbacks
// ============================================================================

static CryptoEngine* g_engine = nullptr;

// Session store callbacks
static int session_store_load_session_func(signal_buffer** record, const signal_protocol_address* address, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return SG_ERR_UNKNOWN;
    
    std::string addr = std::string(signal_protocol_address_get_name(address)) + ":" + 
                       std::to_string(signal_protocol_address_get_device_id(address));
    
    auto data = engine->store_->loadSession(addr);
    if (data.empty()) return SG_ERR_UNKNOWN;
    
    *record = signal_buffer_alloc(data.size());
    if (!*record) return SG_ERR_NOMEM;
    memcpy(signal_buffer_data(*record), data.data(), data.size());
    return 0;
}

static int session_store_get_sub_device_sessions_func(signal_int_list** sessions, const char* name, size_t name_len, void* user_data) {
    (void)user_data;
    (void)name;
    (void)name_len;
    // For simplicity, return empty list - we use single device per user
    *sessions = signal_int_list_alloc();
    return 0;
}

static int session_store_store_session_func(const signal_protocol_address* address, uint8_t* record, size_t record_len, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return SG_ERR_UNKNOWN;
    
    std::string addr = std::string(signal_protocol_address_get_name(address)) + ":" + 
                       std::to_string(signal_protocol_address_get_device_id(address));
    
    std::vector<uint8_t> data(record, record + record_len);
    engine->store_->saveSession(addr, data);
    return 0;
}

static int session_store_contains_session_func(const signal_protocol_address* address, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return 0;
    
    std::string addr = std::string(signal_protocol_address_get_name(address)) + ":" + 
                       std::to_string(signal_protocol_address_get_device_id(address));
    
    auto data = engine->store_->loadSession(addr);
    return !data.empty() ? 1 : 0;
}

static int session_store_delete_session_func(const signal_protocol_address* address, void* user_data) {
    (void)user_data;
    // Not implemented - sessions are not deleted in our design
    return 0;
}

static int session_store_delete_all_sessions_func(const char* name, size_t name_len, void* user_data) {
    (void)user_data;
    (void)name;
    (void)name_len;
    return 0;
}

// Pre-key store callbacks
static int pre_key_store_load_pre_key_func(signal_buffer** record, uint32_t pre_key_id, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return SG_ERR_UNKNOWN;
    
    auto data = engine->store_->loadPreKey(pre_key_id);
    if (data.empty()) return SG_ERR_INVALID_KEY_ID;
    
    *record = signal_buffer_alloc(data.size());
    if (!*record) return SG_ERR_NOMEM;
    memcpy(signal_buffer_data(*record), data.data(), data.size());
    return 0;
}

static int pre_key_store_store_pre_key_func(uint32_t pre_key_id, uint8_t* record, size_t record_len, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return SG_ERR_UNKNOWN;
    
    std::vector<uint8_t> data(record, record + record_len);
    engine->store_->savePreKey(pre_key_id, data);
    return 0;
}

static int pre_key_store_contains_pre_key_func(uint32_t pre_key_id, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return 0;
    
    auto data = engine->store_->loadPreKey(pre_key_id);
    return !data.empty() ? 1 : 0;
}

static int pre_key_store_remove_pre_key_func(uint32_t pre_key_id, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return SG_ERR_UNKNOWN;
    
    engine->store_->removePreKey(pre_key_id);
    return 0;
}

static int pre_key_store_load_pre_key_list_func(signal_buffer** record, uint32_t pre_key_id, void* user_data) {
    // Not used in X3DH
    (void)record;
    (void)pre_key_id;
    (void)user_data;
    return SG_ERR_UNKNOWN;
}

static int pre_key_store_store_pre_key_list_func(uint32_t pre_key_id, uint8_t* record, size_t record_len, void* user_data) {
    // Not used in X3DH
    (void)pre_key_id;
    (void)record;
    (void)record_len;
    (void)user_data;
    return SG_ERR_UNKNOWN;
}

// Signed pre-key store callbacks
static int signed_pre_key_store_load_signed_pre_key_func(signal_buffer** record, uint32_t signed_pre_key_id, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return SG_ERR_UNKNOWN;
    
    auto data = engine->store_->loadSignedPreKey(signed_pre_key_id);
    if (data.empty()) return SG_ERR_INVALID_KEY_ID;
    
    *record = signal_buffer_alloc(data.size());
    if (!*record) return SG_ERR_NOMEM;
    memcpy(signal_buffer_data(*record), data.data(), data.size());
    return 0;
}

static int signed_pre_key_store_store_signed_pre_key_func(uint32_t signed_pre_key_id, uint8_t* record, size_t record_len, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return SG_ERR_UNKNOWN;
    
    std::vector<uint8_t> data(record, record + record_len);
    engine->store_->saveSignedPreKey(signed_pre_key_id, data);
    return 0;
}

static int signed_pre_key_store_contains_signed_pre_key_func(uint32_t signed_pre_key_id, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return 0;
    
    auto data = engine->store_->loadSignedPreKey(signed_pre_key_id);
    return !data.empty() ? 1 : 0;
}

static int signed_pre_key_store_remove_signed_pre_key_func(uint32_t signed_pre_key_id, void* user_data) {
    (void)user_data;
    (void)signed_pre_key_id;
    // Not implemented
    return 0;
}

// Identity key store callbacks
static int identity_key_store_get_identity_key_pair_func(signal_buffer** public_data, signal_buffer** private_data, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return SG_ERR_UNKNOWN;
    
    std::vector<uint8_t> pub_key, priv_key, salt;
    if (!engine->store_->loadIdentity(engine->user_id_, pub_key, priv_key, salt)) {
        return SG_ERR_UNKNOWN;
    }
    
    *public_data = signal_buffer_alloc(pub_key.size());
    *private_data = signal_buffer_alloc(priv_key.size());
    if (!*public_data || !*private_data) {
        signal_buffer_free(*public_data);
        signal_buffer_free(*private_data);
        return SG_ERR_NOMEM;
    }
    
    memcpy(signal_buffer_data(*public_data), pub_key.data(), pub_key.size());
    memcpy(signal_buffer_data(*private_data), priv_key.data(), priv_key.size());
    return 0;
}

static int identity_key_store_get_local_registration_id_func(uint32_t* registration_id, void* user_data) {
    (void)user_data;
    // Return a fixed registration ID for simplicity
    *registration_id = 1;
    return 0;
}

static int identity_key_store_save_identity_func(const signal_protocol_address* address, uint8_t* key_data, size_t key_len, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return SG_ERR_UNKNOWN;
    
    std::string user_id(signal_protocol_address_get_name(address));
    std::vector<uint8_t> key(key_data, key_data + key_len);
    engine->store_->savePeerIdentity(user_id, key);
    return 0;
}

static int identity_key_store_is_trusted_identity_func(const signal_protocol_address* address, uint8_t* key_data, size_t key_len, void* user_data) {
    (void)user_data;
    auto* engine = g_engine;
    if (!engine || !engine->store_) return 1; // Trust on first use
    
    std::string user_id(signal_protocol_address_get_name(address));
    auto existing = engine->store_->loadPeerIdentity(user_id);
    
    if (existing.empty()) return 1; // Trust on first use
    
    // Check if keys match
    if (existing.size() != key_len) return 0;
    return (memcmp(existing.data(), key_data, key_len) == 0) ? 1 : 0;
}

static void identity_key_store_destroy_func(void* user_data) {
    (void)user_data;
}

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
    if (g_engine == this) {
        g_engine = nullptr;
    }
}

bool CryptoEngine::init(IStore* store, const std::string& user_id, const std::string& passphrase) {
    LOGD("CryptoEngine::init - starting");
    
    store_ = store;
    user_id_ = user_id;
    g_engine = this;
    
    // Initialize Signal Protocol context
    signal_crypto_provider crypto_provider = {
        .random_func = random_bytes,
        .hmac_sha256_init_func = hmac_sha256_init,
        .hmac_sha256_update_func = hmac_sha256_update,
        .hmac_sha256_final_func = hmac_sha256_final,
        .hmac_sha256_cleanup_func = hmac_sha256_cleanup,
        .sha512_digest_func = sha512_digest,
        .sha256_digest_func = sha256_digest,
        .aes_cipher_func = aes_cipher,
        .aes_cleanup_func = aes_cleanup,
        .user_data = nullptr
    };
    
    int result = signal_context_create(&signal_ctx_, &crypto_provider);
    if (result != 0) {
        LOGE("Failed to create signal context: %d", result);
        return false;
    }
    
    // Set log level
    signal_context_set_log_function(signal_ctx_, [](int level, const char* message, size_t len, void* user_data) {
        (void)user_data;
        (void)len;
        if (level >= SG_LOG_ERROR) {
            LOGE("Signal: %s", message);
        } else if (level >= SG_LOG_NOTICE) {
            LOGD("Signal: %s", message);
        }
    });
    
    // Create store context
    result = signal_protocol_store_context_create(&store_ctx_, signal_ctx_);
    if (result != 0) {
        LOGE("Failed to create store context: %d", result);
        return false;
    }
    
    // Set up session store
    signal_protocol_session_store session_store = {
        .load_session_func = session_store_load_session_func,
        .get_sub_device_sessions_func = session_store_get_sub_device_sessions_func,
        .store_session_func = session_store_store_session_func,
        .contains_session_func = session_store_contains_session_func,
        .delete_session_func = session_store_delete_session_func,
        .delete_all_sessions_func = session_store_delete_all_sessions_func,
        .destroy_func = nullptr,
        .user_data = nullptr
    };
    signal_protocol_store_context_set_session_store(store_ctx_, &session_store);
    
    // Set up pre-key store
    signal_protocol_pre_key_store pre_key_store = {
        .load_pre_key_func = pre_key_store_load_pre_key_func,
        .store_pre_key_func = pre_key_store_store_pre_key_func,
        .contains_pre_key_func = pre_key_store_contains_pre_key_func,
        .remove_pre_key_func = pre_key_store_remove_pre_key_func,
        .load_pre_key_list_func = pre_key_store_load_pre_key_list_func,
        .store_pre_key_list_func = pre_key_store_store_pre_key_list_func,
        .destroy_func = nullptr,
        .user_data = nullptr
    };
    signal_protocol_store_context_set_pre_key_store(store_ctx_, &pre_key_store);
    
    // Set up signed pre-key store
    signal_protocol_signed_pre_key_store signed_pre_key_store = {
        .load_signed_pre_key_func = signed_pre_key_store_load_signed_pre_key_func,
        .store_signed_pre_key_func = signed_pre_key_store_store_signed_pre_key_func,
        .contains_signed_pre_key_func = signed_pre_key_store_contains_signed_pre_key_func,
        .remove_signed_pre_key_func = signed_pre_key_store_remove_signed_pre_key_func,
        .destroy_func = nullptr,
        .user_data = nullptr
    };
    signal_protocol_store_context_set_signed_pre_key_store(store_ctx_, &signed_pre_key_store);
    
    // Set up identity key store
    signal_protocol_identity_key_store identity_key_store = {
        .get_identity_key_pair_func = identity_key_store_get_identity_key_pair_func,
        .get_local_registration_id_func = identity_key_store_get_local_registration_id_func,
        .save_identity_func = identity_key_store_save_identity_func,
        .is_trusted_identity_func = identity_key_store_is_trusted_identity_func,
        .destroy_func = identity_key_store_destroy_func,
        .user_data = nullptr
    };
    signal_protocol_store_context_set_identity_key_store(store_ctx_, &identity_key_store);
    
    // Load or generate identity
    if (!loadOrGenerateIdentity(passphrase)) {
        LOGE("Failed to load or generate identity");
        return false;
    }
    
    loaded_ = true;
    LOGD("CryptoEngine::init - success");
    return true;
}

bool CryptoEngine::loadOrGenerateIdentity(const std::string& passphrase) {
    std::vector<uint8_t> pub_key, priv_key_encrypted, salt;
    
    if (store_->loadIdentity(user_id_, pub_key, priv_key_encrypted, salt)) {
        LOGD("Loaded existing identity");
        // Decrypt and load identity
        // For now, just store the public key
        if (pub_key.size() == 32) {
            memcpy(ed25519_key_.pub.data(), pub_key.data(), 32);
        }
        // TODO: Decrypt private key and derive X25519 key
        return true;
    }
    
    LOGD("Generating new identity");
    return generateIdentity(passphrase);
}

bool CryptoEngine::generateIdentity(const std::string& passphrase) {
    // Generate Ed25519 identity key pair using OpenSSL
    EVP_PKEY* pkey = EVP_PKEY_new();
    if (!pkey) return false;
    
    EVP_PKEY_CTX* ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_ED25519, nullptr);
    if (!ctx) {
        EVP_PKEY_free(pkey);
        return false;
    }
    
    if (EVP_PKEY_keygen_init(ctx) <= 0 || EVP_PKEY_keygen(ctx, &pkey) <= 0) {
        EVP_PKEY_CTX_free(ctx);
        EVP_PKEY_free(pkey);
        return false;
    }
    
    EVP_PKEY_CTX_free(ctx);
    
    // Get public key
    size_t pub_len = 32;
    if (EVP_PKEY_get_raw_public_key(pkey, ed25519_key_.pub.data(), &pub_len) != 1 || pub_len != 32) {
        EVP_PKEY_free(pkey);
        return false;
    }
    
    // Get private key
    size_t priv_len = 64;
    std::array<uint8_t, 64> priv_key;
    if (EVP_PKEY_get_raw_private_key(pkey, priv_key.data(), &priv_len) != 1 || priv_len != 64) {
        EVP_PKEY_free(pkey);
        return false;
    }
    
    EVP_PKEY_free(pkey);
    
    // Encrypt private key
    std::vector<uint8_t> salt;
    auto encrypted = encryptIdentityPriv(priv_key, passphrase, salt);
    
    // Save identity
    std::vector<uint8_t> pub_vec(ed25519_key_.pub.begin(), ed25519_key_.pub.end());
    store_->saveIdentity(user_id_, pub_vec, encrypted, salt);
    
    // Derive X25519 identity key from Ed25519 key
    // This is done using the standard conversion: X25519 private key = first 32 bytes of SHA512(Ed25519 private key)
    signal_buffer* hash = nullptr;
    sha512_digest(&hash, priv_key.data(), priv_key.size(), nullptr);
    if (hash) {
        memcpy(x25519_identity_.priv.data(), signal_buffer_data(hash), 32);
        signal_buffer_free(hash);
        
        // Derive X25519 public key from private key
        // For now, we'll generate a new X25519 key pair
        // In production, use proper X25519 derivation from Ed25519
        EVP_PKEY_CTX* x25519_ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_X25519, nullptr);
        if (x25519_ctx) {
            EVP_PKEY* x25519_pkey = nullptr;
            if (EVP_PKEY_keygen_init(x25519_ctx) > 0 && EVP_PKEY_keygen(x25519_ctx, &x25519_pkey) > 0) {
                size_t x_pub_len = 32;
                EVP_PKEY_get_raw_public_key(x25519_pkey, x25519_identity_.pub.data(), &x_pub_len);
                EVP_PKEY_free(x25519_pkey);
            }
            EVP_PKEY_CTX_free(x25519_ctx);
        }
    }
    
    LOGD("Generated new identity, public key: %02x%02x...%02x%02x",
         ed25519_key_.pub[0], ed25519_key_.pub[1], ed25519_key_.pub[30], ed25519_key_.pub[31]);
    
    return true;
}

std::vector<uint8_t> CryptoEngine::encryptIdentityPriv(const std::array<uint8_t, 64>& priv_key,
                                                        const std::string& passphrase,
                                                        std::vector<uint8_t>& salt_out) {
    // Generate salt
    salt_out.resize(16);
    RAND_bytes(salt_out.data(), 16);
    
    // Derive key using PBKDF2 (in production, use Argon2id)
    std::array<uint8_t, 32> key;
    PKCS5_PBKDF2_HMAC(passphrase.c_str(), static_cast<int>(passphrase.length()),
                      salt_out.data(), static_cast<int>(salt_out.size()),
                      100000, EVP_sha256(),
                      32, key.data());
    
    // Encrypt with AES-256-GCM using OpenSSL
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return {};
    
    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    std::array<uint8_t, 12> iv;
    RAND_bytes(iv.data(), 12);
    
    if (EVP_EncryptInit_ex(ctx, nullptr, nullptr, key.data(), iv.data()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    std::vector<uint8_t> ciphertext(priv_key.size() + 16); // +16 for GCM tag
    int len;
    if (EVP_EncryptUpdate(ctx, ciphertext.data(), &len, priv_key.data(), static_cast<int>(priv_key.size())) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    int final_len;
    if (EVP_EncryptFinal_ex(ctx, ciphertext.data() + len, &final_len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    // Get GCM tag
    std::array<uint8_t, 16> tag;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, 16, tag.data()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    EVP_CIPHER_CTX_free(ctx);
    
    // Format: [16 bytes IV] [ciphertext] [16 bytes tag]
    std::vector<uint8_t> result;
    result.reserve(iv.size() + ciphertext.size() + tag.size());
    result.insert(result.end(), iv.begin(), iv.end());
    result.insert(result.end(), ciphertext.begin(), ciphertext.begin() + len + final_len);
    result.insert(result.end(), tag.begin(), tag.end());
    
    return result;
}

bool CryptoEngine::decryptIdentityPriv(const std::vector<uint8_t>& ciphertext,
                                        const std::vector<uint8_t>& salt,
                                        const std::string& passphrase,
                                        std::array<uint8_t, 64>& priv_out) {
    if (ciphertext.size() < 12 + 16 + 16) return false; // IV + min ciphertext + tag
    
    // Derive key
    std::array<uint8_t, 32> key;
    PKCS5_PBKDF2_HMAC(passphrase.c_str(), static_cast<int>(passphrase.length()),
                      salt.data(), static_cast<int>(salt.size()),
                      100000, EVP_sha256(),
                      32, key.data());
    
    // Extract IV, ciphertext, and tag
    std::array<uint8_t, 12> iv;
    memcpy(iv.data(), ciphertext.data(), 12);
    
    size_t ct_len = ciphertext.size() - 12 - 16;
    std::vector<uint8_t> plaintext(ct_len);
    
    std::array<uint8_t, 16> tag;
    memcpy(tag.data(), ciphertext.data() + 12 + ct_len, 16);
    
    // Decrypt
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return false;
    
    if (EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, key.data(), iv.data()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return false;
    }
    
    int len;
    if (EVP_DecryptUpdate(ctx, plaintext.data(), &len, ciphertext.data() + 12, static_cast<int>(ct_len)) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return false;
    }
    
    // Set expected tag
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, 16, tag.data()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return false;
    }
    
    int final_len;
    if (EVP_DecryptFinal_ex(ctx, plaintext.data() + len, &final_len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return false;
    }
    
    EVP_CIPHER_CTX_free(ctx);
    
    if (plaintext.size() != 64) return false;
    memcpy(priv_out.data(), plaintext.data(), 64);
    return true;
}

std::vector<uint8_t> CryptoEngine::prepareKeyUpload(int num_opks) {
    LOGD("prepareKeyUpload: generating %d one-time pre-keys", num_opks);
    
    // Generate signed pre-key
    uint32_t spk_id = 1;
    session_signed_pre_key* spk = nullptr;
    
    // Generate signed pre-key using Signal Protocol
    // First generate a key pair
    ec_key_pair* key_pair = nullptr;
    int result = signal_protocol_key_helper_generate_key_pair(&key_pair, signal_ctx_);
    if (result != 0) {
        LOGE("Failed to generate key pair for SPK");
        return {};
    }
    
    // Generate signed pre-key
    result = session_signed_pre_key_create(&spk, spk_id, 
                                           ec_key_pair_get_public(key_pair),
                                           ec_key_pair_get_private(key_pair),
                                           signal_ctx_);
    ec_key_pair_destroy(key_pair);
    
    if (result != 0 || !spk) {
        LOGE("Failed to create signed pre-key");
        return {};
    }
    
    spk_.id = spk_id;
    
    // Get public key
    const ec_public_key* pub = session_signed_pre_key_get_public(spk);
    signal_buffer* pub_buf = nullptr;
    ec_public_key_serialize(&pub_buf, pub);
    if (pub_buf) {
        spk_.key_pair.pub.fill(0);
        size_t copy_len = std::min(size_t(32), signal_buffer_len(pub_buf));
        memcpy(spk_.key_pair.pub.data(), signal_buffer_data(pub_buf), copy_len);
        signal_buffer_free(pub_buf);
    }
    
    // Get signature
    const uint8_t* sig = session_signed_pre_key_get_signature(spk);
    size_t sig_len = session_signed_pre_key_get_signature_len(spk);
    spk_.signature.assign(sig, sig + sig_len);
    
    // Store signed pre-key
    signal_buffer* buf = nullptr;
    session_signed_pre_key_serialize(&buf, spk);
    if (buf) {
        std::vector<uint8_t> data(signal_buffer_data(buf), signal_buffer_data(buf) + signal_buffer_len(buf));
        store_->saveSignedPreKey(spk_id, data);
        signal_buffer_free(buf);
    }
    
    SIGNAL_UNREF(spk);
    
    // Generate one-time pre-keys
    signal_protocol_key_helper_pre_key_list* key_list = nullptr;
    result = signal_protocol_key_helper_generate_pre_keys(&key_list, next_opk_id_, num_opks, signal_ctx_);
    
    if (result == 0 && key_list) {
        session_pre_key* key = nullptr;
        while (signal_protocol_key_helper_key_list_next(key_list, &key) >= 0 && key != nullptr) {
            uint32_t id = session_pre_key_get_id(key);
            
            signal_buffer* buf = nullptr;
            session_pre_key_serialize(&buf, key);
            if (buf) {
                std::vector<uint8_t> data(signal_buffer_data(buf), signal_buffer_data(buf) + signal_buffer_len(buf));
                store_->savePreKey(id, data);
                signal_buffer_free(buf);
            }
            
            SIGNAL_UNREF(key);
            next_opk_id_ = id + 1;
        }
        signal_protocol_key_helper_key_list_free(key_list);
    }
    
    // Build KeyUpload protobuf
    // Format: [identity_pub: 32 bytes][spk_pub: 32 bytes][spk_sig: 64 bytes][spk_id: 4 bytes LE]
    //         [num_opks: 4 bytes LE][opk_id: 4 bytes LE][opk_pub: 32 bytes]...
    std::vector<uint8_t> upload;
    upload.reserve(32 + 32 + 64 + 4 + 4 + num_opks * (4 + 32));
    
    // Identity public key (Ed25519)
    upload.insert(upload.end(), ed25519_key_.pub.begin(), ed25519_key_.pub.end());
    
    // Signed pre-key
    upload.insert(upload.end(), spk_.key_pair.pub.begin(), spk_.key_pair.pub.end());
    upload.insert(upload.end(), spk_.signature.begin(), spk_.signature.end());
    
    // SPK ID (little-endian)
    upload.push_back(spk_id & 0xFF);
    upload.push_back((spk_id >> 8) & 0xFF);
    upload.push_back((spk_id >> 16) & 0xFF);
    upload.push_back((spk_id >> 24) & 0xFF);
    
    // TODO: Add one-time pre-keys to the serialized format
    
    LOGD("prepareKeyUpload: generated upload data (%zu bytes)", upload.size());
    return upload;
}

std::vector<uint8_t> CryptoEngine::signChallenge(const std::vector<uint8_t>& nonce) {
    // Sign nonce with Ed25519 private key
    // Load private key from store
    std::vector<uint8_t> pub_key, priv_key_encrypted, salt;
    if (!store_->loadIdentity(user_id_, pub_key, priv_key_encrypted, salt)) {
        LOGE("Failed to load identity for signing");
        return {};
    }
    
    // For now, return a dummy signature since we need to properly implement
    // passphrase caching or key storage. The server will accept this during development.
    // TODO: Implement proper Ed25519 signing with decrypted private key
    std::vector<uint8_t> signature(64);
    RAND_bytes(signature.data(), 64);
    
    LOGD("Generated challenge signature (64 bytes)");
    return signature;
}

std::vector<uint8_t> CryptoEngine::identityPub() const {
    return std::vector<uint8_t>(ed25519_key_.pub.begin(), ed25519_key_.pub.end());
}

CryptoEngine::SpkInfo CryptoEngine::currentSpk() const {
    return SpkInfo{
        .pub = std::vector<uint8_t>(spk_.key_pair.pub.begin(), spk_.key_pair.pub.end()),
        .sig = spk_.signature,
        .id = spk_.id
    };
}

std::vector<uint8_t> CryptoEngine::encrypt(const std::string& recipient_id, 
                                            const std::string& plaintext) {
    if (!hasSession(recipient_id)) {
        LOGE("No session with %s", recipient_id.c_str());
        // Store as pending and return empty
        pending_plaintexts_[recipient_id] = plaintext;
        return {};
    }
    
    signal_protocol_address addr;
    signal_protocol_address_init(&addr, recipient_id.c_str(), recipient_id.length(), 1);
    
    session_cipher* cipher = nullptr;
    int ret = session_cipher_create(&cipher, store_ctx_, &addr, signal_ctx_);
    if (ret != 0 || !cipher) {
        LOGE("Failed to create session cipher: %d", ret);
        return {};
    }
    
    ciphertext_message* message = nullptr;
    ret = session_cipher_encrypt(cipher, reinterpret_cast<const uint8_t*>(plaintext.data()), 
                                 plaintext.length(), &message);
    if (ret != 0 || !message) {
        LOGE("Failed to encrypt: %d", ret);
        session_cipher_free(cipher);
        return {};
    }
    
    signal_buffer* serialized = ciphertext_message_get_serialized(message);
    std::vector<uint8_t> result_vec;
    if (serialized) {
        result_vec.assign(signal_buffer_data(serialized), 
                          signal_buffer_data(serialized) + signal_buffer_len(serialized));
    }
    
    SIGNAL_UNREF(message);
    session_cipher_free(cipher);
    
    return result_vec;
}

std::vector<uint8_t> CryptoEngine::encryptPending(const std::string& recipient_id) {
    auto it = pending_plaintexts_.find(recipient_id);
    if (it == pending_plaintexts_.end()) {
        return {};
    }
    
    auto result = encrypt(recipient_id, it->second);
    pending_plaintexts_.erase(it);
    return result;
}

DecryptResult CryptoEngine::decrypt(const std::string& sender_id,
                                     const std::string& recipient_id,
                                     const std::vector<uint8_t>& ciphertext,
                                     int ciphertext_type,
                                     const std::vector<uint8_t>& skdm) {
    (void)recipient_id;
    (void)skdm;
    
    DecryptResult result;
    result.sender_id = sender_id;
    
    signal_protocol_address addr;
    signal_protocol_address_init(&addr, sender_id.c_str(), sender_id.length(), 1);
    
    session_cipher* cipher = nullptr;
    int ret = session_cipher_create(&cipher, store_ctx_, &addr, signal_ctx_);
    if (ret != 0 || !cipher) {
        LOGE("Failed to create session cipher: %d", ret);
        return result;
    }
    
    signal_buffer* plaintext = nullptr;
    int decrypt_result = 0;
    
    if (ciphertext_type == 3) { // PRE_KEY_SIGNAL_MESSAGE
        pre_key_signal_message* msg = nullptr;
        decrypt_result = pre_key_signal_message_deserialize(&msg, ciphertext.data(), 
                                                            ciphertext.size(), signal_ctx_);
        if (decrypt_result == 0 && msg) {
            decrypt_result = session_cipher_decrypt_pre_key_signal_message(cipher, msg, nullptr, &plaintext);
            SIGNAL_UNREF(msg);
        } else {
            LOGE("Failed to deserialize pre-key signal message: %d", decrypt_result);
        }
    } else { // SIGNAL_MESSAGE
        signal_message* msg = nullptr;
        decrypt_result = signal_message_deserialize(&msg, ciphertext.data(), 
                                                    ciphertext.size(), signal_ctx_);
        if (decrypt_result == 0 && msg) {
            decrypt_result = session_cipher_decrypt_signal_message(cipher, msg, nullptr, &plaintext);
            SIGNAL_UNREF(msg);
        } else {
            LOGE("Failed to deserialize signal message: %d", decrypt_result);
        }
    }
    
    if (decrypt_result != 0 || !plaintext) {
        LOGE("Failed to decrypt: %d", decrypt_result);
        session_cipher_free(cipher);
        return result;
    }
    
    result.plaintext = std::string(reinterpret_cast<const char*>(signal_buffer_data(plaintext)),
                                    signal_buffer_len(plaintext));
    result.success = true;
    
    signal_buffer_free(plaintext);
    session_cipher_free(cipher);
    
    return result;
}

void CryptoEngine::onKeyBundle(const std::vector<uint8_t>& bundle_data,
                                const std::string& recipient_id) {
    LOGD("Processing key bundle for %s", recipient_id.c_str());
    
    // Parse bundle_data and create session
    // Format: [identity_pub: 32 bytes][spk_pub: 32 bytes][spk_sig: 64 bytes][spk_id: 4 bytes LE]
    if (bundle_data.size() < 32 + 32 + 64 + 4) {
        LOGE("Invalid key bundle size");
        return;
    }
    
    signal_protocol_address addr;
    signal_protocol_address_init(&addr, recipient_id.c_str(), recipient_id.length(), 1);
    
    session_builder* builder = nullptr;
    session_builder_create(&builder, store_ctx_, &addr, signal_ctx_);
    if (!builder) {
        LOGE("Failed to create session builder");
        return;
    }
    
    // TODO: Parse bundle and process using session_builder_process_pre_key_bundle
    
    session_builder_free(builder);
}

bool CryptoEngine::hasSession(const std::string& recipient_id) {
    signal_protocol_address addr;
    signal_protocol_address_init(&addr, recipient_id.c_str(), recipient_id.length(), 1);
    
    return session_store_contains_session_func(&addr, nullptr) == 1;
}

void CryptoEngine::initGroupSession(const std::string& channel_id, 
                                     const std::vector<std::string>& members) {
    LOGD("initGroupSession for %s with %zu members", channel_id.c_str(), members.size());
    
    GroupSessionState state;
    state.initialized = true;
    
    // Generate sender key
    state.chain_key.resize(32);
    RAND_bytes(state.chain_key.data(), 32);
    
    group_sessions_[channel_id] = std::move(state);
}

std::vector<uint8_t> CryptoEngine::encryptGroup(const std::string& channel_id,
                                                 const std::string& plaintext) {
    auto it = group_sessions_.find(channel_id);
    if (it == group_sessions_.end() || !it->second.initialized) {
        LOGE("Group session not initialized for %s", channel_id.c_str());
        return {};
    }
    
    auto& state = it->second;
    
    // Simple sender key encryption: AES-256-GCM with chain key
    // In production, use proper sender key ratcheting
    
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return {};
    
    if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, state.chain_key.data(), nullptr) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    std::array<uint8_t, 12> iv{}; // Zero IV for simplicity (not recommended for production)
    if (EVP_EncryptInit_ex(ctx, nullptr, nullptr, nullptr, iv.data()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    std::vector<uint8_t> ciphertext(plaintext.size() + 16);
    int len;
    if (EVP_EncryptUpdate(ctx, ciphertext.data(), &len, 
                          reinterpret_cast<const uint8_t*>(plaintext.data()), 
                          static_cast<int>(plaintext.size())) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    int final_len;
    if (EVP_EncryptFinal_ex(ctx, ciphertext.data() + len, &final_len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    std::array<uint8_t, 16> tag;
    EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, 16, tag.data());
    EVP_CIPHER_CTX_free(ctx);
    
    // Append tag to ciphertext
    ciphertext.resize(len + final_len + 16);
    memcpy(ciphertext.data() + len + final_len, tag.data(), 16);
    
    // Update chain key (simple ratchet)
    signal_buffer* new_key = nullptr;
    sha256_digest(&new_key, state.chain_key.data(), state.chain_key.size(), nullptr);
    if (new_key) {
        memcpy(state.chain_key.data(), signal_buffer_data(new_key), 32);
        signal_buffer_free(new_key);
    }
    state.iteration++;
    
    return ciphertext;
}

std::vector<uint8_t> CryptoEngine::decryptGroup(const std::string& sender_id,
                                                 const std::string& channel_id,
                                                 const std::vector<uint8_t>& ciphertext,
                                                 const std::vector<uint8_t>& skdm) {
    (void)skdm;
    
    std::string key = makeGroupSessionKey(sender_id, channel_id);
    auto it = sender_key_states_.find(key);
    if (it == sender_key_states_.end()) {
        LOGE("No sender key for %s", key.c_str());
        return {};
    }
    
    auto& state = it->second;
    
    if (ciphertext.size() < 16) return {};
    
    // Extract tag
    std::array<uint8_t, 16> tag;
    memcpy(tag.data(), ciphertext.data() + ciphertext.size() - 16, 16);
    
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return {};
    
    if (EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, state.chain_key.data(), nullptr) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    std::array<uint8_t, 12> iv{};
    if (EVP_DecryptInit_ex(ctx, nullptr, nullptr, nullptr, iv.data()) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    std::vector<uint8_t> plaintext(ciphertext.size() - 16);
    int len;
    if (EVP_DecryptUpdate(ctx, plaintext.data(), &len, ciphertext.data(), 
                          static_cast<int>(ciphertext.size() - 16)) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, 16, tag.data());
    
    int final_len;
    if (EVP_DecryptFinal_ex(ctx, plaintext.data() + len, &final_len) != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return {};
    }
    
    EVP_CIPHER_CTX_free(ctx);
    
    plaintext.resize(len + final_len);
    return plaintext;
}

void CryptoEngine::processSenderKeyDistribution(const std::string& sender_id,
                                                 const std::string& channel_id,
                                                 const std::vector<uint8_t>& skdm) {
    LOGD("Processing SKDM from %s for %s", sender_id.c_str(), channel_id.c_str());
    
    std::string key = makeGroupSessionKey(sender_id, channel_id);
    
    GroupSessionState state;
    state.initialized = true;
    state.chain_key = skdm; // Simplified - in production, parse the SKDM properly
    
    sender_key_states_[key] = std::move(state);
}

std::string CryptoEngine::safetyNumber(const std::string& peer_id) {
    // Compute safety number from identity keys
    std::vector<uint8_t> our_key(ed25519_key_.pub.begin(), ed25519_key_.pub.end());
    auto their_key = store_->loadPeerIdentity(peer_id);
    
    if (their_key.empty()) {
        return "00000 00000 00000 00000 00000 00000 00000 00000 00000 00000 00000 00000";
    }
    
    // Sort keys to ensure deterministic order
    const uint8_t* first = our_key.data();
    const uint8_t* second = their_key.data();
    size_t first_len = our_key.size();
    size_t second_len = their_key.size();
    
    if (memcmp(our_key.data(), their_key.data(), std::min(our_key.size(), their_key.size())) > 0) {
        std::swap(first, second);
        std::swap(first_len, second_len);
    }
    
    // Compute hash
    std::vector<uint8_t> combined;
    combined.reserve(first_len + second_len);
    combined.insert(combined.end(), first, first + first_len);
    combined.insert(combined.end(), second, second + second_len);
    
    signal_buffer* hash = nullptr;
    sha512_digest(&hash, combined.data(), combined.size(), nullptr);
    if (!hash) {
        return "00000 00000 00000 00000 00000 00000 00000 00000 00000 00000 00000 00000";
    }
    
    // Format as 60 digits in groups of 5
    std::string result;
    result.reserve(60 + 11); // 60 digits + 11 spaces
    
    const uint8_t* hash_data = signal_buffer_data(hash);
    for (int i = 0; i < 12; i++) {
        if (i > 0) result += ' ';
        // Use two bytes per 5-digit group
        uint16_t val = (hash_data[i * 2] << 8) | hash_data[i * 2 + 1];
        char buf[6];
        snprintf(buf, sizeof(buf), "%05d", val % 100000);
        result += buf;
    }
    
    signal_buffer_free(hash);
    return result;
}

std::string CryptoEngine::getPendingPlaintext(const std::string& recipient_id) {
    auto it = pending_plaintexts_.find(recipient_id);
    return (it != pending_plaintexts_.end()) ? it->second : "";
}

void CryptoEngine::clearPendingPlaintext(const std::string& recipient_id) {
    pending_plaintexts_.erase(recipient_id);
}

} // namespace ircord::crypto
