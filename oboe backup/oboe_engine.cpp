#include "oboe_engine.hpp"
#include "../utility/ptrptr.hpp"
#include "../utility/log.hpp"
#include "../utility/exception.hpp"
#include <array>
#include <algorithm>
#include <iterator>
#include <limits>
#include <cassert>
#include <string>
#include <sys/system_properties.h>
#include <oboe/OboeExtensions.h>
#include <cstring>

namespace {
/// @note: message should contain {}
inline bool check(oboe::Result result, std::string_view msg) {
    if (result != oboe::Result::OK) {
        warn(msg, oboe::convertToText(result));
        return false;
    }
    return true;
}

/// Android SDK Version
int get_android_sdk_version() {
    char sdk_prop[PROP_VALUE_MAX];
    int len = __system_property_get("ro.build.version.sdk", sdk_prop);
    if (len > 0) {
        return atoi(sdk_prop);
    }
    return 0;
}

/// 获取系统属性字符串
std::string get_system_property(const char* name) {
    char value[PROP_VALUE_MAX] = {};
    int len = __system_property_get(name, value);
    return (len > 0) ? std::string(value, len) : std::string();
}


enum class SocClass {
    High,     // 旗舰 SoC：骁龙8系/天玑9系/麒麟9系，可用 2x 激进低延迟
    Mid,      // 中端 SoC：骁龙7/6系/天玑8/7系，推荐 3x 平衡
    Low,      // 低端/老旧 SoC：骁龙4系及更早/老麒麟，需要 4x+ 保守
    Unknown   // 无法识别，按 SDK 版本回退
};


SocClass classify_soc() {
    // ro.soc.model 是最精确的 SoC 标识，如 "SM8550"(骁龙8 Gen2), "MT6983"(天玑9000)
    std::string soc_model = get_system_property("ro.soc.model");
    // ro.board.platform 是备用，如 "msm8998"(骁龙835), "kona"(骁龙865)
    std::string board_platform = get_system_property("ro.board.platform");
    // ro.hardware 是最底层，如 "qcom", "mt6789"
    std::string hardware = get_system_property("ro.hardware");

    // === 高端 SoC：骁龙8系 ===
    // 骁龙8 Elite/8 Gen3/8 Gen2/8 Gen1/888/865/855/845/835
    static const char* highend_snaps[] = {
        "SM8750", "SM8650", "SM8550", "SM8450",   // 8 Elite/Gen3/Gen2/Gen1
        "SM8350", "SM8250", "SM8150", "SDM845",   // 888/865/855/845
        "SDM835", "SDM845", "msm8998", "msm8996",  // 835/845/835/820
        "kona", "lahaina", "taro", "pineapple",    // 865/888/8G1/8G2 board
    };
    for (const auto& s : highend_snaps) {
        if (soc_model == s || board_platform == s) return SocClass::High;
    }

    if (soc_model.rfind("SM8", 0) == 0) return SocClass::High;


    static const char* highend_mtk[] = {
        "MT6989", "MT6985", "MT6983", "MT6893",   // 天玑9400/9300/9000/1200
    };
    for (const auto& s : highend_mtk) {
        if (soc_model == s) return SocClass::High;
    }

    if (soc_model.rfind("MT69", 0) == 0 || soc_model.rfind("MT698", 0) == 0) return SocClass::High;


    if (soc_model.rfind("HiSilicon Kirin 9", 0) == 0) return SocClass::High;
    if (soc_model.find("kirin9") != std::string::npos) return SocClass::High;

    // === 中端 SoC：骁龙7/6系 ===
    static const char* mid_snaps[] = {
        "SM7675", "SM7630", "SM7475", "SM7450", "SM7325",  // 7+Gen3/7Gen3/7+Gen2/7Gen1/778G
        "SM6375", "SM6225", "SM6115",                      // 695/6 Gen1/680
        "SDM710", "SDM670", "SDM660", "SDM630",           // 710/670/660/630
    };
    for (const auto& s : mid_snaps) {
        if (soc_model == s || board_platform == s) return SocClass::Mid;
    }
    // 骁龙7系 SM7*, 6系 SM6*
    if (soc_model.rfind("SM7", 0) == 0 || soc_model.rfind("SM6", 0) == 0) return SocClass::Mid;
    if (board_platform.find("sdm") == 0 || board_platform.find("sm") == 0) return SocClass::Mid;

    // === 中端 SoC：联发科天玑8/7系 ===
    if (soc_model.rfind("MT68", 0) == 0) return SocClass::Mid;   // 天玑800/700系列
    if (soc_model.rfind("MT678", 0) == 0) return SocClass::Mid;  // 天玑700系列

    // === 低端/老旧 SoC ===
    // 骁龙4系、200系、老款（含 msm 系列，如骁龙800/801/810/600）
    if (soc_model.rfind("SM4", 0) == 0 || soc_model.rfind("SM2", 0) == 0) return SocClass::Low;
    if (soc_model.rfind("SDM4", 0) == 0 || soc_model.rfind("MSM8", 0) == 0) return SocClass::Low;
    if (soc_model.rfind("msm8", 0) == 0 || board_platform.rfind("msm8", 0) == 0) return SocClass::Low;  // msm8974(骁龙800), msm8994(骁龙810), msm8960(APQ8064) 等老款
    // 联发科低端 Helio P/A 系列
    if (soc_model.rfind("MT676", 0) == 0 || soc_model.rfind("MT67", 0) == 0) return SocClass::Low;

    // Unknown：无法通过 SoC 型号判断
    return SocClass::Unknown;
}

/// 根据 SoC 性能等级 + SDK 版本 + 设备 buffer 容量，计算最优 buffer 倍数
int calculate_buffer_burst_multiplier(int32_t framesPerBurst, int32_t bufferCapacity) {
    int sdk = get_android_sdk_version();
    int32_t capacity_in_bursts = bufferCapacity / std::max(framesPerBurst, 1);
    SocClass soc = classify_soc();

    // 32位 SOC 强制更高 multiplier（老旧 32位架构处理能力弱）
    std::string cpu_abi = get_system_property("ro.product.cpu.abi");
    std::string board_platform = get_system_property("ro.board.platform");
    bool is_32bit = (cpu_abi.find("armeabi-v7a") != std::string::npos);
    info("DEBUG: cpu_abi=[{}], is_32bit={}, board_platform=[{}]", cpu_abi.c_str(), is_32bit, board_platform.c_str());

    // 判断是否 820 及更新的 64位高端芯片
    static const char* new_64bit_platforms[] = {
        "msm8998",  // 820之后
        "kona",     // 865
        "lahaina",  // 888
        "taro",     // 8G1
        "pineapple", // 8G2
        "sm8150", "sm8150p",  // 855
        "sm8250", "sm8350", "sm8450", "sm8550", "sm8650", "sm8750", // 8G3/8G2/8+/8E
    };
    bool is_new_64bit_highend = false;
    for (const auto& p : new_64bit_platforms) {
        if (board_platform == p) {
            is_new_64bit_highend = true;
            break;
        }
    }

    int multiplier;
    if (is_32bit) {
        multiplier = 6;  // 低端 32 位 SOC 用更大的 buffer 降低 CPU 占用
    } else if (is_new_64bit_highend || board_platform.rfind("sm8", 0) == 0) {
        // 820之后的新64位高端芯片：2x
        multiplier = 2;
    } else {
        // 820及之前的老64位芯片：4x
        multiplier = 4;
    }

    // 确保不超过设备 buffer 容量
    if (framesPerBurst * multiplier > bufferCapacity) {
        multiplier = std::max(1, static_cast<int>(bufferCapacity / framesPerBurst));
    }

    const char* soc_names[] = {"High", "Mid", "Low", "Unknown"};
    info("Audio buffer config: SDK={}, SoC={}, cpu_abi={}, bursts_capacity={}, multiplier={}x",
         sdk, soc_names[static_cast<int>(soc)], cpu_abi.c_str(),
         capacity_in_bursts, multiplier);
    return multiplier;
}
}

oboe_engine::oboe_engine(mode mode, uint8_t channels, uint32_t sample_rate)
        : oboe::AudioStreamDataCallback()
        , oboe::AudioStreamErrorCallback()
        , m_mode(mode)
        , m_channels(channels)
        , m_sample_rate(sample_rate)
        , m_payload_size(0)
        , m_is_playing(false) {
    connect_to_device();
}

oboe_engine::~oboe_engine() {
    if (!m_stream)
        return;

    stop();
    check(m_stream->close(), "Error closing stream: {}");
}

void oboe_engine::connect_to_device() {
    // 检测 media.aaudio 服务是否可用
    // 如果服务未启动（某些定制 ROM 上会这样），直接跳过 AAudio 避免 requestStart() 挂起
    {
        char value[PROP_VALUE_MAX] = {};
        int len = __system_property_get("init.svc.media.aaudio", value);
        std::string service_status(len > 0 ? value : "");
        if (service_status != "running") {
            info("media.aaudio service is '{}', skipping AAudio and using OpenSL ES", service_status);
            m_use_opensl_es = true;
        }
    }

    // initialize Oboe audio stream
    oboe::AudioStreamBuilder builder;
    builder.setChannelCount(m_channels);
    builder.setSampleRate(static_cast<int32_t>(m_sample_rate));
    builder.setErrorCallback(this);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setPerformanceMode(m_use_low_latency ? oboe::PerformanceMode::LowLatency : oboe::PerformanceMode::None);
    builder.setSharingMode(m_use_exclusive ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared);
    builder.setFormatConversionAllowed(true);

    // 在 openStream 之前通过 OboeExtensions 全局控制 MMAP
    // 旧版 Oboe 没有 AudioStreamBuilder::setMMapAllowed，用 OboeExtensions::setMMapEnabled 代替
    oboe::OboeExtensions::setMMapEnabled(m_mmap_allowed);

    // 如果 AAudio 全部失败，回退到 OpenSL ES（绕过 AAudio MMAP bug）
    if (m_use_opensl_es) {
        builder.setAudioApi(oboe::AudioApi::OpenSLES);
    }

    builder.setUsage(oboe::Usage::Media);
    switch(m_mode) {
        case mode::async_writing:
        case mode::writing: {
            builder.setContentType(oboe::ContentType::Music);
            builder.setDirection(oboe::Direction::Output);

            if (m_mode == mode::async_writing)
                builder.setDataCallback(this);
        }
        break;
        case mode::reading: {
            builder.setDirection(oboe::Direction::Input);
            builder.setInputPreset(oboe::InputPreset::Generic);
        }
        break;
    }

    oboe::Result result = builder.openStream(ptrptr(m_stream));

    // AAudio 打开流失败时，快速降级到 OpenSL ES
    if (result != oboe::Result::OK && !m_use_opensl_es) {
        warn("AAudio stream open failed ({}), fast fallback to OpenSL ES", oboe::convertToText(result));
        m_use_opensl_es = true;
        builder.setAudioApi(oboe::AudioApi::OpenSLES);
        result = builder.openStream(ptrptr(m_stream));
    }

    // 如果 Exclusive 模式打开失败，自动降级到 Shared 模式重试
    // 解决 第三方 ROM 上全局音效导致独占模式被拒绝/窃取的问题
    if (result != oboe::Result::OK && m_use_exclusive) {
        warn("Exclusive mode openStream failed ({}), falling back to Shared mode", oboe::convertToText(result));
        m_use_exclusive = false;
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(ptrptr(m_stream));
    }

    check(result, "Error opening stream: {}");

    // 【自适应 Buffer 配置】根据设备 SDK 版本和硬件能力动态调整 buffer 大小
    int32_t framesPerBurst = m_stream->getFramesPerBurst();
    int32_t bufferCapacity = m_stream->getBufferCapacityInFrames();
    int multiplier = calculate_buffer_burst_multiplier(framesPerBurst, bufferCapacity);

    int32_t calculatedPayload = framesPerBurst * multiplier;
    m_payload_size = static_cast<uint32_t>(std::min(calculatedPayload, bufferCapacity));
    m_xrun_adaptive_multiplier = multiplier;  // 记录初始 multiplier，作为 XRun 自适应下限
    int32_t targetBufferSize = std::min(calculatedPayload, bufferCapacity);
    auto bufferResult = m_stream->setBufferSizeInFrames(targetBufferSize);
    if (!bufferResult) {
        warn("Failed to set buffer size to {} frames: {}", targetBufferSize,
             oboe::convertToText(bufferResult.error()));
        m_payload_size = m_stream->getBufferSizeInFrames();
    } else {
        info("Buffer: {}/{} frames ({}x burst), sharing={}, mmap={}, api={}",
             bufferResult.value(), bufferCapacity, multiplier,
             m_stream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared",
             m_mmap_allowed ? "on" : "off",
             m_use_opensl_es ? "OpenSL ES" : "AAudio");
    }
}

void oboe_engine::onErrorAfterClose(oboe::AudioStream *self, oboe::Result error) {
    // 如果 resume() 正在处理重连，跳过回调避免竞态
    if (m_reconnecting) {
        info("Skipping onErrorAfterClose - reconnection already in progress");
        return;
    }
    if (error == oboe::Result::ErrorDisconnected) {
        m_reconnect_attempts++;
        if (m_reconnect_attempts > k_max_reconnect_attempts) {
            warn("Max reconnect attempts ({}) reached, giving up. "
                 "Audio will be unavailable until next resume().",
                 k_max_reconnect_attempts);
            return;
        }
        info("Previous device disconnected (attempt {}/{}). Trying to connect to a new one...",
             m_reconnect_attempts, k_max_reconnect_attempts);

        // 逐步降级策略：禁用MMAP → OpenSL ES → PerformanceMode::None
        if (m_reconnect_attempts >= 1 && m_mmap_allowed) {
            warn("Stream disconnected with MMAP, disabling MMAP and retrying");
            m_mmap_allowed = false;
            m_use_exclusive = false;  // MMAP 禁用时 Exclusive 无意义
        } else if (m_reconnect_attempts >= 2 && !m_use_opensl_es) {
            warn("AAudio keeps failing, falling back to OpenSL ES");
            m_use_opensl_es = true;
        } else if (m_reconnect_attempts >= 3 && m_use_low_latency) {
            warn("All low-latency modes failed, falling back to PerformanceMode::None");
            m_use_low_latency = false;
        }

        connect_to_device();
        if (m_is_playing) {
            resume();
        }
    }
}

oboe::DataCallbackResult oboe_engine::onAudioReady(oboe::AudioStream *self, void *audio_data,
                                                   int32_t num_frames) {
    android_assert(m_mode == mode::async_writing,
                  "engine not in async_writing mode, something went wrong.");

    if (num_frames > 0 && m_on_async_write) {
        auto& pcm_queue = m_on_async_write(static_cast<uint32_t>(num_frames * m_channels));
        auto stream = static_cast<int16_t*>(audio_data);
        int32_t write_size = std::min(static_cast<int32_t>(pcm_queue.size()), num_frames * m_channels);

        if (write_size != 0) {
            std::copy(pcm_queue.begin(),
                     std::next(pcm_queue.begin(), write_size),
                     stream);
        }

        if (write_size < num_frames) {
            std::fill(std::next(stream, write_size),
                     std::next(stream, num_frames * m_channels),
                     0);
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void oboe_engine::resume() {
    if (!m_stream)
        return;

    auto state = m_stream->getState();
    debug("oboe_engine::resume. State: {}", oboe::convertToText(state));

    // 流已经在播放中，直接返回成功（避免 ErrorInvalidState）
    if (state == oboe::StreamState::Started || state == oboe::StreamState::Starting) {
        m_is_playing = true;
        m_reconnect_attempts = 0;
        return;
    }

    // 流处于 Disconnected 状态时，需要先关闭重连
    if (state == oboe::StreamState::Disconnected) {
        warn("Stream is Disconnected, reconnecting...");
        m_reconnecting = true;
        m_stream->close();
        connect_to_device();
        m_reconnecting = false;
        if (!m_stream) return;
    }

    auto result = m_stream->requestStart();
    if (result == oboe::Result::OK) {
        m_is_playing = true;
        m_reconnect_attempts = 0;
        return;
    }

    // === 降级链：Exclusive → Shared → MMAP禁用 → OpenSL ES → None ===

    // 1) Exclusive → Shared
    if (result == oboe::Result::ErrorDisconnected && m_use_exclusive) {
        m_reconnecting = true;
        warn("requestStart() ErrorDisconnected in Exclusive mode, falling back to Shared");
        m_use_exclusive = false;
        m_stream->close();
        connect_to_device();
        if (m_stream) {
            result = m_stream->requestStart();
            if (result == oboe::Result::OK) {
                m_is_playing = true;
                m_reconnect_attempts = 0;
                m_reconnecting = false;
                return;
            }
        }
        m_reconnecting = false;
    }

    // 2) Shared + MMAP → Shared + MMAP禁用
    if (result == oboe::Result::ErrorDisconnected && m_mmap_allowed) {
        m_reconnecting = true;
        warn("requestStart() ErrorDisconnected with MMAP, disabling MMAP");
        m_mmap_allowed = false;
        m_stream->close();
        connect_to_device();
        if (m_stream) {
            result = m_stream->requestStart();
            if (result == oboe::Result::OK) {
                m_is_playing = true;
                m_reconnect_attempts = 0;
                m_reconnecting = false;
                return;
            }
        }
        m_reconnecting = false;
    }

    // 3) AAudio 失败 → OpenSL ES
    if (result == oboe::Result::ErrorDisconnected && !m_use_opensl_es) {
        m_reconnecting = true;
        warn("AAudio keeps failing, falling back to OpenSL ES with LowLatency");
        m_use_opensl_es = true;
        m_stream->close();
        connect_to_device();
        if (m_stream) {
            result = m_stream->requestStart();
            if (result == oboe::Result::OK) {
                m_is_playing = true;
                m_reconnect_attempts = 0;
                m_reconnecting = false;
                return;
            }
        }
        m_reconnecting = false;
    }

    // 4) 所有低延迟模式都失败 → PerformanceMode::None（高延迟保底）
    if (result == oboe::Result::ErrorDisconnected && m_use_low_latency) {
        m_reconnecting = true;
        warn("All low-latency modes failed, falling back to PerformanceMode::None");
        m_use_low_latency = false;
        m_stream->close();
        connect_to_device();
        if (m_stream) {
            result = m_stream->requestStart();
            if (result == oboe::Result::OK) {
                m_is_playing = true;
                m_reconnect_attempts = 0;
                m_reconnecting = false;
                return;
            }
        }
        m_reconnecting = false;
    }

    check(result, "Error starting stream: {}");
}

void oboe_engine::stop() {
    if (!m_stream)
        return;

    debug("stop::resume. State: {}", oboe::convertToText(m_stream->getState()));

    if (check(m_stream->requestStop(), "Error stopping stream: {}")) {
        m_is_playing = false;
    }
}

void oboe_engine::blocking_write(const int16_t* pcm, size_t len) {
    android_assert(m_mode == mode::writing,
                  "engine not in writing mode, something went wrong.");

    if (!m_stream)
        return;

    // === XRun 自适应 buffer ===
    // 每隔 k_xrun_check_interval 次 write 检测一次 xrun 计数
    // 如果发现新的 xrun（欠载），自动增大 buffer（最多翻倍到 capacity 上限）
    if (++m_xrun_check_counter >= k_xrun_check_interval) {
        m_xrun_check_counter = 0;
        auto xrun_result = m_stream->getXRunCount();
        if (xrun_result && xrun_result.value() > m_last_xrun_count) {
            int32_t new_xruns = xrun_result.value() - m_last_xrun_count;
            m_last_xrun_count = xrun_result.value();
            warn("XRun detected (new={}, total={}), attempting buffer increase", new_xruns, m_last_xrun_count);

            // 尝试增大 buffer：multiplier 翻倍，上限为 capacity / framesPerBurst
            int32_t framesPerBurst = m_stream->getFramesPerBurst();
            int32_t bufferCapacity = m_stream->getBufferCapacityInFrames();
            int max_multiplier = std::max(1, static_cast<int>(bufferCapacity / framesPerBurst));
            int new_multiplier = std::min(m_xrun_adaptive_multiplier * 2, max_multiplier);
            if (new_multiplier > m_xrun_adaptive_multiplier) {
                int32_t newBufferSize = framesPerBurst * new_multiplier;
                newBufferSize = std::min(newBufferSize, bufferCapacity);
                auto result = m_stream->setBufferSizeInFrames(newBufferSize);
                if (result) {
                    m_xrun_adaptive_multiplier = new_multiplier;
                    int32_t actual = result.value();
                    info("XRun buffer adapted: {} frames ({}x burst) was {}x", actual, new_multiplier, new_multiplier / 2);
                }
            }
        }
    }

    int32_t len_in_frames = static_cast<int32_t>(len) / m_channels;
    auto frames = m_stream->write(pcm, len_in_frames, std::numeric_limits<int64_t>::max());
    check(frames, "Error while reading stream: {}");
}

void oboe_engine::blocking_read(int16_t* buffer, size_t len) {
    android_assert(m_mode == mode::reading,
                  "engine not in reading mode, something went wrong.");

    if (!m_stream)
        return;

    int32_t len_in_frames = static_cast<int32_t>(len) / m_channels;
    auto frames = m_stream->read(buffer, len_in_frames, std::numeric_limits<int64_t>::max());

    check(frames, "Error while writing into stream: {}");
    if (frames && frames.value() < len_in_frames) {
        std::fill(std::next(buffer, frames.value() * m_channels),
                 std::next(buffer, static_cast<int32_t>(len)),
                 0);
    }
}

uint32_t oboe_engine::payload_size() const {
    return m_payload_size * m_channels;
}

void oboe_engine::set_buffer_burst_multiplier(int burst_multiplier) {
    if (!m_stream) return;

    int32_t framesPerBurst = m_stream->getFramesPerBurst();
    int32_t bufferCapacity = m_stream->getBufferCapacityInFrames();

    burst_multiplier = std::max(1, burst_multiplier);
    int32_t targetSize = framesPerBurst * burst_multiplier;
    targetSize = std::min(targetSize, bufferCapacity);

    auto result = m_stream->setBufferSizeInFrames(targetSize);
    if (result) {
        m_payload_size = result.value();
        info("Runtime buffer adjusted: {} frames ({}x burst)", result.value(), burst_multiplier);
    } else {
        warn("Runtime buffer adjust failed: {}", oboe::convertToText(result.error()));
    }
}

int32_t oboe_engine::get_xrun_count() const {
    if (!m_stream) return 0;
    auto result = m_stream->getXRunCount();
    return result ? result.value() : 0;
}
