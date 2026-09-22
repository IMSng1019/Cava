/* cava_handle.cpp —— 句柄生命周期（B1）。
 *
 * 设计要点（都是为了「非法句柄绝不段错误」）：
 *   - 句柄**不是裸指针**：handle = ((uint64)generation << 32) | (uint64)(slot_index + 1)。
 *     slot_index+1 >= 1，所以句柄恒非 0；generation 从 1 开始，重复关闭后 +1，
 *     老句柄不会「复活」到新对象上。
 *   - 固定槽位表 + std::shared_ptr：查表拿 shared_ptr，关表后对象不会立刻析构，
 *     并发 close 不会让正在用的人踩空（P0 没并发，但先把地基打对）。
 *   - 所有入口先校验 null / 越界 / magic / generation，任一不对返回错误码。
 */
#include "cava_internal.h"

#include <atomic>
#include <memory>
#include <mutex>

namespace {

constexpr uint32_t kMagicLive = 0x41564143u; /* 'C','A','V','A'（小端读作 CAVA）*/
constexpr uint32_t kMagicDead = 0x44414544u; /* 'D','E','A','D'：只用于诊断 */
constexpr int32_t  kMaxHandles = 256;

struct CavaInstance {
    uint32_t magic = kMagicLive;
    uint32_t generation = 1;
    int32_t  flags = 0;
    int32_t  abi_version = CAVA_ABI_VERSION;
    uint64_t native_layout_sum = 0;
    int64_t  serial = 0;
};

struct Slot {
    std::shared_ptr<CavaInstance> inst; /* null = 空槽 */
    uint32_t next_generation = 1;       /* 下一个要发出去的 generation（从 1 起，0 保留）*/
};

std::mutex  g_slot_mutex;
Slot        g_slots[kMaxHandles];
std::atomic<int64_t> g_serial{0};

inline int64_t encode_handle(uint32_t slot_index, uint32_t generation) {
    return (int64_t)(((uint64_t)generation << 32) | (uint64_t)(slot_index + 1));
}

inline bool decode_handle(int64_t handle, uint32_t* out_slot_index, uint32_t* out_generation) {
    const uint64_t u = (uint64_t)handle;
    const uint32_t lo = (uint32_t)(u & 0xFFFFFFFFull);
    if (lo == 0 || lo > (uint32_t)kMaxHandles) {
        return false;
    }
    *out_slot_index = lo - 1;
    *out_generation = (uint32_t)(u >> 32);
    return true;
}

/* 查表；只有「活着且 generation 对得上」才返回对象。
 * P0 还没有子系统入口调用它（P1 才会用），先留着当地基。*/
[[maybe_unused]] std::shared_ptr<CavaInstance> lookup(int64_t handle) {
    uint32_t idx = 0;
    uint32_t gen = 0;
    if (!decode_handle(handle, &idx, &gen)) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_slot_mutex);
    Slot& s = g_slots[idx];
    if (!s.inst || s.inst->magic != kMagicLive || s.inst->generation != gen) {
        return nullptr;
    }
    return s.inst;
}

} /* namespace */

extern "C" CAVA_EXPORT int32_t cava_open(const CavaOpenParams* params,
                                         int64_t* out_handle,
                                         CavaOpenResult* out_result) {
    /* 出参先置失败态：任何提前 return 都不会留下野句柄。*/
    if (out_handle != nullptr) {
        *out_handle = 0;
    }
    const uint64_t native_sum = cava::detail::native_layout_sum();
    if (out_result != nullptr) {
        out_result->status            = CAVA_ERR_NULL;
        out_result->abi_version       = CAVA_ABI_VERSION;
        out_result->native_layout_sum = native_sum; /* 永远回填，方便 Java 打印诊断 */
        out_result->reserved0         = 0;
    }

    CAVA_ASSERT(out_handle != nullptr, CAVA_ERR_NULL);
    CAVA_ASSERT(params != nullptr, CAVA_ERR_NULL);
    if (out_handle == nullptr || params == nullptr) {
        return CAVA_ERR_NULL;
    }
    if (params->abi_version != CAVA_ABI_VERSION) {
        if (out_result != nullptr) {
            out_result->status = CAVA_ERR_ABI_VERSION;
        }
        return CAVA_ERR_ABI_VERSION;
    }
    if (params->layout_hash_sum != native_sum) {
        if (out_result != nullptr) {
            out_result->status = CAVA_ERR_LAYOUT;
        }
        return CAVA_ERR_LAYOUT;
    }

    int32_t status = CAVA_OK;
    int64_t handle = 0;
    try {
        std::lock_guard<std::mutex> lock(g_slot_mutex);
        int32_t free_idx = -1;
        for (int32_t i = 0; i < kMaxHandles; ++i) {
            if (!g_slots[i].inst) {
                free_idx = i;
                break;
            }
        }
        if (free_idx < 0) {
            status = CAVA_ERR_OOM; /* 句柄表满：当成资源耗尽 */
        } else {
            Slot& s = g_slots[free_idx];
            if (s.next_generation == 0) {
                s.next_generation = 1; /* generation 0 保留；回绕时跳过去（2^32 次 open 才可能发生）*/
            }
            std::shared_ptr<CavaInstance> inst(new CavaInstance());
            inst->generation         = s.next_generation;
            inst->flags              = params->flags;
            inst->native_layout_sum  = native_sum;
            inst->serial             = g_serial.fetch_add(1, std::memory_order_relaxed) + 1;
            s.inst = inst;
            handle = encode_handle((uint32_t)free_idx, inst->generation);
        }
    } catch (...) {
        status = CAVA_ERR_OOM; /* 分配失败：不抛过 ABI 边界 */
    }

    if (status != CAVA_OK) {
        if (out_result != nullptr) {
            out_result->status = status;
        }
        return status;
    }

    *out_handle = handle;
    if (out_result != nullptr) {
        out_result->status = CAVA_OK;
    }
    return CAVA_OK;
}

extern "C" CAVA_EXPORT int32_t cava_close(int64_t handle) {
    if (handle == 0) {
        return CAVA_ERR_NULL; /* 契约 2.2：关闭 0 返回 CAVA_ERR_NULL */
    }
    uint32_t idx = 0;
    uint32_t gen = 0;
    if (!decode_handle(handle, &idx, &gen)) {
        return CAVA_ERR_ARG; /* 槽位号越界/为 0：伪造句柄 */
    }
    std::lock_guard<std::mutex> lock(g_slot_mutex);
    Slot& s = g_slots[idx];
    if (s.inst && s.inst->generation == gen && s.inst->magic == kMagicLive) {
        s.inst->magic = kMagicDead;
        s.inst.reset();
        s.next_generation = gen + 1;
        if (s.next_generation == 0) {
            s.next_generation = 1;
        }
        return CAVA_OK;
    }
    /* 契约 2.2：重复关闭返回 CAVA_ERR_NULL（陈旧句柄 = generation 比当前小）。
     * generation 比当前大 => 这个句柄从来没人发过 => 伪造，返回 CAVA_ERR_ARG。*/
    if (gen < s.next_generation) {
        return CAVA_ERR_NULL;
    }
    return CAVA_ERR_ARG;
}
