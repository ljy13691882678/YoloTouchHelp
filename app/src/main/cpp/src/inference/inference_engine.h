#pragma once

#include "common.h"
#include <string>

enum class BackendType {
    NCNN,
    LITERT,
    MEDIATEK,
    ONNX,
    UNKNOWN
};

class InferenceEngine {
public:
    virtual ~InferenceEngine() = default;

    virtual bool init(const char* model_path) = 0;
    virtual void release() = 0;

    virtual std::vector<Detection> detect(
        uint8_t* src,
        int offsetX, int offsetY,
        int regionWidth, int regionHeight,
        int screenWidth, int screenHeight,
        int rowStride, int pixelStride
    ) = 0;

    virtual std::string getBackendType() const = 0;
    virtual bool isInitialized() const = 0;

    virtual void setConfidence(float threshold) { m_conf_thresh = threshold; }
    virtual void setInputSize(int width, int height) {
        m_input_width = width;
        m_input_height = height;
    }
    virtual void setForceCpu(bool force) { m_force_cpu = force; }
    virtual void setCpuThreads(int threads) { m_cpu_threads = threads; }

    static bool isNcnnModel(const char* model_path) {
        std::string path(model_path);
        return path.size() >= 6 && path.substr(path.size() - 6) == ".param";
    }

    static bool isOnnxModel(const char* model_path) {
        std::string path(model_path);
        size_t len = path.size();
        return (len >= 5 && (path.substr(len - 5) == ".onnx" ||
                             path.substr(len - 5) == ".ONNX"));
    }

protected:
    int m_input_width = 256;
    int m_input_height = 256;
    bool m_input_nhwc = false;
    int m_num_outputs = 1344;
    int m_num_classes = 1;
    float m_conf_thresh = 0.25f;
    bool m_force_cpu = false;
    int m_cpu_threads = 4;
};
