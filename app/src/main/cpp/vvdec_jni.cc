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
// Minimal JNI bridge for vvdec mirroring the dav1d pattern.

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
static constexpr int kMaxDrainPerCall = 8;
static constexpr uint64_t kMagic = 0x5643565644454341ULL; // "VCVVDECA" (arbitrary guard)

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
    uint64_t magic = kMagic;

    vvdecDecoder* dec = nullptr;

    // Protects all vvdec_* calls and pending/ready state transitions.
    std::mutex dec_mtx;

    std::deque<InputNode*> pending;       // AUs created (freed after feeding)
    std::deque<vvdecFrame*> ready;        // frames produced by vvdec_decode()/flush

    // Stats
    uint32_t pkts_in_total = 0;
    uint32_t pkts_send_ok = 0;
    uint32_t pkts_send_tryagain = 0;
    uint32_t pkts_send_err = 0;
    uint32_t pics_out = 0;
    uint32_t pics_tryagain = 0;
    uint32_t dropped_at_flush = 0;

    int num_frames_decoded   = 0;
    int num_frames_displayed = 0;
    int num_frames_not_decoded = 0;

    int64_t last_in_pts  = -1;
    int64_t last_out_pts = -1;

    // Surface
    ANativeWindow* win = nullptr;
    int win_w = 0, win_h = 0, win_fmt = 0;
    std::mutex win_mtx;

    bool eos = false;
};

struct PictureHolder {
    vvdecFrame* frame = nullptr; // release with vvdec_frame_unref(dec, frame)
};

static inline bool isValid(NativeCtx* ctx) {
    return ctx && ctx->magic == kMagic && ctx->dec;
}

static inline void ensureWindowConfigured(NativeCtx* ctx, int w, int h, int fmt) {
    if (!ctx || !ctx->win) {return;}
    if (ctx->win_w != w || ctx->win_h != h || ctx->win_fmt != fmt) {
        ANativeWindow_setBuffersGeometry(ctx->win, w, h, fmt);
        ctx->win_w = w;
        ctx->win_h = h;
        ctx->win_fmt = fmt;
    }
}

// These helpers assume caller holds ctx->dec_mtx.
static void release_all_pending_locked(NativeCtx* ctx) {
    if (!ctx) {return;}
    while (!ctx->pending.empty()) {
        auto* n = ctx->pending.front();
        ctx->pending.pop_front();
        delete n;
        ctx->num_frames_not_decoded++;
    }
}

// These helpers assume caller holds ctx->dec_mtx.
static void release_all_ready_locked(NativeCtx* ctx) {
    if (!ctx) {return;}
    while (!ctx->ready.empty()) {
        vvdecFrame* f = ctx->ready.front();
        ctx->ready.pop_front();
        if (ctx->dec && f) {
            vvdec_frame_unref(ctx->dec, f);
        }
    }
}

extern "C" JNIEXPORT jlong JNICALL Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeCreate(JNIEnv*, jclass, jint threads) {

    auto* ctx = new (std::nothrow) NativeCtx();
    if (!ctx) {
        LOGE("nativeCreate: failed to allocate NativeCtx");
        return 0;
    }

    vvdecParams p;
    vvdec_params_default(&p);

    threads = (threads == 0) ? 1 : threads;

    // Prefer a deterministic single-thread default for stability on low-RAM devices.
    p.threads = threads;

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
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeFlush(
        JNIEnv*, jclass, jlong handle) {

    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!isValid(ctx)) {return;}

    std::lock_guard<std::mutex> lk(ctx->dec_mtx);

    ctx->dropped_at_flush += (uint32_t)ctx->pending.size();
    release_all_pending_locked(ctx);

    // Drain decoder into ready queue with a safety cap.
    for (int i = 0; i < kMaxDrainPerCall; ++i) {
        vvdecFrame* f = nullptr;
        int ret = vvdec_flush(ctx->dec, &f);

        if (ret == VVDEC_EOF || ret == VVDEC_TRY_AGAIN) {
            if (f) {ctx->ready.push_back(f);}
            break;
        }

        if (ret != VVDEC_OK) {
            LOGW("vvdec_flush ret=%d", ret);
            if (f) {
                vvdec_frame_unref(ctx->dec, f);
            }
            break;
        }

        // ret == VVDEC_OK
        if (f) {
            ctx->ready.push_back(f);
        } else {
            // Defensive: avoid potential busy-spin if OK but no frame.
            break;
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeClose(
        JNIEnv*, jclass, jlong handle) {

    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx || ctx->magic != kMagic) {return;}

    {
        std::lock_guard<std::mutex> lk(ctx->dec_mtx);

        release_all_ready_locked(ctx);
        release_all_pending_locked(ctx);

        if (ctx->dec) {
            vvdec_decoder_close(ctx->dec);
            ctx->dec = nullptr;
        }

        // Poison magic under lock to help catch UAF early.
        ctx->magic = 0;
    }

    {
        std::lock_guard<std::mutex> lk(ctx->win_mtx);
        if (ctx->win) {
            ANativeWindow_release(ctx->win);
            ctx->win = nullptr;
        }
        ctx->win_w = ctx->win_h = ctx->win_fmt = 0;
    }

    LOGD("CLOSE stats: decoded=%d displayed=%d not_decoded=%d send_ok=%u tryagain=%u err=%u pics_out=%u dropped_at_flush=%u",
         ctx->num_frames_decoded, ctx->num_frames_displayed, ctx->num_frames_not_decoded,
         ctx->pkts_send_ok, ctx->pkts_send_tryagain, ctx->pkts_send_err,
         ctx->pics_out, ctx->dropped_at_flush);

    delete ctx;
}

extern "C" JNIEXPORT jint JNICALL Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeQueueInput(
        JNIEnv* env, jclass, jlong handle,
        jobject byteBuffer, jint offset, jint size, jlong ptsUs) {

    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!isValid(ctx)) {return -EINVAL;}
    if (!byteBuffer) {return -EINVAL;}
    if (size <= 0) {return -EINVAL;}

    // Validate direct buffer capacity + bounds before touching the pointer.
    jlong cap = env->GetDirectBufferCapacity(byteBuffer);
    if (cap < 0) {
        LOGE("nativeQueueInput: non-direct ByteBuffer");
        return -EINVAL;
    }
    if (offset < 0 || size < 0 || (jlong)offset + (jlong)size > cap) {
        LOGE("nativeQueueInput: bad offset/size (offset=%d size=%d cap=%lld)",
            offset, size, (long long)cap);
        return -EINVAL;
    }

    uint8_t* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!base) {
        LOGE("nativeQueueInput: GetDirectBufferAddress returned null");
        return -EINVAL;
    }
    uint8_t* src = base + offset;

    // Build AU outside the lock to keep lock hold time short.
    auto* node = new (std::nothrow) InputNode();
    if (!node) {return -ENOMEM;}

    node->au = vvdec_accessUnit_alloc();
    if (!node->au) {
        delete node;
        return -ENOMEM;
    }

    vvdec_accessUnit_default(node->au);

    // Allocate payload (returns void in your vvdec headers).
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

    vvdecFrame* out = nullptr;

    {
        std::lock_guard<std::mutex> lk(ctx->dec_mtx);
        if (!isValid(ctx)) {
        delete node;
        return -EINVAL;
    }

    ctx->pkts_in_total++;

    // If the pending queue is full, we prefer not to crash playback.
    // This should be prevented by nativeHasCapacity() in normal operation.
    if (ctx->pending.size() >= kMaxPendingPackets) {
        LOGW("nativeQueueInput: pending full (%zu), dropping AU pts=%lld",
            ctx->pending.size(), (long long)ptsUs);
        delete node;
        ctx->pkts_send_err++;
        ctx->num_frames_not_decoded++;
        return 0;
    }

    // Enqueue AU for later (or immediate) feeding.
    ctx->pending.push_back(node);

    // Try to feed as many queued AUs as vvdec will accept.
    while (!ctx->pending.empty()) {
        InputNode* head = ctx->pending.front();
        out = nullptr;

        int ret = vvdec_decode(ctx->dec, head->au, &out);

        if (ret == VVDEC_TRY_AGAIN) {
        ctx->pkts_send_tryagain++;
        if (out) ctx->ready.push_back(out);
            // Do not consume the AU; keep it in pending.
            break;
        }

        if (ret != VVDEC_OK && ret != VVDEC_EOF) {
            ctx->pkts_send_err++;
            LOGE("nativeQueueInput: vvdec_decode(feed) ret=%d", ret);

            // Drop this AU to avoid stalling the queue indefinitely.
            ctx->pending.pop_front();
            delete head;
            ctx->num_frames_not_decoded++;

            if (out) {
                // Conservative: keep frame if provided.
                ctx->ready.push_back(out);
            }
            // Treat as fatal for this call.
            return -EIO;
        }

        // OK or EOF: AU was accepted/consumed by decoder.
        ctx->pkts_send_ok++;
        ctx->num_frames_decoded++;
        ctx->last_in_pts = head->pts_us;

        ctx->pending.pop_front();
        delete head;

        if (out) {ctx->ready.push_back(out);}

        if (ret == VVDEC_EOF) {
            // No more input accepted after EOF.
            break;
        }
    }

    // Non-blocking drain: pull additional ready frames without feeding.
    for (int i = 0; i < kMaxDrainPerCall; ++i) {
        vvdecFrame* f = nullptr;
        int r = vvdec_decode(ctx->dec, nullptr, &f);

        if (r == VVDEC_TRY_AGAIN) {
            if (f) {ctx->ready.push_back(f);}
            break;
        }
        if (r != VVDEC_OK && r != VVDEC_EOF) {
            LOGE("nativeQueueInput: vvdec_decode(drain) ret=%d", r);
            if (f && ctx->dec) {
                vvdec_frame_unref(ctx->dec, f);
            }
            break;
        }

        if (f) {
            ctx->ready.push_back(f);
        } else {
            // Defensive: avoid potential busy-spin if OK but no frame.
            if (r == VVDEC_OK) {break;}
        }

        if (r == VVDEC_EOF) {break;}
        }
    }

    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
        Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeHasCapacity(
        JNIEnv*, jclass, jlong handle) {

    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!isValid(ctx)) {return JNI_FALSE;}

    std::lock_guard<std::mutex> lk(ctx->dec_mtx);
    if (!isValid(ctx)) {return JNI_FALSE;}

    return (ctx->pending.size() < kMaxPendingPackets) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
        Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeDequeueFrame(
        JNIEnv* env, jclass, jlong handle,
jintArray outWH, jlongArray outPtsUs) {

    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!isValid(ctx)) return 0;
    if (!outWH || !outPtsUs) return 0;

    std::lock_guard<std::mutex> lk(ctx->dec_mtx);
    if (!isValid(ctx)) {return 0;}
    if (ctx->ready.empty()) return 0;

    vvdecFrame* frame = ctx->ready.front();
    ctx->ready.pop_front();
    if (!frame) {return 0;}

    auto* hold = new (std::nothrow) PictureHolder();
    if (!hold) {
        // Put back so we don't leak ownership expectations.
        ctx->ready.push_front(frame);
        return 0;
    }
    hold->frame = frame;

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
    if (!ctx || ctx->magic != kMagic) {return;}

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

    auto* ctx  = reinterpret_cast<NativeCtx*>(handle);
    auto* hold = reinterpret_cast<PictureHolder*>(nativePic);
    if (!ctx || ctx->magic != kMagic || !hold || !hold->frame) {return -EINVAL;}

    const vvdecFrame* f = hold->frame;

    // Fast 8-bit YUV420 planar path (like the dav1d YV12 path)
    if (f->bitDepth != 8 || f->colorFormat != VVDEC_CF_YUV420_PLANAR) {return -ENOSYS;}

    const int w = f->width;
    const int h = f->height;
    const int YV12 = 0x32315659; // 'YV12'

    std::lock_guard<std::mutex> lk(ctx->win_mtx);
    if (!ctx->win) {return -ENODEV;}

    ensureWindowConfigured(ctx, w, h, YV12);

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(ctx->win, &buf, nullptr) != 0) {return -1;}

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
    const int srcYStride  = (int)f->planes[0].stride;
    const int srcUVStride = (int)f->planes[1].stride;

    auto copyPlanePad = [](const uint8_t* s, int ss, uint8_t* d, int ds, int rb, int rows) {
        if (!s || !d || ss <= 0 || ds <= 0 || rb <= 0 || rows <= 0) {return;}
        if (ds == rb) {
            for (int j = 0; j < rows; ++j) {
                memcpy(d, s, rb);
                s += ss;
                d += ds;
            }
        } else {
            const int pad = ds - rb;
            for (int j = 0; j < rows; ++j) {
                memcpy(d, s, rb);
                memset(d + rb, 0, pad);
                s += ss;
                d += ds;
            }
        }
    };

    copyPlanePad(srcY, srcYStride,  dstY, dstYStride,  w,   h);
    copyPlanePad(srcV, srcUVStride, dstV, dstUVStride, uvW, uvH); // V first in YV12
    copyPlanePad(srcU, srcUVStride, dstU, dstUVStride, uvW, uvH);

    ANativeWindow_unlockAndPost(ctx->win);

    // This is a render-only stat; no need for dec_mtx.
    ctx->num_frames_displayed++;
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeReleasePicture(
        JNIEnv*, jclass, jlong handle, jlong nativePic) {

    auto* ctx  = reinterpret_cast<NativeCtx*>(handle);
    auto* hold = reinterpret_cast<PictureHolder*>(nativePic);
    if (!ctx || ctx->magic != kMagic || !hold) {return;}

    std::lock_guard<std::mutex> lk(ctx->dec_mtx);
    if (ctx->dec && hold->frame) {
        vvdec_frame_unref(ctx->dec, hold->frame);
        hold->frame = nullptr;
    }
    delete hold;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_vvdecGetVersion(
        JNIEnv* env, jclass) {

    const char* v = vvdec_get_version();
    return env->NewStringUTF(v ? v : "unknown");
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeSignalEof(
        JNIEnv*, jclass, jlong handle) {

    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx || ctx->magic != kMagic) {return;}

    std::lock_guard<std::mutex> lk(ctx->dec_mtx);
    if (!isValid(ctx)) {return;}
    ctx->eos = true;
}
