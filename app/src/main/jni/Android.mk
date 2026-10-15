# Нативная часть Zastra для ndk-build.
#   libhev-socks5-tunnel.so — tun2socks (hev-socks5-tunnel, MIT) с встроенным mapped-DNS;
#   libzastra-pac.so        — JNI-обёртка над QuickJS (quickjs-ng, MIT) для исполнения PAC-сценария.

ZASTRA_JNI := $(call my-dir)
HEV        := $(ZASTRA_JNI)/third_party/hev-socks5-tunnel

# ---------- статические зависимости hev-socks5-tunnel ----------
include $(HEV)/third-part/yaml/Android.mk
include $(HEV)/third-part/lwip/Android.mk
include $(HEV)/third-part/hev-task-system/Android.mk

# ---------- libhev-socks5-tunnel.so ----------
LOCAL_PATH := $(HEV)
SRCDIR     := $(LOCAL_PATH)/src
REV_ID     := vendored
include $(LOCAL_PATH)/build.mk

HEV_SOCKS5_TUNNEL_SRC := $(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))
HEV_SOCKS5_TUNNEL_INCLUDES := \
    $(LOCAL_PATH)/src \
    $(LOCAL_PATH)/src/misc \
    $(LOCAL_PATH)/src/core/include \
    $(LOCAL_PATH)/third-part/yaml/include \
    $(LOCAL_PATH)/third-part/lwip/src/include \
    $(LOCAL_PATH)/third-part/lwip/src/ports/include \
    $(LOCAL_PATH)/third-part/hev-task-system/include

include $(CLEAR_VARS)
LOCAL_MODULE := hev-socks5-tunnel
LOCAL_SRC_FILES := $(HEV_SOCKS5_TUNNEL_SRC)
LOCAL_C_INCLUDES := $(HEV_SOCKS5_TUNNEL_INCLUDES)
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY
LOCAL_CFLAGS += -fno-stack-clash-protection $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)

# ---------- libzastra-pac.so (QuickJS + JNI) ----------
LOCAL_PATH := $(ZASTRA_JNI)
QJS := third_party/quickjs-ng

include $(CLEAR_VARS)
LOCAL_MODULE := zastra-pac
LOCAL_SRC_FILES := \
    zastra_pac.c \
    $(QJS)/quickjs.c \
    $(QJS)/cutils.c \
    $(QJS)/libregexp.c \
    $(QJS)/libunicode.c \
    $(QJS)/xsum.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(QJS)
LOCAL_CFLAGS += -O2 -D_GNU_SOURCE -funsigned-char -fwrapv
LOCAL_CFLAGS += -Wno-implicit-fallthrough -Wno-sign-compare -Wno-unused-parameter
LOCAL_CFLAGS += -Wno-missing-field-initializers -Wno-unused-but-set-variable -Wno-unused-result
LOCAL_LDLIBS += -llog -lm
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
