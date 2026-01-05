/*
 * VCAT (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 VCAT authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of VCAT.
 *
 * VCAT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VCAT is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VCAT. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 * All VCAT artwork is owned exclusively by RoncaTech LLC. Use of VCAT logos and artwork is permitted for the purpose
 * of discussing, documenting, or promoting VCAT itself. Any other use requires prior written permission from RoncaTech LLC.
 * Contact: legal@roncatech.com • https://roncatech.com/legal
 */

// SPDX-License-Identifier: GPL-3.0-or-later
// Minimal JNI bridge for vvdec following the dav1d pattern closely.

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <new>
#include <errno.h>

extern "C" {
#include "vvdec/vvdec.h"
}

#define LOG_TAG "vvdec_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN , LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO , LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static constexpr size_t kMaxPendingPackets = 16;

struct InputNode {
    vvdecAccessUnit* au = nullptr;
    int64_t pts_us = -1;
    InputNode() = default;
    ~InputNode() {
        if (au) {
            vvdec_accessUnit_free(au);
            au = nullptr;
        }
    }
    InputNode(const InputNode&) = delete;
    InputNode& operator=(const InputNode&) = delete;
};

struct NativeCtx {
    vvdecDecoder* dec = nullptr;
    std::deque<InputNode*> pending;  // Input AUs waiting to be fed
    std::deque<vvdecFrame*> ready;   // Decoded frames waiting to be dequeued

    // Stats
    uint32_t pkts_in_total = 0;
    uint32_t pkts_send_ok = 0;
    uint32_t pkts_send_tryagain = 0;
    uint32_t pkts_send_err = 0;
    uint32_t pics_out = 0;
    uint32_t dropped_at_flush = 0;

    int num_frames_decoded = 0;
    int num_frames_displayed = 0;
    int num_frames_not_decoded = 0;

    int64_t last_in_pts = -1;
    int64_t last_out_pts = -1;

    // Surface (only this needs mutex)
    ANativeWindow* win = nullptr;
    int win_w = 0, win_h = 0, win_fmt = 0;
    std::mutex win_mtx;

    bool eos = false;
};

struct PictureHolder {
    vvdecFrame* frame = nullptr;
    vvdecDecoder* dec = nullptr;  // Store decoder ref for unref
};

static inline void ensureWindowConfigured(NativeCtx* ctx, int w, int h, int fmt) {
    if (!ctx || !ctx->win) return;
    if (ctx->win_w != w || ctx->win_h != h || ctx->win_fmt != fmt) {
        ANativeWindow_setBuffersGeometry(ctx->win, w, h, fmt);
        ctx->win_w = w;
        ctx->win_h = h;
        ctx->win_fmt = fmt;
    }
}

// Release all pending input nodes
static void release_all_pending(NativeCtx* ctx) {
    while (!ctx->pending.empty()) {
        InputNode* n = ctx->pending.front();
        ctx->pending.pop_front();
        delete n;
        ctx->num_frames_not_decoded++;
    }
}

// Release all ready frames
static void release_all_ready(NativeCtx* ctx) {
    while (!ctx->ready.empty()) {
        vvdecFrame* f = ctx->ready.front();
        ctx->ready.pop_front();
        if (f && ctx->dec) {
            vvdec_frame_unref(ctx->dec, f);
        }
    }
}

// Try to feed pending AUs to decoder (like dav1d's flush_pending_to_decoder)
static void flush_pending_to_decoder(NativeCtx* ctx) {
    while (!ctx->pending.empty()) {
        InputNode* n = ctx->pending.front();
        vvdecFrame* out = nullptr;

        int rc = vvdec_decode(ctx->dec, n->au, &out);

        if (rc == VVDEC_TRY_AGAIN) {
            ctx->pkts_send_tryagain++;
            // Decoder needs to output frames first - can't accept more input
            if (out) ctx->ready.push_back(out);
            break;
        }

        if (rc != VVDEC_OK && rc != VVDEC_EOF) {
            ctx->pkts_send_err++;
            LOGE("vvdec_decode failed: %d (dropping packet)", rc);
            ctx->pending.pop_front();
            delete n;
            if (out) vvdec_frame_unref(ctx->dec, out);
            continue;
        }

        // Success - AU was consumed
        ctx->pkts_send_ok++;
        ctx->num_frames_decoded++;
        ctx->last_in_pts = n->pts_us;
        ctx->pending.pop_front();
        delete n;

        // Queue any frame returned during input
        if (out) ctx->ready.push_back(out);

        if (rc == VVDEC_EOF) break;
    }
}

// --------------------------- JNI API ---------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeCreate(JNIEnv*, jclass, jint threads) {
    auto* ctx = new (std::nothrow) NativeCtx();
    if (!ctx) {
        LOGE("nativeCreate: failed to allocate NativeCtx");
        return 0;
    }

    vvdecParams p;
    vvdec_params_default(&p);
    p.threads = (threads == 0) ? 1 : threads;

    ctx->dec = vvdec_decoder_open(&p);
    if (!ctx->dec) {
        LOGE("nativeCreate: vvdec_decoder_open failed (threads=%d)", p.threads);
        delete ctx;
        return 0;
    }

    LOGI("vvdec created (threads=%d)", p.threads);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeFlush(JNIEnv*, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx || !ctx->dec) return;

    ctx->dropped_at_flush += static_cast<uint32_t>(ctx->pending.size());
    release_all_pending(ctx);
    release_all_ready(ctx);

    // Drain any remaining frames from decoder
    vvdecFrame* f = nullptr;
    while (true) {
        int ret = vvdec_flush(ctx->dec, &f);
        if (f) {
            vvdec_frame_unref(ctx->dec, f);
            f = nullptr;
        }
        if (ret == VVDEC_EOF || ret == VVDEC_TRY_AGAIN || ret != VVDEC_OK) {
            break;
        }
    }

    ctx->eos = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeClose(JNIEnv*, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx) return;

    release_all_pending(ctx);
    release_all_ready(ctx);

    if (ctx->dec) {
        vvdec_decoder_close(ctx->dec);
        ctx->dec = nullptr;
    }

    {
        std::lock_guard<std::mutex> lk(ctx->win_mtx);
        if (ctx->win) {
            ANativeWindow_release(ctx->win);
            ctx->win = nullptr;
        }
    }

    LOGD("CLOSE stats: decoded=%d displayed=%d not_decoded=%d send_ok=%u tryagain=%u err=%u pics_out=%u dropped_at_flush=%u",
         ctx->num_frames_decoded, ctx->num_frames_displayed, ctx->num_frames_not_decoded,
         ctx->pkts_send_ok, ctx->pkts_send_tryagain, ctx->pkts_send_err,
         ctx->pics_out, ctx->dropped_at_flush);

    delete ctx;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeQueueInput(
        JNIEnv* env, jclass, jlong handle,
        jobject byteBuffer, jint offset, jint size, jlong ptsUs) {

    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx || !ctx->dec) return -EINVAL;
    if (!byteBuffer || size <= 0) return -EINVAL;

    if (ctx->pending.size() >= kMaxPendingPackets) {
        return -EAGAIN;
    }

    jlong cap = env->GetDirectBufferCapacity(byteBuffer);
    if (cap < 0 || offset < 0 || (jlong)offset + size > cap) {
        LOGE("nativeQueueInput: invalid buffer bounds");
        return -EINVAL;
    }

    uint8_t* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!src) {
        LOGE("nativeQueueInput: not a direct ByteBuffer");
        return -EINVAL;
    }
    src += offset;

    auto* node = new (std::nothrow) InputNode();
    if (!node) return -ENOMEM;

    node->au = vvdec_accessUnit_alloc();
    if (!node->au) {
        delete node;
        return -ENOMEM;
    }

    vvdec_accessUnit_default(node->au);
    vvdec_accessUnit_alloc_payload(node->au, (uint32_t)size);
    if (!node->au->payload || node->au->payloadSize < (uint32_t)size) {
        delete node;
        return -ENOMEM;
    }

    std::memcpy(node->au->payload, src, (size_t)size);
    node->au->payloadUsedSize = (uint32_t)size;
    node->au->cts = (int64_t)ptsUs;
    node->au->ctsValid = 1;
    node->pts_us = (int64_t)ptsUs;

    ctx->pkts_in_total++;
    ctx->pending.push_back(node);

    // Try to feed to decoder (like dav1d pattern)
    flush_pending_to_decoder(ctx);

    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeHasCapacity(JNIEnv*, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx || !ctx->dec) return JNI_FALSE;
    return (ctx->pending.size() < kMaxPendingPackets) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeDequeueFrame(
        JNIEnv* env, jclass, jlong handle,
        jintArray outWH, jlongArray outPtsUs) {

    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx || !ctx->dec) return 0;
    if (!outWH || !outPtsUs) return 0;

    // Try to feed any pending input first (like dav1d)
    flush_pending_to_decoder(ctx);

    vvdecFrame* frame = nullptr;

    // First check the ready queue
    if (!ctx->ready.empty()) {
        frame = ctx->ready.front();
        ctx->ready.pop_front();
    } else {
        // Try to get a frame from decoder directly
        int rc = vvdec_decode(ctx->dec, nullptr, &frame);

        if (rc == VVDEC_TRY_AGAIN || rc == VVDEC_EOF) {
            if (frame) vvdec_frame_unref(ctx->dec, frame);
            return 0;
        }

        if (rc != VVDEC_OK) {
            LOGE("vvdec_decode(null) failed: %d", rc);
            if (frame) vvdec_frame_unref(ctx->dec, frame);
            return 0;
        }
    }

    if (!frame) {
        return 0;
    }

    auto* hold = new (std::nothrow) PictureHolder();
    if (!hold) {
        vvdec_frame_unref(ctx->dec, frame);
        return 0;
    }
    hold->frame = frame;
    hold->dec = ctx->dec;

    jint wh[2] = { (jint)frame->width, (jint)frame->height };
    env->SetIntArrayRegion(outWH, 0, 2, wh);

    jlong pts[1] = { (jlong)frame->cts };
    env->SetLongArrayRegion(outPtsUs, 0, 1, pts);

    ctx->pics_out++;
    ctx->last_out_pts = frame->cts;

    return reinterpret_cast<jlong>(hold);
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeSetSurface(
        JNIEnv* env, jclass, jlong handle, jobject surface) {

    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx) return;

    std::lock_guard<std::mutex> lk(ctx->win_mtx);

    if (ctx->win) {
        ANativeWindow_release(ctx->win);
        ctx->win = nullptr;
        ctx->win_w = ctx->win_h = ctx->win_fmt = 0;
    }
    if (surface) {
        ctx->win = ANativeWindow_fromSurface(env, surface);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeRenderToSurface(
        JNIEnv*, jclass, jlong handle, jlong nativePic, jobject) {

    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    auto* hold = reinterpret_cast<PictureHolder*>(nativePic);
    if (!ctx || !hold || !hold->frame) return -EINVAL;

    const vvdecFrame* f = hold->frame;

    // Fast 8-bit YUV420 planar path
    if (f->bitDepth != 8 || f->colorFormat != VVDEC_CF_YUV420_PLANAR) {
        LOGE("Unsupported format: bitDepth=%d colorFormat=%d", f->bitDepth, f->colorFormat);
        return -ENOSYS;
    }

    const int w = f->width;
    const int h = f->height;
    const int YV12 = 0x32315659;

    std::lock_guard<std::mutex> lk(ctx->win_mtx);
    if (!ctx->win) return -ENODEV;

    ensureWindowConfigured(ctx, w, h, YV12);

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(ctx->win, &buf, nullptr) != 0) return -1;

    auto* dstY = static_cast<uint8_t*>(buf.bits);
    const int dstYStride = buf.stride;
    const int dstUVStride = ((dstYStride >> 1) + 15) & ~15;
    const int uvW = (w + 1) / 2;
    const int uvH = (h + 1) / 2;
    uint8_t* dstV = dstY + dstYStride * h;
    uint8_t* dstU = dstV + dstUVStride * uvH;

    const uint8_t* srcY = (const uint8_t*)f->planes[0].ptr;
    const uint8_t* srcU = (const uint8_t*)f->planes[1].ptr;
    const uint8_t* srcV = (const uint8_t*)f->planes[2].ptr;
    const int srcYStride = (int)f->planes[0].stride;
    const int srcUVStride = (int)f->planes[1].stride;

    // Copy Y plane
    for (int j = 0; j < h; ++j) {
        memcpy(dstY + j * dstYStride, srcY + j * srcYStride, w);
    }
    // Copy V plane (YV12 has V before U)
    for (int j = 0; j < uvH; ++j) {
        memcpy(dstV + j * dstUVStride, srcV + j * srcUVStride, uvW);
    }
    // Copy U plane
    for (int j = 0; j < uvH; ++j) {
        memcpy(dstU + j * dstUVStride, srcU + j * srcUVStride, uvW);
    }

    ANativeWindow_unlockAndPost(ctx->win);
    ctx->num_frames_displayed++;
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeReleasePicture(
        JNIEnv*, jclass, jlong /*handle*/, jlong nativePic) {

    auto* hold = reinterpret_cast<PictureHolder*>(nativePic);
    if (!hold) return;

    // Release frame directly (like dav1d - no mutex needed)
    if (hold->frame && hold->dec) {
        vvdec_frame_unref(hold->dec, hold->frame);
    }
    delete hold;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_vvdecGetVersion(JNIEnv* env, jclass) {
    const char* v = vvdec_get_version();
    return env->NewStringUTF(v ? v : "unknown");
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeSignalEof(JNIEnv*, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx) return;
    ctx->eos = true;
}
