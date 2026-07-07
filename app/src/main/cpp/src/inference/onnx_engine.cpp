#include "onnx_engine.h"
#include "common.h"
#include <cmath>
#include <cstring>
#include <algorithm>
#include <numeric>

#ifdef ANDROID
#include <android/log.h>
#define ONNX_LOG(fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, "OnnxEngine", fmt, ##__VA_ARGS__)
#define ONNX_ERR(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, "OnnxEngine", fmt, ##__VA_ARGS__)
#else
#define ONNX_LOG(fmt, ...) printf("[OnnxEngine] " fmt "\n", ##__VA_ARGS__)
#define ONNX_ERR(fmt, ...) fprintf(stderr, "[OnnxEngine] " fmt "\n", ##__VA_ARGS__)
#endif

OnnxEngine::OnnxEngine() = default;

OnnxEngine::~OnnxEngine() {
    release();
}

bool OnnxEngine::init(const char* modelPath, int imgWidth, int imgHeight,
                      int cpuThreads, bool useGpu) {
    release();

    m_inputWidth = imgWidth;
    m_inputHeight = imgHeight;
    m_modelPath = modelPath;

    try {
        // Create ONNX Runtime environment
        m_env = std::make_unique<Ort::Env>(OrtLoggingLevel::ORT_LOGGING_LEVEL_WARNING, "OnnxEngine");

        // Configure session options
        Ort::SessionOptions sessionOptions;

        // Set CPU threads
        if (cpuThreads > 0) {
            sessionOptions.SetIntraOpNumThreads(cpuThreads);
        }

        // Set execution mode to parallel
        sessionOptions.SetExecutionMode(ExecutionMode::ORT_PARALLEL);

        // Graph optimization level
        sessionOptions.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

        // Try GPU providers if requested
        if (useGpu) {
            // Try XNNPACK (CPU optimization for ARM — widely available)
            try {
                Ort::ThrowOnError(OrtSessionOptionsAppendExecutionProvider_XNNPACK(sessionOptions));
                ONNX_LOG("XNNPACK provider enabled");
            } catch (const std::exception& e) {
                ONNX_LOG("XNNPACK not available: %s", e.what());
            }
        }

        // Disable memory pattern optimization for better memory usage on mobile
        sessionOptions.DisableMemPattern();

        // Create session
        m_session = std::make_unique<Ort::Session>(*m_env, modelPath, sessionOptions);

        // Create memory info
        m_memoryInfo = std::make_unique<Ort::MemoryInfo>(
            Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault));

        // Get input/output info
        Ort::AllocatorWithDefaultOptions allocator;
        size_t numInputNodes = m_session->GetInputCount();
        size_t numOutputNodes = m_session->GetOutputCount();

        ONNX_LOG("Model: %zu inputs, %zu outputs", numInputNodes, numOutputNodes);

        m_inputNames.clear();
        m_inputShapes.clear();
        for (size_t i = 0; i < numInputNodes; i++) {
            auto name = m_session->GetInputNameAllocated(i, allocator);
            m_inputNames.push_back(strdup(name.get()));
            auto typeInfo = m_session->GetInputTypeInfo(i);
            auto tensorInfo = typeInfo.GetTensorTypeAndShapeInfo();
            auto shape = tensorInfo.GetShape();
            m_inputShapes.push_back(shape);

            ONNX_LOG("  Input[%zu] '%s': shape=[", i, m_inputNames[i]);
            for (size_t j = 0; j < shape.size(); j++) {
                ONNX_LOG("%s%lld", j > 0 ? ", " : "", (long long)shape[j]);
            }
            ONNX_LOG("]");
        }

        m_outputNames.clear();
        m_outputShapes.clear();
        for (size_t i = 0; i < numOutputNodes; i++) {
            auto name = m_session->GetOutputNameAllocated(i, allocator);
            m_outputNames.push_back(strdup(name.get()));
            auto typeInfo = m_session->GetOutputTypeInfo(i);
            auto tensorInfo = typeInfo.GetTensorTypeAndShapeInfo();
            auto shape = tensorInfo.GetShape();
            m_outputShapes.push_back(shape);

            ONNX_LOG("  Output[%zu] '%s': shape=[", i, m_outputNames[i]);
            for (size_t j = 0; j < shape.size(); j++) {
                ONNX_LOG("%s%lld", j > 0 ? ", " : "", (long long)shape[j]);
            }
            ONNX_LOG("]");
        }

        // Determine number of classes from output shape
        if (!m_outputShapes.empty()) {
            auto& outShape = m_outputShapes[0];
            if (outShape.size() >= 2) {
                int64_t featureDim = outShape[outShape.size() - 1];
                int64_t numAnchors = outShape[outShape.size() - 2];

                // YOLOv8 format: [1, 84, 8400] → 84 = 4 bbox + 80 classes
                // YOLOv11 format: similar
                // For [1, 84, 8400]: outShape[1] = 84, outShape[2] = 8400
                const int reg_max_1 = 16;
                if (featureDim > reg_max_1 * 4) {
                    m_numClasses = featureDim - reg_max_1 * 4;
                } else if (featureDim > 4) {
                    m_numClasses = featureDim - 4;
                } else {
                    m_numClasses = 1;
                }
            }
        }

        if (m_numClasses <= 0) m_numClasses = 1;

        // Pre-allocate preprocessing buffer
        m_preprocessBuffer.resize(m_inputWidth * m_inputHeight * 3);
        m_inputBuffer.resize(m_inputWidth * m_inputHeight * 3 * sizeof(float));

        m_initialized = true;
        ONNX_LOG("ONNX initialized OK: %s, input=%dx%d, %d classes, %zu threads",
                 modelPath, m_inputWidth, m_inputHeight, m_numClasses,
                 cpuThreads);
        return true;
    } catch (const std::exception& e) {
        ONNX_ERR("ONNX init failed: %s", e.what());
        release();
        return false;
    }
}

void OnnxEngine::release() {
    // Free input/output name strings
    for (auto& name : m_inputNames) {
        if (name) free(const_cast<char*>(name));
    }
    m_inputNames.clear();
    for (auto& name : m_outputNames) {
        if (name) free(const_cast<char*>(name));
    }
    m_outputNames.clear();

    m_session.reset();
    m_memoryInfo.reset();
    m_env.reset();
    m_initialized = false;
}

void OnnxEngine::preprocess(const uint8_t* rgbaData, int srcWidth, int srcHeight,
                            float* inputTensor) {
    // RGBA → RGB, resize via bilinear interpolation, normalize to [0,1]
    int dstW = m_inputWidth;
    int dstH = m_inputHeight;

    float scaleX = (float)srcWidth / dstW;
    float scaleY = (float)srcHeight / dstH;

    for (int dy = 0; dy < dstH; dy++) {
        for (int dx = 0; dx < dstW; dx++) {
            // Source coordinate (center-aligned)
            float sx = (dx + 0.5f) * scaleX - 0.5f;
            float sy = (dy + 0.5f) * scaleY - 0.5f;

            int x0 = (int)std::floor(sx);
            int y0 = (int)std::floor(sy);
            int x1 = x0 + 1;
            int y1 = y0 + 1;

            x0 = std::max(0, std::min(x0, srcWidth - 1));
            y0 = std::max(0, std::min(y0, srcHeight - 1));
            x1 = std::max(0, std::min(x1, srcWidth - 1));
            y1 = std::max(0, std::min(y1, srcHeight - 1));

            float wx1 = sx - x0;
            float wy1 = sy - y0;
            float wx0 = 1.0f - wx1;
            float wy0 = 1.0f - wy1;

            // Bilinear interpolation for each channel
            for (int c = 0; c < 3; c++) {
                float val = wx0 * wy0 * rgbaData[(y0 * srcWidth + x0) * 4 + c] +
                            wx1 * wy0 * rgbaData[(y0 * srcWidth + x1) * 4 + c] +
                            wx0 * wy1 * rgbaData[(y1 * srcWidth + x0) * 4 + c] +
                            wx1 * wy1 * rgbaData[(y1 * srcWidth + x1) * 4 + c];
                // Normalize to [0, 1]
                inputTensor[c * dstH * dstW + dy * dstW + dx] = val / 255.0f;
            }
        }
    }
}

// Softmax for DFL
static inline float softmax_compute(const float* src, int n) {
    float max_val = -1e9f;
    for (int i = 0; i < n; i++) {
        if (src[i] > max_val) max_val = src[i];
    }
    float sum = 0.0f;
    for (int i = 0; i < n; i++) {
        sum += expf(src[i] - max_val);
    }
    float sum_inv = 1.0f / (sum + 1e-8f);
    float result = 0.0f;
    for (int i = 0; i < n; i++) {
        result += i * expf(src[i] - max_val) * sum_inv;
    }
    return result;
}

static inline float sigmoidFn(float x) {
    return 1.0f / (1.0f + expf(-x));
}

std::vector<DetectionResult> OnnxEngine::parseYoloV8Output(
    const float* output, const std::vector<int64_t>& shape,
    int imgWidth, int imgHeight) {
    std::vector<DetectionResult> detections;

    if (shape.size() < 2) return detections;

    // Shape is typically [1, feature_dim, num_anchors] or [1, num_anchors, feature_dim]
    // Try to detect which layout
    int64_t dim1 = shape[1];
    int64_t dim2 = shape[2];

    int featureDim, numAnchors;
    bool transposed = false;

    const int reg_max_1 = 16;
    if (dim1 > dim2) {
        // [1, feature_dim, num_anchors] like [1, 84, 8400]
        featureDim = dim1;
        numAnchors = dim2;
        transposed = false;
    } else {
        // [1, num_anchors, feature_dim]
        featureDim = dim2;
        numAnchors = dim1;
        transposed = true;
    }

    int numClass = featureDim - reg_max_1 * 4;
    if (numClass < 1) numClass = 1;

    std::vector<int> strides = {8, 16, 32};
    int pred_row_offset = 0;

    for (size_t s = 0; s < strides.size(); s++) {
        int stride = strides[s];
        int num_grid_x = imgWidth / stride;
        int num_grid_y = imgHeight / stride;
        int num_grid = num_grid_x * num_grid_y;

        for (int i = 0; i < num_grid && i < numAnchors; i++) {
            int row_idx = pred_row_offset + i;

            // Find max class score
            int label = -1;
            float score = -1e9f;
            for (int c = 0; c < numClass; c++) {
                float s;
                if (transposed) {
                    s = output[c * numAnchors + row_idx];
                } else {
                    s = output[row_idx * featureDim + reg_max_1 * 4 + c];
                }
                if (s > score) {
                    score = s;
                    label = c;
                }
            }
            score = sigmoidFn(score);

            if (score < m_confThreshold) continue;

            // DFL bbox decode
            float pred_ltrb[4] = {0, 0, 0, 0};
            for (int k = 0; k < 4; k++) {
                float dis_values[reg_max_1];
                for (int l = 0; l < reg_max_1; l++) {
                    if (transposed) {
                        dis_values[l] = output[(k * reg_max_1 + l) * numAnchors + row_idx];
                    } else {
                        dis_values[l] = output[row_idx * featureDim + k * reg_max_1 + l];
                    }
                }
                pred_ltrb[k] = softmax_compute(dis_values, reg_max_1) * stride;
            }

            int grid_x = i % num_grid_x;
            int grid_y = i / num_grid_x;
            float pb_cx = (grid_x + 0.5f) * stride;
            float pb_cy = (grid_y + 0.5f) * stride;

            float x0 = pb_cx - pred_ltrb[0];
            float y0 = pb_cy - pred_ltrb[1];
            float x1 = pb_cx + pred_ltrb[2];
            float y1 = pb_cy + pred_ltrb[3];

            x0 /= imgWidth; y0 /= imgHeight;
            x1 /= imgWidth; y1 /= imgHeight;

            x0 = std::max(0.0f, std::min(1.0f, x0));
            y0 = std::max(0.0f, std::min(1.0f, y0));
            x1 = std::max(0.0f, std::min(1.0f, x1));
            y1 = std::max(0.0f, std::min(1.0f, y1));

            if (x1 <= x0 || y1 <= y0) continue;

            detections.push_back({
                x0, y0, x1, y1,
                score,
                (float)label
            });
        }
        pred_row_offset += num_grid;
    }

    return detections;
}

std::vector<DetectionResult> OnnxEngine::parseMultiOutput(
    const std::vector<float*>& outputs,
    const std::vector<std::vector<int64_t>>& shapes,
    int imgWidth, int imgHeight) {
    std::vector<DetectionResult> detections;

    for (size_t o = 0; o < outputs.size(); o++) {
        const float* output = outputs[o];
        auto& shape = shapes[o];

        if (shape.size() < 3) continue;

        int64_t batch = shape[0];
        int64_t channels = shape[1];
        int64_t gridH = shape[2];
        int64_t gridW = (shape.size() >= 4) ? shape[3] : 1;

        int numClass = channels - 4;
        if (numClass < 1) numClass = 1;

        int stride = imgWidth / gridW;

        for (int64_t gy = 0; gy < gridH; gy++) {
            for (int64_t gx = 0; gx < gridW; gx++) {
                int64_t anchorIdx = gy * gridW + gx;
                const float* row = output + anchorIdx * channels;

                // Find max class score
                float maxScore = -1e9f;
                int classId = 0;
                for (int c = 0; c < numClass; c++) {
                    float s = sigmoidFn(row[4 + c]);
                    if (s > maxScore) { maxScore = s; classId = c; }
                }

                if (maxScore < m_confThreshold) continue;

                float cx = sigmoidFn(row[0]);
                float cy = sigmoidFn(row[1]);
                float bw = expf(row[2]);
                float bh = expf(row[3]);

                cx = (cx * 2.0f - 0.5f + gx) * stride / imgWidth;
                cy = (cy * 2.0f - 0.5f + gy) * stride / imgHeight;
                bw = bw * bw * 4.0f * stride / imgWidth;
                bh = bh * bh * 4.0f * stride / imgHeight;

                float x0 = cx - bw * 0.5f;
                float y0 = cy - bh * 0.5f;
                float x1 = cx + bw * 0.5f;
                float y1 = cy + bh * 0.5f;

                x0 = std::max(0.0f, std::min(1.0f, x0));
                y0 = std::max(0.0f, std::min(1.0f, y0));
                x1 = std::max(0.0f, std::min(1.0f, x1));
                y1 = std::max(0.0f, std::min(1.0f, y1));

                if (x1 <= x0 || y1 <= y0) continue;

                detections.push_back({
                    x0, y0, x1, y1,
                    maxScore,
                    (float)classId
                });
            }
        }
    }

    return detections;
}

void OnnxEngine::nms(std::vector<DetectionResult>& detections, float iouThreshold) {
    // Sort by confidence descending
    std::sort(detections.begin(), detections.end(),
              [](const DetectionResult& a, const DetectionResult& b) {
                  return a.confidence > b.confidence;
              });

    std::vector<bool> keep(detections.size(), true);

    for (size_t i = 0; i < detections.size(); i++) {
        if (!keep[i]) continue;
        for (size_t j = i + 1; j < detections.size(); j++) {
            if (!keep[j]) continue;
            if (detections[i].classId != detections[j].classId) continue;

            float xi1 = std::max(detections[i].x1, detections[j].x1);
            float yi1 = std::max(detections[i].y1, detections[j].y1);
            float xi2 = std::min(detections[i].x2, detections[j].x2);
            float yi2 = std::min(detections[i].y2, detections[j].y2);
            float interW = std::max(0.0f, xi2 - xi1);
            float interH = std::max(0.0f, yi2 - yi1);
            float interArea = interW * interH;

            float areaI = (detections[i].x2 - detections[i].x1) * (detections[i].y2 - detections[i].y1);
            float areaJ = (detections[j].x2 - detections[j].x1) * (detections[j].y2 - detections[j].y1);
            float unionArea = areaI + areaJ - interArea;

            if (unionArea > 0 && interArea / unionArea > iouThreshold) {
                keep[j] = false;
            }
        }
    }

    std::vector<DetectionResult> filtered;
    for (size_t i = 0; i < detections.size(); i++) {
        if (keep[i]) filtered.push_back(detections[i]);
    }
    detections = std::move(filtered);
}

bool OnnxEngine::detect(const uint8_t* rgbaData, int width, int height,
                        std::vector<DetectionResult>& results) {
    results.clear();
    if (!m_initialized || !m_session) return false;

    try {
        // Preprocess
        preprocess(rgbaData, width, height, m_preprocessBuffer.data());

        // Create input tensor
        std::vector<int64_t> inputShape = {1, 3, m_inputHeight, m_inputWidth};
        size_t inputSize = m_inputWidth * m_inputHeight * 3;
        std::memcpy(m_inputBuffer.data(), m_preprocessBuffer.data(), inputSize * sizeof(float));

        Ort::Value inputTensor = Ort::Value::CreateTensor<float>(
            *m_memoryInfo, m_preprocessBuffer.data(), inputSize,
            inputShape.data(), inputShape.size());

        long long t1 = getTimeUs();

        // Run inference
        auto outputTensors = m_session->Run(
            Ort::RunOptions{nullptr},
            m_inputNames.data(), &inputTensor, 1,
            m_outputNames.data(), m_outputNames.size());

        long long t2 = getTimeUs();
        ONNX_LOG("ONNX Inference: %lld us", t2 - t1);

        // Process outputs
        if (m_outputNames.size() == 1) {
            // Single output (YOLOv8/v11 format)
            auto& outTensor = outputTensors.front();
            auto typeInfo = outTensor.GetTensorTypeAndShapeInfo();
            auto shape = typeInfo.GetShape();
            const float* outputData = outTensor.GetTensorData<float>();

            auto rawDetections = parseYoloV8Output(outputData, shape, m_inputWidth, m_inputHeight);
            nms(rawDetections, 0.45f);
            results = std::move(rawDetections);
        } else {
            // Multi-output (3 scales)
            std::vector<float*> outputs;
            std::vector<std::vector<int64_t>> shapes;
            for (auto& outTensor : outputTensors) {
                auto typeInfo = outTensor.GetTensorTypeAndShapeInfo();
                shapes.push_back(typeInfo.GetShape());
                outputs.push_back(outTensor.GetTensorMutableData<float>());
            }
            auto rawDetections = parseMultiOutput(outputs, shapes, m_inputWidth, m_inputHeight);
            nms(rawDetections, 0.45f);
            results = std::move(rawDetections);
        }

        ONNX_LOG("Detections: %zu raw → %zu after NMS", results.size(), results.size());
        return true;
    } catch (const std::exception& e) {
        ONNX_ERR("ONNX detect failed: %s", e.what());
        return false;
    }
}

std::vector<DetectionResult> OnnxEngine::decodeOutput(
    const float* output, int numAnchors, int numClasses,
    int imgWidth, int imgHeight, float confThreshold) {
    // Not used internally — kept for compatibility
    return {};
}

void OnnxEngine::postprocess(const float* outputData, const std::vector<int64_t>& outputShape,
                             int srcWidth, int srcHeight,
                             std::vector<DetectionResult>& results) {
    // Not used internally — kept for compatibility
}