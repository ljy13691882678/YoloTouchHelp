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

    bool init(const char* modelPath, int imgWidth, int imgHeight,
              int cpuThreads, bool useGpu) override;
    bool detect(const uint8_t* rgbaData, int width, int height,
                std::vector<DetectionResult>& results) override;
    void release() override;
    BackendType getBackendType() const override { return BackendType::ONNX; }
    bool isInitialized() const override { return m_initialized; }

private:
    // Preprocess: RGBA → model input tensor
    void preprocess(const uint8_t* rgbaData, int srcWidth, int srcHeight,
                    float* inputTensor);

    // Postprocess: parse YOLO output
    void postprocess(const float* outputData, const std::vector<int64_t>& outputShape,
                     int srcWidth, int srcHeight,
                     std::vector<DetectionResult>& results);

    // NMS
    static void nms(std::vector<DetectionResult>& detections, float iouThreshold);

    // YOLO decode
    static std::vector<DetectionResult> decodeOutput(
        const float* output, int numAnchors, int numClasses,
        int imgWidth, int imgHeight, float confThreshold);

    // Parse YOLOv8/v11 output (single tensor [1, 84, 8400] or similar)
    std::vector<DetectionResult> parseYoloV8Output(
        const float* output, const std::vector<int64_t>& shape,
        int imgWidth, int imgHeight);

    // Parse multi-output YOLO format (3 outputs at different scales)
    std::vector<DetectionResult> parseMultiOutput(
        const std::vector<float*>& outputs,
        const std::vector<std::vector<int64_t>>& shapes,
        int imgWidth, int imgHeight);

    std::unique_ptr<Ort::Env> m_env;
    std::unique_ptr<Ort::Session> m_session;
    std::unique_ptr<Ort::MemoryInfo> m_memoryInfo;
    Ort::SessionOptions m_sessionOptions{nullptr};

    std::string m_modelPath;
    int m_inputWidth = 0;
    int m_inputHeight = 0;
    int m_numClasses = 0;
    bool m_initialized = false;

    // Input/output node names
    std::vector<const char*> m_inputNames;
    std::vector<const char*> m_outputNames;
    std::vector<std::vector<int64_t>> m_inputShapes;
    std::vector<std::vector<int64_t>> m_outputShapes;

    // Pre-allocated buffer
    std::vector<float> m_preprocessBuffer;
    std::vector<uint8_t> m_inputBuffer;
};