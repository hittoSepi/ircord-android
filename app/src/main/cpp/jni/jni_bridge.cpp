#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <android/log.h>

#include "crypto/crypto_engine.hpp"

#define LOG_TAG "IRCordNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace ircord::crypto;

// Global engine instance (singleton for now)
static std::unique_ptr<CryptoEngine> g_engine;

// ============================================================================
// JNI Store Implementation
// ============================================================================

class JNIStore : public IStore {
public:
    JNIStore(JNIEnv* env, jobject store_obj) : env_(env) {
        store_obj_ = env->NewGlobalRef(store_obj);
        
        jclass cls = env->GetObjectClass(store_obj);
        
        // Cache method IDs
        saveIdentity_mid = env->GetMethodID(cls, "saveIdentity", 
            "(Ljava/lang/String;[B[B[B)V");
        loadIdentity_mid = env->GetMethodID(cls, "loadIdentity",
            "(Ljava/lang/String;)[B");
        saveSession_mid = env->GetMethodID(cls, "saveSession",
            "(Ljava/lang/String;[B)V");
        loadSession_mid = env->GetMethodID(cls, "loadSession",
            "(Ljava/lang/String;)[B");
        savePreKey_mid = env->GetMethodID(cls, "savePreKey",
            "(I[B)V");
        loadPreKey_mid = env->GetMethodID(cls, "loadPreKey",
            "(I)[B");
        removePreKey_mid = env->GetMethodID(cls, "removePreKey",
            "(I)V");
        saveSignedPreKey_mid = env->GetMethodID(cls, "saveSignedPreKey",
            "(I[B)V");
        loadSignedPreKey_mid = env->GetMethodID(cls, "loadSignedPreKey",
            "(I)[B");
        savePeerIdentity_mid = env->GetMethodID(cls, "savePeerIdentity",
            "(Ljava/lang/String;[B)V");
        loadPeerIdentity_mid = env->GetMethodID(cls, "loadPeerIdentity",
            "(Ljava/lang/String;)[B");
        saveSenderKey_mid = env->GetMethodID(cls, "saveSenderKey",
            "(Ljava/lang/String;[B)V");
        loadSenderKey_mid = env->GetMethodID(cls, "loadSenderKey",
            "(Ljava/lang/String;)[B");
    }
    
    ~JNIStore() {
        if (env_ && store_obj_) {
            env_->DeleteGlobalRef(store_obj_);
        }
    }

    void saveIdentity(const std::string& user_id, 
                      const std::vector<uint8_t>& pub_key,
                      const std::vector<uint8_t>& priv_key_encrypted,
                      const std::vector<uint8_t>& salt) override {
        jstring juser = env_->NewStringUTF(user_id.c_str());
        jbyteArray jpub = toByteArray(pub_key);
        jbyteArray jpriv = toByteArray(priv_key_encrypted);
        jbyteArray jsalt = toByteArray(salt);
        env_->CallVoidMethod(store_obj_, saveIdentity_mid, juser, jpub, jpriv, jsalt);
        env_->DeleteLocalRef(juser);
        env_->DeleteLocalRef(jpub);
        env_->DeleteLocalRef(jpriv);
        env_->DeleteLocalRef(jsalt);
    }
    
    bool loadIdentity(const std::string& user_id,
                      std::vector<uint8_t>& pub_key_out,
                      std::vector<uint8_t>& priv_key_encrypted_out,
                      std::vector<uint8_t>& salt_out) override {
        jstring juser = env_->NewStringUTF(user_id.c_str());
        jbyteArray result = (jbyteArray)env_->CallObjectMethod(store_obj_, 
                                                               loadIdentity_mid, juser);
        env_->DeleteLocalRef(juser);
        
        if (!result) return false;
        
        // Format: [4 bytes pub_len LE] [4 bytes salt_len LE] [salt] [pub_key] [priv_encrypted]
        jsize len = env_->GetArrayLength(result);
        if (len < 8) {
            env_->DeleteLocalRef(result);
            return false;
        }
        
        jbyte* data = env_->GetByteArrayElements(result, nullptr);
        
        uint32_t pub_len = *reinterpret_cast<const uint32_t*>(data);
        uint32_t salt_len = *reinterpret_cast<const uint32_t*>(data + 4);
        
        if (len < 8 + salt_len + pub_len) {
            env_->ReleaseByteArrayElements(result, data, JNI_ABORT);
            env_->DeleteLocalRef(result);
            return false;
        }
        
        salt_out.assign(reinterpret_cast<const uint8_t*>(data) + 8,
                        reinterpret_cast<const uint8_t*>(data) + 8 + salt_len);
        pub_key_out.assign(reinterpret_cast<const uint8_t*>(data) + 8 + salt_len,
                           reinterpret_cast<const uint8_t*>(data) + 8 + salt_len + pub_len);
        priv_key_encrypted_out.assign(reinterpret_cast<const uint8_t*>(data) + 8 + salt_len + pub_len,
                                      reinterpret_cast<const uint8_t*>(data) + len);
        
        env_->ReleaseByteArrayElements(result, data, JNI_ABORT);
        env_->DeleteLocalRef(result);
        return true;
    }

    void saveSession(const std::string& address, const std::vector<uint8_t>& record) override {
        jstring jaddr = env_->NewStringUTF(address.c_str());
        jbyteArray jrecord = toByteArray(record);
        env_->CallVoidMethod(store_obj_, saveSession_mid, jaddr, jrecord);
        env_->DeleteLocalRef(jaddr);
        env_->DeleteLocalRef(jrecord);
    }
    
    std::vector<uint8_t> loadSession(const std::string& address) override {
        jstring jaddr = env_->NewStringUTF(address.c_str());
        jbyteArray result = (jbyteArray)env_->CallObjectMethod(store_obj_, 
                                                               loadSession_mid, jaddr);
        env_->DeleteLocalRef(jaddr);
        return fromByteArray(result);
    }

    void savePreKey(uint32_t id, const std::vector<uint8_t>& record) override {
        jbyteArray jrecord = toByteArray(record);
        env_->CallVoidMethod(store_obj_, savePreKey_mid, (jint)id, jrecord);
        env_->DeleteLocalRef(jrecord);
    }
    
    std::vector<uint8_t> loadPreKey(uint32_t id) override {
        jbyteArray result = (jbyteArray)env_->CallObjectMethod(store_obj_, 
                                                               loadPreKey_mid, (jint)id);
        return fromByteArray(result);
    }
    
    void removePreKey(uint32_t id) override {
        env_->CallVoidMethod(store_obj_, removePreKey_mid, (jint)id);
    }

    void saveSignedPreKey(uint32_t id, const std::vector<uint8_t>& record) override {
        jbyteArray jrecord = toByteArray(record);
        env_->CallVoidMethod(store_obj_, saveSignedPreKey_mid, (jint)id, jrecord);
        env_->DeleteLocalRef(jrecord);
    }
    
    std::vector<uint8_t> loadSignedPreKey(uint32_t id) override {
        jbyteArray result = (jbyteArray)env_->CallObjectMethod(store_obj_,
                                                               loadSignedPreKey_mid, (jint)id);
        return fromByteArray(result);
    }

    void savePeerIdentity(const std::string& user_id, const std::vector<uint8_t>& pub_key) override {
        jstring juser = env_->NewStringUTF(user_id.c_str());
        jbyteArray jpub = toByteArray(pub_key);
        env_->CallVoidMethod(store_obj_, savePeerIdentity_mid, juser, jpub);
        env_->DeleteLocalRef(juser);
        env_->DeleteLocalRef(jpub);
    }
    
    std::vector<uint8_t> loadPeerIdentity(const std::string& user_id) override {
        jstring juser = env_->NewStringUTF(user_id.c_str());
        jbyteArray result = (jbyteArray)env_->CallObjectMethod(store_obj_,
                                                               loadPeerIdentity_mid, juser);
        env_->DeleteLocalRef(juser);
        return fromByteArray(result);
    }

    void saveSenderKey(const std::string& sender_key_id, const std::vector<uint8_t>& record) override {
        jstring jid = env_->NewStringUTF(sender_key_id.c_str());
        jbyteArray jrecord = toByteArray(record);
        env_->CallVoidMethod(store_obj_, saveSenderKey_mid, jid, jrecord);
        env_->DeleteLocalRef(jid);
        env_->DeleteLocalRef(jrecord);
    }
    
    std::vector<uint8_t> loadSenderKey(const std::string& sender_key_id) override {
        jstring jid = env_->NewStringUTF(sender_key_id.c_str());
        jbyteArray result = (jbyteArray)env_->CallObjectMethod(store_obj_,
                                                               loadSenderKey_mid, jid);
        env_->DeleteLocalRef(jid);
        return fromByteArray(result);
    }

private:
    JNIEnv* env_ = nullptr;
    jobject store_obj_ = nullptr;
    
    jmethodID saveIdentity_mid, loadIdentity_mid;
    jmethodID saveSession_mid, loadSession_mid;
    jmethodID savePreKey_mid, loadPreKey_mid, removePreKey_mid;
    jmethodID saveSignedPreKey_mid, loadSignedPreKey_mid;
    jmethodID savePeerIdentity_mid, loadPeerIdentity_mid;
    jmethodID saveSenderKey_mid, loadSenderKey_mid;
    
    jbyteArray toByteArray(const std::vector<uint8_t>& data) {
        jbyteArray arr = env_->NewByteArray(data.size());
        env_->SetByteArrayRegion(arr, 0, data.size(), 
                                 reinterpret_cast<const jbyte*>(data.data()));
        return arr;
    }
    
    std::vector<uint8_t> fromByteArray(jbyteArray arr) {
        if (!arr) return {};
        jsize len = env_->GetArrayLength(arr);
        std::vector<uint8_t> result(len);
        env_->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(result.data()));
        env_->DeleteLocalRef(arr);
        return result;
    }
};

// ============================================================================
// JNI Helper Functions
// ============================================================================

static std::string jbyteArrayToString(JNIEnv* env, jbyteArray arr) {
    if (!arr) return {};
    jsize len = env->GetArrayLength(arr);
    std::string result(len, '\0');
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(&result[0]));
    return result;
}

static std::vector<uint8_t> jbyteArrayToVector(JNIEnv* env, jbyteArray arr) {
    if (!arr) return {};
    jsize len = env->GetArrayLength(arr);
    std::vector<uint8_t> result(len);
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(result.data()));
    return result;
}

static jbyteArray vectorToJbyteArray(JNIEnv* env, const std::vector<uint8_t>& data) {
    if (data.empty()) return nullptr;
    jbyteArray arr = env->NewByteArray(data.size());
    env->SetByteArrayRegion(arr, 0, data.size(), 
                           reinterpret_cast<const jbyte*>(data.data()));
    return arr;
}

// Helper that always returns a valid byte array (empty if input is empty)
static jbyteArray vectorToJbyteArrayNonNull(JNIEnv* env, const std::vector<uint8_t>& data) {
    jbyteArray arr = env->NewByteArray(data.size());
    if (!data.empty()) {
        env->SetByteArrayRegion(arr, 0, data.size(), 
                               reinterpret_cast<const jbyte*>(data.data()));
    }
    return arr;
}

// ============================================================================
// JNI Exports
// ============================================================================

extern "C" {

JNIEXPORT jboolean JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_nativeInit(
        JNIEnv* env,
        jclass /*clazz*/,
        jobject store_obj,
        jstring user_id,
        jstring passphrase) {
    
    const char* user_cstr = env->GetStringUTFChars(user_id, nullptr);
    const char* pass_cstr = env->GetStringUTFChars(passphrase, nullptr);
    
    g_engine = std::make_unique<CryptoEngine>();
    
    // Create JNI store wrapper (stored in global for engine lifetime)
    // Note: In production, you'd want to handle JNIEnv attachment properly
    // across threads. This is simplified for the example.
    auto store = new JNIStore(env, store_obj);
    
    bool result = g_engine->init(store, user_cstr, pass_cstr);
    
    env->ReleaseStringUTFChars(user_id, user_cstr);
    env->ReleaseStringUTFChars(passphrase, pass_cstr);
    
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_prepareRegistration(
        JNIEnv* env,
        jclass /*clazz*/,
        jint numOpks) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return nullptr;
    }
    
    auto data = g_engine->prepareKeyUpload(numOpks);
    return vectorToJbyteArray(env, data);
}

JNIEXPORT jbyteArray JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_encrypt(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring recipientId,
        jbyteArray plaintext) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return nullptr;
    }
    
    const char* recipient = env->GetStringUTFChars(recipientId, nullptr);
    std::string plain = jbyteArrayToString(env, plaintext);
    
    auto result = g_engine->encrypt(recipient, plain);
    
    env->ReleaseStringUTFChars(recipientId, recipient);
    return vectorToJbyteArray(env, result);
}

JNIEXPORT jbyteArray JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_decrypt(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring senderId,
        jstring recipientId,
        jbyteArray ciphertext,
        jint type) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return nullptr;
    }
    
    const char* sender = env->GetStringUTFChars(senderId, nullptr);
    const char* recipient = env->GetStringUTFChars(recipientId, nullptr);
    std::vector<uint8_t> ct = jbyteArrayToVector(env, ciphertext);
    
    auto result = g_engine->decrypt(sender, recipient ? recipient : "", ct, type);
    
    env->ReleaseStringUTFChars(senderId, sender);
    if (recipient) env->ReleaseStringUTFChars(recipientId, recipient);
    
    if (!result.success) {
        return nullptr;
    }
    
    return vectorToJbyteArray(env, 
        std::vector<uint8_t>(result.plaintext.begin(), result.plaintext.end()));
}

JNIEXPORT void JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_onKeyBundle(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring recipientId,
        jbyteArray bundleData) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return;
    }
    
    const char* recipient = env->GetStringUTFChars(recipientId, nullptr);
    std::vector<uint8_t> bundle = jbyteArrayToVector(env, bundleData);
    
    g_engine->onKeyBundle(bundle, recipient);
    
    env->ReleaseStringUTFChars(recipientId, recipient);
}

JNIEXPORT jboolean JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_hasSession(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring recipientId) {
    if (!g_engine || !g_engine->ready()) {
        return JNI_FALSE;
    }
    
    const char* recipient = env->GetStringUTFChars(recipientId, nullptr);
    bool has = g_engine->hasSession(recipient);
    env->ReleaseStringUTFChars(recipientId, recipient);
    
    return has ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_initGroupSession(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring channelId,
        jobjectArray members) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return;
    }
    
    const char* channel = env->GetStringUTFChars(channelId, nullptr);
    
    jsize len = env->GetArrayLength(members);
    std::vector<std::string> member_list;
    for (jsize i = 0; i < len; i++) {
        jstring member = (jstring)env->GetObjectArrayElement(members, i);
        const char* mstr = env->GetStringUTFChars(member, nullptr);
        member_list.push_back(mstr);
        env->ReleaseStringUTFChars(member, mstr);
        env->DeleteLocalRef(member);
    }
    
    g_engine->initGroupSession(channel, member_list);
    
    env->ReleaseStringUTFChars(channelId, channel);
}

JNIEXPORT jbyteArray JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_encryptGroup(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring channelId,
        jbyteArray plaintext) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return nullptr;
    }
    
    const char* channel = env->GetStringUTFChars(channelId, nullptr);
    std::string plain = jbyteArrayToString(env, plaintext);
    
    auto result = g_engine->encryptGroup(channel, plain);
    
    env->ReleaseStringUTFChars(channelId, channel);
    return vectorToJbyteArray(env, result);
}

JNIEXPORT jbyteArray JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_decryptGroup(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring senderId,
        jstring channelId,
        jbyteArray ciphertext,
        jbyteArray skdm) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return nullptr;
    }
    
    const char* sender = env->GetStringUTFChars(senderId, nullptr);
    const char* channel = env->GetStringUTFChars(channelId, nullptr);
    std::vector<uint8_t> ct = jbyteArrayToVector(env, ciphertext);
    std::vector<uint8_t> skdm_vec = jbyteArrayToVector(env, skdm);
    
    auto result = g_engine->decryptGroup(sender, channel, ct, skdm_vec);
    
    env->ReleaseStringUTFChars(senderId, sender);
    env->ReleaseStringUTFChars(channelId, channel);
    
    return vectorToJbyteArray(env, result);
}

JNIEXPORT void JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_processSenderKeyDistribution(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring senderId,
        jstring channelId,
        jbyteArray skdm) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return;
    }
    
    const char* sender = env->GetStringUTFChars(senderId, nullptr);
    const char* channel = env->GetStringUTFChars(channelId, nullptr);
    std::vector<uint8_t> skdm_vec = jbyteArrayToVector(env, skdm);
    
    g_engine->processSenderKeyDistribution(sender, channel, skdm_vec);
    
    env->ReleaseStringUTFChars(senderId, sender);
    env->ReleaseStringUTFChars(channelId, channel);
}

JNIEXPORT jstring JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_safetyNumber(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring peerId) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return env->NewStringUTF("");
    }
    
    const char* peer = env->GetStringUTFChars(peerId, nullptr);
    std::string num = g_engine->safetyNumber(peer);
    env->ReleaseStringUTFChars(peerId, peer);
    
    return env->NewStringUTF(num.c_str());
}

JNIEXPORT jbyteArray JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_signChallenge(
        JNIEnv* env,
        jclass /*clazz*/,
        jbyteArray nonce) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return nullptr;
    }
    
    std::vector<uint8_t> nonce_vec = jbyteArrayToVector(env, nonce);
    auto result = g_engine->signChallenge(nonce_vec);
    
    return vectorToJbyteArray(env, result);
}

JNIEXPORT jbyteArray JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_identityPub(
        JNIEnv* env,
        jclass /*clazz*/) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return nullptr;
    }
    
    auto result = g_engine->identityPub();
    return vectorToJbyteArray(env, result);
}

JNIEXPORT jobject JNICALL
Java_fi_ircord_android_crypto_NativeCrypto_currentSpk(
        JNIEnv* env,
        jclass /*clazz*/) {
    if (!g_engine || !g_engine->ready()) {
        LOGE("CryptoEngine not initialized");
        return nullptr;
    }
    
    auto spk = g_engine->currentSpk();
    
    // Create SpkInfo class
    jclass spkClass = env->FindClass("fi/ircord/android/crypto/NativeCrypto$SpkInfo");
    jmethodID constructor = env->GetMethodID(spkClass, "<init>", "([B[BI)V");
    
    // Use non-null helper to avoid Kotlin non-null parameter violations
    jbyteArray jpub = vectorToJbyteArrayNonNull(env, spk.pub);
    jbyteArray jsig = vectorToJbyteArrayNonNull(env, spk.sig);
    
    jobject result = env->NewObject(spkClass, constructor, jpub, jsig, spk.id);
    
    env->DeleteLocalRef(jpub);
    env->DeleteLocalRef(jsig);
    env->DeleteLocalRef(spkClass);
    
    return result;
}

} // extern "C"
