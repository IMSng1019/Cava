/* cava_push.h -- P2 第 2 核：实体间 broadphase 与 Entity.pushAwayFrom 的【纯几何内核】。
 *
 * 设计纪律（照 docs/CAVA-工程接口契约.md 与 P2 第 1 核的四条教训）：
 *   1. 【同一份常量只允许定义一处】：Java 语义常量（0.01F / 0.05F / sqrt / 2.0 / 4.0）
 *      只在本文件出现。
 *   2. 内核【不读世界、不碰 Minecraft 类型】：实体身份、谓词、对象集合一律留在 Java 侧。
 *      本内核只接受"已算好的数值"，这样它能脱离 MC 编译与单测。
 *   3. 数值一律 double 语义；只允许 + - * / 与 sqrt（IEEE 精确舍入，跨编译器逐位一致）。
 *      **禁止超越函数**（契约 2.1 第 4 条）。sqrt 是 IEEE-754 规定的正确舍入运算，允许。
 *   4. 内核【不产生副作用】：输出只写调用方给的缓冲区。
 *
 * 语义权威：docs/CAVA-push-oracle-spec.md（javap 逐条转写，含偏移号）。
 * ABI 提案：docs/CAVA-push-notes.md（本文件不是冻结 ABI；cava_abi.h 由 captain 持有）。
 * 作者：P2-push 子代理。
 */
#ifndef CAVA_PUSH_H
#define CAVA_PUSH_H

#include <stdint.h>

/* 复用 P2 第 1 核的 Java 数值语义助手（java_max / java_min / java_abs）。
 * **不复制实现** —— 契约要求"同一份语义只有一处定义"，第 1 核的 cava_entity.h 就是那一处。*/
#include "../cava_entity.h"

namespace cava {
namespace push {

/* 本内核的返回码。**数值必须与 native/include/cava_abi.h 的 CAVA_OK / CAVA_ERR_* 一致**，
 * 但本文件刻意不 include 那份冻结 ABI 头 —— 内核要能脱离 ABI 与 MC 单独编译单测。
 * CAVA_ENTITY_ERR_NULL(-3) / CAVA_ENTITY_ERR_ARG(-4) 直接取自第 1 核（同一份数值）。*/
#define CAVA_PUSH_OK 0

/* ---------------------------------------------------------------- */
/* A. Entity.pushAwayFrom(Entity) 的位移合成                          */
/* ---------------------------------------------------------------- */

/* 原版字面量（javap 实读的 ldc2_w 常量，全部是 (double)<float> 提升）：
 *   #1991  0.009999999776482582d  = (double)0.01F
 *   #1993  0.05000000074505806d   = (double)0.05F                                */
constexpr double CAVA_PUSH_MIN_SEP_SQ = 0.00999999977648258209228515625;   /* (double)0.01F */
constexpr double CAVA_PUSH_FACTOR     = 0.05000000074505805969238281250;   /* (double)0.05F */

/* pushAwayFrom 的几何结果。
 * 原版把 (p, q) 分别喂给两次 addVelocity：
 *   this .addVelocity(-p, +0.0, -q)   （当 !this.hasPassengers() && this.isPushable()）
 *   other.addVelocity( p, +0.0,  q)   （当 !other.hasPassengers() && other.isPushable()）
 * 本内核只算 (p, q)；"给谁加、加不加"是 Java 侧的谓词，**不在这里**。*/
struct PushDelta {
    double dx;   /* = p */
    double dz;   /* = q */
    int32_t hit; /* 1 = 进入了 f >= 0.01F 分支（原版会调 addVelocity）；0 = 直接 return */
};

/* 逐位复刻 net.minecraft.entity.Entity#pushAwayFrom 的几何部分（字节码 24-121）。
 *
 * 字节码（l 为 this，r 为形参 entity）：
 *   24..33   d  = r.getX() - l.getX()                ; d 存 local 2
 *   34..43   e  = r.getZ() - l.getZ()                ; e 存 local 4
 *   45..51   f  = MathHelper.absMax(d, e)            ; f 存 local 6
 *   53..59   if (f >= (double)0.01F) { ... }         ; dcmpl + iflt（NaN 走"不进"）
 *   62..67   f  = Math.sqrt(f)
 *   69..73   d  = d / f
 *   74..79   e  = e / f
 *   81..85   g  = 1.0 / f
 *   87..95   if (g > 1.0) g = 1.0                    ; dcmpl + ifle（NaN 不钳）
 *   97..101  d  = d * g
 *   102..107 e  = e * g
 *   109..114 d  = d * (double)0.05F
 *   115..121 e  = e * (double)0.05F
 *
 * 【除数会不会是 0？】不会，而且顺序是**唯一**安全的：
 *   f 先被 53 行的守卫压到 f >= (double)0.01F（否则整个分支不进），
 *   之后才出现 62/69/74/81 行的三次除法。所以 f 恒 >= 0.01 > 0。
 *   **把守卫挪到除法之后就会产生 inf/NaN —— 这是本函数最容易读反的一处。**
 *   （唯一的例外是 f = +Inf：sqrt(+Inf)=+Inf，d/f 与 1/f 合法，结果见真值表 TT-PA-8。）
 *
 * 返回值：CAVA_OK；out 为 null 时 CAVA_ERR_NULL。
 * out->hit == 0 时 out->dx/dz 恒为 +0.0（原版直接 return，不触碰速度）。*/
int push_away_from(double this_x, double this_z, double other_x, double other_z, PushDelta* out);

/* ---------------------------------------------------------------- */
/* B. 实体 AABB 的 broadphase 叶子过滤（= Box.intersects(Box) ）      */
/* ---------------------------------------------------------------- */

/* AABB 用 6 个 double 的扁平数组表示：{minX, minY, minZ, maxX, maxY, maxZ}。
 * **不用结构体**：契约要求"数组算一个字段"，扁平数组天然满足，也不需要布局哈希登记。*/
struct Box6 {
    double min_x, min_y, min_z, max_x, max_y, max_z;
};

/* 逐位复刻 net.minecraft.util.math.Box#intersects(double,double,double,double,double,double)
 * （字节码 925-960）：minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY
 *                       && minZ < o.maxZ && maxZ > o.minZ
 * 编译形状是 dcmpg+ifge / dcmpl+ifle —— **严格不等**，NaN 参与比较时整个表达式为 false。
 * 注意：不是 <= / >=（相切不算相交）。*/
bool box_intersects(const Box6& a, const Box6& b);

/* 顺序保持的候选集过滤：对 boxes[0..count) 逐个判 box_intersects(query, boxes[i])，
 * 命中的**下标**按 i 升序写进 out_idx。返回写出的个数；
 * out_cap 不足返回 CAVA_ERR_ARG（且不写越界；调用方看到 rc < 0 必须整段丢弃）。
 * **顺序就是输入顺序** —— 原版 EntityTrackingSection.forEach 的语义正是"按列表顺序过滤"。*/
int filter_intersecting(const Box6& query, const Box6* boxes, int32_t count,
                        int32_t* out_idx, int32_t out_cap);

/* ---------------------------------------------------------------- */
/* C. 区段 broadphase（= SectionedEntityCache.forEachInBox 的访问计划）*/
/* ---------------------------------------------------------------- */

/* ChunkSectionPos 的打包（javap 实读，asLong 字节码 476-508）：
 *     pack(x,y,z) = ((int64)x & 0x3FFFFF) << 42    <-- x 占高 22 位
 *                 | ((int64)z & 0x3FFFFF) << 20    <-- z 占中 22 位
 *                 | ((int64)y & 0xFFFFF)           <-- y 占低 20 位
 *   **y 在最低位、z 在中间** —— 这一点极易读反（直觉是 (x,y,z) 顺序）。
 *   两个独立印证：(a) 解码侧 unpackY = (v << 44) >> 44 只取低 20 位、
 *   unpackZ = (v << 22) >> 42 取第 20..41 位、unpackX = v >> 42 取高 22 位；
 *   (b) 掩码宽度与解码宽度逐一吻合（x/z 是 22 位 = 0x3FFFFF，y 是 20 位 = 0xFFFFF）。*/
int64_t pack_section(int32_t x, int32_t y, int32_t z);
int32_t unpack_x(int64_t packed);
int32_t unpack_y(int64_t packed);
int32_t unpack_z(int64_t packed);

/* ChunkSectionPos.getSectionCoord(double) = MathHelper.floor(d) >> 4
 * MathHelper.floor(double)（字节码 108-123）：int i = (int)d; return d < (double)i ? i - 1 : i;
 * (int)d 是 JVM d2i 的**饱和**语义（NaN->0, +Inf->INT_MAX, -Inf->INT_MIN），由
 * cava::entity::java_d2i_sat 提供。*/
int32_t section_coord(double d);

/* 逐位复刻 net.minecraft.world.entity.SectionedEntityCache#forEachInBox(Box, LazyIterationConsumer)
 * 的【访问顺序】（字节码 33-146）：
 *
 *   xMin = section_coord(box.minX - 2.0)      zMin = section_coord(box.minZ - 2.0)
 *   yMin = section_coord(box.minY - 4.0)      yMax = section_coord(box.maxY + 0.0)
 *   xMax = section_coord(box.maxX + 2.0)      zMax = section_coord(box.maxZ + 2.0)
 *   for (x = xMin; x <= xMax; x++)                       // x **数值升序**（不是打包序）
 *       for (pos in trackedPositions.subSet(pack(x,0,0), pack(x,-1,-1)+1))   // 打包值升序
 *           if (y in [yMin,yMax] && z in [zMin,zMax]) yield pos
 *
 * 三条最容易读反的地方（真值表 TT-SP-* 全部钉死）：
 *   1. 内层是**打包值升序**，而打包值是 x<<42 | z<<20 | y ⇒ 顺序是
 *      **(x 升序, z 的掩码升序, y 的掩码升序)** —— 先 z 后 y，且"负 z 排在正 z 之后"。
 *   2. y 的窗口是 [minY - 4.0, maxY + 0.0]（**不对称**）；x/z 的窗口是 ±2.0。
 *   3. maxY + 0.0 里的 dconst_0; dadd 只对 -0.0 有含义（(-0.0)+0.0 = +0.0）。
 *
 * 输入 positions[] **必须已经是 trackedPositions 的升序快照**（Java 侧由 LongAVLTreeSet
 * 的迭代器保证；内核不再排序，也不校验有序 —— 见 ABI 提案的"前置条件"条）。
 *
 * 返回写出的个数；out_cap 不足返回 CAVA_ERR_ARG；参数非法返回 CAVA_ERR_NULL/ARG。
 * **顺序保持**：返回的是输入数组的一个升序子序列 ⇒ 与原版访问顺序逐一相同。*/
int section_plan(const Box6& box, const int64_t* sorted_positions, int32_t count,
                 int64_t* out, int32_t out_cap);

} /* namespace push */
} /* namespace cava */

#endif /* CAVA_PUSH_H */
