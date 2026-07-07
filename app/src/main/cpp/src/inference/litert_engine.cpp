#include "litert_engine.h"
#include <dlfcn.h>
#include <cstdio>
#include <cstdint>
#include <limits>

namespace {

struct OutputTensorData {
    std::vector<int> shape;
    std::vector<float> values;
    TfLiteType type = kTfLiteNoType;
};

// TFLite type name lookup
static const char* getTfLiteTypeName(TfLiteType type) {
    static const char* names[] = {"FLOAT32","FLOAT16","INT32","UINT8","INT64","STRING","BOOL","INT16","COMPLEX64","INT8","FLOAT64"};
    return (type >= 0 && type <= 10) ? names[type] : "UNKNOWN";
}

float clampValue(float value, float lo, float hi) {
    return std::max(lo, std::min(value, hi));
}

float maybeSigmoid(float value) {
    // Allow small tolerance around [0,1] for INT8 quantization errors
    if (value >= -0.02f && value <= 1.02f) return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    if (value > -30.0f && value < 30.0f) return 1.0f / (1.0f + std::exp(-value));
    return value > 0.0f ? 1.0f : 0.0f;
}

bool isValidNumber(float value) {
    return std::isfinite(value) && std::fabs(value) < 100000.0f;
}

bool readOutputTensor(const TfLiteTensor* tensor, OutputTensorData& output) {
    if (!tensor) return false;
    output.shape.clear();
    int dims = TfLiteTensorNumDims(tensor);
    output.shape.reserve(dims);
    for (int i = 0; i < dims; ++i) output.shape.push_back(TfLiteTensorDim(tensor, i));
    while (!output.shape.empty() && output.shape[0] == 1) output.shape.erase(output.shape.begin());

    size_t byteSize = TfLiteTensorByteSize(tensor);
    output.type = TfLiteTensorType(tensor);
    if (output.type == kTfLiteFloat32) {
        const float* data = static_cast<const float*>(TfLiteTensorData(tensor));
        if (!data) return false;
        output.values.assign(data, data + byteSize / sizeof(float));
        return true;
    }

    TfLiteQuantizationParams q = TfLiteTensorQuantizationParams(tensor);
    if ((output.type == kTfLiteUInt8 || output.type == kTfLiteInt8) && q.scale > 0.0f) {
        const uint8_t* data = static_cast<const uint8_t*>(TfLiteTensorData(tensor));
        if (!data) return false;
        output.values.resize(byteSize);
        float dqMin = 1e9f, dqMax = -1e9f;
        double dqSum = 0.0;
        for (size_t i = 0; i < byteSize; ++i) {
            int raw = output.type == kTfLiteUInt8 ? data[i] : static_cast<int>(static_cast<int8_t>(data[i]));
            output.values[i] = (raw - q.zero_point) * q.scale;
            if (output.values[i] < dqMin) dqMin = output.values[i];
            if (output.values[i] > dqMax) dqMax = output.values[i];
            dqSum += output.values[i];
        }
        LOGD("readOutput: INT8 dequant, scale=%.6f zp=%d, %zu floats, min=%.4f max=%.4f mean=%.4f",
             q.scale, (int)q.zero_point, byteSize, dqMin, dqMax, dqSum / byteSize);
        return true;
    }
    return false;
}

bool shape2D(const OutputTensorData& tensor, int& dim0, int& dim1) {
    if (tensor.shape.size() != 2) return false;
    dim0 = tensor.shape[0];
    dim1 = tensor.shape[1];
    return dim0 > 0 && dim1 > 0 && static_cast<size_t>(dim0) * dim1 <= tensor.values.size();
}

Detection makeDetectionFromInputBox(float x1, float y1, float x2, float y2,
                                    float score, int classId,
                                    int offsetX, int offsetY,
                                    int regionWidth, int regionHeight,
                                    int screenWidth, int screenHeight,
                                    int inputWidth, int inputHeight) {
    float invW = 1.0f / screenWidth;
    float invH = 1.0f / screenHeight;
    return {
        (offsetX + x1 * regionWidth / inputWidth) * invW,
        (offsetY + y1 * regionHeight / inputHeight) * invH,
        (offsetX + x2 * regionWidth / inputWidth) * invW,
        (offsetY + y2 * regionHeight / inputHeight) * invH,
        score,
        static_cast<float>(classId)
    };
}

bool buildDetection(float a0, float a1, float a2, float a3, float rawScore, float rawLabel,
                    int offsetX, int offsetY, int regionWidth, int regionHeight,
                    int screenWidth, int screenHeight, int inputWidth, int inputHeight,
                    int labelCount, float scoreThreshold, Detection& detection) {
    if (!isValidNumber(a0) || !isValidNumber(a1) || !isValidNumber(a2) || !isValidNumber(a3) || !isValidNumber(rawScore)) return false;

    float score = rawScore;
    if (score > 1.0f && score <= 100.0f) score *= 0.01f;
    else score = maybeSigmoid(score);
    if (score < scoreThreshold || score > 1.0f) return false;

    bool normalized = std::max(std::max(std::fabs(a0), std::fabs(a1)), std::max(std::fabs(a2), std::fabs(a3))) <= 2.0f;
    if (normalized) {
        a0 *= inputWidth;
        a1 *= inputHeight;
        a2 *= inputWidth;
        a3 *= inputHeight;
    }

    float candidates[2][4] = {
        {a0, a1, a2, a3},
        {a0 - a2 * 0.5f, a1 - a3 * 0.5f, a0 + a2 * 0.5f, a1 + a3 * 0.5f}
    };

    for (auto& box : candidates) {
        float x1 = clampValue(box[0], 0.0f, static_cast<float>(inputWidth - 1));
        float y1 = clampValue(box[1], 0.0f, static_cast<float>(inputHeight - 1));
        float x2 = clampValue(box[2], 0.0f, static_cast<float>(inputWidth - 1));
        float y2 = clampValue(box[3], 0.0f, static_cast<float>(inputHeight - 1));
        float boxW = x2 - x1;
        float boxH = y2 - y1;
        if (boxW >= 2.0f && boxH >= 2.0f && boxW <= inputWidth * 1.05f && boxH <= inputHeight * 1.05f) {
            int label = std::max(0, std::min(std::max(1, labelCount) - 1, static_cast<int>(std::round(rawLabel))));
            detection = makeDetectionFromInputBox(x1, y1, x2, y2, score, label,
                                                  offsetX, offsetY, regionWidth, regionHeight,
                                                  screenWidth, screenHeight, inputWidth, inputHeight);
            return true;
        }
    }
    return false;
}

bool decodeNmsOutput(const float* out, int dim0, int dim1,
                     int offsetX, int offsetY, int regionWidth, int regionHeight,
                     int screenWidth, int screenHeight, int inputWidth, int inputHeight,
                     int classCount, float scoreThreshold, std::vector<Detection>& detections) {
    int boxCount = (dim1 >= 6 && dim1 <= 8) ? dim0 : (dim0 >= 6 && dim0 <= 8 ? dim1 : 0);
    if (boxCount <= 0) return false;

    int maxLabel = 0;
    for (int i = 0; i < boxCount; ++i) {
        float label = dim1 >= 6 && dim1 <= 8
            ? out[static_cast<size_t>(i) * dim1 + 5]
            : out[static_cast<size_t>(5) * dim1 + i];
        if (isValidNumber(label)) maxLabel = std::max(maxLabel, static_cast<int>(std::round(label)));
    }

    detections.clear();
    detections.reserve(std::min(boxCount, 128));
    for (int i = 0; i < boxCount; ++i) {
        float a0, a1, a2, a3, score, label;
        if (dim1 >= 6 && dim1 <= 8) {
            const float* p = out + static_cast<size_t>(i) * dim1;
            a0 = p[0]; a1 = p[1]; a2 = p[2]; a3 = p[3]; score = p[4]; label = p[5];
            if (dim1 >= 7 && p[6] >= 0.0f && p[6] <= 1.0f && (score < 0.0f || score > 1.0f)) score = p[6];
        } else {
            a0 = out[static_cast<size_t>(0) * dim1 + i];
            a1 = out[static_cast<size_t>(1) * dim1 + i];
            a2 = out[static_cast<size_t>(2) * dim1 + i];
            a3 = out[static_cast<size_t>(3) * dim1 + i];
            score = out[static_cast<size_t>(4) * dim1 + i];
            label = out[static_cast<size_t>(5) * dim1 + i];
        }
        Detection detection{};
        if (buildDetection(a0, a1, a2, a3, score, label,
                           offsetX, offsetY, regionWidth, regionHeight,
                           screenWidth, screenHeight, inputWidth, inputHeight,
                           std::max(classCount, maxLabel + 1), scoreThreshold, detection)) {
            detections.push_back(detection);
        }
    }
    return true;
}

bool decodeRawYoloOutput(const float* out, int dim0, int dim1,
                         int offsetX, int offsetY, int regionWidth, int regionHeight,
                         int screenWidth, int screenHeight, int inputWidth, int inputHeight,
                         int classCount, float scoreThreshold, std::vector<Detection>& detections) {
    bool channelFirst = dim0 >= 5 && dim0 <= 512 && dim1 > dim0;
    bool channelLast = dim1 >= 5 && dim1 <= 512 && dim0 > dim1;
    if (!channelFirst && !channelLast) return false;

    int channelCount = channelFirst ? dim0 : dim1;
    int anchorCount = channelFirst ? dim1 : dim0;
    int outputClassCount = channelCount - 4;
    if (outputClassCount <= 0) return false;

    detections.clear();
    detections.reserve(128);
    for (int anchor = 0; anchor < anchorCount; ++anchor) {
        float cx = channelFirst ? out[0 * anchorCount + anchor] : out[anchor * channelCount + 0];
        float cy = channelFirst ? out[1 * anchorCount + anchor] : out[anchor * channelCount + 1];
        float bw = channelFirst ? out[2 * anchorCount + anchor] : out[anchor * channelCount + 2];
        float bh = channelFirst ? out[3 * anchorCount + anchor] : out[anchor * channelCount + 3];
        if (!isValidNumber(cx) || !isValidNumber(cy) || !isValidNumber(bw) || !isValidNumber(bh) || bw <= 0.0f || bh <= 0.0f) continue;

        int bestLabel = -1;
        float bestScore = 0.0f;
        for (int cls = 0; cls < outputClassCount; ++cls) {
            float rawScore = channelFirst ? out[(4 + cls) * anchorCount + anchor] : out[anchor * channelCount + (4 + cls)];
            if (!isValidNumber(rawScore)) continue;
            float score = maybeSigmoid(rawScore);
            if (score > bestScore) {
                bestScore = score;
                bestLabel = cls;
            }
        }
        if (bestScore < scoreThreshold || bestScore > 1.0f || bestLabel < 0) continue;

        Detection detection{};
        if (buildDetection(cx, cy, bw, bh, bestScore, bestLabel,
                           offsetX, offsetY, regionWidth, regionHeight,
                           screenWidth, screenHeight, inputWidth, inputHeight,
                           outputClassCount, scoreThreshold, detection)) {
            detections.push_back(detection);
        }
    }
    return true;
}

bool looksLikeRawYoloOutput(int dim0, int dim1) {
    bool channelFirst = dim0 >= 5 && dim0 <= 512 && dim1 > dim0;
    bool channelLast = dim1 >= 5 && dim1 <= 512 && dim0 > dim1;
    if (!channelFirst && !channelLast) return false;
    int channelCount = channelFirst ? dim0 : dim1;
    int anchorCount = channelFirst ? dim1 : dim0;
    return channelCount >= 5 && anchorCount >= 512;
}

bool decodeYoloOutput(const float* out, int dim0, int dim1,
                      int offsetX, int offsetY, int regionWidth, int regionHeight,
                      int screenWidth, int screenHeight, int inputWidth, int inputHeight,
                      int classCount, float scoreThreshold, std::vector<Detection>& detections) {
    if (looksLikeRawYoloOutput(dim0, dim1)) {
        LOGD("decodeYolo: trying raw YOLO output (dim0=%d dim1=%d classCount=%d)", dim0, dim1, classCount);
        bool ok = decodeRawYoloOutput(out, dim0, dim1, offsetX, offsetY, regionWidth, regionHeight,
                                   screenWidth, screenHeight, inputWidth, inputHeight,
                                   classCount, scoreThreshold, detections);
        LOGD("decodeYolo: raw YOLO result=%d detections=%zu", ok, detections.size());
        if (ok && !detections.empty()) return true;
    }
    if (decodeNmsOutput(out, dim0, dim1, offsetX, offsetY, regionWidth, regionHeight,
                        screenWidth, screenHeight, inputWidth, inputHeight,
                        classCount, scoreThreshold, detections) && !detections.empty()) {
        LOGD("decodeYolo: NMS format succeeded, detections=%zu", detections.size());
        return true;
    }
    LOGD("decodeYolo: falling back to raw YOLO (last attempt)");
    return decodeRawYoloOutput(out, dim0, dim1, offsetX, offsetY, regionWidth, regionHeight,
                               screenWidth, screenHeight, inputWidth, inputHeight,
                               classCount, scoreThreshold, detections);
}

bool decodeSplitBoxesScores(const OutputTensorData& boxes, const OutputTensorData& scores,
                            int offsetX, int offsetY, int regionWidth, int regionHeight,
                            int screenWidth, int screenHeight, int inputWidth, int inputHeight,
                            int classCount, float scoreThreshold, std::vector<Detection>& detections) {
    int boxDim0 = 0, boxDim1 = 0, scoreDim0 = 0, scoreDim1 = 0;
    if (!shape2D(boxes, boxDim0, boxDim1) || !shape2D(scores, scoreDim0, scoreDim1)) return false;

    bool boxesCf = boxDim0 == 4 && boxDim1 > 0;
    bool boxesCl = boxDim1 == 4 && boxDim0 > 0;
    if (!boxesCf && !boxesCl) return false;

    int anchorCount = boxesCf ? boxDim1 : boxDim0;
    bool scoresCf = scoreDim1 == anchorCount && scoreDim0 > 0;
    bool scoresCl = scoreDim0 == anchorCount && scoreDim1 > 0;
    if (!scoresCf && !scoresCl) return false;

    int outputClassCount = scoresCf ? scoreDim0 : scoreDim1;
    detections.clear();
    detections.reserve(128);
    for (int anchor = 0; anchor < anchorCount; ++anchor) {
        float cx = boxesCf ? boxes.values[0 * anchorCount + anchor] : boxes.values[anchor * 4 + 0];
        float cy = boxesCf ? boxes.values[1 * anchorCount + anchor] : boxes.values[anchor * 4 + 1];
        float bw = boxesCf ? boxes.values[2 * anchorCount + anchor] : boxes.values[anchor * 4 + 2];
        float bh = boxesCf ? boxes.values[3 * anchorCount + anchor] : boxes.values[anchor * 4 + 3];
        if (!isValidNumber(cx) || !isValidNumber(cy) || !isValidNumber(bw) || !isValidNumber(bh) || bw <= 0.0f || bh <= 0.0f) continue;

        int bestLabel = -1;
        float bestScore = 0.0f;
        for (int cls = 0; cls < outputClassCount; ++cls) {
            float rawScore = scoresCf ? scores.values[cls * anchorCount + anchor] : scores.values[anchor * outputClassCount + cls];
            if (!isValidNumber(rawScore)) continue;
            float score = maybeSigmoid(rawScore);
            if (score > bestScore) {
                bestScore = score;
                bestLabel = cls;
            }
        }
        if (bestScore < scoreThreshold || bestScore > 1.0f || bestLabel < 0) continue;

        Detection detection{};
        if (buildDetection(cx, cy, bw, bh, bestScore, bestLabel,
                           offsetX, offsetY, regionWidth, regionHeight,
                           screenWidth, screenHeight, inputWidth, inputHeight,
                           outputClassCount, scoreThreshold, detection)) {
            detections.push_back(detection);
        }
    }
    return true;
}

bool decodeMultiOutput(const std::vector<OutputTensorData>& outputs,
                       int offsetX, int offsetY, int regionWidth, int regionHeight,
                       int screenWidth, int screenHeight, int inputWidth, int inputHeight,
                       int classCount, float scoreThreshold, std::vector<Detection>& detections) {
    if (outputs.size() > 1) {
        for (size_t i = 0; i < outputs.size(); ++i) {
            for (size_t j = 0; j < outputs.size(); ++j) {
                if (i == j) continue;
                if (decodeSplitBoxesScores(outputs[i], outputs[j], offsetX, offsetY, regionWidth, regionHeight,
                                           screenWidth, screenHeight, inputWidth, inputHeight,
                                           classCount, scoreThreshold, detections)) {
                    return true;
                }
            }
        }
    }

    for (const auto& output : outputs) {
        int dim0 = 0, dim1 = 0;
        if (shape2D(output, dim0, dim1) &&
            decodeYoloOutput(output.values.data(), dim0, dim1, offsetX, offsetY, regionWidth, regionHeight,
                             screenWidth, screenHeight, inputWidth, inputHeight,
                             classCount, scoreThreshold, detections)) {
            return true;
        }
    }
    return false;
}

} // namespace

//==============================================================================
//  GPU Delegate Builder (universal, stays in LiteRtEngine)
//==============================================================================
TfLiteDelegate* LiteRtEngine::buildGpuDelegate() {
    TfLiteGpuDelegateOptionsV2 gpu_options = TfLiteGpuDelegateOptionsV2Default();
    gpu_options.inference_preference = TFLITE_GPU_INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER;
    gpu_options.inference_priority1 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_LATENCY;
    gpu_options.inference_priority2 = TFLITE_GPU_INFERENCE_PRIORITY_MAX_PRECISION;
    gpu_options.inference_priority3 = TFLITE_GPU_INFERENCE_PRIORITY_AUTO;

    TfLiteDelegate* delegate = TfLiteGpuDelegateV2Create(&gpu_options);
    if (delegate) LOGD("GPU delegate created (OpenCL/OpenGL)");
    else          LOGW("GPU delegate creation failed");
    return delegate;
}

void LiteRtEngine::deleteDelegate() {
    if (m_delegate) {
        if (m_backend_type == "QNN HTP") {
            m_qnn_engine.deleteDelegate();
        } else if (m_backend_type == "GPU") {
            TfLiteGpuDelegateV2Delete(m_delegate);
        }
        // Neuron delegate delete - TODO
        m_delegate = nullptr;
    }
}

//==============================================================================
//  Lifecycle
//==============================================================================
LiteRtEngine::LiteRtEngine() = default;

LiteRtEngine::~LiteRtEngine() {
    release();
}

bool LiteRtEngine::init(const char* model_path) {
    release();

    m_model = TfLiteModelCreateFromFile(model_path);
    if (!m_model) {
        LOGE("Failed to load model: %s", model_path);
        return false;
    }

    // Delegate fallback chain:
    // Try platform-specific NPU first (QNN HTP / Neuron)
    // Then universal GPU
    // Finally CPU
    struct DelegateTrial {
        std::function<TfLiteDelegate*()> builder;
        std::string name;
    };

    std::vector<DelegateTrial> trials;
    if (!m_force_cpu) {
        // Platform-specific NPU delegates
        trials.push_back({[this]{ return m_qnn_engine.buildDelegate(); }, "QNN HTP"});
        // trials.push_back({[this]{ return m_neuron_engine.buildDelegate(); }, "Neuron"});  // TODO

        // Universal GPU delegate
        trials.push_back({[this]{ return buildGpuDelegate(); }, "GPU"});
    }

    bool interpreter_created = false;
    for (auto& trial : trials) {
        LOGD("Trying %s delegate...", trial.name.c_str());
        TfLiteDelegate* del = trial.builder();
        if (!del) {
            LOGD("%s delegate not available, skipping", trial.name.c_str());
            continue;
        }

        TfLiteInterpreterOptions* trial_opts = TfLiteInterpreterOptionsCreate();
        TfLiteInterpreterOptionsAddDelegate(trial_opts, del);
        TfLiteInterpreterOptionsSetNumThreads(trial_opts, 1);

        TfLiteInterpreter* interp = TfLiteInterpreterCreate(m_model, trial_opts);
        TfLiteInterpreterOptionsDelete(trial_opts);

        if (interp) {
            m_delegate = del;
            m_interpreter = interp;
            m_backend_type = trial.name;
            LOGD("Interpreter created with %s delegate", trial.name.c_str());
            interpreter_created = true;
            break;
        } else {
            LOGW("%s delegate: interpreter creation failed", trial.name.c_str());
            // Clean up failed delegate
            if (trial.name == "QNN HTP") m_qnn_engine.deleteDelegate();
            else if (trial.name == "GPU") TfLiteGpuDelegateV2Delete(del);
        }
    }

    if (!interpreter_created) {
        m_delegate = nullptr;
        m_backend_type = "CPU";
        LOGD("Falling back to CPU");
        TfLiteInterpreterOptions* cpu_opts = TfLiteInterpreterOptionsCreate();
        TfLiteInterpreterOptionsSetNumThreads(cpu_opts, m_cpu_threads);
        m_interpreter = TfLiteInterpreterCreate(m_model, cpu_opts);
        TfLiteInterpreterOptionsDelete(cpu_opts);

        if (!m_interpreter) {
            LOGE("Failed to create interpreter with CPU");
            TfLiteModelDelete(m_model);
            m_model = nullptr;
            return false;
        }
    }

    if (TfLiteInterpreterAllocateTensors(m_interpreter) != kTfLiteOk) {
        LOGE("Failed to allocate tensors");
        release();
        return false;
    }

    // Get input tensor info
    int input_count = TfLiteInterpreterGetInputTensorCount(m_interpreter);
    if (input_count > 0) {
        const TfLiteTensor* input_tensor = TfLiteInterpreterGetInputTensor(m_interpreter, 0);
        if (input_tensor) {
            int ndim = TfLiteTensorNumDims(input_tensor);
            int dim1 = TfLiteTensorDim(input_tensor, 1);
            int dim2 = TfLiteTensorDim(input_tensor, 2);
            if (ndim >= 4) {
                int dim3 = TfLiteTensorDim(input_tensor, 3);
                if (dim3 == 3 && dim1 != 3) {
                    m_input_nhwc = true;
                    m_input_height = dim1;
                    m_input_width = dim2;
                } else {
                    m_input_nhwc = false;
                    m_input_height = dim1;
                    m_input_width = dim2;
                }
            } else {
                m_input_nhwc = false;
                m_input_height = dim1;
                m_input_width = dim2;
            }
            LOGD("Input: %s, H=%d, W=%d", m_input_nhwc ? "NHWC" : "NCHW", m_input_height, m_input_width);
            TfLiteType inType = TfLiteTensorType(input_tensor);
            LOGD("Input type: %d (%s)", inType, getTfLiteTypeName(inType));
            if (inType == kTfLiteInt8 || inType == kTfLiteUInt8) {
                TfLiteQuantizationParams qp = TfLiteTensorQuantizationParams(input_tensor);
                LOGD("Input quant: scale=%.6f zero_point=%d", qp.scale, qp.zero_point);
            }
        }
    }

    // Get output tensor info
    {
        const TfLiteTensor* out = TfLiteInterpreterGetOutputTensor(m_interpreter, 0);
        if (out) {
            int ndim = TfLiteTensorNumDims(out);
            m_num_outputs = TfLiteTensorDim(out, ndim - 1);
            int channels = TfLiteTensorDim(out, 1);
            m_num_classes = channels - 4;
            if (m_num_classes < 1) m_num_classes = 1;
            LOGD("Output: dims=%d, channels=%d, num_outputs=%d, num_classes=%d",
                 ndim, channels, m_num_outputs, m_num_classes);
            TfLiteType outType = TfLiteTensorType(out);
            LOGD("Output type: %d (%s)", outType, getTfLiteTypeName(outType));
            if (outType == kTfLiteInt8 || outType == kTfLiteUInt8) {
                TfLiteQuantizationParams qp = TfLiteTensorQuantizationParams(out);
                LOGD("Output quant: scale=%.6f zero_point=%d", qp.scale, qp.zero_point);
            }
        }
    }

    m_initialized = true;
    LOGD("LiteRT initialized, backend: %s", m_backend_type.c_str());
    return true;
}

void LiteRtEngine::release() {
    if (m_interpreter) {
        TfLiteInterpreterDelete(m_interpreter);
        m_interpreter = nullptr;
    }
    deleteDelegate();
    if (m_model) {
        TfLiteModelDelete(m_model);
        m_model = nullptr;
    }
    m_initialized = false;
}

//==============================================================================
//  Detect
//==============================================================================
std::vector<Detection> LiteRtEngine::detect(
    uint8_t* src,
    int offsetX, int offsetY,
    int regionWidth, int regionHeight,
    int screenWidth, int screenHeight,
    int rowStride, int pixelStride)
{
    if (!m_interpreter) return {};

    TfLiteTensor* input_tensor = TfLiteInterpreterGetInputTensor(m_interpreter, 0);
    if (!input_tensor) return {};

    int H = m_input_height;
    int W = m_input_width;

    // Precompute coordinate LUTs
    std::vector<int> srcX_lut(W);
    std::vector<int> srcY_lut(H);
    for (int x = 0; x < W; ++x) srcX_lut[x] = offsetX + x * regionWidth / W;
    for (int y = 0; y < H; ++y) srcY_lut[y] = offsetY + y * regionHeight / H;

    static const float inv255 = 1.0f / 255.0f;

    TfLiteType input_type = TfLiteTensorType(input_tensor);
    void* input_data = TfLiteTensorData(input_tensor);
    if (!input_data) return {};

    TfLiteQuantizationParams qp_input = TfLiteTensorQuantizationParams(input_tensor);

    if (input_type == kTfLiteInt8) {
        int8_t* data = static_cast<int8_t*>(input_data);
        float input_scale = qp_input.scale;
        int input_zero_point = qp_input.zero_point;

        for (int y = 0; y < H; ++y) {
            int baseRow = srcY_lut[y] * rowStride;
            for (int x = 0; x < W; ++x) {
                int srcIdx = baseRow + srcX_lut[x] * pixelStride;
                int idx = (y * W + x) * 3;
                data[idx + 0] = (int8_t)std::round(src[srcIdx + 0] * inv255 / input_scale + input_zero_point);
                data[idx + 1] = (int8_t)std::round(src[srcIdx + 1] * inv255 / input_scale + input_zero_point);
                data[idx + 2] = (int8_t)std::round(src[srcIdx + 2] * inv255 / input_scale + input_zero_point);
            }
        }
    } else if (input_type == kTfLiteUInt8) {
        uint8_t* data = static_cast<uint8_t*>(input_data);
        float input_scale = qp_input.scale;
        int input_zero_point = qp_input.zero_point;

        for (int y = 0; y < H; ++y) {
            int baseRow = srcY_lut[y] * rowStride;
            for (int x = 0; x < W; ++x) {
                int srcIdx = baseRow + srcX_lut[x] * pixelStride;
                int idx = (y * W + x) * 3;
                data[idx + 0] = (uint8_t)std::round(src[srcIdx + 0] * inv255 / input_scale + input_zero_point);
                data[idx + 1] = (uint8_t)std::round(src[srcIdx + 1] * inv255 / input_scale + input_zero_point);
                data[idx + 2] = (uint8_t)std::round(src[srcIdx + 2] * inv255 / input_scale + input_zero_point);
            }
        }
    } else {
        float* data = static_cast<float*>(input_data);
        for (int y = 0; y < H; ++y) {
            int baseRow = srcY_lut[y] * rowStride;
            for (int x = 0; x < W; ++x) {
                int srcIdx = baseRow + srcX_lut[x] * pixelStride;
                int idx = (y * W + x) * 3;
                data[idx + 0] = src[srcIdx + 0] * inv255;
                data[idx + 1] = src[srcIdx + 1] * inv255;
                data[idx + 2] = src[srcIdx + 2] * inv255;
            }
        }
    }

    long long t1 = getTimeUs();

    if (TfLiteInterpreterInvoke(m_interpreter) != kTfLiteOk) {
        LOGE("Inference failed");
        return {};
    }

    long long t2 = getTimeUs();
    LOGD("LiteRT Inference: %lld us", t2 - t1);

    int outputCount = TfLiteInterpreterGetOutputTensorCount(m_interpreter);
    if (outputCount <= 0) return {};

    std::vector<OutputTensorData> outputs;
    outputs.reserve(outputCount);
    for (int outputIndex = 0; outputIndex < outputCount; ++outputIndex) {
        const TfLiteTensor* outputTensor = TfLiteInterpreterGetOutputTensor(m_interpreter, outputIndex);
        OutputTensorData output;
        if (!readOutputTensor(outputTensor, output)) {
            LOGE("LiteRT output %d unsupported or unreadable", outputIndex);
            return {};
        }
        outputs.push_back(std::move(output));
    }

    std::vector<Detection> detections;
    if (!decodeMultiOutput(outputs,
                           offsetX, offsetY, regionWidth, regionHeight,
                           screenWidth, screenHeight, m_input_width, m_input_height,
                           m_num_classes, m_conf_thresh, detections)) {
        LOGE("LiteRT unsupported output layout, outputs=%d", outputCount);
        return {};
    }

    LOGD("LiteRT Raw: %zu", detections.size());
    return nms(detections, 0.45f);
}
