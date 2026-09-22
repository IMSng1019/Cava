/* cava_abi.h —— Cava 原生库 ABI 的唯一权威定义。
 *
 * 纪律（违反即返工，见 docs/CAVA-工程接口契约.md）：
 *   1. 本头文件是 Java(FFM) 与 C++ 之间唯一的契约。任何一侧改动都必须改这里。
 *   2. 结构体只通过指针跨边界，绝不按值传递、绝不返回结构体。
 *   3. 允许跨边界的标量：int8_t / int16_t / int32_t / int64_t / uint32_t / uint64_t /
 *      float / double / void* / int32_t 句柄。禁用 long、禁用裸 char 做数值。
 *   4. 数组一律 (指针, 长度) 且必须同源；调用方保证容量，被调用方保证不越界。
 *   5. 数值一致性铁律：只有 + - * / 与 sqrt 允许在原生侧参与"会被观测到"的数值计算。
 *      sin/cos/tan/atan2/exp/log/pow 一律留在 Java。
 *   6. 编译固定：-O2 -fwrapv -ffp-contract=off -fno-fast-math（MSVC: /O2 /fp:strict），禁 -march=native。
 *   7. 任何入口都必须能在"未初始化/已释放/参数非法"时安全返回错误码，绝不段错误。
 */
#ifndef CAVA_ABI_H
#define CAVA_ABI_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ */
/* 版本                                                                */
/* ------------------------------------------------------------------ */

/* ABI 主版本。不兼容改动才 +1；加字段走 cava_layout_* 自检自动失效。*/
#define CAVA_ABI_VERSION 1

/* 布局自检使用的签名素因子（仅用于混淆指纹，不参与运算）。*/
#define CAVA_LAYOUT_FNV_OFFSET 0x811C9DC5u
#define CAVA_LAYOUT_FNV_PRIME  0x01000193u

/* ------------------------------------------------------------------ */
/* 静态字符串（生命周期 = 进程，调用方不得释放）                        */
/* ------------------------------------------------------------------ */

/* 例："cava 0.1.0 win-x64 mingw-gcc15.2.0 O2/fwrapv/ffp-contract=off O0=0" */
const char* cava_build_id(void);

/* 原子地 +1 并返回新值；用于 Java 侧观测原生代码真的被调用过（金丝雀）。*/
int64_t cava_abi_touch(void);

/* 返回 CAVA_ABI_VERSION。*/
int32_t cava_abi_version(void);

/* ------------------------------------------------------------------ */
/* 布局自检                                                            */
/* ------------------------------------------------------------------ */
/* 每个结构体必须提供 cava_layout_<name>(hash*, size*, align*)，返回字段个数。
 * Java 侧用同样的公式算出期望值，任一不等 => 整体回退纯 Java。
 * 公式（必须逐位一致，用 CAVA_LAYOUT_FNV_OFFSET/PRIME 做 32 位 FNV-1a，对
 * 每个字段依次喂入 (offset:u32, size:u32) 的小端字节）——见 cava_layout.c 实现。*/

#define CAVA_LAYOUT_MAX_FIELDS 32
#define CAVA_LAYOUT_REPORT_CAP 64

typedef struct CavaLayoutEntry {
    int32_t  abi_version;   /* CAVA_ABI_VERSION */
    int32_t  reserved0;
    uint64_t struct_size;
    uint64_t struct_align;
    uint32_t field_count;
    uint32_t layout_hash;
    uint64_t field_offsets[CAVA_LAYOUT_MAX_FIELDS];
    uint64_t field_sizes[CAVA_LAYOUT_MAX_FIELDS];
} CavaLayoutEntry;

typedef struct CavaLayoutReport {
    int32_t         abi_version;
    int32_t         build_flags;    /* 位标志，见 CAVA_BUILD_FLAG_* */
    int32_t         platform;       /* CAVA_PLATFORM_* */
    int32_t         pointer_size;
    int32_t         entry_count;
    int32_t         reserved0;
    uint64_t        build_id_hash;  /* 对 cava_build_id() 的 32 位 FNV-1a */
    CavaLayoutEntry entries[CAVA_LAYOUT_REPORT_CAP];
} CavaLayoutReport;

#define CAVA_BUILD_FLAG_SAFE_ASSERTS   (1 << 0)
#define CAVA_BUILD_FLAG_DEBUG          (1 << 1)
#define CAVA_BUILD_FLAG_ASAN           (1 << 2)
#define CAVA_BUILD_FLAG_UBSAN          (1 << 3)

#define CAVA_PLATFORM_WINDOWS_X64 1
#define CAVA_PLATFORM_WINDOWS_ARM64 2
#define CAVA_PLATFORM_LINUX_X64 3
#define CAVA_PLATFORM_LINUX_ARM64 4
#define CAVA_PLATFORM_MACOS_X64 5
#define CAVA_PLATFORM_MACOS_ARM64 6

/* 把全部结构体布局填进 report。返回实际写入的 entry 个数（<0 = 参数非法）。*/
int32_t cava_layout_report(CavaLayoutReport* out);

/* ------------------------------------------------------------------ */
/* 错误码                                                              */
/* ------------------------------------------------------------------ */
#define CAVA_OK               0
#define CAVA_ERR_ABI_VERSION  (-1)
#define CAVA_ERR_LAYOUT       (-2)
#define CAVA_ERR_NULL         (-3)
#define CAVA_ERR_ARG          (-4)
#define CAVA_ERR_OOM          (-5)
#define CAVA_ERR_INTERNAL     (-6)
#define CAVA_ERR_UNIMPLEMENTED (-7)

typedef struct CavaOpenParams {
    int32_t  abi_version;       /* in: 期望的 CAVA_ABI_VERSION */
    int32_t  flags;             /* in: 位标志，见 CAVA_OPEN_FLAG_* */
    uint64_t layout_hash_sum;   /* in: Java 侧算出的期望布局哈希和（见契约文档）*/
    int64_t  reserved0;
    int64_t  reserved1;
} CavaOpenParams;

#define CAVA_OPEN_FLAG_SAFE_ASSERTS (1 << 0)
#define CAVA_OPEN_FLAG_DETERMINISTIC (1 << 1)  /* 拒绝任何依赖 wall-clock/线程调度的路径 */

typedef struct CavaOpenResult {
    int32_t  status;            /* out: CAVA_OK / CAVA_ERR_* */
    int32_t  abi_version;       /* out: 原生库自己的 CAVA_ABI_VERSION */
    uint64_t native_layout_sum; /* out: 原生库算出的布局哈希和 */
    int64_t  reserved0;
} CavaOpenResult;

/* ------------------------------------------------------------------ */
/* 句柄生命周期                                                        */
/* ------------------------------------------------------------------ */
/* 约定：所有返回句柄的入口都把句柄写进 *out_handle（类型 int64_t，0 表示失败），
 *       并把状态写进 *out_result（可为 NULL）。返回 status 便于 Java 直接判定。*/

int32_t cava_open(const CavaOpenParams* params, int64_t* out_handle, CavaOpenResult* out_result);
int32_t cava_close(int64_t handle);

/* ------------------------------------------------------------------ */
/* P1 生物寻路：请求 / 响应                                            */
/* ------------------------------------------------------------------ */
/* 一次调用返回整条路径。所有坐标都是**世界坐标**（Java 侧已经算好区域偏移），
 * 原生侧不做任何世界边界裁剪外的隐式变换。
 *
 * 纪律（parity 风险点，见 docs/CAVA-v1-plan.md 4.1）：
 *   - 邻居展开顺序、PathMinHeap 的 sift/sift-down 与**相等元素的相对顺序**必须逐位复刻；
 *   - malus 一律用 float，禁止中途提升为 double、禁止改结合顺序；
 *   - g/f 值是 float，跨边界原样传位模式。
 */

/* 通行档案（决定用哪个 PathNodeMaker 的语义）。位或组合非法。*/
#define CAVA_PROFILE_LAND             1
#define CAVA_PROFILE_WATER            2
#define CAVA_PROFILE_FLYING           4
#define CAVA_PROFILE_AMPHIBIOUS       8

/* 能力位（对应 MobNavigation 的 canOpenDoors / canEnterOpenDoors / canFloat 等）。*/
#define CAVA_CAP_CAN_OPEN_DOORS       (1 << 16)
#define CAVA_CAP_CAN_ENTER_OPEN_DOORS (1 << 17)
#define CAVA_CAP_CAN_FLOAT            (1 << 18)
#define CAVA_CAP_CAN_WALK_ON_WATER    (1 << 19)

#define CAVA_PATH_NODE_TERMINAL 0x1u  /* 终点节点，Java 侧据此做 Path 截断/后处理 */

typedef struct CavaPathRequest {
    int32_t sx, sy, sz;             /* in: 起点方块坐标 */
    int32_t tx, ty, tz;             /* in: 终点方块坐标 */
    int32_t maxVisitedNodes;        /* in: 预算，<=0 视为默认 */
    int32_t reachRange;             /* in: 终点可接受的曼哈顿/切比雪夫相近范围（按 PathNodeMaker 语义）*/
    float   maxFallDistance;        /* in: 与 getMaxFallDistance 同源，float 原样 */
    uint32_t profile;               /* in: CAVA_PROFILE_* | CAVA_CAP_* */
    uint32_t flags;                 /* in: 保留，必须为 0；非 0 时返回 CAVA_ERR_ARG */
    int64_t  reserved0;             /* in: 必须为 0 */
} CavaPathRequest;

typedef struct CavaPathNode {
    int32_t x, y, z;
    int32_t heapIndex;              /* out: 复刻用的堆下标，差值即为 parity 证据 */
    float   g;                      /* out: 原样位模式 */
    float   f;                      /* out: 原样位模式 */
    uint32_t type;                  /* out: PathNodeType 序号（见契约） */
    uint32_t flags;                 /* out: CAVA_PATH_NODE_* */
} CavaPathNode;

/* 返回值：>0 = 节点数（写入 out[0..n-1]，顺序即 Path 顺序）；
 *         0  = 无路径（合法结果，Java 侧走原逻辑的回退分支）；
 *         <0 = 错误码（Java 侧必须回退原逻辑）。
 * cap 不足时返回 CAVA_ERR_ARG，**绝不部分写入**。*/
int32_t cava_pathfind(int64_t handle, const CavaPathRequest* req, CavaPathNode* out, int32_t cap);

/* 镜像侧 ABI（区块/方块状态推送）**故意留到 P1 开工时冻结**：
 * 它必须由"区段镜像 + 方块状态表"的实际实现推导，提前冻结会锁死错误的形状。
 * 在此之前，P1 代理只能实现 cava_pathfind 的纯算法内核 + 从 Java 侧注入的
 * 只读方块查询回调，不得自行发明镜像 ABI。*/

/* ------------------------------------------------------------------ */
/* 数值工具（跨平台逐位一致性的唯一入口）                               */
/* ------------------------------------------------------------------ */
/* Java 的 (double)->int 越界饱和；C++ 直接转是 UB。所有需要落到 int 的
 * double 都必须走这里。NaN -> 0（与 Java 的 (int)Double.NaN == 0 一致）。*/
int32_t cava_d2i_sat(double v);
int64_t cava_d2l_sat(double v);

/* 原样传递 double 的位模式（用于自检与差分测试，不做任何数值转换）。*/
uint64_t cava_bits_of_double(double v);
double   cava_double_of_bits(uint64_t bits);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* CAVA_ABI_H */