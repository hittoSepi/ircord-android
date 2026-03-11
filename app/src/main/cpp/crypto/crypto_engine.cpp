#include "crypto/crypto_engine.hpp"
#include <android/log.h>

#define LOG_TAG "IRCordCrypto"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace ircord::crypto {

CryptoEngine::CryptoEngine() = default;
CryptoEngine::~CryptoEngine() = default;

bool CryptoEngine::init(IStore* store, const std::string& user_id, const std::string& passphrase) {
    LOGD("CryptoEngine::init - STUB");
    store_ = store;
    user_id_ = user_id;
    loaded_ = true;
    return true;
}

std::vector<uint8_t> CryptoEngine::prepareKeyUpload(int num_opks) {
    LOGD("prepareKeyUpload - STUB");
    return std::vector<uint8_t>();
}

std::vector<uint8_t> CryptoEngine::signChallenge(const std::vector<uint8_t>& nonce) {
    LOGD("signChallenge - STUB");
    return std::vector<uint8_t>(64, 0);
}

std::vector<uint8_t> CryptoEngine::identityPub() const {
    LOGD("identityPub - STUB");
    return std::vector<uint8_t>(32, 0);
}

CryptoEngine::SpkInfo CryptoEngine::currentSpk() const {
    LOGD("currentSpk - STUB");
    return SpkInfo{};
}

std::vector<uint8_t> CryptoEngine::encrypt(const std::string& recipient_id, 
                                            const std::string& plaintext) {
    LOGD("encrypt - STUB");
    return std::vector<uint8_t>(plaintext.begin(), plaintext.end());
}

std::vector<uint8_t> CryptoEngine::encryptPending(const std::string& recipient_id) {
    LOGD("encryptPending - STUB");
    return std::vector<uint8_t>();
}

DecryptResult CryptoEngine::decrypt(const std::string& sender_id,
                                     const std::string& recipient_id,
                                     const std::vector<uint8_t>& ciphertext,
                                     int ciphertext_type,
                                     const std::vector<uint8_t>& skdm) {
    LOGD("decrypt - STUB");
    DecryptResult result;
    result.plaintext = std::string(ciphertext.begin(), ciphertext.end());
    result.sender_id = sender_id;
    result.success = true;
    return result;
}

void CryptoEngine::onKeyBundle(const std::vector<uint8_t>& bundle_data,
                                const std::string& recipient_id) {
    LOGD("onKeyBundle - STUB");
}

void CryptoEngine::initGroupSession(const std::string& channel_id, 
                                     const std::vector<std::string>& members) {
    LOGD("initGroupSession - STUB");
}

std::vector<uint8_t> CryptoEngine::encryptGroup(const std::string& channel_id,
                                                 const std::string& plaintext) {
    LOGD("encryptGroup - STUB");
    return std::vector<uint8_t>(plaintext.begin(), plaintext.end());
}

std::vector<uint8_t> CryptoEngine::decryptGroup(const std::string& sender_id,
                                                 const std::string& channel_id,
                                                 const std::vector<uint8_t>& ciphertext,
                                                 const std::vector<uint8_t>& skdm) {
    LOGD("decryptGroup - STUB");
    return std::vector<uint8_t>(ciphertext.begin(), ciphertext.end());
}

void CryptoEngine::processSenderKeyDistribution(const std::string& sender_id,
                                                 const std::string& channel_id,
                                                 const std::vector<uint8_t>& skdm) {
    LOGD("processSenderKeyDistribution - STUB");
}

std::string CryptoEngine::safetyNumber(const std::string& peer_id) {
    LOGD("safetyNumber - STUB");
    return "0000 0000 0000 0000";
}

bool CryptoEngine::hasSession(const std::string& recipient_id) {
    LOGD("hasSession - STUB");
    return false;
}

std::string CryptoEngine::getPendingPlaintext(const std::string& recipient_id) {
    LOGD("getPendingPlaintext - STUB");
    auto it = pending_plaintexts_.find(recipient_id);
    return (it != pending_plaintexts_.end()) ? it->second : "";
}

void CryptoEngine::clearPendingPlaintext(const std::string& recipient_id) {
    LOGD("clearPendingPlaintext - STUB");
    pending_plaintexts_.erase(recipient_id);
}

void CryptoEngine::setupSignalCrypto(signal_context* ctx) {
    LOGD("setupSignalCrypto - STUB");
}

bool CryptoEngine::loadOrGenerateIdentity(const std::string& passphrase) {
    LOGD("loadOrGenerateIdentity - STUB");
    return true;
}

bool CryptoEngine::generateIdentity(const std::string& passphrase) {
    LOGD("generateIdentity - STUB");
    return true;
}

std::vector<uint8_t> CryptoEngine::encryptIdentityPriv(const std::array<uint8_t, 64>& priv_key,
                                                        const std::string& passphrase,
                                                        std::vector<uint8_t>& salt_out) {
    LOGD("encryptIdentityPriv - STUB");
    salt_out = std::vector<uint8_t>(16, 0);
    return std::vector<uint8_t>(priv_key.begin(), priv_key.end());
}

bool CryptoEngine::decryptIdentityPriv(const std::vector<uint8_t>& ciphertext,
                                        const std::vector<uint8_t>& salt,
                                        const std::string& passphrase,
                                        std::array<uint8_t, 64>& priv_out) {
    LOGD("decryptIdentityPriv - STUB");
    priv_out.fill(0);
    return true;
}

void CryptoEngine::setupStores(signal_protocol_store_context* store_ctx, CryptoEngine* engine) {
    LOGD("setupStores - STUB");
}

} // namespace ircord::crypto
