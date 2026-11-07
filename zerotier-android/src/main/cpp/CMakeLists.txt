cmake_minimum_required(VERSION 3.22.1)
project(herdr_zt_jni LANGUAGES C CXX)

# Build the C API directly. Upstream's Android target forces its Java wrapper; that
# would create a second binding/runtime and has different callback signatures.
get_filename_component(LIBZT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libzt" ABSOLUTE)
set(ZTO "${LIBZT_ROOT}/ext/ZeroTierOne")
set(LWIP "${LIBZT_ROOT}/ext/lwip/src")
set(LWIP_PORT "${LIBZT_ROOT}/ext/lwip-contrib/ports/unix/port")
foreach(required "${LIBZT_ROOT}/include/ZeroTierSockets.h" "${ZTO}/node/Node.cpp" "${LWIP}/core/init.c" "${LWIP_PORT}/sys_arch.c")
    if(NOT EXISTS "${required}")
        message(FATAL_ERROR "Missing libzt/submodule source: ${required}")
    endif()
endforeach()

# Android skips upstream's getifaddrs fallback. A readable /proc/net/dev followed
# by failed/filtered address enumeration can leave an empty map while the success
# flag stays true, preventing even ordinary wildcard sockets. Fix only that empty
# result; preserve every successful binding and all system routing/VPN decisions.
# Keep the checked-out dependency unchanged and persist this small compatibility
# adjustment in our build, rather than relying on an ignored third_party edit.
set(BINDER_SOURCE "${ZTO}/osdep/Binder.hpp")
file(READ "${BINDER_SOURCE}" BINDER_TEXT)
set(BINDER_ANCHOR "// Default to binding to wildcard if we can't enumerate addresses")
string(FIND "${BINDER_TEXT}" "${BINDER_ANCHOR}" BINDER_ANCHOR_POSITION)
if(BINDER_ANCHOR_POSITION EQUAL -1)
    message(FATAL_ERROR "libzt Binder fallback changed; review the Android compatibility adjustment")
endif()
string(REPLACE "${BINDER_ANCHOR}"
    "#if defined(__ANDROID__)\n        if (localIfAddrs.empty()) { interfacesEnumerated = false; }\n#endif\n        ${BINDER_ANCHOR}"
    BINDER_TEXT "${BINDER_TEXT}")
set(BINDER_COMPAT_DIR "${CMAKE_CURRENT_BINARY_DIR}/herdr_compat")
file(MAKE_DIRECTORY "${BINDER_COMPAT_DIR}")
file(WRITE "${BINDER_COMPAT_DIR}/Binder.hpp" "${BINDER_TEXT}")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${BINDER_SOURCE}")

file(GLOB ZT_CORE CONFIGURE_DEPENDS "${ZTO}/node/*.cpp")
file(GLOB ZT_API CONFIGURE_DEPENDS "${LIBZT_ROOT}/src/*.cpp")
file(GLOB LWIP_SOURCES CONFIGURE_DEPENDS
    "${LWIP}/netif/*.c" "${LWIP}/api/*.c" "${LWIP}/core/*.c"
    "${LWIP}/core/ipv4/*.c" "${LWIP}/core/ipv6/*.c")
list(REMOVE_ITEM LWIP_SOURCES "${LWIP}/netif/slipif.c")
set(UPNP_NAMES connecthostport igd_desc_parse minisoap minissdpc miniupnpc miniwget
    minixml portlistingparse receivedata upnpcommands upnpdev upnperrors upnpreplyparse)
set(UPNP_SOURCES "")
foreach(name IN LISTS UPNP_NAMES)
    list(APPEND UPNP_SOURCES "${ZTO}/ext/miniupnpc/${name}.c")
endforeach()

add_library(herdr_zt_jni SHARED herdr_zt_jni.cpp ${ZT_CORE} ${ZT_API} ${LWIP_SOURCES}
    "${LWIP_PORT}/sys_arch.c" "${ZTO}/osdep/OSUtils.cpp" "${ZTO}/osdep/PortMapper.cpp"
    "${ZTO}/ext/libnatpmp/natpmp.c" "${ZTO}/ext/libnatpmp/getgateway.c" ${UPNP_SOURCES})
target_include_directories(herdr_zt_jni PRIVATE
    "${BINDER_COMPAT_DIR}"
    "${LIBZT_ROOT}/include" "${LIBZT_ROOT}/src" "${LIBZT_ROOT}/ext/concurrentqueue"
    "${ZTO}" "${ZTO}/include" "${ZTO}/node" "${ZTO}/osdep"
    "${ZTO}/ext/libnatpmp"
    "${ZTO}/ext/prometheus-cpp-lite-1.0/core/include"
    "${ZTO}/ext/prometheus-cpp-lite-1.0/simpleapi/include"
    "${LWIP}/include" "${LWIP_PORT}/include")
target_compile_features(herdr_zt_jni PRIVATE cxx_std_17)
target_compile_definitions(herdr_zt_jni PRIVATE ZT_SDK=1 ZT_USE_MINIUPNPC=1
    ZTS_DISABLE_CENTRAL_API=1 ZTS_NDK_ONLY=1 OMIT_JSON_SUPPORT=1
    _USING_LWIP_DEFINITIONS_=0 LWIP_DBG_TYPES_ON=0 NATPMP_EXPORTS MINIUPNP_STATICLIB
    MINIUPNPC_SET_SOCKET_TIMEOUT MINIUPNPC_GET_SRC_ADDR _DEFAULT_SOURCE
    MINIUPNPC_VERSION_STRING="2.0" UPNP_VERSION_STRING="UPnP/1.1" OS_STRING="Android")
get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
file(TO_NATIVE_PATH "${PROJECT_ROOT}" PROJECT_ROOT_NATIVE)
target_compile_options(herdr_zt_jni PRIVATE
    -fstack-protector-strong
    -Wno-unused-parameter
    "-ffile-prefix-map=${PROJECT_ROOT}=."
    "-fmacro-prefix-map=${PROJECT_ROOT}=."
    "-fdebug-prefix-map=${PROJECT_ROOT}=."
    "-ffile-prefix-map=${PROJECT_ROOT_NATIVE}=."
    "-fmacro-prefix-map=${PROJECT_ROOT_NATIVE}=."
    "-fdebug-prefix-map=${PROJECT_ROOT_NATIVE}=.")
target_link_options(herdr_zt_jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(herdr_zt_jni PRIVATE android log)
