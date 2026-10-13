cmake_minimum_required(VERSION 3.22.1)

project(droidbox_jni C)

# The JNI bridge to the real QEMU system engine.
# It dlopen()s libqemu-system-x86_64.so (built separately from QEMU 11.1.2
# source with the NDK) at runtime. That .so is NOT built here — this target
# only builds the bridge, which refuses to start any VM if the engine .so
# is absent from the APK.
add_library(droidbox_jni SHARED
    vm_engine_jni.c
)

find_library(log-lib log)

target_link_libraries(droidbox_jni
    ${log-lib}
    dl
)

target_compile_options(droidbox_jni PRIVATE -Wall -Wextra)
