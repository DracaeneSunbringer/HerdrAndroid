#include <jni.h>
#include "ZeroTierSockets.h"
#include "World.hpp"
#include "Identity.hpp"
#include "NodeService.hpp"
#include "Mutex.hpp"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cerrno>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

// zts_node_stop() is asynchronous in this pinned libzt. Wait for its service
// destruction before re-initializing storage/identity. No vendored files are patched.
namespace ZeroTier { extern NodeService* zts_service; extern Mutex service_m; }
namespace {
using Clock = std::chrono::steady_clock;
std::mutex nodeMutex;
bool nodeStarted = false;
bool stopPending = false;
struct SocketState {
    explicit SocketState(int value) : fd(value) {}
    int fd;
    std::atomic<bool> closing{false};
    bool closed = false;
    int timeoutMs = 0;
    std::mutex mutex;
    std::condition_variable changed;
};
std::mutex socketMapMutex;
std::unordered_map<jlong, std::shared_ptr<SocketState>> sockets;
std::atomic<jlong> nextHandle{1};

void fail(JNIEnv* env, const char* type, const std::string& message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(type), message.c_str());
}
void ioError(JNIEnv* env, const std::string& message) { fail(env, "java/io/IOException", message); }
void socketError(JNIEnv* env, const std::string& action, int error) {
    fail(env, "java/net/SocketException", action + " (ZeroTier errno=" + std::to_string(error) + ")");
}
void connectError(JNIEnv* env, int error) {
    const char* type = "java/net/SocketException";
    if (error == ZTS_ECONNREFUSED) type = "java/net/ConnectException";
    else if (error == ZTS_ETIMEDOUT) type = "java/net/SocketTimeoutException";
    else if (error == ZTS_ENETUNREACH || error == ZTS_EHOSTUNREACH || error == ZTS_ENETDOWN) type = "java/net/NoRouteToHostException";
    fail(env, type, "ZeroTier connect failed (errno=" + std::to_string(error) + ")");
}
struct Utf {
    JNIEnv* env; jstring value; const char* chars;
    Utf(JNIEnv* e, jstring s) : env(e), value(s), chars(s ? e->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~Utf() { if (chars) env->ReleaseStringUTFChars(value, chars); }
};
std::shared_ptr<SocketState> lookup(JNIEnv* env, jlong handle) {
    std::lock_guard<std::mutex> lock(socketMapMutex);
    auto found = sockets.find(handle);
    if (found == sockets.end()) { socketError(env, "Socket is closed", ZTS_EBADF); return {}; }
    return found->second;
}
bool open(JNIEnv* env, const std::shared_ptr<SocketState>& state) {
    if (state->closing.load()) { socketError(env, "Socket is closed", ZTS_EBADF); return false; }
    return true;
}
bool range(JNIEnv* env, jbyteArray bytes, jint offset, jint length) {
    if (!bytes || offset < 0 || length < 0 || offset > env->GetArrayLength(bytes) - length) {
        fail(env, "java/lang/IndexOutOfBoundsException", "Invalid socket buffer range"); return false;
    }
    return true;
}
bool again(int error) { return error == ZTS_EAGAIN || error == ZTS_EWOULDBLOCK || error == ZTS_EINTR; }
// This lwIP port sets POSIX thread-local errno as well as a shared zts_errno.
// Read errno on this thread so simultaneous SSH reader/writer calls cannot race.
int lastError(int result) {
    if (result == ZTS_ERR_SERVICE) return ZTS_ENETDOWN;
    if (result == ZTS_ERR_ARG) return ZTS_EINVAL;
    return errno;
}
Clock::time_point deadline(int timeout) {
    return timeout == 0 ? Clock::time_point::max() : Clock::now() + std::chrono::milliseconds(timeout);
}
// Every lwIP operation is nonblocking. Wait outside the operation lock, so an idle
// reader cannot starve the SSH writer. close notifies the wait immediately; bounded
// 10ms polling detects lwIP readiness without closing/reusing any in-flight fd.
bool waitReady(JNIEnv* env, const std::shared_ptr<SocketState>& state, short events, Clock::time_point until) {
    while (true) {
        int slice = 10;
        if (until != Clock::time_point::max()) {
            auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(until - Clock::now()).count();
            if (remaining <= 0) { fail(env, "java/net/SocketTimeoutException", "ZeroTier socket timed out"); return false; }
            slice = static_cast<int>(std::min<int64_t>(slice, remaining));
        }
        std::unique_lock<std::mutex> lock(state->mutex);
        if (!open(env, state)) return false;
        zts_pollfd poll{state->fd, events, 0};
        int result = zts_bsd_poll(&poll, 1, 0);
        int error = lastError(result);
        if (result > 0) {
            if (poll.revents & ZTS_POLLNVAL) { socketError(env, "Invalid ZeroTier socket", ZTS_EBADF); return false; }
            if (poll.revents & (events | ZTS_POLLHUP | ZTS_POLLERR)) return true;
        } else if (result < 0 && !again(error)) { socketError(env, "ZeroTier poll failed", error); return false; }
        state->changed.wait_for(lock, std::chrono::milliseconds(slice), [&state] { return state->closing.load(); });
    }
}
void closeState(const std::shared_ptr<SocketState>& state) {
    state->closing.store(true);
    state->changed.notify_all();
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->closed) {
        state->closed = true;
        zts_bsd_shutdown(state->fd, ZTS_SHUT_RDWR);
        zts_bsd_close(state->fd);
    }
}
void closeAll() {
    std::vector<std::shared_ptr<SocketState>> current;
    { std::lock_guard<std::mutex> lock(socketMapMutex); for (auto& entry : sockets) current.push_back(entry.second); sockets.clear(); }
    for (const auto& state : current) closeState(state);
}
bool awaitStopped(JNIEnv* env) {
    if (!stopPending) return true;
    auto until = Clock::now() + std::chrono::seconds(5);
    do {
        bool gone;
        { ZeroTier::Mutex::Lock lock(ZeroTier::service_m); gone = ZeroTier::zts_service == nullptr; }
        if (gone) { stopPending = false; return true; }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    } while (Clock::now() < until);
    ioError(env, "ZeroTier node is still stopping; retry after shutdown completes"); return false;
}
int checkPlanet(JNIEnv* env, jbyteArray bytes, std::vector<uint8_t>& data) {
    if (!bytes || env->GetArrayLength(bytes) <= 0 || env->GetArrayLength(bytes) > ZTS_STORE_DATA_LEN) {
        ioError(env, "Planet binary size must be in 1..4096 bytes"); return 0;
    }
    data.resize(env->GetArrayLength(bytes));
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(data.size()), reinterpret_cast<jbyte*>(data.data()));
    if (env->ExceptionCheck()) return 0;
    try {
        ZeroTier::Buffer<ZT_WORLD_MAX_SERIALIZED_LENGTH> buffer;
        buffer.append(data.data(), static_cast<unsigned int>(data.size()));
        ZeroTier::World world;
        if (world.deserialize(buffer) != data.size() || world.type() != ZeroTier::World::TYPE_PLANET || world.roots().empty()) {
            ioError(env, "Invalid Planet world definition"); return 0;
        }
        for (const auto& root : world.roots()) {
            if (root.stableEndpoints.empty() || !root.identity.locallyValidate()) { ioError(env, "Invalid Planet root identity or endpoint"); return 0; }
        }
        return static_cast<int>(world.roots().size());
    } catch (...) { ioError(env, "Malformed Planet binary"); return 0; }
}
jobjectArray strings(JNIEnv* env, const std::vector<std::string>& values) {
    auto result = env->NewObjectArray(static_cast<jsize>(values.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < values.size(); ++i) {
        jstring value = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value); env->DeleteLocalRef(value);
    }
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_validatePlanet(JNIEnv* env, jobject, jbyteArray bytes) {
    std::vector<uint8_t> data; return checkPlanet(env, bytes, data);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_validateIdentity(JNIEnv* env, jobject, jstring text) {
    Utf value(env, text); if (!value.chars) return false;
    try {
        ZeroTier::Identity identity;
        if (!identity.fromString(value.chars) || !identity.hasPrivate() || !identity.locallyValidate()) return false;
        const char challenge[] = "Herdr identity import validation";
        auto signature = identity.sign(challenge, sizeof(challenge));
        return identity.verify(challenge, sizeof(challenge), signature);
    } catch (...) { return false; }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_startNode(JNIEnv* env, jobject, jstring path, jbyteArray planet) {
    std::lock_guard<std::mutex> lock(nodeMutex);
    if (nodeStarted) { ioError(env, "ZeroTier node is already started"); return; }
    if (!awaitStopped(env)) return;
    std::vector<uint8_t> roots; if (!checkPlanet(env, planet, roots)) return;
    Utf storage(env, path); if (!storage.chars) return;
    int result = zts_init_from_storage(storage.chars);
    if (result == ZTS_ERR_OK) result = zts_init_set_roots(roots.data(), static_cast<unsigned int>(roots.size()));
    // Poll control state in Kotlin; no callback thread or Java wrapper is required.
    if (result == ZTS_ERR_OK) result = zts_node_start();
    if (result != ZTS_ERR_OK) { ioError(env, "ZeroTier node initialization failed (code=" + std::to_string(result) + ")"); return; }
    nodeStarted = true;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_stopNode(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(nodeMutex);
    closeAll();
    if (nodeStarted) {
        zts_node_stop(); nodeStarted = false; stopPending = true;
    }
    awaitStopped(env); // Never zts_node_free(): the runtime must be reusable after import/reconnect.
}
extern "C" JNIEXPORT void JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_joinNetwork(JNIEnv* env, jobject, jlong networkId) {
    std::lock_guard<std::mutex> lock(nodeMutex);
    int result = nodeStarted ? zts_net_join(static_cast<uint64_t>(networkId)) : ZTS_ERR_SERVICE;
    if (result != ZTS_ERR_OK) ioError(env, "ZeroTier network join failed (code=" + std::to_string(result) + ")");
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_snapshot(JNIEnv* env, jobject, jlong networkId) {
    std::lock_guard<std::mutex> lock(nodeMutex);
    uint64_t id = nodeStarted ? zts_node_get_id() : 0;
    if (id > 0xffffffffffULL) id = 0; // pre-start C API error values are not node identities
    char node[16] = {}; if (id) snprintf(node, sizeof(node), "%010llx", static_cast<unsigned long long>(id));
    char ip[ZTS_IP_MAX_STR_LEN] = {};
    uint64_t network = static_cast<uint64_t>(networkId);
    bool online = nodeStarted && zts_node_is_online() == 1;
    bool ready = nodeStarted && zts_net_transport_is_ready(network) == 1;
    int status = nodeStarted ? zts_net_get_status(network) : -1;
    if (nodeStarted) zts_addr_get_str(network, ZTS_AF_INET, ip, sizeof(ip));
    return strings(env, {node, online ? "1" : "0", std::to_string(status), ready ? "1" : "0", ip, "", "0"});
}
extern "C" JNIEXPORT jstring JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_exportNodeIdentity(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(nodeMutex);
    char identity[ZT_IDENTITY_STRING_BUFFER_LENGTH] = {}; unsigned int length = sizeof(identity);
    if (!nodeStarted || zts_node_get_id_pair(identity, &length) != ZTS_ERR_OK) { ioError(env, "ZeroTier identity is not ready"); return nullptr; }
    identity[sizeof(identity) - 1] = 0;
    jstring result = env->NewStringUTF(identity);
    ZeroTier::Utils::burn(identity, sizeof(identity));
    return result;
}
extern "C" JNIEXPORT jlong JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_createSocket(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> nodeLock(nodeMutex);
    if (!nodeStarted) { ioError(env, "ZeroTier node is not running"); return 0; }
    int fd = zts_bsd_socket(ZTS_AF_INET, ZTS_SOCK_STREAM, 0);
    if (fd < 0) { socketError(env, "Create ZeroTier socket failed", lastError(fd)); return 0; }
    if (zts_bsd_fcntl(fd, ZTS_F_SETFL, ZTS_O_NONBLOCK) < 0) {
        int error = errno; zts_bsd_close(fd); socketError(env, "Set nonblocking mode failed", error); return 0;
    }
    jlong handle = nextHandle.fetch_add(1);
    { std::lock_guard<std::mutex> lock(socketMapMutex); sockets.emplace(handle, std::make_shared<SocketState>(fd)); }
    return handle;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_connectSocket(JNIEnv* env, jobject, jlong handle, jstring ip, jint port, jint timeout) {
    auto state = lookup(env, handle); if (!state) return;
    if (timeout < 0 || port < 1 || port > 65535) { ioError(env, "Invalid ZeroTier endpoint or timeout"); return; }
    Utf address(env, ip); if (!address.chars) return;
    zts_sockaddr_storage storage{}; zts_socklen_t length = sizeof(storage);
    if (zts_util_ipstr_to_saddr(address.chars, static_cast<unsigned short>(port), reinterpret_cast<zts_sockaddr*>(&storage), &length) != ZTS_ERR_OK) {
        ioError(env, "Invalid virtual IPv4 address"); return;
    }
    int result, error;
    {
        std::lock_guard<std::mutex> lock(state->mutex); if (!open(env, state)) return;
        result = zts_bsd_connect(state->fd, reinterpret_cast<zts_sockaddr*>(&storage), length); error = lastError(result);
    }
    if (result == 0) return;
    if (error != ZTS_EINPROGRESS && error != ZTS_EALREADY && !again(error)) { connectError(env, error); return; }
    if (!waitReady(env, state, ZTS_POLLOUT, deadline(timeout))) return;
    std::lock_guard<std::mutex> lock(state->mutex); if (!open(env, state)) return;
    int pending = 0; zts_socklen_t size = sizeof(pending);
    if (zts_bsd_getsockopt(state->fd, ZTS_SOL_SOCKET, ZTS_SO_ERROR, &pending, &size) < 0) pending = errno;
    if (pending != 0) connectError(env, pending);
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_readSocket(JNIEnv* env, jobject, jlong handle, jbyteArray bytes, jint offset, jint length) {
    if (!range(env, bytes, offset, length)) return -1;
    if (length == 0) return 0;
    auto state = lookup(env, handle); if (!state) return -1;
    int timeout;
    { std::lock_guard<std::mutex> lock(state->mutex); if (!open(env, state)) return -1; timeout = state->timeoutMs; }
    auto until = deadline(timeout);
    std::vector<jbyte> buffer(static_cast<size_t>(std::min(length, 16384)));
    while (true) {
        int result, error;
        {
            std::lock_guard<std::mutex> lock(state->mutex); if (!open(env, state)) return -1;
            result = static_cast<int>(zts_bsd_recv(state->fd, buffer.data(), buffer.size(), ZTS_MSG_DONTWAIT)); error = lastError(result);
        }
        if (result > 0) { env->SetByteArrayRegion(bytes, offset, result, buffer.data()); return result; }
        if (result == 0) return -1;
        if (!again(error)) { socketError(env, "ZeroTier read failed", error); return -1; }
        if (!waitReady(env, state, ZTS_POLLIN, until)) return -1;
    }
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_writeSocket(JNIEnv* env, jobject, jlong handle, jbyteArray bytes, jint offset, jint length) {
    if (!range(env, bytes, offset, length)) return -1;
    if (length == 0) return 0;
    auto state = lookup(env, handle); if (!state) return -1;
    std::vector<jbyte> buffer(static_cast<size_t>(std::min(length, 16384)));
    env->GetByteArrayRegion(bytes, offset, static_cast<jsize>(buffer.size()), buffer.data());
    if (env->ExceptionCheck()) return -1;
    while (true) {
        int result, error;
        {
            std::lock_guard<std::mutex> lock(state->mutex); if (!open(env, state)) return -1;
            result = static_cast<int>(zts_bsd_send(state->fd, buffer.data(), buffer.size(), ZTS_MSG_DONTWAIT)); error = lastError(result);
        }
        if (result > 0) return result; // Kotlin loops until the entire caller buffer is sent.
        if (result == 0) { socketError(env, "ZeroTier write made no progress", ZTS_ECONNRESET); return -1; }
        if (!again(error)) { socketError(env, "ZeroTier write failed", error); return -1; }
        if (!waitReady(env, state, ZTS_POLLOUT, Clock::time_point::max())) return -1;
    }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_closeSocket(JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<SocketState> state;
    { std::lock_guard<std::mutex> lock(socketMapMutex); auto found = sockets.find(handle); if (found == sockets.end()) return; state = found->second; sockets.erase(found); }
    closeState(state);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_shutdownSocket(JNIEnv* env, jobject, jlong handle, jint how) {
    auto state = lookup(env, handle); if (!state) return;
    std::lock_guard<std::mutex> lock(state->mutex); if (!open(env, state)) return;
    if (how < 0 || how > 2) { ioError(env, "Invalid shutdown direction"); return; }
    if (zts_bsd_shutdown(state->fd, how) < 0) socketError(env, "ZeroTier shutdown failed", errno);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_setReadTimeout(JNIEnv* env, jobject, jlong handle, jint timeout) {
    if (timeout < 0) { ioError(env, "Negative socket timeout"); return; }
    auto state = lookup(env, handle); if (!state) return;
    std::lock_guard<std::mutex> lock(state->mutex); if (!open(env, state)) return;
    if (zts_set_recv_timeout(state->fd, timeout / 1000, (timeout % 1000) * 1000) < 0) {
        socketError(env, "Set ZeroTier receive timeout failed", errno); return;
    }
    state->timeoutMs = timeout;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_setNoDelay(JNIEnv* env, jobject, jlong handle, jboolean enabled) {
    auto state = lookup(env, handle); if (!state) return;
    std::lock_guard<std::mutex> lock(state->mutex); if (!open(env, state)) return;
    if (zts_set_no_delay(state->fd, enabled ? 1 : 0) < 0) socketError(env, "Set ZeroTier TCP_NODELAY failed", errno);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_setKeepAlive(JNIEnv* env, jobject, jlong handle, jboolean enabled) {
    auto state = lookup(env, handle); if (!state) return;
    std::lock_guard<std::mutex> lock(state->mutex); if (!open(env, state)) return;
    int value = enabled ? 1 : 0;
    if (zts_bsd_setsockopt(state->fd, ZTS_SOL_SOCKET, ZTS_SO_KEEPALIVE, &value, sizeof(value)) < 0) socketError(env, "Set ZeroTier keepalive failed", errno);
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_herdr_android_zerotier_HerdrZeroTierNative_localEndpoint(JNIEnv* env, jobject, jlong handle) {
    auto state = lookup(env, handle); if (!state) return nullptr;
    std::lock_guard<std::mutex> lock(state->mutex); if (!open(env, state)) return nullptr;
    char address[ZTS_INET6_ADDRSTRLEN] = {}; unsigned short port = 0;
    if (zts_getsockname(state->fd, address, sizeof(address), &port) < 0) { socketError(env, "Get ZeroTier local endpoint failed", errno); return nullptr; }
    return strings(env, {address, std::to_string(port)});
}
