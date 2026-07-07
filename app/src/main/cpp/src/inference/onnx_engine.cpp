#include "onnx_engine.h"
#include "common.h"
#include <cmath>
#include <cstring>
#include <algorithm>

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

bool OnnxEngine::init(const char* model_path) {
    release();

    m_modelPath = model_path;

    try {
        // Create ONNX Runtime environment
        m_env = std::make_unique<Ort::Env>(OrtLoggingLevel::ORT_LOGGING_LEVEL_WARNING, "OnnxEngine");

        // Configure session options
        Ort::SessionOptions sessionOptions;

        // Set CPU threads from base class
        if (m_cpu_threads > 0) {
            sessionOptions.SetIntraOpNumThreads(m_cpu_threads);
        } else {
            sessionOptions.SetIntraOpNumThreads(4);
        }

        // Set execution mode
        sessionOptions.SetExecutionMode(ExecutionMode::ORT_PARALLEL);

        // Graph optimization
        sessionOptions.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

        // Disable memory pattern for mobile
        sessionOptions.DisableMemPattern();

        // Create session
        m_session = std::make_unique<Ort::Session>(*m_env, model_path, sessionOptions);

        // Create memory info
        m_memoryInfo = std::make_unique<Ort::MemoryInfo>(
            Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault));

        // Get input/output info
        Ort::AllocatorWithDefaultOptions allocator;
        size_t numInputNodes = m_session->GetInputCount();
        size_t numOutputNodes = m_session->GetOutputCount();

        ONNX_LOG("Model: %zu inputs, %zu outputs", numInputNodes, numOutputNodes);

        m_inputNames.clear();
        for (size_t i = 0; i < numInputNodes; i++) {
            auto name = m_session->GetInputNameAllocated(i, allocator);
            m_inputNames.push_back(strdup(name.get()));
            auto typeInfo = m_session->GetInputTypeInfo(i);
            auto tensorInfo = typeInfo.GetTensorTypeAndShapeInfo();
            auto shape = tensorInfo.GetShape();

            // Auto-detect input size from model
            if (shape.size() >= 4) {
                // Usually NCHW: [1, 3, H, W]
                int64_t h = shape[shape.size() - 2];
                int64_t w = shape[shape.size() - 1];
                if (h > 0 && w > 0 && h <= 4096 && w <= 4096) {
                    m_input_width = static_cast<int>(w);
                    m_input_height = static_cast<int>(h);
                }
            }

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

                // YOLOv8 format: [1, 84, 8400] → 84 = 4*reg_max + num_classes
                const int reg_max_1 = 16;
                if (featureDim > reg_max_1 * 4) {
                    m_num_classes = featureDim - reg_max_1 * 4;
                } else if (featureDim > 4) {
                    m_num_classes = featureDim - 4;
                }
            }
        }
        if (m_num_classes <= 0) m_num_classes = 1;

        // Pre-allocate preprocessing buffer
        m_preprocessBuffer.resize(m_input_width * m_input_height * 3);

        m_initialized = true;
        ONNX_LOG("ONNX ready: %s, input=%dx%d, %d classes, %d threads",
                 model_path, m_input_width, m_input_height, m_num_classes, m_cpu_threads);
        return true;
    } catch (const std::exception& e) {
        ONNX_ERR("ONNX init failed: %s", e.what());
        release();
        return false;
    }
}

void OnnxEngine::release() {
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

void OnnxEngine::preprocess(const uint8_t* rgbaData,
                            int offsetX, int offsetY,
                            int regionWidth, int regionHeight,
                            int screenWidth, int screenHeight,
                            int rowStride, int pixelStride,
                            float* inputTensor) {
    int dstW = m_input_width;
    int dstH = m_input_height;

    float scaleX = (float)regionWidth / dstW;
    float scaleY = (float)regionHeight / dstH;

    for (int dy = 0; dy < dstH; dy++) {
        for (int dx = 0; dx < dstW; dx++) {
            // Source coordinate (center-aligned)
            float sx = (dx + 0.5f) * scaleX - 0.5f + offsetX;
            float sy = (dy + 0.5f) * scaleY - 0.5f + offsetY;

            int x0 = (int)std::floor(sx);
            int y0 = (int)std::floor(sy);
            int x1 = x0 + 1;
            int y1 = y0 + 1;

            x0 = std::max(0, std::min(x0, screenWidth - 1));
            y0 = std::max(0, std::min(y0, screenHeight - 1));
            x1 = std::max(0, std::min(x1, screenWidth - 1));
            y1 = std::max(0, std::min(y1, screenHeight - 1));

            float wx1 = sx - x0;
            float wy1 = sy - y0;
            float wx0 = 1.0f - wx1;
            float wy0 = 1.0f - wy1;

            // RGBA → BGR (match NCNN PIXEL_RGBA2BGR convention)
            // BGR channel order: c=0→B(2), c=1→G(1), c=2→R(0)
            static const int bgrMap[3] = {2, 1, 0};
            for (int c = 0; c < 3; c++) {
                int srcC = bgrMap[c];
                float val = wx0 * wy0 * rgbaData[(y0 * rowStride) + x0 * pixelStride + srcC] +
                            wx1 * wy0 * rgbaData[(y0 * rowStride) + x1 * pixelStride + srcC] +
                            wx0 * wy1 * rgbaData[(y1 * rowStride) + x0 * pixelStride + srcC] +
                            wx1 * wy1 * rgbaData[(y1 * rowStride) + x1 * pixelStride + srcC];
                // NCHW layout: [C, H, W]
                inputTensor[c * dstH * dstW + dy * dstW + dx] = val / 255.0f;
            }
        }
    }
}

float OnnxEngine::softmaxCompute(const float* src, int n) {
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

std::vector<Detection> OnnxEngine::parseYoloV8Output(
    const float* output, const std::vector<int64_t>& shape,
    int imgWidth, int imgHeight) {
    std::vector<Detection> detections;

    if (shape.size() < 2) return detections;

    int64_t dim1 = shape[1];
    int64_t dim2 = shape[2];

    int featureDim, numAnchors;
    bool transposed = false;

    const int reg_max_1 = 16;
    if (dim1 > dim2) {
        featureDim = dim1;
        numAnchors = dim2;
        transposed = false;
    } else {
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
            score = sigmoid(score);

            if (score < m_conf_thresh) continue;

            // DFL bbox decode
            float pred_ltrb[4] = {0, 0, 0, 0};
            for (int k = 0; k < 4; k++) {
                float dis_values[16];
                for (int l = 0; l < reg_max_1; l++) {
                    if (transposed) {
                        dis_values[l] = output[(k * reg_max_1 + l) * numAnchors + row_idx];
                    } else {
                        dis_values[l] = output[row_idx * featureDim + k * reg_max_1 + l];
                    }
                }
                pred_ltrb[k] = softmaxCompute(dis_values, reg_max_1) * stride;
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

            detections.push_back({x0, y0, x1, y1, score, (float)label});
        }
        pred_row_offset += num_grid;
    }

    return detections;
}

std::vector<Detection> OnnxEngine::parseMultiOutput(
    const std::vector<float*>& outputs,
    const std::vector<std::vector<int64_t>>& shapes,
    int imgWidth, int imgHeight) {
    std::vector<Detection> detections;

    for (size_t o = 0; o < outputs.size(); o++) {
        const float* output = outputs[o];
        auto& shape = shapes[o];

        if (shape.size() < 3) continue;

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

                float maxScore = -1e9f;
                int classId = 0;
                for (int c = 0; c < numClass; c++) {
                    float s = sigmoid(row[4 + c]);
                    if (s > maxScore) { maxScore = s; classId = c; }
                }

                if (maxScore < m_conf_thresh) continue;

                float cx = sigmoid(row[0]);
                float cy = sigmoid(row[1]);
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

                detections.push_back({x0, y0, x1, y1, maxScore, (float)classId});
            }
        }
    }

    return detections;
}

std::vector<Detection> OnnxEngine::detect(
    uint8_t* src,
    int offsetX, int offsetY,
    int regionWidth, int regionHeight,
    int screenWidth, int screenHeight,
    int rowStride, int pixelStride) {

    if (!m_initialized || !m_session) return {};

    try {
        // Preprocess
        preprocess(src, offsetX, offsetY, regionWidth, regionHeight,
                   screenWidth, screenHeight, rowStride, pixelStride,
                   m_preprocessBuffer.data());

        // Create input tensor (NCHW: [1, 3, H, W])
        std::vector<int64_t> inputShape = {1, 3, m_input_height, m_input_width};
        size_t inputSize = m_input_width * m_input_height * 3;

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
        ONNX_LOG("ONNX inference: %lld us", t2 - t1);

        // Process outputs
        std::vector<Detection> rawDetections;

        if (m_outputNames.size() == 1) {
            auto& outTensor = outputTensors.front();
            auto typeInfo = outTensor.GetTensorTypeAndShapeInfo();
            auto shape = typeInfo.GetShape();
            const float* outputData = outTensor.GetTensorData<float>();
            size_t elemCount = typeInfo.GetElementCount();

            // Debug: print output stats
            if (elemCount > 0) {
                float outMin = outputData[0], outMax = outputData[0];
                double outSum = 0.0;
                for (size_t i = 0; i < elemCount; i++) {
                    if (outputData[i] < outMin) outMin = outputData[i];
                    if (outputData[i] > outMax) outMax = outputData[i];
                    outSum += outputData[i];
                }
                ONNX_LOG("Output[0] shape=[%d", (int)shape[0]);
                for (size_t s = 1; s < shape.size(); s++) ONNX_LOG(",%d", (int)shape[s]);
                ONNX_LOG("] elements=%zu min=%.4f max=%.4f mean=%.4f",
                         elemCount, outMin, outMax, outSum / elemCount);
            }

            rawDetections = parseYoloV8Output(outputData, shape, m_input_width, m_input_height);
        } else {
            std::vector<float*> outputs;
            std::vector<std::vector<int64_t>> shapes;
            for (auto& outTensor : outputTensors) {
                auto typeInfo = outTensor.GetTensorTypeAndShapeInfo();
                shapes.push_back(typeInfo.GetShape());
                outputs.push_back(outTensor.GetTensorMutableData<float>());
            }
            rawDetections = parseMultiOutput(outputs, shapes, m_input_width, m_input_height);
        }

        // Apply NMS (from common.h)
        std::vector<Detection> results = nms(rawDetections, 0.45f);

        ONNX_LOG("Detections: %zu raw → %zu after NMS", rawDetections.size(), results.size());
        return results;
    } catch (const std::exception& e) {
        ONNX_ERR("ONNX detect failed: %s", e.what());
        return {};
    }
}