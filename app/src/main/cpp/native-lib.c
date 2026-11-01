#include <stdlib.h>
#include <string.h>
#include <netdb.h>
#include <unistd.h>
#include <sys/socket.h>

#include <jni.h>
#include <android/log.h>

#include "byedpi/error.h"
#include "byedpi/proxy.h"
#include "byedpi/params.h"
#include "byedpi/packets.h"
#include "main.h"
#include "utils.h"

JNIEXPORT jint JNI_OnLoad(
        __attribute__((unused)) JavaVM *vm,
        __attribute__((unused)) void *reserved) {
    save_defaults();
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_sametgkte_gktedpi_core_ByeDpiProxy_jniCreateSocketWithCommandLine(
        JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jobjectArray args) {
    save_defaults();
    reset_params();

    int argc = (*env)->GetArrayLength(env, args);
    if (argc <= 0) {
        return -1;
    }

    char **argv = calloc((size_t)argc, sizeof(char *));
    if (!argv) {
        return -1;
    }

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        argv[i] = strdup(arg_str ? arg_str : "");
        (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        (*env)->DeleteLocalRef(env, arg);
    }

    store_allocated_args(argv, argc);

    int res = parse_args(argc, argv);
    if (res < 0) {
        uniperror("parse_args");
        reset_params();
        return -1;
    }

    int fd = listen_socket((struct sockaddr_ina *)&params.laddr);
    if (fd < 0) {
        uniperror("listen_socket");
        reset_params();
        return -1;
    }
    LOG(LOG_S, "GkTeDPI listen_socket, fd: %d", fd);

    return fd;
}

JNIEXPORT jint JNICALL
Java_com_sametgkte_gktedpi_core_ByeDpiProxy_jniStartProxy(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jint fd) {
    LOG(LOG_S, "GkTeDPI start_proxy, fd: %d", fd);
    NOT_EXIT = 1;
    int rc = event_loop(fd);
    reset_params();
    if (rc < 0) {
        uniperror("event_loop");
        return get_e();
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_sametgkte_gktedpi_core_ByeDpiProxy_jniStopProxy(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jint fd) {
    LOG(LOG_S, "GkTeDPI stop_proxy, fd: %d", fd);
    NOT_EXIT = 0;
    int res = shutdown(fd, SHUT_RDWR);
    if (res < 0) {
        uniperror("shutdown");
        return get_e();
    }
    return 0;
}
