/* cava_pathfind_vectors.cpp —— P1 寻路的**跨语言差分测试**：
 * 原生内核 vs Java 参照实现产出的 10000 组向量（逐节点、逐 float 位模式）。
 *
 * 向量格式与生成算法由 docs/CAVA-pathfind-oracle-spec.md §10 冻结，
 * 生产者是 src/test/java/cava/oracle/VectorGen.java（本文件逐行对齐）。
 *
 * 用法： cava_pathfind_vectors <vectorDir> [maxCases] [--verbose]
 * 退出码：0 = 全部逐位一致；1 = 有不一致；2 = 文件/格式问题。
 */
#include "../src/pathfind/cava_pf.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

struct Reader {
    const uint8_t* p; size_t n; size_t i = 0; bool bad = false;
    Reader(const uint8_t* d, size_t len) : p(d), n(len) {}
    void need(size_t k) { if (i + k > n) bad = true; }
    uint8_t u8() { need(1); return bad ? 0 : p[i++]; }
    uint16_t u16() { need(2); if (bad) return 0; uint16_t v = (uint16_t)((p[i] << 8) | p[i+1]); i += 2; return v; }
    uint32_t u32() { need(4); if (bad) return 0; uint32_t v = ((uint32_t)p[i]<<24)|((uint32_t)p[i+1]<<16)|((uint32_t)p[i+2]<<8)|p[i+3]; i += 4; return v; }
    int32_t i32() { return (int32_t)u32(); }
    uint64_t u64() { uint64_t hi = u32(), lo = u32(); return (hi << 32) | lo; }
    float f32() { uint32_t v = u32(); float f; std::memcpy(&f, &v, 4); return f; }
    double f64() { uint64_t v = u64(); double d; std::memcpy(&d, &v, 8); return d; }
    void skip(size_t k) { need(k); if (!bad) i += k; }
};

static uint32_t fbits(float f) { uint32_t v; std::memcpy(&v, &f, 4); return v; }

struct Xorshift {
    uint64_t state;
    explicit Xorshift(uint64_t seed) : state(seed == 0 ? 0x9E3779B97F4A7C15ull : seed) {}
    uint64_t next() { uint64_t x = state; x ^= x >> 12; x ^= x << 25; x ^= x >> 27; state = x; return x * 0x2545F4914F6CDD1Dull; }
    int32_t next_int(int32_t bound) { return (int32_t)((next() >> 1) % (uint64_t)bound); }
    bool next_bool() { return (next() >> 63) != 0; }
};

enum : int32_t { P_OUT_OF_WORLD=0, P_AIR=1, P_STONE=2, P_DIRT=3, P_SLAB=4, P_WATER=5, P_LAVA=6,
                 P_DOOR_CLOSED=7, P_DOOR_OPEN=8, P_FENCE=9, P_SCAFFOLDING=10, P_LEAVES=11,
                 P_HONEY=12, P_RAIL=13, P_CACTUS=14, P_BUSH=15, PALETTE_COUNT=16 };

enum : uint32_t {
    BK_AIR=1u<<0, BK_TRAPDOOR=1u<<1, BK_POWDER_SNOW=1u<<2, BK_CACTUS_OR_BERRY=1u<<3,
    BK_HONEY=1u<<4, BK_COCOA=1u<<5, BK_CAUTIOUS=1u<<6, BK_DOOR=1u<<7, BK_DOOR_OPEN=1u<<8,
    BK_DOOR_HAND=1u<<9, BK_RAIL=1u<<10, BK_LEAVES=1u<<11, BK_FENCE_TAG=1u<<12,
    BK_WALL_TAG=1u<<13, BK_FENCE_GATE=1u<<14, BK_FENCE_GATE_OPEN=1u<<15,
    BK_FIRE_DAMAGE=1u<<16, BK_PATHFIND_LAND=1u<<17, BK_WATER_BLOCK=1u<<18
};

struct PaletteEntry { uint32_t bk_flags; uint8_t bk_fluid; float min_y; float max_y; };

static const PaletteEntry kPalette[PALETTE_COUNT] = {
    {BK_AIR | BK_PATHFIND_LAND, 0, 0.0f, 0.0f},
    {BK_AIR | BK_PATHFIND_LAND, 0, 0.0f, 0.0f},
    {0, 0, 0.0f, 1.0f},
    {0, 0, 0.0f, 1.0f},
    {BK_PATHFIND_LAND, 0, 0.0f, 0.5f},
    {BK_WATER_BLOCK | BK_PATHFIND_LAND, 1, 0.0f, 0.0f},
    {0, 2, 0.0f, 0.0f},
    {BK_DOOR | BK_DOOR_HAND, 0, 0.0f, 0.0f},
    {BK_DOOR | BK_DOOR_OPEN | BK_PATHFIND_LAND, 0, 0.0f, 0.0f},
    {BK_FENCE_TAG, 0, 0.0f, 1.5f},
    {BK_PATHFIND_LAND, 0, 0.875f, 1.0f},
    {BK_LEAVES, 0, 0.0f, 1.0f},
    {BK_HONEY, 0, 0.0f, 1.0f},
    {BK_RAIL | BK_PATHFIND_LAND, 0, 0.0f, 0.0625f},
    {BK_CACTUS_OR_BERRY, 0, 0.0f, 1.0f},
    {BK_PATHFIND_LAND, 0, 0.0f, 0.0f},
};

static uint32_t map_bk_flags(uint32_t bk, uint8_t fluid) {
    using namespace cava::pathfind;
    uint32_t f = 0;
    if (bk & BK_AIR) f |= PF_AIR;
    if (bk & BK_TRAPDOOR) f |= PF_TRAPDOOR;
    if (bk & BK_POWDER_SNOW) f |= PF_POWDER_SNOW;
    if (bk & BK_CACTUS_OR_BERRY) f |= PF_CACTUS_OR_BERRY;
    if (bk & BK_HONEY) f |= PF_HONEY;
    if (bk & BK_COCOA) f |= PF_COCOA;
    if (bk & BK_CAUTIOUS) f |= PF_CAUTIOUS;
    if (bk & BK_DOOR) f |= PF_DOOR;
    if (bk & BK_DOOR_OPEN) f |= PF_DOOR_OPEN;
    if (bk & BK_DOOR_HAND) f |= PF_DOOR_HAND;
    if (bk & BK_RAIL) f |= PF_RAIL;
    if (bk & BK_LEAVES) f |= PF_LEAVES;
    if (bk & BK_FENCE_TAG) f |= PF_FENCE_TAG;
    if (bk & BK_WALL_TAG) f |= PF_WALL_TAG;
    if (bk & BK_FENCE_GATE) f |= PF_FENCE_GATE;
    if (bk & BK_FENCE_GATE_OPEN) f |= PF_FENCE_GATE_OPEN;
    if (bk & BK_FIRE_DAMAGE) f |= PF_FIRE_DAMAGE;
    if (bk & BK_PATHFIND_LAND) f |= PF_PATHFIND_LAND;
    if (bk & BK_WATER_BLOCK) f |= PF_WATER_BLOCK;
    if (fluid == 1) f |= CAVA_SF_FLUID | CAVA_SF_WATER;
    if (fluid == 2) f |= CAVA_SF_FLUID | CAVA_SF_LAVA;
    return f;
}

/* TerrainGen.generate 的逐行移植（规格 §10.6）。*/
static void gen_set(std::vector<uint8_t>& b, int32_t sx, int32_t sy, int32_t sz,
                    int32_t x, int32_t y, int32_t z, uint8_t v) {
    if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return;
    b[((size_t)y * sz + z) * sx + x] = v;
}
static inline int32_t max1(int32_t v) { return v > 1 ? v : 1; }

static std::vector<uint8_t> terrain_generate(uint64_t seed, int32_t scenario,
                                             int32_t origin_y, int32_t size_x, int32_t size_y, int32_t size_z,
                                             int32_t ground_y) {
    Xorshift rng(seed);
    std::vector<uint8_t> blocks((size_t)size_x * size_y * size_z, (uint8_t)P_AIR);
    for (int32_t y = 0; y < size_y; ++y)
        for (int32_t z = 0; z < size_z; ++z)
            for (int32_t x = 0; x < size_x; ++x) {
                const int32_t wy = origin_y + y;
                uint8_t v = (uint8_t)P_AIR;
                if (wy < ground_y) v = (uint8_t)P_DIRT;
                else if (wy == ground_y) v = (uint8_t)P_STONE;
                blocks[((size_t)y * size_z + z) * size_x + x] = v;
            }
    switch (scenario) {
        case 0: break;
        case 1: {
            const int32_t n = 12 + rng.next_int(24);
            for (int32_t i = 0; i < n; ++i) {
                const int32_t x = rng.next_int(size_x), z = rng.next_int(size_z);
                const int32_t h = 1 + rng.next_int(3), kind = rng.next_int(4);
                const uint8_t b = (uint8_t)(kind == 0 ? P_STONE : kind == 1 ? P_FENCE : kind == 2 ? P_LEAVES : P_BUSH);
                for (int32_t k = 0; k < h; ++k) gen_set(blocks, size_x, size_y, size_z, x, ground_y + 1 + k - origin_y, z, b);
            }
            break;
        }
        case 2: {
            const int32_t dir = rng.next_int(2), len = 4 + rng.next_int(4);
            const int32_t x0 = 2 + rng.next_int(max1(size_x - 6)), z0 = 2 + rng.next_int(max1(size_z - 6));
            for (int32_t k = 0; k < len; ++k) {
                const int32_t x = dir == 0 ? x0 + k : x0, z = dir == 0 ? z0 : z0 + k;
                for (int32_t dz = 0; dz < 3; ++dz)
                    for (int32_t dx = 0; dx < 3; ++dx)
                        gen_set(blocks, size_x, size_y, size_z, x + (dir == 1 ? dx : 0),
                                ground_y + 1 + k - origin_y, z + (dir == 0 ? dz : 0), (uint8_t)P_SLAB);
            }
            break;
        }
        case 3: {
            const int32_t cx = 3 + rng.next_int(max1(size_x - 8)), cz = 3 + rng.next_int(max1(size_z - 8));
            const int32_t rx = 2 + rng.next_int(3), rz = 2 + rng.next_int(3);
            for (int32_t x = cx - rx; x <= cx + rx; ++x)
                for (int32_t z = cz - rz; z <= cz + rz; ++z) {
                    gen_set(blocks, size_x, size_y, size_z, x, ground_y - origin_y, z, (uint8_t)P_WATER);
                    gen_set(blocks, size_x, size_y, size_z, x, ground_y + 1 - origin_y, z, (uint8_t)P_WATER);
                }
            break;
        }
        case 4: {
            const int32_t cx = 3 + rng.next_int(max1(size_x - 8)), cz = 3 + rng.next_int(max1(size_z - 8));
            for (int32_t x = cx - 1; x <= cx + 1; ++x)
                for (int32_t z = cz - 1; z <= cz + 1; ++z)
                    gen_set(blocks, size_x, size_y, size_z, x, ground_y - origin_y, z, (uint8_t)P_LAVA);
            break;
        }
        case 5: {
            const int32_t wall_x = 3 + rng.next_int(max1(size_x - 6));
            const int32_t gap = rng.next_int(size_z);
            const bool open = rng.next_bool();
            for (int32_t z = 0; z < size_z; ++z) {
                for (int32_t k = 1; k <= 2; ++k) gen_set(blocks, size_x, size_y, size_z, wall_x, ground_y + k - origin_y, z, (uint8_t)P_STONE);
                gen_set(blocks, size_x, size_y, size_z, wall_x, ground_y + 1 - origin_y, z, (uint8_t)P_STONE);
            }
            for (int32_t k = 1; k <= 2; ++k)
                gen_set(blocks, size_x, size_y, size_z, wall_x, ground_y + k - origin_y, gap, (uint8_t)(open ? P_DOOR_OPEN : P_DOOR_CLOSED));
            break;
        }
        case 6: {
            const int32_t z0 = 2 + rng.next_int(max1(size_z - 4));
            for (int32_t x = 1; x < size_x - 1; ++x) gen_set(blocks, size_x, size_y, size_z, x, ground_y + 1 - origin_y, z0, (uint8_t)P_FENCE);
            const int32_t gap = 1 + rng.next_int(max1(size_x - 2));
            gen_set(blocks, size_x, size_y, size_z, gap, ground_y + 1 - origin_y, z0, (uint8_t)P_AIR);
            break;
        }
        case 7: {
            const int32_t x0 = 2 + rng.next_int(max1(size_x - 6)), z0 = 2 + rng.next_int(max1(size_z - 6));
            const int32_t h = 1 + rng.next_int(3);
            for (int32_t k = 0; k < h; ++k)
                for (int32_t x = x0; x < x0 + 3 && x < size_x; ++x)
                    for (int32_t z = z0; z < z0 + 3 && z < size_z; ++z)
                        gen_set(blocks, size_x, size_y, size_z, x, ground_y + 1 + k - origin_y, z, (uint8_t)P_SCAFFOLDING);
            break;
        }
        case 8: {
            for (int32_t x = 0; x < size_x; ++x)
                for (int32_t z = 0; z < size_z; ++z) {
                    const bool wall = (x % 3 == 0 && z % 3 != 1) || (z % 3 == 0 && x % 3 != 1);
                    if (wall && rng.next_int(10) < 8)
                        for (int32_t k = 1; k <= 2; ++k) gen_set(blocks, size_x, size_y, size_z, x, ground_y + k - origin_y, z, (uint8_t)P_STONE);
                }
            break;
        }
        default: {
            for (int32_t i = 0; i < 40; ++i) {
                const int32_t x = rng.next_int(size_x), z = rng.next_int(size_z), kind = rng.next_int(10);
                uint8_t b;
                switch (kind) {
                    case 0: b = (uint8_t)P_STONE; break;
                    case 1: b = (uint8_t)P_SLAB; break;
                    case 2: b = (uint8_t)P_WATER; break;
                    case 3: b = (uint8_t)P_LAVA; break;
                    case 4: b = (uint8_t)P_FENCE; break;
                    case 5: b = (uint8_t)P_SCAFFOLDING; break;
                    case 6: b = (uint8_t)P_RAIL; break;
                    case 7: b = (uint8_t)P_HONEY; break;
                    case 8: b = (uint8_t)P_CACTUS; break;
                    default: b = (uint8_t)P_BUSH; break;
                }
                const int32_t h = 1 + rng.next_int(3);
                for (int32_t k = 0; k < h; ++k) gen_set(blocks, size_x, size_y, size_z, x, ground_y + 1 + k - origin_y, z, b);
            }
            break;
        }
    }
    return blocks;
}

static uint64_t world_hash(const std::vector<uint8_t>& blocks) {
    uint64_t h = 0xCBF29CE484222325ull;
    for (uint8_t b : blocks) { h ^= (uint64_t)b; h *= 0x100000001B3ull; }
    return h;
}

/* ================= case 结构 ================= */
struct CaseInput {
    uint32_t case_id = 0;
    uint64_t case_seed = 0;
    int32_t scenario = 0, maker_kind = 0;
    int32_t origin_x = 0, origin_y = 0, origin_z = 0;
    int32_t size_x = 0, size_y = 0, size_z = 0;
    int32_t min_y = 0, sea_level = 0, ground_y = 0;
    float width = 0, height = 0, step_height = 0;
    int32_t safe_fall = 0;
    int32_t pflags = 0;
    double ex = 0, ey = 0, ez = 0;
    bool can_walk_on_fluid = false;
    uint32_t penalty_mask = 0;
    float penalty[26];
    int32_t tx = 0, ty = 0, tz = 0;
    int32_t range = 0;
    float max_range = 0;
    int32_t reach_radius = 0;
    float follow_range = 0;
    uint32_t world_hash_low = 0;
};

struct ExpNode {
    int32_t x = 0, y = 0, z = 0, type = 0;
    bool visited = false;
    float path_length = 0, penalized = 0, distance = 0, heap_weight = 0, penalty = 0;
};

struct CaseOutput {
    bool found = false;
    int32_t node_count = 0;
    bool reaches_target = false;
    int32_t expanded = 0;
    uint64_t trace_hash = 0;
    float manhattan = 0;
    std::vector<ExpNode> nodes;
};

static CaseInput read_input(Reader& r) {
    CaseInput c;
    c.case_id = r.u32();
    c.case_seed = r.u64();
    c.scenario = r.u8();
    c.maker_kind = r.u8();
    r.skip(2);
    c.origin_x = r.i32(); c.origin_y = r.i32(); c.origin_z = r.i32();
    c.size_x = r.u16(); c.size_y = r.u16(); c.size_z = r.u16();
    c.min_y = r.i32();
    c.sea_level = r.i32();
    c.ground_y = r.i32();
    c.width = r.f32(); c.height = r.f32(); c.step_height = r.f32();
    c.safe_fall = r.i32();
    c.pflags = r.u8();
    r.skip(3);
    c.ex = r.f64(); c.ey = r.f64(); c.ez = r.f64();
    c.can_walk_on_fluid = r.u8() != 0;
    r.skip(7);
    c.penalty_mask = r.u32();
    for (int32_t i = 0; i < 26; ++i) c.penalty[i] = r.f32();
    c.tx = r.i32(); c.ty = r.i32(); c.tz = r.i32();
    c.range = (int32_t) r.u16();
    c.max_range = r.f32();
    c.reach_radius = r.i32();
    c.follow_range = r.f32();
    c.world_hash_low = r.u32();
    return c;
}

static CaseOutput read_output(Reader& r) {
    CaseOutput o;
    o.found = r.u8() != 0;
    const uint16_t n = r.u16();
    o.reaches_target = r.u8() != 0;
    o.expanded = r.i32();
    o.trace_hash = r.u64();
    o.manhattan = r.f32();
    if (!o.found) { o.node_count = -1; return o; }
    o.node_count = (int32_t) n;
    int32_t px = 0, py = 0, pz = 0;
    for (int32_t i = 0; i < o.node_count; ++i) {
        ExpNode e;
        if (i == 0) { e.x = r.i32(); e.y = r.i32(); e.z = r.i32(); }
        else { e.x = px + (int8_t) r.u8(); e.y = py + (int8_t) r.u8(); e.z = pz + (int8_t) r.u8(); }
        px = e.x; py = e.y; pz = e.z;
        e.type = r.u8();
        e.visited = (r.u8() & 1) != 0;
        e.path_length = r.f32();
        e.penalized = r.f32();
        e.distance = r.f32();
        e.heap_weight = r.f32();
        e.penalty = r.f32();
        o.nodes.push_back(e);
    }
    return o;
}

/* ================= 跑一组并比对 ================= */
struct Mismatch { std::string what; };

static void build_world(const CaseInput& c, const std::vector<uint8_t>& blocks,
                        std::vector< ::CavaCollisionBox>& boxes,
                        std::vector<int32_t>& ids) {
    using namespace cava::pathfind;
    boxes.clear();
    for (int32_t i = 0; i < PALETTE_COUNT; ++i) {
        const PaletteEntry& pe = kPalette[i];
        const bool empty = (pe.max_y <= pe.min_y && pe.max_y <= 0.0f);   /* 参照实现的"空形状"判据 */
        if (empty) continue;
        CavaCollisionBox b;
        b.min_x = 0.0f; b.min_y = pe.min_y; b.min_z = 0.0f;
        b.max_x = 1.0f; b.max_y = pe.max_y; b.max_z = 1.0f;
        boxes.push_back(b);
    }
    ids.assign(blocks.size(), 0);
    for (size_t i = 0; i < blocks.size(); ++i) ids[i] = (int32_t) blocks[i];
}

static bool run_case(const CaseInput& c, const std::vector<uint8_t>& blocks,
                     const CaseOutput& exp, std::string& why) {
    using namespace cava::pathfind;

    std::vector< ::CavaCollisionBox> boxes;
    std::vector<int32_t> ids;
    build_world(c, blocks, boxes, ids);

    /* 状态表：每个调色板项一条记录，box_offset 指向自己的盒（或 CAVA_BOX_NONE）。*/
    std::vector<CavaStateRecord> recs((size_t) PALETTE_COUNT);
    uint32_t box_cursor = 0;
    for (int32_t i = 0; i < PALETTE_COUNT; ++i) {
        const PaletteEntry& pe = kPalette[i];
        const bool empty = (pe.max_y <= pe.min_y && pe.max_y <= 0.0f);
        recs[i].flags = map_bk_flags(pe.bk_flags, pe.bk_fluid);
        recs[i].box_offset = empty ? CAVA_BOX_NONE : box_cursor;
        recs[i].box_count = empty ? 0u : 1u;
        if (!empty) box_cursor += 1;
        recs[i].path_type_idx = 0;
        recs[i].malus = 0.0f;
    }

    WorldView w;
    w.recs = recs.data(); w.rec_count = PALETTE_COUNT;
    w.boxes = boxes.empty() ? nullptr : boxes.data(); w.box_count = (int32_t) boxes.size();
    w.origin_x = c.origin_x; w.origin_y = c.origin_y; w.origin_z = c.origin_z;
    w.dim_x = c.size_x; w.dim_y = c.size_y; w.dim_z = c.size_z;
    w.ids = ids.data();
    w.min_y = c.min_y;
    w.sea_level = c.sea_level;

    MobProfile m;
    m.width = c.width; m.height = c.height; m.step_height = c.step_height;
    m.safe_fall_distance = c.safe_fall;
    m.x = c.ex; m.y = c.ey; m.z = c.ez;
    m.on_ground = (c.pflags & (1 << 6)) != 0;
    m.touching_water = (c.pflags & (1 << 7)) != 0;
    m.can_walk_on_fluid = c.can_walk_on_fluid;
    m.caps = 0;
    if (c.pflags & 1) m.caps |= CAVA_NAV_CAN_OPEN_DOORS;
    if (c.pflags & (1 << 1)) m.caps |= CAVA_NAV_CAN_ENTER_OPEN_DOORS;
    if (c.pflags & (1 << 2)) m.caps |= CAVA_NAV_CAN_SWIM;
    if (c.pflags & (1 << 3)) m.caps |= CAVA_NAV_CAN_WALK_OVER_FENCES;
    if (c.pflags & (1 << 4)) m.caps |= CAVA_NAV_AMPHIBIOUS;
    if (c.pflags & (1 << 5)) m.caps |= CAVA_NAV_PENALIZE_DEEP_WATER;
    for (int32_t i = 0; i < 26; ++i) {
        if (c.penalty_mask & (1u << i)) m.set_penalty(i, c.penalty[i]);
    }

    SolveParams p;
    p.start_x = java_floor(m.x); p.start_y = java_floor(m.y); p.start_z = java_floor(m.z);
    p.target_x = c.tx; p.target_y = c.ty; p.target_z = c.tz;
    p.reach_radius = c.reach_radius;
    p.max_range = c.max_range;
    p.node_budget = cava_d2i_sat((double) ((float) c.range * c.follow_range));

    SolveResult res;
    if (!solve(w, m, p, res)) { why = "solve() rejected valid input"; return false; }

    char buf[512];
    if (res.nodes.size() != (size_t) exp.node_count) {
        std::snprintf(buf, sizeof buf, "nodeCount native=%zu expected=%d", res.nodes.size(), exp.node_count);
        why = buf; return false;
    }
    if (res.reaches_target != exp.reaches_target) { why = "reachesTarget differs"; return false; }
    if (res.expanded_count != exp.expanded) {
        std::snprintf(buf, sizeof buf, "expandedCount native=%d expected=%d", res.expanded_count, exp.expanded);
        why = buf; return false;
    }
    if (res.trace_hash != exp.trace_hash) {
        std::snprintf(buf, sizeof buf, "traceHash native=%016llx expected=%016llx",
                      (unsigned long long) res.trace_hash, (unsigned long long) exp.trace_hash);
        why = buf; return false;
    }
    if (fbits(res.manhattan_distance_from_target) != fbits(exp.manhattan)) {
        std::snprintf(buf, sizeof buf, "manhattan native=%08x expected=%08x",
                      fbits(res.manhattan_distance_from_target), fbits(exp.manhattan));
        why = buf; return false;
    }
    for (size_t i = 0; i < res.nodes.size(); ++i) {
        const OutNode& a = res.nodes[i];
        const ExpNode& b = exp.nodes[i];
        if (a.x != b.x || a.y != b.y || a.z != b.z) {
            std::snprintf(buf, sizeof buf, "node[%zu] coord native=(%d,%d,%d) expected=(%d,%d,%d)",
                          i, a.x, a.y, a.z, b.x, b.y, b.z);
            why = buf; return false;
        }
        if (a.type != b.type) {
            std::snprintf(buf, sizeof buf, "node[%zu] type native=%d expected=%d", i, a.type, b.type);
            why = buf; return false;
        }
        if (a.visited != b.visited) {
            std::snprintf(buf, sizeof buf, "node[%zu] visited native=%d expected=%d", i, (int) a.visited, (int) b.visited);
            why = buf; return false;
        }
        struct F { const char* name; float a; float b; };
        const F fs[5] = {
            {"pathLength", a.path_length, b.path_length},
            {"penalizedPathLength", a.penalized_path_length, b.penalized},
            {"distanceToNearestTarget", a.distance_to_nearest_target, b.distance},
            {"heapWeight", a.heap_weight, b.heap_weight},
            {"penalty", a.penalty, b.penalty},
        };
        for (const F& f : fs) {
            if (fbits(f.a) != fbits(f.b)) {
                std::snprintf(buf, sizeof buf, "node[%zu] %s native=%08x expected=%08x",
                              i, f.name, fbits(f.a), fbits(f.b));
                why = buf; return false;
            }
        }
    }
    return true;
}

/* ================= 定点真值表：isValidDiagonalSuccessor 的极性 ================= */
/* 这一条是**读过反**的高危分支（oracle 参照实现读反过，captain 已裁决 !flag5）。
 * 用真值表把它钉死，比多跑一万组随机向量更能防回归。*/
static int truth_table_test() {
    using cava::pathfind::diagonal_side_rejected;
    struct Row { bool y_ge, p_neg, flag5, expect; };
    const Row rows[8] = {
        {true,  true,  false, true },   /* side 坏 + 不是"窄生物夹两面栅栏" -> 拒绝 */
        {true,  true,  true,  false},   /* 窄生物夹在两面栅栏之间 -> 放行 */
        {true,  false, false, false},
        {true,  false, true,  false},
        {false, true,  false, false},
        {false, true,  true,  false},
        {false, false, false, false},
        {false, false, true,  false},
    };
    int fails = 0;
    for (const Row& r : rows) {
        const bool got = diagonal_side_rejected(r.y_ge, r.p_neg, r.flag5);
        if (got != r.expect) {
            std::printf("  TRUTH TABLE FAIL: y>=host=%d penalty<0=%d flag5=%d -> got %d expect %d\n",
                        (int) r.y_ge, (int) r.p_neg, (int) r.flag5, (int) got, (int) r.expect);
            fails++;
        }
    }
    std::printf("[truth-table] diagonal_side_rejected: %d/%d rows OK  (%s)\n",
                8 - fails, 8, fails == 0 ? "PASS" : "FAIL");
    return fails;
}

/* ================= 主流程 ================= */
static bool read_file(const std::string& path, std::vector<uint8_t>& out) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    const long len = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    out.resize((size_t) len);
    const size_t got = std::fread(out.data(), 1, (size_t) len, f);
    std::fclose(f);
    return got == (size_t) len;
}

static int run_shard(const std::string& path, int32_t max_cases, bool verbose, bool golden) {
    std::vector<uint8_t> data;
    if (!read_file(path, data)) return 2;
    Reader r(data.data(), data.size());
    char magic[4];
    for (int i = 0; i < 4; ++i) magic[i] = (char) r.u8();
    const uint16_t version = r.u16();
    r.skip(2);
    const uint32_t case_count = r.u32();
    const uint64_t master = r.u64();
    /* CVOV 头 28 字节（多 shardIndex/shardCount），CVOG 头只有 20 字节。*/
    uint32_t shard_index = 0, shard_count = 0;
    if (!golden) { shard_index = r.u32(); shard_count = r.u32(); }
    const char* want = golden ? "CVOG" : "CVOV";
    if (std::memcmp(magic, want, 4) != 0) {
        std::printf("  BAD MAGIC in %s\n", path.c_str());
        return 2;
    }
    std::printf("[shard] %s  version=%u cases=%u master=%016llx shard=%u/%u\n",
                path.c_str(), version, case_count, (unsigned long long) master, shard_index, shard_count);

    int32_t cases = 0, mismatches = 0, hash_fail = 0, golden_block_mismatch = 0;
    for (uint32_t i = 0; i < case_count; ++i) {
        if (max_cases > 0 && cases >= max_cases) break;
        const CaseInput c = read_input(r);
        if (r.bad) { std::printf("  TRUNCATED input at case %u\n", c.case_id); return 2; }

        std::vector<uint8_t> blocks;
        if (golden) {
            /* golden 里连调色板与体素一起存。调色板用**硬编码**的那份：
             * 文件里 writeShort(flags) 会把 1<<17 / 1<<18 截掉（见 notes 的"向量格式缺陷"）。*/
            const uint16_t pal_count = r.u16();
            r.skip((size_t) pal_count * 12);   /* u16 flags + u8 fluid + u8 reserved + f32 minY + f32 maxY */
            blocks.resize((size_t) c.size_x * c.size_y * c.size_z);
            for (size_t k = 0; k < blocks.size(); ++k) blocks[k] = r.u8();
            std::vector<uint8_t> regen = terrain_generate(c.case_seed ^ 0xA5A5A5A5A5A5A5A5ull, c.scenario,
                                                          c.origin_y, c.size_x, c.size_y, c.size_z, c.ground_y);
            if (regen != blocks) golden_block_mismatch++;
        } else {
            blocks = terrain_generate(c.case_seed ^ 0xA5A5A5A5A5A5A5A5ull, c.scenario,
                                      c.origin_y, c.size_x, c.size_y, c.size_z, c.ground_y);
            const uint32_t h = (uint32_t) (world_hash(blocks) & 0xFFFFFFFFull);
            if (h != c.world_hash_low) {
                if (hash_fail < 3) {
                    std::printf("  worldHash mismatch case %u: native=%08x expected=%08x\n", c.case_id, h, c.world_hash_low);
                }
                hash_fail++;
            }
        }
        const CaseOutput exp = read_output(r);
        if (r.bad) { std::printf("  TRUNCATED output at case %u\n", c.case_id); return 2; }

        std::string why;
        if (!run_case(c, blocks, exp, why)) {
            if (mismatches < 5) std::printf("  MISMATCH case %u: %s\n", c.case_id, why.c_str());
            mismatches++;
        }
        cases++;
    }
    std::printf("  cases=%d mismatches=%d worldHashFail=%d goldenBlockMismatch=%d\n",
                cases, mismatches, hash_fail, golden_block_mismatch);
    if (verbose) std::printf("  (verbose) done\n");
    if (hash_fail > 0 || golden_block_mismatch > 0) return 2;
    return mismatches == 0 ? 0 : 1;
}


/* ================= ABI 层冒烟（cava_abi.h 冻结的那几个符号） ================= */
/* 目的：证明 1) state/region 推送与查询真的能用；2) cava_pathfind 在缺生物档案时
 * **返回错误码而不是算出一条不一致的路径**（契约：错误码 = Java 回退原逻辑）。*/
static int abi_smoke_test() {
    int fails = 0;
    CavaOpenParams op;
    std::memset(&op, 0, sizeof op);
    op.abi_version = CAVA_ABI_VERSION;
    /* 不硬编码 layout_hash_sum —— ABI 扩展期这个值会连续变（本会话就改过 3 次）。
     * 契约 2.3 的定义：sum = 所有导出结构体 layout_hash 的 uint32 回绕和。
     * 这里用 cava_layout_report 自己算出来（Java 侧也必须用同一公式）。*/
    {
        CavaLayoutReport rep;
        std::memset(&rep, 0, sizeof rep);
        const int32_t n = cava_layout_report(&rep);
        uint32_t sum = 0;
        for (int32_t i = 0; i < rep.entry_count && i < n; ++i) sum += rep.entries[i].layout_hash;
        op.layout_hash_sum = (uint64_t) sum;
        std::printf("[abi] layout_report entries=%d sum=%08x\n", rep.entry_count, sum);
    }
    int64_t handle = 0;
    CavaOpenResult ores;
    std::memset(&ores, 0, sizeof ores);
    int32_t st = cava_open(&op, &handle, &ores);
    std::printf("[abi] cava_open=%d handle=%lld native_layout_sum=%08llx\n",
                st, (long long) handle, (unsigned long long) ores.native_layout_sum);
    if (st != CAVA_OK || handle == 0) return 1;

    CavaStateRecord recs[2];
    std::memset(recs, 0, sizeof recs);
    recs[0].flags = CAVA_SF_OPEN | cava::pathfind::PF_AIR | cava::pathfind::PF_PATHFIND_LAND;
    recs[0].box_offset = CAVA_BOX_NONE;
    recs[0].box_count = 0;
    recs[1].flags = CAVA_SF_SOLID | CAVA_SF_BLOCKS_MOTION;
    recs[1].box_offset = 0;
    recs[1].box_count = 1;
    CavaCollisionBox box;
    box.min_x = 0.0f; box.min_y = 0.0f; box.min_z = 0.0f;
    box.max_x = 1.0f; box.max_y = 1.0f; box.max_z = 1.0f;
    st = cava_state_table_upload(handle, recs, 2, &box, 1);
    std::printf("[abi] state_table_upload=%d (expect 0)\n", st);
    if (st != CAVA_OK) fails++;

    /* 索引顺序 x 最快、y 最慢：dim 2x2x2 时 idx1 = (1,0,0)。*/
    int32_t ids[8] = {0, 1, 0, 0, 0, 0, 0, 0};
    st = cava_region_upload(handle, 2, 2, 2, 0, 0, 0, ids, 8);
    std::printf("[abi] region_upload=%d (expect 0)\n", st);
    if (st != CAVA_OK) fails++;

    int32_t got = -99;
    cava_region_state_id_at(handle, 1, 0, 0, &got);
    std::printf("[abi] state_id_at(1,0,0)=%d (expect 1)\n", got);
    if (got != 1) fails++;
    cava_region_state_id_at(handle, 5, 0, 0, &got);
    std::printf("[abi] state_id_at(5,0,0)=%d (expect -1, 区域外)\n", got);
    if (got != -1) fails++;
    cava_region_state_id_at(handle, 0, 1, 0, &got);
    std::printf("[abi] state_id_at(0,1,0)=%d (expect 0)\n", got);
    if (got != 0) fails++;

    /* 参数校验：维度乘积与 id_count 不一致必须 CAVA_ERR_ARG，且不破坏已有区域。*/
    st = cava_region_upload(handle, 2, 2, 2, 0, 0, 0, ids, 7);
    std::printf("[abi] region_upload(id_count=7)=%d (expect %d)\n", st, CAVA_ERR_ARG);
    if (st != CAVA_ERR_ARG) fails++;
    cava_region_state_id_at(handle, 1, 0, 0, &got);
    if (got != 1) { std::printf("[abi] FAIL: 失败的 upload 破坏了已有区域\n"); fails++; }

    /* cava_pathfind：先把生物档案的缺失/非法路径走一遍。*/
    CavaPathRequest req;
    std::memset(&req, 0, sizeof req);
    req.max_range = 16.0f;
    req.reach_range = 1;
    req.max_visited_nodes = 100;
    CavaPathNode out_nodes[8];
    std::memset(out_nodes, 0, sizeof out_nodes);
    st = cava_pathfind(handle, &req, out_nodes, 8);
    std::printf("[abi] cava_pathfind(无生物档案)=%d (expect %d)\n", st, CAVA_ERR_ARG);
    if (st != CAVA_ERR_ARG) fails++;

    CavaMobProfile mp;
    std::memset(&mp, 0, sizeof mp);
    mp.width = 0.6f; mp.height = 1.8f; mp.step_height = 0.0f;
    mp.reserved_max_fall_distance = 0.0f; mp.safe_fall_distance = 3;
    mp.min_y = -64; mp.sea_level = 63;
    mp.caps = CAVA_NAV_CAN_OPEN_DOORS | CAVA_NAV_ON_GROUND;
    mp.start_x = 0.5; mp.start_y = 1.0; mp.start_z = 0.5;
    st = cava_mob_profile_upload(handle, &mp);
    std::printf("[abi] mob_profile_upload=%d (expect 0)\n", st);
    if (st != CAVA_OK) fails++;
    CavaMobProfile bad = mp;
    bad.width = -1.0f;
    st = cava_mob_profile_upload(handle, &bad);
    std::printf("[abi] mob_profile_upload(width<0)=%d (expect %d)\n", st, CAVA_ERR_ARG);
    if (st != CAVA_ERR_ARG) fails++;

    /* 输入齐了，但 ABI 的 CAVA_PNT_* 序号表与 javap 实证的枚举 ordinal 不一致（见 cava_pf_abi.cpp
     * 的注释），所以**只回退不猜** -> CAVA_ERR_UNIMPLEMENTED。*/
    st = cava_pathfind(handle, &req, out_nodes, 8);
    std::printf("[abi] cava_pathfind(输入齐)=%d (expect %d = CAVA_ERR_UNIMPLEMENTED: PNT 序号表待裁决)\n",
                st, CAVA_ERR_UNIMPLEMENTED);
    if (st != CAVA_ERR_UNIMPLEMENTED) fails++;
    st = cava_mob_profile_clear(handle);
    std::printf("[abi] mob_profile_clear=%d (expect 0)\n", st);
    if (st != CAVA_OK) fails++;

    req.flags = 1;
    st = cava_pathfind(handle, &req, out_nodes, 8);
    std::printf("[abi] cava_pathfind(flags!=0)=%d (expect %d)\n", st, CAVA_ERR_ARG);
    if (st != CAVA_ERR_ARG) fails++;
    req.flags = 0;
    st = cava_pathfind(handle, &req, out_nodes, 0);
    std::printf("[abi] cava_pathfind(cap=0)=%d (expect %d)\n", st, CAVA_ERR_ARG);
    if (st != CAVA_ERR_ARG) fails++;
    st = cava_pathfind(12345, &req, out_nodes, 8);
    std::printf("[abi] cava_pathfind(伪造句柄)=%d (expect %d)\n", st, CAVA_ERR_NULL);
    if (st != CAVA_ERR_NULL) fails++;

    st = cava_close(handle);
    std::printf("[abi] cava_close=%d (expect 0); closeAgain=%d\n", st, cava_close(handle));
    if (st != CAVA_OK) fails++;

    std::printf("[abi] smoke: %s\n", fails == 0 ? "PASS" : "FAIL");
    return fails;
}

} /* namespace */

int main(int argc, char** argv) {
    /* 目录解析：优先 argv[1]；否则从 cwd 与可执行文件位置向上找
     * src/test/resources/cava/oracle —— 这样 P0-A 的 CTest target 不带参数也能跑。*/
    std::string dir;
    if (argc >= 2 && argv[1][0] != '-') {
        dir = argv[1];
    } else {
        std::vector<std::string> roots;
        roots.push_back(".");
        roots.push_back(argv[0] ? argv[0] : ".");
        for (const std::string& r : roots) {
            std::string p = r;
            for (int up = 0; up < 8; ++up) {
                const std::string cand = p + "/src/test/resources/cava/oracle";
                std::FILE* f = std::fopen((cand + "/vectors-00.bin").c_str(), "rb");
                if (f) { std::fclose(f); dir = cand; break; }
                const size_t slash = p.find_last_of("/\\");
                if (slash == std::string::npos) break;
                p = p.substr(0, slash);
                if (p.empty()) { p = "."; }
            }
            if (!dir.empty()) break;
        }
        if (dir.empty()) {
            std::printf("usage: cava_pathfind_vectors [vectorDir] [maxCases] [--verbose]\n");
            std::printf("       (vectorDir 未给且自动探测失败；默认位置是 src/test/resources/cava/oracle)\n");
            return 2;
        }
    }
    int32_t max_cases = (argc > 2) ? std::atoi(argv[2]) : 0;
    bool verbose = false;
    for (int i = 2; i < argc; ++i) if (std::strcmp(argv[i], "--verbose") == 0) verbose = true;

    std::printf("=== Cava P1 pathfind native differential test ===\n");
    const int tt = truth_table_test();
    const int abi_fails = abi_smoke_test();

    /* 分片数由**第一个分片的头**决定。不要按文件名探测到底：
     * 重新生成向量时旧的高编号分片会残留在目录里（实测踩到过 vectors-01.bin 的 BAD MAGIC）。*/
    int rc = 0;
    int shards = 0;
    int32_t shard_count = 0;
    {
        std::vector<uint8_t> head;
        if (read_file(dir + "/vectors-00.bin", head) && head.size() >= 28
            && std::memcmp(head.data(), "CVOV", 4) == 0) {
            shard_count = (int32_t) (((uint32_t) head[24] << 24) | ((uint32_t) head[25] << 16)
                                     | ((uint32_t) head[26] << 8) | head[27]);
        }
    }
    for (int s = 0; s < shard_count; ++s) {
        char name[64];
        std::snprintf(name, sizeof name, "/vectors-%02d.bin", s);
        const std::string p = dir + name;
        const int r = run_shard(p, max_cases, verbose, false);
        if (r > rc) rc = r;
        shards++;
    }
    if (shards == 0) { std::printf("no vectors-*.bin under %s\n", dir.c_str()); return 2; }
    {
        const std::string g = dir + "/golden-00.bin";
        std::FILE* f = std::fopen(g.c_str(), "rb");
        if (f) { std::fclose(f); const int r = run_shard(g, max_cases, verbose, true); if (r > rc) rc = r; }
    }
    if (abi_fails > 0) rc = 1;
    std::printf("RESULT: %s (truthTableFails=%d, abiSmokeFails=%d, shards=%d)\n",
                rc == 0 ? "PASS" : "FAIL", tt, abi_fails, shards);
    return rc;
}
