#pragma once

#include "inference_engine.h"
#include <onnxruntime_cxx_api.h>
#include <vector>
#include <string>
#include <memory>

class OnnxEngine : public InferenceEngine {
public:
    OnnxEngine();
    ~OnnxEngine() override;

    bool init(const char* model_path) override;
    void release() override;

    std::vector<Detection> detect(
        uint8_t* src,
        int offsetX, int offsetY,
        int regionWidth, int regionHeight,
        int screenWidth, int screenHeight,
        int rowStride, int pixelStride
    ) override;

    std::string getBackendType() const override { return "ONNX"; }
    bool isInitialized() const override { return m_initialized; }

private:
    // Preprocess: crop + resize RGBA → RGB float [0,1]
    void preprocess(const uint8_t* rgbaData,
                    int offsetX, int offsetY,
                    int regionWidth, int regionHeight,
                    int screenWidth, int screenHeight,
                    int rowStride, int pixelStride,
                    float* inputTensor);

    // Parse YOLOv8/v11 single output [1, featureDim, numAnchors]
    std::vector<Detection> parseYoloV8Output(
        const float* output, const std::vector<int64_t>& shape,
        int imgWidth, int imgHeight);

    // Parse multi-output YOLO format (3 scales)
    std::vector<Detection> parseMultiOutput(
        const std::vector<float*>& outputs,
        const std::vector<std::vector<int64_t>>& shapes,
        int imgWidth, int imgHeight);

    // DFL softmax helper
    static float softmaxCompute(const float* src, int n);

    std::unique_ptr<Ort::Env> m_env;
    std::unique_ptr<Ort::Session> m_session;
    std::unique_ptr<Ort::MemoryInfo> m_memoryInfo;
    Ort::SessionOptions m_sessionOptions{nullptr};

    std::string m_modelPath;
    bool m_initialized = false;

    // Input/output node names
    std::vector<const char*> m_inputNames;
    std::vector<const char*> m_outputNames;
    std::vector<std::vector<int64_t>> m_outputShapes;

    // Pre-allocated buffer
    std::vector<float> m_preprocessBuffer;
};