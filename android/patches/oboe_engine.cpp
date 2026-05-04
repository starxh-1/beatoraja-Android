#include "oboe_engine.hpp"
#include "../utility/ptrptr.hpp"
#include "../utility/log.hpp"
#include "../utility/exception.hpp"
#include <array>
#include <algorithm>
#include <iterator>
#include <limits>
#include <cassert>

namespace {
/// @note: message should contain {}
inline bool check(oboe::Result result, std::string_view msg) {
    if (result != oboe::Result::OK) {
        warn(msg, oboe::convertToText(result));
        return false;
    }
    return true;
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
    // initialize Oboe audio stream
    oboe::AudioStreamBuilder builder;
    builder.setChannelCount(m_channels);
    builder.setSampleRate(static_cast<int32_t>(m_sample_rate));
    builder.setErrorCallback(this);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);  // 先尝试独占模式
    builder.setFormatConversionAllowed(true);

    builder.setUsage(oboe::Usage::Game);
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

    // [修改点] 如果 Exclusive 模式打开失败，自动降级到 Shared 模式重试
    // 解决 第三方 ROM 上全局音效导致独占模式被拒绝/窃取的问题
    if (result != oboe::Result::OK) {
        warn("Exclusive mode openStream failed ({}), falling back to Shared mode", oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(ptrptr(m_stream));
    }

    check(result, "Error opening stream: {}");

    m_payload_size = m_stream->getFramesPerBurst() * 2;
    m_stream->setBufferSizeInFrames(static_cast<int32_t>(m_payload_size));
}

void oboe_engine::onErrorAfterClose(oboe::AudioStream *self, oboe::Result error) {
    if (error == oboe::Result::ErrorDisconnected) {
        info("Previous device disconnected. Trying to connect to a new one...");
        // connect_to_device() 内部已实现 Exclusive → Shared 降级逻辑
        // 当 stream was stolen 后重连时会先尝试 Exclusive，失败后自动降级到 Shared
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

    debug("oboe_engine::resume. State: {}", oboe::convertToText(m_stream->getState()));

    if (check(m_stream->requestStart(), "Error starting stream: {}")) {
        m_is_playing = true;
    }
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
