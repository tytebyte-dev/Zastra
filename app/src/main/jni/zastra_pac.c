/*
 * Zastra — JNI-обёртка над QuickJS для исполнения PAC-сценария.
 *
 * Класс-владелец: app.zastra.vpn.core.pac.PacNative (Kotlin object, @JvmStatic external).
 * Контекст QuickJS НЕ потокобезопасен: Kotlin-слой вызывает все функции из одного
 * выделенного потока (см. PacEngine).
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "quickjs.h"

typedef struct {
    JSRuntime *rt;
    JSContext *ctx;
} PacCtx;

static void throw_runtime(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls) {
        (*env)->ThrowNew(env, cls, msg ? msg : "unknown error");
        (*env)->DeleteLocalRef(env, cls);
    }
}

/* Возвращает malloc'нутую строку с описанием текущего исключения JS (или NULL). */
static char *describe_exception(JSContext *ctx) {
    JSValue exc = JS_GetException(ctx);
    const char *msg = JS_ToCString(ctx, exc);
    char *out = NULL;
    if (msg) {
        size_t n = strlen(msg);
        out = (char *)malloc(n + 1);
        if (out) memcpy(out, msg, n + 1);
        JS_FreeCString(ctx, msg);
    }
    JS_FreeValue(ctx, exc);
    return out;
}

JNIEXPORT jlong JNICALL
Java_app_zastra_vpn_core_pac_PacNative_create(JNIEnv *env, jclass cls) {
    PacCtx *p = (PacCtx *)calloc(1, sizeof(PacCtx));
    if (!p) {
        throw_runtime(env, "out of memory");
        return 0;
    }
    p->rt = JS_NewRuntime();
    if (!p->rt) {
        free(p);
        throw_runtime(env, "JS_NewRuntime failed");
        return 0;
    }
    JS_SetMemoryLimit(p->rt, (size_t)384 * 1024 * 1024);
    JS_SetMaxStackSize(p->rt, (size_t)2 * 1024 * 1024);
    p->ctx = JS_NewContext(p->rt);
    if (!p->ctx) {
        JS_FreeRuntime(p->rt);
        free(p);
        throw_runtime(env, "JS_NewContext failed");
        return 0;
    }
    return (jlong)(intptr_t)p;
}

JNIEXPORT void JNICALL
Java_app_zastra_vpn_core_pac_PacNative_destroy(JNIEnv *env, jclass cls, jlong handle) {
    PacCtx *p = (PacCtx *)(intptr_t)handle;
    if (!p) return;
    if (p->ctx) JS_FreeContext(p->ctx);
    if (p->rt) JS_FreeRuntime(p->rt);
    free(p);
}

/* Выполняет JS-код в глобальном контексте и возвращает результат, приведённый к строке
 * (undefined/null -> null). При ошибке JS бросает RuntimeException с описанием. */
JNIEXPORT jstring JNICALL
Java_app_zastra_vpn_core_pac_PacNative_eval(JNIEnv *env, jclass cls, jlong handle,
                                            jstring code, jstring name) {
    PacCtx *p = (PacCtx *)(intptr_t)handle;
    if (!p || !p->ctx) {
        throw_runtime(env, "PAC context is closed");
        return NULL;
    }
    const char *src = (*env)->GetStringUTFChars(env, code, NULL);
    if (!src) return NULL; /* OutOfMemoryError уже выставлен */
    const char *fname = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;

    JS_UpdateStackTop(p->rt);
    JSValue v = JS_Eval(p->ctx, src, strlen(src), fname ? fname : "<pac>", JS_EVAL_TYPE_GLOBAL);

    (*env)->ReleaseStringUTFChars(env, code, src);
    if (fname) (*env)->ReleaseStringUTFChars(env, name, fname);

    if (JS_IsException(v)) {
        char *desc = describe_exception(p->ctx);
        throw_runtime(env, desc ? desc : "JavaScript error");
        free(desc);
        return NULL;
    }

    jstring result = NULL;
    if (!JS_IsUndefined(v) && !JS_IsNull(v)) {
        const char *s = JS_ToCString(p->ctx, v);
        if (s) {
            result = (*env)->NewStringUTF(env, s);
            JS_FreeCString(p->ctx, s);
        }
    }
    JS_FreeValue(p->ctx, v);
    return result;
}
