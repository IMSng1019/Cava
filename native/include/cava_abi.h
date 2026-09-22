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
/* 每个结构体必须能算出一个 32 位 layout_hash；Java 侧用同样的公式算出期望值，
 * 任一不等 => 整体回退纯 Java。
 *
 * 【唯一权威公式，2026-09-22 裁定】32 位 FNV-1a，对每个字段按声明顺序走满 4 步：
 *     对 (uint32_t)offset        喂 **1 个 uint32**（整个 32 位值参与异或，不截断成字节）
 *     对 (uint32_t)size          喂 **1 个 uint32**
 *     对 (uint32_t)(offset>>32)  喂 **1 个 uint32**
 *     对 (uint32_t)(size>>32)    喂 **1 个 uint32**
 *   即每个字段固定 4 次 `h ^= v; h *= PRIME`，其中 v 是完整的 uint32（32 位字段的高位补 0 也要走这 4 步）。
 *   数组字段算**一个**字段（offset = 数组起点，size = 整个数组的字节数）。
 *
 *   ⚠️ **措辞勘误（2026-09-22，由 P0-C 指出）**：本注释早期写成"喂 1 字节"，按字面读会变成
 *   "每次只异或该 u32 的**最低字节**" —— 那样**算不出**实测值（size=256 会退化成 0）。
 *   两侧实现用的都是**整个 uint32 参与异或**。下一个人不要照旧措辞实现。
 *
 * layout_hash_sum = 所有导出结构体 layout_hash 的 uint32 无符号加法（回绕）。
 *   **本机实测权威值（9 个结构体，2026-09-22）**：
 *     CavaLayoutEntry=0xF837804D / CavaLayoutReport=0xE9FFC021 / CavaOpenParams=0x7FDE7499 /
 *     CavaOpenResult=0xFF344829 / CavaPathRequest=0xE566F98D / CavaPathNode=0x0DCFFE65 /
 *     CavaMobProfile=0x9C6C98CD / CavaStateRecord=0x53797229 / CavaCollisionBox=0x250ECBE1
 *     =>  sum = 0x6975CBF9
 *   （旧的 4 结构体和值 0x6149FD30 已作废：扩了 5 个结构体，且 CavaPathNode 曾漏登记 2 个字段。）
 *
 * 【已废除】更早期注释里写的"逐字节"变体（sum=0xDB2A07ED）**不是**契约的一部分，
 * 双变体协商代码已删除（原生侧会以 CAVA_ERR_LAYOUT 拒绝它）。*/

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

/* ------------------------------------------------------------------ */
/* 生物档案（每个生物一份，变化时重推一次）                            */
/* ------------------------------------------------------------------ */
/* 为什么单独开一张表而不是塞进 CavaPathRequest：原版陆地寻路要的
 * width/height/stepHeight/惩罚表/世界上下界都是**每个生物（每个维度）恒定**的量，
 * 每次调用都传会浪费 FFM 边界（实测 14–16 ns/次）。*/

/* 能力位（proposal 里的 caps）。低 16 位是"导航能力"，高 16 位留给后续。*/
#define CAVA_NAV_CAN_OPEN_DOORS       (1u << 0)
#define CAVA_NAV_CAN_ENTER_OPEN_DOORS (1u << 1)
#define CAVA_NAV_CAN_FLOAT            (1u << 2)
#define CAVA_NAV_AMPHIBIOUS           (1u << 3)
#define CAVA_NAV_PENALIZE_DEEP_WATER  (1u << 4)
#define CAVA_NAV_CAN_WALK_OVER_FENCES (1u << 5)
#define CAVA_NAV_CAN_SWIM             (1u << 6)
#define CAVA_NAV_CAN_PATHFIND_THROUGH (1u << 7)  /* 仅飞行类有意义 */
#define CAVA_NAV_ON_GROUND            (1u << 8)
#define CAVA_NAV_TOUCHING_WATER       (1u << 9)
#define CAVA_NAV_CAN_WALK_ON_FLUID    (1u << 10)

/* PathNodeType 的完整序号表 = **Yarn 1.20.4 枚举的 ordinal，逐条从字节码 static{} 读出**
 * （`javap -p -c net.minecraft.entity.ai.pathing.PathNodeType`：每个常量先 push ordinal 再
 * `<init>(String,int,float)`，随后 `putstatic`。低位用 iconst_*，≥6 用 bipush）。
 * 惩罚表 float penalty[26] 按它索引，所以**这张表错一位 = 整张惩罚表错位，且路径照样能算出来** ——
 * 属于最难发现的 parity bug。任何改动都必须重读字节码。
 *
 * ⚠️ **勘误（2026-09-22）**：本表第一版是 captain 凭记忆写的，**序号顺序全错、还包含 4 个
 * 1.20.4 里根本不存在的常量**（DAMAGE_CACTUS / DOOR_OPEN_IRON / DAMAGE_WITHER_ROSE / DANGER_WATER），
 * 同时缺 POWDER_SNOW / WATER_BORDER / DAMAGE_CAUTIOUS / DANGER_TRAPDOOR。由 P1 流发现并以
 * javap 实证纠正。下表为**实测值**。*/
#define CAVA_PNT_BLOCKED             0
#define CAVA_PNT_OPEN                1
#define CAVA_PNT_WALKABLE            2
#define CAVA_PNT_WALKABLE_DOOR       3
#define CAVA_PNT_TRAPDOOR            4
#define CAVA_PNT_POWDER_SNOW         5
#define CAVA_PNT_DANGER_POWDER_SNOW  6
#define CAVA_PNT_FENCE               7
#define CAVA_PNT_LAVA                8
#define CAVA_PNT_WATER               9
#define CAVA_PNT_WATER_BORDER       10
#define CAVA_PNT_RAIL               11
#define CAVA_PNT_UNPASSABLE_RAIL    12
#define CAVA_PNT_DANGER_FIRE        13
#define CAVA_PNT_DAMAGE_FIRE        14
#define CAVA_PNT_DANGER_OTHER       15
#define CAVA_PNT_DAMAGE_OTHER       16
#define CAVA_PNT_DOOR_OPEN          17
#define CAVA_PNT_DOOR_WOOD_CLOSED   18
#define CAVA_PNT_DOOR_IRON_CLOSED   19
#define CAVA_PNT_BREACH             20
#define CAVA_PNT_LEAVES             21
#define CAVA_PNT_STICKY_HONEY       22
#define CAVA_PNT_COCOA              23
#define CAVA_PNT_DAMAGE_CAUTIOUS    24
#define CAVA_PNT_DANGER_TRAPDOOR    25

#define CAVA_PNT_COUNT 26
/* 与 Java 侧一致：PathNodeType 里没有"UNPASSABLE"这个常量，只有 UNPASSABLE_RAIL。*/
#define CAVA_PENALTY_ALL_SET 0x03FFFFFFu  /* penalty_mask 全 1 */

typedef struct CavaMobProfile {
    /* **字段顺序不要重排**（实测约束）：先用 2 个 4 字节字段把 3 个 double 顶到
     * 8 的倍数偏移上（float[26] + float + int32 = 112），这样结构体内部
     * **不需要任何填充**，Java 侧 MemoryLayout.structLayout 才不用显式 paddingLayout
     * （实测：字段错位会让 FFM 抛 "Invalid alignment constraint for member layout"，
     * 而且交错放置曾把 float 顶到非 4 对齐的偏移上）。*/

    /* 惩罚表：索引 = CAVA_PNT_*，值 = Entity.getPathfindingPenalty(type)。
     * 只有 penalty_mask 里置 1 的项有效，未置位的用 PathNodeType 的默认值。*/
    float    penalty[CAVA_PNT_COUNT];
    float    max_fall_distance;     /* in: 与 getMaxFallDistance 同源，float 原样 */

    /* 起点：**起点是实体位姿推出来的**（pathNodeMaker.getStart()），
     * 不是从参数取。所以这里给 double 位姿，不给"起点方块坐标"。*/
    double   start_x, start_y, start_z;
    int32_t  start_block_x, start_block_y, start_block_z;  /* in: 实体所在方块坐标 */

    float    width;                 /* in: Entity.getWidth() */
    float    height;                /* in: Entity.getHeight() */
    float    step_height;           /* in: Entity.getStepHeight() */
    int32_t  safe_fall_distance;    /* in: getSafeFallDistance() */
    int32_t  min_y;                 /* in: world.getBottomY() */
    int32_t  sea_level;             /* in: world.getSeaLevel() */
    uint32_t caps;                  /* in: CAVA_NAV_* */
    uint32_t penalty_mask;          /* in: 置 1 的项才用上面的值 */
    int32_t  reserved0;
    int32_t  reserved1;
} CavaMobProfile;

/* 字段顺序说明（**不要随意重排**）：int64 放在开头 8 字节边界上，
 * 后面全部是 4 字节字段，这样**结构体内部不需要任何填充**，
 * Java 侧 MemoryLayout.structLayout 才能不用显式 paddingLayout 就对齐
 * （实测：把 int64 放在 24 字节处会让 FFM 抛 "Invalid alignment constraint"）。*/
typedef struct CavaPathRequest {
    int64_t  reserved1;             /* in: 必须为 0（也把结构体顶到 8 字节对齐）*/
    int32_t  tx, ty, tz;            /* in: 终点方块坐标 */
    int32_t  reach_range;           /* in: 终点可接受的相近范围（PathNodeMaker 语义）*/
    float    max_range;             /* in: findPathToAny 的 maxRange（float，原样）*/
    uint32_t flags;                 /* in: 保留，必须为 0；非 0 时返回 CAVA_ERR_ARG */
    int32_t  reserved0;
    int32_t  reserved2;
    int32_t  max_visited_nodes;     /* in: 真实预算；<=0 表示"由 max_range 推" */
    /* 尾部填充写成**具名字段**（不是 C 的匿名填充）：这样 Java 侧能用普通命名字段
     * 一一对应，不必依赖无法命名的 MemoryLayout.paddingLayout（JDK 21 的 paddingLayout
     * 没有 withName，实测会给布局计算带来两边的字段数/大小不一致）。*/
    int32_t  pad0;
    int32_t  pad1;
    int32_t  pad2;
} CavaPathRequest;   /* 13 个 4 字节字段 + 1 个 int64 = 56 字节，8 对齐，零内部填充 */
/* 断言：本结构体**内部没有任何填充**，全部字段都是 4 字节步长（首个 int64 占 0..7）。*/

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

/* 上传/更新当前生物档案。同一句柄同一时刻只有一份"当前档案"。
 * 任一字段非法（width<=0 / height<=0 / NaN / profile 未上传）=> CAVA_ERR_ARG，
 * 且**不改变已有档案**。*/
int32_t cava_mob_profile_upload(int64_t handle, const CavaMobProfile* profile);

/* 释放当前档案（生物卸载/换维度时调用）。幂等。*/
int32_t cava_mob_profile_clear(int64_t handle);

/* ------------------------------------------------------------------ */
/* 镜像侧 ABI：方块状态表 + 区域推送（P1 已冻结）                       */
/* ------------------------------------------------------------------ */
/* 设计裁定（2026-09-22，captain）：
 *   镜像**不把整块世界搬到原生**，而是由 Java 侧按需推送一个**有界的长方体区域**
 *   （pathfinding 的求解窗口）。理由：整块镜像需要调色板压缩 + 脏标记 + 区段卸载通知，
 *   而 P1 的每次寻路本来就有天然边界（起点→终点 + maxVisitedNodes）。
 *
 *   方块状态用 **state id** 作键，id 的定义 = Block.STATE_IDS 的原始 id
 *   （Yarn: net.minecraft.block.Block.getRawIdFromState(BlockState) / getStateFromRawId(int)）。
 *   **禁止在任何地方用 BlockState 的对象身份做键** —— FerriteCore 的
 *   blockstateCacheDeduplication 会让内容相同的状态共享实例。
 *
 *   state id 的具体数值由 **Java 侧**在推送时给出（它不是编译期常量），
 *   原生侧只把它们当作不透明整数。方块状态表一次性推送（cava_state_table_upload）。
 *
 *   已知待验证假设：**air 的 state id == 0**。Java 侧在启动时必须实测校验
 *   （Block.getRawIdFromState(Blocks.AIR.getDefaultState()) == 0），
 *   不成立时**不要静默**，要么拒绝启用该子系统，要么按实际 id 传参。*/

/* 方块状态表的 flags：**32 位掩码**。
 * 低 8 位 = 跨子系统通用的静态属性（CAVA_SF_*）；
 * 高 24 位（1<<8 起）= **寻路专用谓词**（CAVA_PF_*），逐位对应原版
 * getCommonNodeType 的一个分支。**只允许在末尾追加新位，绝不复用旧位。** */
#define CAVA_SF_SOLID            (1u << 0)  /* isSolid */
#define CAVA_SF_BLOCKS_MOTION    (1u << 1)  /* blocksMotion */
#define CAVA_SF_FLUID            (1u << 2)
#define CAVA_SF_WATER            (1u << 3)
#define CAVA_SF_LAVA             (1u << 4)
#define CAVA_SF_OPEN             (1u << 5)  /* 门/活板门/栅栏门的"开着" */
#define CAVA_SF_AIR              (1u << 6)  /* isAir */
#define CAVA_SF_DOOR             (1u << 7)  /* 是门（任意开关状态）*/

/* 寻路专用谓词（原版 getCommonNodeType 的判据）。名字对应
 * docs/CAVA-pathfind-oracle-spec.md 与 native/src/pathfind/cava_pf.h 的 PF_*。*/
#define CAVA_PF_TRAPDOOR          (1u << 8)
#define CAVA_PF_POWDER_SNOW       (1u << 9)
#define CAVA_PF_CACTUS_OR_BERRY   (1u << 10)
#define CAVA_PF_HONEY             (1u << 11)
#define CAVA_PF_COCOA             (1u << 12)
#define CAVA_PF_CAUTIOUS          (1u << 13)  /* 谨慎方块（岩浆锅/营火等）*/
#define CAVA_PF_DOOR_HAND         (1u << 14)  /* 门可由该生物用手开 */
#define CAVA_PF_RAIL              (1u << 15)
#define CAVA_PF_LEAVES            (1u << 16)
#define CAVA_PF_FENCES            (1u << 17)
#define CAVA_PF_WALLS             (1u << 18)
#define CAVA_PF_FENCE_GATE        (1u << 19)
#define CAVA_PF_FIRE_DAMAGE       (1u << 20)  /* 会造成火焰伤害 */
#define CAVA_PF_PATH_THROUGH_LAND (1u << 21)  /* canPathfindThrough(LAND) */
#define CAVA_PF_WATER_BLOCK       (1u << 22)  /* 水方块（区别于"含流体"）*/
#define CAVA_PF_FENCE_OR_WALL_CLOSED (1u << 23)
#define CAVA_PF_DOOR_IRON         (1u << 24)  /* 铁门（不能用手开）*/
#define CAVA_PF_FIRE              (1u << 25)  /* 火焰方块 */
#define CAVA_PF_WITHER_ROSE       (1u << 26)  /* 凋灵玫瑰（危险）*/
/* 1u << 27 .. 1u << 31 保留（追加新谓词时从 27 往上加，绝不复用）*/

/* PathNodeType 的序号表在生物档案一节（CAVA_PNT_*）。*/
#define CAVA_PATH_TYPE_COUNT CAVA_PNT_COUNT

typedef struct CavaCollisionBox {
    float min_x, min_y, min_z;   /* 相对方块原点的扁平 AABB 集合，不是体素近似 */
    float max_x, max_y, max_z;
} CavaCollisionBox;

typedef struct CavaStateRecord {
    uint32_t flags;              /* CAVA_SF_* */
    uint32_t box_offset;         /* 进 coll_boxes 的下标，MAX 表示无碰撞盒 */
    uint32_t box_count;
    uint32_t path_type_idx;      /* 该状态的默认 PathNodeType 序号 */
    float    malus;              /* 该状态的默认 malus，float 原样 */
} CavaStateRecord;

#define CAVA_BOX_NONE 0xFFFFFFFFu

/* 一次性上传方块状态表。cap 不足返回 CAVA_ERR_ARG 且不写任何内容。*/
int32_t cava_state_table_upload(int64_t handle,
                                const CavaStateRecord* records, int32_t record_count,
                                const CavaCollisionBox* boxes, int32_t box_count);

/* 区域推送：把 Java 侧读到的 state id 拷进原生侧的区域缓存。
 * ids 是 dim_x*dim_y*dim_z 个 int32，索引顺序 = ((y*dim_z)+z)*dim_x+x，
 * 即 **x 最快、y 最慢**（与 region_state_id_at 一致）。
 * 任一维度 <=0、或 dim 乘积 <=0、或超过原生侧上限 => CAVA_ERR_ARG，且不改变已有区域。*/
int32_t cava_region_upload(int64_t handle,
                           int32_t dim_x, int32_t dim_y, int32_t dim_z,
                           int32_t origin_x, int32_t origin_y, int32_t origin_z,
                           const int32_t* ids, int32_t id_count);

int32_t cava_region_clear(int64_t handle);

typedef struct CavaRegionQuery {
    int32_t x, y, z;             /* in: 世界方块坐标 */
    int32_t state_id;            /* out: 该坐标的 state id；区域外为 -1 */
    uint32_t flags;              /* out: 从状态表查到的 CAVA_SF_*（区域外为 0）*/
    uint32_t box_count;          /* out: 该方块碰撞盒个数 */
} CavaRegionQuery;

/* 单点查询，给单元层/调试用。真正热的路径不要让 Java 逐点查。*/
int32_t cava_region_state_id_at(int64_t handle, int32_t x, int32_t y, int32_t z, int32_t* out_state_id);

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