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
* All VCAT artwork is owned exclusively by RoncaTech LLC. Use of VCAT logos and artwork is permitted for the purpose of discussing, documenting, or promoting VCAT itself. Any other use requires prior written permission from RoncaTech LLC.
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
    ~InputNode() { if (au) { vvdec_accessUnit_free(au); au = nullptr; } }
    InputNode(const InputNode&) = delete;
    InputNode& operator=(const InputNode&) = delete;
};

struct NativeCtx {
    vvdecDecoder* dec = nullptr;

    std::deque<InputNode*> pending;       // AUs created (freed after feeding)
    std::deque<vvdecFrame*> ready;        // frames produced by vvdec_decode()

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

static inline void ensureWindowConfigured(NativeCtx* ctx, int w, int h, int fmt) {
    if (!ctx->win) return;
    if (ctx->win_w != w || ctx->win_h != h || ctx->win_fmt != fmt) {
        ANativeWindow_setBuffersGeometry(ctx->win, w, h, fmt);
        ctx->win_w = w; ctx->win_h = h; ctx->win_fmt = fmt;
    }
}

static void release_all_pending(NativeCtx* ctx) {
    while (!ctx->pending.empty()) {
        auto* n = ctx->pending.front(); ctx->pending.pop_front();
        delete n;
        ctx->num_frames_not_decoded++;
    }
}

static void release_all_ready(NativeCtx* ctx) {
    while (!ctx->ready.empty()) {
        vvdecFrame* f = ctx->ready.front(); ctx->ready.pop_front();
        vvdec_frame_unref(ctx->dec, f);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeCreate(
        JNIEnv*, jclass, jint threads) {
    auto* ctx = new NativeCtx();

    vvdecParams p;
    vvdec_params_default(&p);
    p.threads = (threads > 0) ? threads : 0; // 0=single, -1=auto

    ctx->dec = vvdec_decoder_open(&p);
    if (!ctx->dec) {
        LOGE("vvdec_decoder_open failed");
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
if (!ctx || !ctx->dec) return;

ctx->dropped_at_flush += (uint32_t)ctx->pending.size();
release_all_pending(ctx);

// Drain decoder into ready queue
for (;;) {
vvdecFrame* f = nullptr;
int ret = vvdec_flush(ctx->dec, &f);
if (ret == VVDEC_EOF) break;
if (ret == VVDEC_TRY_AGAIN) break;
if (ret != VVDEC_OK) { LOGW("vvdec_flush ret=%d", ret); break; }
if (f) ctx->ready.push_back(f);
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeClose(
        JNIEnv*, jclass, jlong handle) {
auto* ctx = reinterpret_cast<NativeCtx*>(handle);
if (!ctx) return;
release_all_ready(ctx);
release_all_pending(ctx);
if (ctx->dec) { vvdec_decoder_close(ctx->dec); ctx->dec = nullptr; }
if (ctx->win) { ANativeWindow_release(ctx->win); ctx->win = nullptr; }
LOGD("CLOSE stats: decoded=%d displayed=%d not_decoded=%d send_ok=%u tryagain=%u err=%u pics_out=%u dropped_at_flush=%u",
     ctx->num_frames_decoded, ctx->num_frames_displayed, ctx->num_frames_not_decoded,
     ctx->pkts_send_ok, ctx->pkts_send_tryagain, ctx->pkts_send_err, ctx->pics_out, ctx->dropped_at_flush);
delete ctx;
}

extern "C" JNIEXPORT jint JNICALL
        Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeQueueInput(
        JNIEnv* env, jclass, jlong handle,
jobject byteBuffer, jint offset, jint size, jlong ptsUs) {
auto* ctx = reinterpret_cast<NativeCtx*>(handle);
if (!ctx || !ctx->dec || !byteBuffer || size <= 0) return -EINVAL;
if (ctx->pending.size() >= kMaxPendingPackets) return -EAGAIN;

uint8_t* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
if (!src) { LOGE("Input buffer not a direct ByteBuffer"); return -EINVAL; }
src += offset;

auto* node = new InputNode();
node->au = vvdec_accessUnit_alloc();
if (!node->au) { delete node; return -ENOMEM; }
vvdec_accessUnit_default(node->au);

// Allocate payload (returns void); check fields afterwards.
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

// Feed this AU; stash any produced frame(s) in ready queue.
vvdecFrame* out = nullptr;
int ret = vvdec_decode(ctx->dec, node->au, &out);
if (ret == VVDEC_TRY_AGAIN) {
ctx->pkts_send_tryagain++;
} else if (ret != VVDEC_OK) {
ctx->pkts_send_err++;
LOGE("vvdec_decode(feed) ret=%d", ret);
} else {
ctx->pkts_send_ok++;
ctx->num_frames_decoded++;
ctx->last_in_pts = ptsUs;
}
// vvdec took ownership/copy, we can free our AU wrapper now
ctx->pending.pop_front();
delete node;

// If decode returned a frame immediately, enqueue it
if (out) ctx->ready.push_back(out);

// Non-blocking drain: pull any additional ready frames without feeding
for (;;) {
vvdecFrame* f = nullptr;
int r = vvdec_decode(ctx->dec, nullptr, &f);
if (r == VVDEC_TRY_AGAIN) break;
if (r != VVDEC_OK && r != VVDEC_EOF) break;
if (f) ctx->ready.push_back(f);
if (r == VVDEC_EOF) break;
}
return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
        Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeHasCapacity(
        JNIEnv*, jclass, jlong handle) {
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

// If we already have a frame queued, use it
vvdecFrame* frame = nullptr;
if (!ctx->ready.empty()) {
frame = ctx->ready.front();
ctx->ready.pop_front();
} else {
return 0;
}

auto* hold = new PictureHolder();
hold->frame = frame;

// Dimensions & pts
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
if (surface) ctx->win = ANativeWindow_fromSurface(env, surface);
}

extern "C" JNIEXPORT jint JNICALL
        Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeRenderToSurface(
        JNIEnv*, jclass, jlong handle, jlong nativePic, jobject) {
auto* ctx  = reinterpret_cast<NativeCtx*>(handle);
auto* hold = reinterpret_cast<PictureHolder*>(nativePic);
if (!ctx || !ctx->dec || !hold || !hold->frame) return -EINVAL;

const vvdecFrame* f = hold->frame;

// Fast 8-bit YUV420 planar path (like the dav1d YV12 path)
if (f->bitDepth != 8 || f->colorFormat != VVDEC_CF_YUV420_PLANAR) return -ENOSYS;

const int w = f->width, h = f->height;
const int YV12 = 0x32315659; // 'YV12'

std::lock_guard<std::mutex> lk(ctx->win_mtx);
if (!ctx->win) return -ENODEV;

ensureWindowConfigured(ctx, w, h, YV12);

ANativeWindow_Buffer buf;
if (ANativeWindow_lock(ctx->win, &buf, nullptr) != 0) return -1;

auto* dstY = static_cast<uint8_t*>(buf.bits);
const int dstYStride = buf.stride;
const int dstUVStride = ((dstYStride >> 1) + 15) & ~15;
const int uvW = (w + 1) / 2, uvH = (h + 1) / 2;
uint8_t* dstV = dstY + dstYStride * h;
uint8_t* dstU = dstV + dstUVStride * uvH;

// vvdecFrame planes
const uint8_t* srcY = (const uint8_t*)f->planes[0].ptr;
const uint8_t* srcU = (const uint8_t*)f->planes[1].ptr;
const uint8_t* srcV = (const uint8_t*)f->planes[2].ptr;
const int srcYStride  = (int)f->planes[0].stride;
const int srcUVStride = (int)f->planes[1].stride;

auto copyPlanePad = [](const uint8_t* s, int ss, uint8_t* d, int ds, int rb, int rows) {
    if (ds == rb) {
        for (int j = 0; j < rows; ++j) { memcpy(d, s, rb); s += ss; d += ds; }
    } else {
        const int pad = ds - rb;
        for (int j = 0; j < rows; ++j) {
            memcpy(d, s, rb);
            memset(d + rb, 0, pad);
            s += ss; d += ds;
        }
    }
};

copyPlanePad(srcY, srcYStride,  dstY, dstYStride,  w,   h);
copyPlanePad(srcV, srcUVStride, dstV, dstUVStride, uvW, uvH); // V first in YV12
copyPlanePad(srcU, srcUVStride, dstU, dstUVStride, uvW, uvH);

ANativeWindow_unlockAndPost(ctx->win);
ctx->num_frames_displayed++;
return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeReleasePicture(
        JNIEnv*, jclass, jlong handle, jlong nativePic) {
auto* ctx  = reinterpret_cast<NativeCtx*>(handle);
auto* hold = reinterpret_cast<PictureHolder*>(nativePic);
if (!ctx || !ctx->dec || !hold) return;
if (hold->frame) {
vvdec_frame_unref(ctx->dec, hold->frame);
hold->frame = nullptr;
}
delete hold;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_vvdecGetVersion(JNIEnv* env, jclass) {
    const char* v = vvdec_get_version();
    return env->NewStringUTF(v ? v : "unknown");
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_libvcat_vvdec_NativeVvdec_nativeSignalEof(
        JNIEnv*, jclass, jlong handle) {
auto* ctx = reinterpret_cast<NativeCtx*>(handle);
if (!ctx || !ctx->dec) return;
ctx->eos = true;
}
