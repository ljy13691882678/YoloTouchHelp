#include "qnn_engine.h"
#include "common.h"
#include <qnn/TFLiteDelegate/QnnTFLiteDelegate.h>
#include <dlfcn.h>
#include <cstdio>

//==============================================================================
//  Helper: Get native library directory
//==============================================================================
static const char* getNativeLibDir() {
    static char dir[512] = {0};
    if (dir[0]) return dir;
    Dl_info info;
    if (dladdr((void*)getNativeLibDir, &info)) {
        std::string libPath(info.dli_fname);
        size_t pos = libPath.find_last_of('/');
        if (pos != std::string::npos) {
            strncpy(dir, libPath.substr(0, pos).c_str(), sizeof(dir) - 1);
        }
    }
    return dir;
}

//==============================================================================
//  Hardware Detection
//==============================================================================
bool QnnEngine::isQualcommSnapdragon() {
    FILE* f = fopen("/proc/cpuinfo", "r");
    if (f) {
        char line[512];
        while (fgets(line, sizeof(line), f)) {
            if (strstr(line, "Qualcomm") || strstr(line, "qcom") || strstr(line, "Snapdragon")) {
                fclose(f);
                return true;
            }
        }
        fclose(f);
    }
    FILE* p = popen("getprop ro.hardware", "r");
    if (p) {
        char line[128];
        if (fgets(line, sizeof(line), p)) {
            if (strstr(line, "qcom")) {
                pclose(p);
                return true;
            }
        }
        pclose(p);
    }
    return false;
}

//==============================================================================
//  Lifecycle
//==============================================================================
QnnEngine::QnnEngine() = default;

QnnEngine::~QnnEngine() {
    deleteDelegate();
}

//==============================================================================
//  Build Delegate
//==============================================================================
TfLiteDelegate* QnnEngine::buildDelegate() {
    if (!isQualcommSnapdragon()) {
        LOGW("Non-Qualcomm CPU detected, skipping QNN HTP");
        return nullptr;
    }

    if (!m_preloaded) {
        void* h = dlopen("libcdsprpc.so", RTLD_NOW);
        if (h) LOGD("libcdsprpc.so preloaded");
        else   LOGW("libcdsprpc.so not available: %s", dlerror());

        h = dlopen("libadsprpc.so", RTLD_NOW);
        if (h) LOGD("libadsprpc.so preloaded");
        else   LOGW("libadsprpc.so not available: %s", dlerror());

        strncpy(m_native_lib_dir, getNativeLibDir(), sizeof(m_native_lib_dir) - 1);
        LOGD("Native lib dir: %s", m_native_lib_dir);
        m_preloaded = true;
    }

    TfLiteQnnDelegateOptions qnn_options = TfLiteQnnDelegateOptionsDefault();
    qnn_options.backend_type = kHtpBackend;
    qnn_options.skel_library_dir = m_native_lib_dir;
    qnn_options.cache_dir = "/data/data/com.xunlei.ai/cache/qnn";
    qnn_options.model_token = "yolov8n_htp_v1";

    m_delegate = TfLiteQnnDelegateCreate(&qnn_options);
    if (m_delegate) {
        LOGD("QNN HTP delegate created");
    } else {
        LOGW("QNN HTP delegate creation failed");
    }
    return m_delegate;
}

void QnnEngine::deleteDelegate() {
    if (m_delegate) {
        TfLiteQnnDelegateDelete(m_delegate);
        m_delegate = nullptr;
    }
}

