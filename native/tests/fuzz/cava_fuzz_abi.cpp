/* cava_fuzz_abi.cpp -- systematic bad-input coverage for the FROZEN Cava ABI.
 *
 * Target: cava_pathfind / cava_resolve_move / cava_region_* /
 *         cava_state_table_upload / cava_shape_table_upload (+ cava_mob_profile_*).
 *
 * Why a standalone driver: it talks to the real cava.dll (LoadLibrary + GetProcAddress),
 * so it does not depend on Minecraft, on Fabric, or on the Java side at all. The same
 * binary can be pointed at the shipped artifact, at a CAVA_SAFE build, or at a release
 * rebuild -- which is exactly what build-fuzz.ps1 does.
 *
 * What it asserts (this is the acceptance criterion of the fuzz item):
 *   1. NEVER a segfault             -> the process must reach the SUMMARY line.
 *   2. NEVER an out-of-bounds write -> every output buffer is allocated so that its
 *      declared capacity ends exactly at a PAGE_NOACCESS guard page, and the unused
 *      tail is filled with a 0xA5 sentinel that is verified after every call.
 *   3. MUST return a defined error code -> rc is classified as
 *      {legal (>=0), ABI error (-1..-7), undefined (anything else)}; undefined counts.
 *   4. A failing call MUST NOT change existing state -> after a legal baseline is
 *      established, every bad call is followed by a re-read; any difference counts as
 *      a state mutation.
 *
 * Determinism: the pseudo-random sweep uses xorshift64* with a FIXED default seed
 * (0x5EEDC0DE5EEDC0DE) printed in the header line. Same seed + same dll => same cases.
 *
 * Build / run: native/tests/fuzz/build-fuzz.ps1 (do not use CMake; see that script).
 * NOTE: this file is intentionally ASCII-only (PowerShell 5.1 + g++ on this machine
 * mis-handle non-ASCII in build scripts / console output).
 */
#include "cava_abi.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <atomic>
#include <string>
#include <thread>
#include <vector>

#ifdef _WIN32
#  include <windows.h>
#else
#  include <dlfcn.h>
#  include <sys/mman.h>
#  include <unistd.h>
#endif

/* ------------------------------------------------------------------ */
/* counters                                                           */
/* ------------------------------------------------------------------ */
static uint64_t g_cases = 0;
static uint64_t g_undefined = 0;
static uint64_t g_state_mutations = 0;
static uint64_t g_guard_violations = 0;
static uint64_t g_unexpected_ok = 0;
static uint64_t g_crashes = 0;          /* always 0 if we reach SUMMARY */
static uint64_t g_verbose_every = 1;    /* print every Nth case line */
static FILE* g_log = nullptr;

/* ------------------------------------------------------------------ */
/* multi-threaded phase counters (P4-C: "concurrent section unload")   */
/* ------------------------------------------------------------------ */
static std::atomic<uint64_t> mt_cases(0);
static std::atomic<uint64_t> mt_undefined(0);
static std::atomic<uint64_t> mt_torn(0);        /* a result that contradicts its own return code */
static std::atomic<uint64_t> mt_contract(0);    /* error rc but the callee wrote to its out param */
static std::atomic<uint64_t> mt_guard_bad(0);   /* sentinel/overrun seen by a worker thread     */
static std::atomic<uint64_t> mt_rc_hist[16];    /* rc 0..>7 bucketed, so the mix is inspectable */
static std::atomic<uint64_t> mt_per_role[8];    /* calls issued per role index                  */
static std::atomic<uint64_t> mt_region_upload_ok(0);
static std::atomic<uint64_t> mt_region_clear_ok(0);

/* ------------------------------------------------------------------ */
/* deterministic PRNG (xorshift64*) -- fixed default seed             */
/* ------------------------------------------------------------------ */
static const uint64_t kDefaultSeed = 0x5EEDC0DE5EEDC0DEull;
static uint64_t g_rng = kDefaultSeed;

static uint64_t rnd_next() {
    uint64_t x = g_rng;
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    g_rng = x;
    return x * 0x2545F4914F6CDD1Dull;
}

static int32_t rnd_i32() { return (int32_t) (uint32_t) rnd_next(); }

static int32_t rnd_interesting_i32() {
    static const int32_t pool[] = {
        0, 1, -1, 2, 255, 256, 257, 1024, 0x7FFFFFFF, (int32_t) 0x80000000,
        (int32_t) 0x80000001, 0xFFFF, 1 << 20, (1 << 24), (1 << 24) + 1, 12345
    };
    const int n = (int) (sizeof(pool) / sizeof(pool[0]));
    uint64_t r = rnd_next();
    if ((r & 3) != 0) {
        return pool[r % (uint64_t) n];
    }
    return rnd_i32();
}

static double rnd_interesting_f64() {
    static const double pool[] = {
        0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 1e-7, -1e-7, 1e300, -1e300,
        2147483647.0, -2147483648.0, 4.9e-324, 1.7976931348623157e308
    };
    const int n = (int) (sizeof(pool) / sizeof(pool[0]));
    uint64_t r = rnd_next();
    switch (r & 7) {
        case 0: return (double) rnd_i32();
        case 1: return (double) rnd_i32() * 1e9;
        case 2: return (double) (r & 0xFFFF) / 8.0;
        default: return pool[(r >> 3) % (uint64_t) n];
    }
}

static uint64_t rnd_interesting_u64() {
    static const uint64_t pool[] = {0, 1, 2, 0xFFFFFFFFull, 0x100000000ull, 0x8000000000000000ull,
                                    0xFFFFFFFFFFFFFFFFull, 64, 65, 1024, 1 << 20};
    const int n = (int) (sizeof(pool) / sizeof(pool[0]));
    return pool[rnd_next() % (uint64_t) n];
}

/* Per-thread deterministic PRNG so the multi-threaded phase does not share the global stream
 * (a shared g_rng across threads would be a data race in the DRIVER, which would make any
 * finding unattributable). Same algorithm and same fixed-seed derivation => reproducible input
 * streams per worker, independent of scheduling. */
struct CavaRng {
    uint64_t s;
    explicit CavaRng(uint64_t seed) : s(seed ? seed : kDefaultSeed) {}
    uint64_t next() {
        uint64_t x = s;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        s = x;
        return x * 0x2545F4914F6CDD1Dull;
    }
};

/* ------------------------------------------------------------------ */
/* guarded allocation: [ptr, ptr+size) ends exactly at a guard page    */
/* ------------------------------------------------------------------ */
struct Guarded {
    void* base = nullptr;   /* start of the reservation (for free) */
    void* ptr = nullptr;    /* caller-visible buffer               */
    size_t size = 0;
    size_t tail = 0;        /* accessible bytes after the buffer   */
};

static size_t page_size() {
#ifdef _WIN32
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    return (size_t) si.dwPageSize;
#else
    return (size_t) sysconf(_SC_PAGESIZE);
#endif
}

static const unsigned char kSentinel = 0xA5;

static Guarded guarded_alloc(size_t size) {
    Guarded g;
    const size_t ps = page_size();
    const size_t pages = (size + ps - 1) / ps;
    const size_t total = (pages + 1) * ps;   /* +1 page = the guard page */
#ifdef _WIN32
    char* base = (char*) VirtualAlloc(nullptr, total, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
    if (base == nullptr) {
        std::fprintf(stderr, "FATAL: VirtualAlloc failed\n");
        std::exit(4);
    }
    DWORD old = 0;
    if (!VirtualProtect(base + pages * ps, ps, PAGE_NOACCESS, &old)) {
        std::fprintf(stderr, "FATAL: VirtualProtect guard page failed\n");
        std::exit(4);
    }
#else
    char* base = (char*) mmap(nullptr, total, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (base == MAP_FAILED) {
        std::fprintf(stderr, "FATAL: mmap failed\n");
        std::exit(4);
    }
    mprotect(base + pages * ps, ps, PROT_NONE);
#endif
    std::memset(base, kSentinel, pages * ps);
    g.base = base;
    g.size = size;
    g.tail = pages * ps - size;
    g.ptr = base + g.tail;   /* buffer ends exactly where the guard page begins */
    return g;
}

static void guarded_free(Guarded& g) {
    if (g.base == nullptr) {
        return;
    }
#ifdef _WIN32
    VirtualFree(g.base, 0, MEM_RELEASE);
#else
    const size_t ps = page_size();
    const size_t total = ((g.size + ps - 1) / ps + 1) * ps;
    munmap(g.base, total);
#endif
    g.base = nullptr;
    g.ptr = nullptr;
}

static bool guarded_tail_intact(const Guarded& g, size_t used) {
    const unsigned char* p = (const unsigned char*) g.base;
    if (used > g.tail) {
        return true;
    }
    for (size_t i = g.tail - used; i > 0; --i) {
        if (p[i - 1] != kSentinel) {
            return false;
        }
    }
    return true;
}

/* ------------------------------------------------------------------ */
/* ABI entry points (loaded dynamically)                              */
/* ------------------------------------------------------------------ */
typedef const char* (*fn_build_id)();
typedef int64_t (*fn_abi_touch)();
typedef int32_t (*fn_abi_version)();
typedef int32_t (*fn_layout_report)(CavaLayoutReport*);
typedef int32_t (*fn_open)(const CavaOpenParams*, int64_t*, CavaOpenResult*);
typedef int32_t (*fn_close)(int64_t);
typedef int32_t (*fn_pathfind)(int64_t, const CavaPathRequest*, CavaPathNode*, int32_t);
typedef int32_t (*fn_mob_upload)(int64_t, const CavaMobProfile*);
typedef int32_t (*fn_mob_clear)(int64_t);
typedef int32_t (*fn_state_upload)(int64_t, const CavaStateRecord*, int32_t, const CavaCollisionBox*, int32_t);
typedef int32_t (*fn_region_upload)(int64_t, int32_t, int32_t, int32_t, int32_t, int32_t, int32_t, const int32_t*, int32_t);
typedef int32_t (*fn_region_clear)(int64_t);
typedef int32_t (*fn_region_at)(int64_t, int32_t, int32_t, int32_t, int32_t*);
typedef int32_t (*fn_shape_upload)(int64_t, const CavaShapeRecord*, int32_t, const double*, int32_t, const uint64_t*, int32_t);
typedef int32_t (*fn_resolve)(int64_t, const CavaMoveRequest*, const CavaMoveShapeRef*, int32_t,
                              const CavaShapeRecord*, int32_t, const double*, int32_t, const uint64_t*, int32_t,
                              CavaMoveEvent*, int32_t, CavaMoveResult*);

static fn_pathfind      p_pathfind = nullptr;
static fn_mob_upload    p_mob_upload = nullptr;
static fn_mob_clear     p_mob_clear = nullptr;
static fn_state_upload  p_state_upload = nullptr;
static fn_region_upload p_region_upload = nullptr;
static fn_region_clear  p_region_clear = nullptr;
static fn_region_at     p_region_at = nullptr;
static fn_shape_upload  p_shape_upload = nullptr;
static fn_resolve       p_resolve = nullptr;
static fn_close         p_close = nullptr;

#ifdef _WIN32
static HMODULE g_lib = nullptr;
static void* sym(const char* name) { return (void*) GetProcAddress(g_lib, name); }
#else
static void* g_lib = nullptr;
static void* sym(const char* name) { return dlsym(g_lib, name); }
#endif

/* ------------------------------------------------------------------ */
/* result classification                                              */
/* ------------------------------------------------------------------ */
enum RcClass { RC_LEGAL = 0, RC_ABI_ERROR = 1, RC_UNDEFINED = 2 };

static RcClass classify(int32_t rc, bool allow_positive) {
    if (rc == CAVA_OK) {
        return RC_LEGAL;
    }
    if (allow_positive && rc > 0) {
        return RC_LEGAL;   /* cava_pathfind: >0 = node count */
    }
    if (rc <= -1 && rc >= -7) {
        return RC_ABI_ERROR;
    }
    return RC_UNDEFINED;
}

static const char* rc_name(int32_t rc) {
    if (rc > 0) {
        return "NODES";
    }
    switch (rc) {
        case CAVA_OK: return "OK";
        case CAVA_ERR_ABI_VERSION: return "ABI_VERSION";
        case CAVA_ERR_LAYOUT: return "LAYOUT";
        case CAVA_ERR_NULL: return "NULL";
        case CAVA_ERR_ARG: return "ARG";
        case CAVA_ERR_OOM: return "OOM";
        case CAVA_ERR_INTERNAL: return "INTERNAL";
        case CAVA_ERR_UNIMPLEMENTED: return "UNIMPLEMENTED";
        default: return "?";
    }
}

static int32_t record(const char* family, const char* inputs, int32_t rc, bool allow_positive,
                      bool expect_error) {
    ++g_cases;
    const RcClass c = classify(rc, allow_positive);
    if (c == RC_UNDEFINED) {
        ++g_undefined;
    }
    const bool unexpected_ok = expect_error && c == RC_LEGAL;
    if (unexpected_ok) {
        ++g_unexpected_ok;
    }
    if ((g_cases % g_verbose_every) == 0 || c == RC_UNDEFINED || unexpected_ok) {
        std::fprintf(g_log, "%-16s %-58s -> %4d (%-11s) %s\n", family, inputs, rc, rc_name(rc),
                     c == RC_UNDEFINED ? "*** UNDEFINED ***" : (unexpected_ok ? "*** UNEXPECTED OK ***" : ""));
    }
    return rc;
}

static void mutation(const char* family, const char* what) {
    ++g_state_mutations;
    std::fprintf(g_log, "%-16s *** STATE MUTATION *** %s\n", family, what);
}

static void guard_bad(const char* family, const char* what) {
    ++g_guard_violations;
    std::fprintf(g_log, "%-16s *** SENTINEL/OVERRUN *** %s\n", family, what);
}

/* ------------------------------------------------------------------ */
/* legal baseline scenarios                                           */
/* ------------------------------------------------------------------ */
static const int32_t kRegX = 8, kRegY = 4, kRegZ = 8;
static const int32_t kRegOX = 0, kRegOY = -1, kRegOZ = 0;

static int64_t g_handle = 0;

static void build_state_table(std::vector<CavaStateRecord>& recs, std::vector<CavaCollisionBox>& boxes) {
    CavaStateRecord air;
    std::memset(&air, 0, sizeof(air));
    air.flags = CAVA_SF_AIR | CAVA_SF_OPEN | CAVA_PF_PATH_THROUGH_LAND;
    air.box_offset = CAVA_BOX_NONE;
    air.box_count = 0;
    air.path_type_idx = 1;   /* CAVA_PNT_OPEN */
    air.malus = 0.0f;

    CavaStateRecord solid;
    std::memset(&solid, 0, sizeof(solid));
    solid.flags = CAVA_SF_SOLID | CAVA_SF_BLOCKS_MOTION;
    solid.box_offset = 0;
    solid.box_count = 1;
    solid.path_type_idx = 0; /* CAVA_PNT_BLOCKED */
    solid.malus = 0.0f;

    recs.clear();
    recs.push_back(air);
    recs.push_back(solid);

    CavaCollisionBox b;
    b.min_x = 0.0f; b.min_y = 0.0f; b.min_z = 0.0f;
    b.max_x = 1.0f; b.max_y = 1.0f; b.max_z = 1.0f;
    boxes.clear();
    boxes.push_back(b);
}

static int upload_legal_state_table() {
    std::vector<CavaStateRecord> recs;
    std::vector<CavaCollisionBox> boxes;
    build_state_table(recs, boxes);
    return p_state_upload(g_handle, recs.data(), (int32_t) recs.size(), boxes.data(), (int32_t) boxes.size());
}

/* A legal region with a REAL FLOOR: the bottom layer (local y == 0, world y == -1) is the
 * solid state id 1, everything above is air. Without a floor an ON_GROUND mob has no
 * successors at all and the navigator degenerates to a single-node "path", which would make
 * the invariance/cap checks vacuous. */
static int upload_legal_region() {
    std::vector<int32_t> ids((size_t) kRegX * kRegY * kRegZ, 0);
    for (int32_t z = 0; z < kRegZ; ++z) {
        for (int32_t x = 0; x < kRegX; ++x) {
            ids[((size_t) 0 * kRegZ + z) * kRegX + x] = 1;   /* local y = 0 => world y = -1 */
        }
    }
    return p_region_upload(g_handle, kRegX, kRegY, kRegZ, kRegOX, kRegOY, kRegOZ,
                           ids.data(), (int32_t) ids.size());
}

static int upload_legal_profile() {
    CavaMobProfile m;
    std::memset(&m, 0, sizeof(m));
    m.width = 0.6f;
    m.height = 1.8f;
    m.step_height = 0.6f;
    m.safe_fall_distance = 3;
    m.min_y = -64;
    m.sea_level = 63;
    m.caps = CAVA_NAV_ON_GROUND;
    m.penalty_mask = 0;
    m.start_x = 1.5;
    m.start_y = 0.0;
    m.start_z = 1.5;
    m.start_block_x = 1;
    m.start_block_y = 0;
    m.start_block_z = 1;
    return p_mob_upload(g_handle, &m);
}

static void build_legal_req(CavaPathRequest& r) {
    std::memset(&r, 0, sizeof(r));
    r.tx = 5;
    r.ty = 0;
    r.tz = 5;
    r.reach_range = 1;
    r.max_range = 16.0f;
    r.flags = 0;
    r.max_visited_nodes = 1024;
}

static void build_legal_shape_table(std::vector<CavaShapeRecord>& recs, std::vector<uint64_t>& bits) {
    CavaShapeRecord r;
    std::memset(&r, 0, sizeof(r));
    r.points_kind = CAVA_SHAPE_POINTS_FRACTIONAL;
    r.point_offset = 0;
    r.bit_offset = 0;
    r.bit_words = 1;              /* ceil(1*1*1 / 64) */
    r.size_x = 1; r.size_y = 1; r.size_z = 1;
    r.reserved0 = 0;
    recs.assign(1, r);
    bits.assign(1, 1ull);         /* voxel 0 set => the full unit cube */
}

static void build_legal_move(CavaMoveRequest& req, CavaMoveShapeRef& ref) {
    std::memset(&req, 0, sizeof(req));
    req.min_x = -0.3; req.min_y = 0.0; req.min_z = -0.3;
    req.max_x = 0.3;  req.max_y = 1.8; req.max_z = 0.3;
    req.move_x = 0.1; req.move_y = -0.5; req.move_z = 0.0;
    req.step_height = 0.6;
    req.flags = 0;
    req.on_ground = 1;
    req.shape_count = 1;
    req.reserved1 = 0;

    std::memset(&ref, 0, sizeof(ref));
    ref.shape_token = 42;
    ref.kind = CAVA_MSHAPE_STATE;
    ref.state_id = 0;
    ref.block_x = 0;
    ref.block_y = -1;
    ref.block_z = 0;
    ref.source = CAVA_ESHAPE_SRC_BLOCK;
}

/* ------------------------------------------------------------------ */
/* families                                                           */
/* ------------------------------------------------------------------ */
static const int64_t kForgedHandles[] = {
    0, -1, 1, 256, 257, 258, 1024, 0x100000001ll, 0x200000001ll,
    0xDEADBEEFll, (int64_t) 0x8000000000000000ull, 0x7FFFFFFFFFFFFFFFll, -123456789
};

/* The ABI handle encoding is (generation << 32) | (slot + 1) with 256 slots, so a handle is
 * only rejected for its SHAPE: {lo == 0} or {lo > 256}. A shape-valid handle that has no
 * state yet simply makes the entry a no-op / a legal empty answer -- that is by design
 * (see the comment in native/src/pathfind/cava_pf_abi.cpp), NOT a defence failure. */
static bool abi_handle_shape_ok(int64_t h) {
    const uint32_t lo = (uint32_t) ((uint64_t) h & 0xFFFFFFFFull);
    return lo != 0 && lo <= 256u;
}

static void family_handles() {
    CavaPathRequest req;
    build_legal_req(req);
    Guarded out = guarded_alloc(sizeof(CavaPathNode) * 8);
    Guarded one = guarded_alloc(sizeof(int32_t));
    CavaMobProfile prof;
    std::memset(&prof, 0, sizeof(prof));
    prof.width = 1.0f; prof.height = 1.0f;
    CavaStateRecord rec;
    std::memset(&rec, 0, sizeof(rec));
    rec.box_offset = CAVA_BOX_NONE;
    CavaCollisionBox box;
    std::memset(&box, 0, sizeof(box));
    CavaShapeRecord srec;
    std::memset(&srec, 0, sizeof(srec));
    int32_t ids[1] = {0};

    for (int64_t h : kForgedHandles) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "handle=0x%llX", (unsigned long long) h);
        const bool bad = !abi_handle_shape_ok(h);   /* only shape-invalid handles must fail */
        record("handles", buf, p_pathfind(h, &req, (CavaPathNode*) out.ptr, 8), true, false);
        record("handles", buf, p_mob_upload(h, &prof), false, bad);
        record("handles", buf, p_mob_clear(h), false, false);
        record("handles", buf, p_state_upload(h, &rec, 1, &box, 1), false, bad);
        record("handles", buf, p_region_upload(h, 1, 1, 1, 0, 0, 0, ids, 1), false, bad);
        record("handles", buf, p_region_clear(h), false, false);
        record("handles", buf, p_region_at(h, 0, 0, 0, (int32_t*) one.ptr), false, false);
        record("handles", buf, p_shape_upload(h, &srec, 1, nullptr, 0, nullptr, 0), false, bad);
        record("handles", buf, p_close(h), false, false);
    }
    guarded_free(out);
    guarded_free(one);
}

static void family_pathfind() {
    upload_legal_state_table();
    upload_legal_region();
    upload_legal_profile();

    CavaPathRequest base;
    build_legal_req(base);

    Guarded out = guarded_alloc(sizeof(CavaPathNode) * 8);
    record("pathfind", "req=NULL", p_pathfind(g_handle, nullptr, (CavaPathNode*) out.ptr, 8), true, true);
    record("pathfind", "out=NULL", p_pathfind(g_handle, &base, nullptr, 8), true, true);
    record("pathfind", "out=NULL cap=0", p_pathfind(g_handle, &base, nullptr, 0), true, true);

    {
        /* cap <= 0 is an argument error; cap >= 1 is legal (the node count may simply fit) */
        const int32_t caps[] = {0, -1, INT32_MIN, INT32_MAX, 1, 2};
        for (int32_t c : caps) {
            char buf[96];
            std::snprintf(buf, sizeof(buf), "cap=%d", c);
            record("pathfind", buf, p_pathfind(g_handle, &base, (CavaPathNode*) out.ptr, c), true, c <= 0);
        }
    }
    guarded_free(out);

    Guarded o = guarded_alloc(sizeof(CavaPathNode) * 64);
    {
        const uint32_t bad_flags[] = {1u, 2u, 0x80000000u, 0xFFFFFFFFu};
        for (uint32_t f : bad_flags) {
            CavaPathRequest r = base;
            r.flags = f;
            char buf[96];
            std::snprintf(buf, sizeof(buf), "flags=0x%X", f);
            record("pathfind", buf, p_pathfind(g_handle, &r, (CavaPathNode*) o.ptr, 64), true, true);
        }
        for (int f = 0; f < 3; ++f) {
            CavaPathRequest r = base;
            if (f == 0) r.reserved0 = 1;
            if (f == 1) r.reserved1 = -1;
            if (f == 2) r.reserved2 = INT32_MIN;
            char buf[96];
            std::snprintf(buf, sizeof(buf), "reserved%d != 0", f);
            record("pathfind", buf, p_pathfind(g_handle, &r, (CavaPathNode*) o.ptr, 64), true, true);
        }
        const int32_t budgets[] = {0, -1, INT32_MIN};
        for (int32_t b : budgets) {
            CavaPathRequest r = base;
            r.max_visited_nodes = b;
            char buf[96];
            std::snprintf(buf, sizeof(buf), "max_visited_nodes=%d", b);
            record("pathfind", buf, p_pathfind(g_handle, &r, (CavaPathNode*) o.ptr, 64), true, true);
        }
        const int32_t coords[] = {INT32_MIN, INT32_MIN + 1, -1, 0, 1, 30000000, INT32_MAX - 1, INT32_MAX};
        for (int32_t c : coords) {
            for (int axis = 0; axis < 3; ++axis) {
                CavaPathRequest r = base;
                if (axis == 0) r.tx = c;
                if (axis == 1) r.ty = c;
                if (axis == 2) r.tz = c;
                char buf[96];
                std::snprintf(buf, sizeof(buf), "t%s=%d", axis == 0 ? "x" : (axis == 1 ? "y" : "z"), c);
                record("pathfind", buf, p_pathfind(g_handle, &r, (CavaPathNode*) o.ptr, 64), true, false);
            }
        }
        const float ranges[] = {0.0f, -1.0f, -0.0f, 1e30f, -1e30f,
                                (float) NAN, (float) INFINITY, (float) -INFINITY, 3.4e38f};
        for (float v : ranges) {
            CavaPathRequest r = base;
            r.max_range = v;
            char buf[96];
            std::snprintf(buf, sizeof(buf), "max_range=%g", (double) v);
            record("pathfind", buf, p_pathfind(g_handle, &r, (CavaPathNode*) o.ptr, 64), true, false);
        }
        const int32_t reach[] = {0, -1, INT32_MIN, INT32_MAX, 1000000};
        for (int32_t v : reach) {
            CavaPathRequest r = base;
            r.reach_range = v;
            char buf[96];
            std::snprintf(buf, sizeof(buf), "reach_range=%d", v);
            record("pathfind", buf, p_pathfind(g_handle, &r, (CavaPathNode*) o.ptr, 64), true, false);
        }
    }
    guarded_free(o);
}

static uint64_t pathfind_baseline(int32_t* out_nodes) {
    Guarded o = guarded_alloc(sizeof(CavaPathNode) * 4096);
    CavaPathRequest req;
    build_legal_req(req);
    int32_t rc = p_pathfind(g_handle, &req, (CavaPathNode*) o.ptr, 4096);
    uint64_t h = 1469598103934665603ull;   /* FNV-1a 64 */
    const unsigned char* p = (const unsigned char*) o.ptr;
    const size_t n = (rc > 0) ? (size_t) rc * sizeof(CavaPathNode) : 0;
    for (size_t i = 0; i < n; ++i) {
        h ^= p[i];
        h *= 1099511628211ull;
    }
    if (!guarded_tail_intact(o, n)) {
        guard_bad("pathfind", "baseline wrote past the node count");
    }
    guarded_free(o);
    if (out_nodes != nullptr) {
        *out_nodes = rc;
    }
    return h;
}

static void family_resolve_move() {
    std::vector<CavaShapeRecord> srecs;
    std::vector<uint64_t> bits;
    build_legal_shape_table(srecs, bits);
    p_shape_upload(g_handle, srecs.data(), (int32_t) srecs.size(), nullptr, 0, bits.data(), (int32_t) bits.size());

    CavaMoveRequest base;
    CavaMoveShapeRef ref;
    build_legal_move(base, ref);

    Guarded out = guarded_alloc(sizeof(CavaMoveResult));
    Guarded events = guarded_alloc(sizeof(CavaMoveEvent) * 16);
    Guarded refs = guarded_alloc(sizeof(CavaMoveShapeRef));

    record("resolve", "req=NULL", p_resolve(g_handle, nullptr, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                            (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
    record("resolve", "out=NULL", p_resolve(g_handle, &base, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                            (CavaMoveEvent*) events.ptr, 16, nullptr), false, true);

    {
        const int32_t counts[] = {0, -1, INT32_MIN, INT32_MAX, 2, 1 << 20, (1 << 20) + 1};
        for (int32_t c : counts) {
            char buf[96];
            std::snprintf(buf, sizeof(buf), "ref_count=%d (req says 1)", c);
            record("resolve", buf, p_resolve(g_handle, &base, &ref, c, nullptr, 0, nullptr, 0, nullptr, 0,
                                             (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        }
        /* event_cap == 0 and a small cap are LEGAL (the overflow bit carries the loss);
         * only negative / above-cap values are argument errors. */
        const int32_t caps[] = {0, 1, -1, INT32_MIN, (1 << 22) + 1};
        for (int32_t c : caps) {
            char buf[96];
            std::snprintf(buf, sizeof(buf), "event_cap=%d", c);
            const bool bad = (c < 0) || (c > (1 << 22));
            record("resolve", buf, p_resolve(g_handle, &base, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                             (CavaMoveEvent*) events.ptr, c, (CavaMoveResult*) out.ptr), false, bad);
        }
    }

    {
        const uint32_t bad[] = {1u, 0x80000000u, 0xFFFFFFFFu};
        for (uint32_t f : bad) {
            CavaMoveRequest r = base; r.flags = f;
            char buf[96]; std::snprintf(buf, sizeof(buf), "flags=0x%X", f);
            record("resolve", buf, p_resolve(g_handle, &r, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                             (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        }
        CavaMoveRequest r0 = base; r0.reserved0 = 1;
        record("resolve", "reserved0=1", p_resolve(g_handle, &r0, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                                   (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        CavaMoveRequest r1 = base; r1.reserved1 = -1;
        record("resolve", "reserved1=-1", p_resolve(g_handle, &r1, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                                    (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        const uint32_t og[] = {2u, 3u, 0xFFFFFFFFu};
        for (uint32_t v : og) {
            CavaMoveRequest r = base; r.on_ground = v;
            char buf[96]; std::snprintf(buf, sizeof(buf), "on_ground=%u", v);
            record("resolve", buf, p_resolve(g_handle, &r, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                             (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        }
        CavaMoveRequest r2 = base; r2.shape_count = 0;
        record("resolve", "shape_count=0 vs ref_count=1", p_resolve(g_handle, &r2, &ref, 1, nullptr, 0, nullptr, 0,
                                                                   nullptr, 0, (CavaMoveEvent*) events.ptr, 16,
                                                                   (CavaMoveResult*) out.ptr), false, true);
        CavaMoveRequest r3 = base; r3.shape_count = 99;
        record("resolve", "shape_count=99 vs ref_count=1", p_resolve(g_handle, &r3, &ref, 1, nullptr, 0, nullptr, 0,
                                                                     nullptr, 0, (CavaMoveEvent*) events.ptr, 16,
                                                                     (CavaMoveResult*) out.ptr), false, true);
    }

    {
        struct AabbCase { const char* name; double minx, miny, minz, maxx, maxy, maxz; };
        const AabbCase cases[] = {
            {"inverted AABB (min>max)",  0.5, 0.5, 0.5, -0.5, -0.5, -0.5},
            {"zero-size AABB",           0.0, 0.0, 0.0, 0.0, 0.0, 0.0},
            {"negative-size AABB",      -1.0, -1.0, -1.0, -2.0, -2.0, -2.0},
            {"huge AABB (1e308)",       -1e308, -1e308, -1e308, 1e308, 1e308, 1e308},
            {"NaN AABB",                 NAN, NAN, NAN, NAN, NAN, NAN},
            {"+Inf AABB",                INFINITY, INFINITY, INFINITY, INFINITY, INFINITY, INFINITY},
            {"-Inf AABB",               -INFINITY, -INFINITY, -INFINITY, -INFINITY, -INFINITY, -INFINITY},
            {"min=+Inf max=-Inf",        INFINITY, INFINITY, INFINITY, -INFINITY, -INFINITY, -INFINITY},
            {"wide AABB (1e6)",         -1e6, 0.0, -1e6, 1e6, 1.8, 1e6},
        };
        for (const AabbCase& c : cases) {
            CavaMoveRequest r = base;
            r.min_x = c.minx; r.min_y = c.miny; r.min_z = c.minz;
            r.max_x = c.maxx; r.max_y = c.maxy; r.max_z = c.maxz;
            record("resolve", c.name, p_resolve(g_handle, &r, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                                (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, false);
        }
        const double moves[] = {0.0, -0.0, NAN, INFINITY, -INFINITY, 1e308, -1e308, 1e-7, -1e-7};
        for (double mv : moves) {
            for (int axis = 0; axis < 3; ++axis) {
                CavaMoveRequest r = base;
                if (axis == 0) r.move_x = mv;
                if (axis == 1) r.move_y = mv;
                if (axis == 2) r.move_z = mv;
                char buf[96];
                std::snprintf(buf, sizeof(buf), "move_%s=%g", axis == 0 ? "x" : (axis == 1 ? "y" : "z"), mv);
                record("resolve", buf, p_resolve(g_handle, &r, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                                 (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, false);
            }
        }
        const double steps[] = {0.0, -0.0, -1.0, NAN, INFINITY, -INFINITY, 1e308, 1e-7};
        for (double s : steps) {
            CavaMoveRequest r = base;
            r.step_height = s;
            char buf[96];
            std::snprintf(buf, sizeof(buf), "step_height=%g", s);
            record("resolve", buf, p_resolve(g_handle, &r, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                             (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, false);
        }
    }

    {
        const uint32_t kinds[] = {2u, 3u, 0xFFFFFFFFu};
        for (uint32_t k : kinds) {
            CavaMoveShapeRef r = ref; r.kind = k;
            char buf[96]; std::snprintf(buf, sizeof(buf), "ref.kind=%u", k);
            record("resolve", buf, p_resolve(g_handle, &base, &r, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                             (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        }
        const uint32_t sources[] = {4u, 0xFFFFFFFFu};
        for (uint32_t s : sources) {
            CavaMoveShapeRef r = ref; r.source = s;
            char buf[96]; std::snprintf(buf, sizeof(buf), "ref.source=%u", s);
            record("resolve", buf, p_resolve(g_handle, &base, &r, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                             (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        }
        CavaMoveShapeRef rr0 = ref; rr0.reserved0 = 1;
        record("resolve", "ref.reserved0=1", p_resolve(g_handle, &base, &rr0, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                                       (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        CavaMoveShapeRef rr1 = ref; rr1.reserved1 = -1; rr1.reserved2 = INT32_MIN;
        record("resolve", "ref.reserved1/2 != 0", p_resolve(g_handle, &base, &rr1, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                                            (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        const uint32_t ids[] = {1u, 2u, 0xFFFFFFFFu, 0x7FFFFFFFu};
        for (uint32_t id : ids) {
            CavaMoveShapeRef r = ref; r.state_id = id;
            char buf[96]; std::snprintf(buf, sizeof(buf), "ref.state_id=%u (table has 1)", id);
            record("resolve", buf, p_resolve(g_handle, &base, &r, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                             (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        }
        CavaMoveShapeRef ri = ref; ri.kind = CAVA_MSHAPE_INLINE; ri.inline_slot = 0;
        record("resolve", "inline_slot=0 with 0 inline shapes",
               p_resolve(g_handle, &base, &ri, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                         (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        ri.inline_slot = 0xFFFFFFFFu;
        record("resolve", "inline_slot=UINT32_MAX",
               p_resolve(g_handle, &base, &ri, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                         (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
        const int32_t coords[] = {INT32_MIN, INT32_MIN + 1, -1, 0, 1, INT32_MAX - 1, INT32_MAX};
        for (int32_t c : coords) {
            CavaMoveShapeRef r = ref; r.block_x = c; r.block_y = c; r.block_z = c;
            char buf[96]; std::snprintf(buf, sizeof(buf), "ref.block=INT(%d)", c);
            record("resolve", buf, p_resolve(g_handle, &base, &r, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                             (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, false);
        }
        record("resolve", "refs=ptr ref_count=0",
               p_resolve(g_handle, &base, (CavaMoveShapeRef*) refs.ptr, 0, nullptr, 0, nullptr, 0, nullptr, 0,
                         (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr), false, true);
    }

    {
        Guarded irefs = guarded_alloc(sizeof(CavaShapeRecord));
        CavaShapeRecord* ir = (CavaShapeRecord*) irefs.ptr;
        CavaMoveShapeRef r = ref;
        r.kind = CAVA_MSHAPE_INLINE;
        r.inline_slot = 0;
        CavaMoveRequest req = base;

        struct InlineCase { const char* name; CavaShapeRecord rec; };
        InlineCase cases[8];
        for (int i = 0; i < 8; ++i) {
            std::memset(&cases[i].rec, 0, sizeof(CavaShapeRecord));
            cases[i].rec.size_x = 1; cases[i].rec.size_y = 1; cases[i].rec.size_z = 1;
            cases[i].rec.bit_words = 1;
        }
        cases[0].name = "inline points_kind=2";
        cases[0].rec.points_kind = 2;
        cases[1].name = "inline reserved0!=0";
        cases[1].rec.reserved0 = 7;
        cases[2].name = "inline bit_words=0 (state says 1)";
        cases[2].rec.bit_words = 0;
        cases[3].name = "inline bit_words=UINT32_MAX";
        cases[3].rec.bit_words = 0xFFFFFFFFu;
        cases[4].name = "inline size_x negative";
        cases[4].rec.size_x = -1;
        cases[5].name = "inline size huge (1<<20)";
        cases[5].rec.size_x = 1 << 20; cases[5].rec.size_y = 1 << 20; cases[5].rec.size_z = 1 << 20;
        cases[6].name = "inline EXPLICIT point_offset=UINT32_MAX";
        cases[6].rec.points_kind = CAVA_SHAPE_POINTS_EXPLICIT;
        cases[6].rec.point_offset = 0xFFFFFFFFu;
        cases[7].name = "inline bit_offset=UINT32_MAX";
        cases[7].rec.bit_offset = 0xFFFFFFFFu;

        for (int i = 0; i < 8; ++i) {
            *ir = cases[i].rec;
            record("resolve", cases[i].name, p_resolve(g_handle, &req, &r, 1, ir, 1, nullptr, 0, nullptr, 0,
                                                       (CavaMoveEvent*) events.ptr, 16, (CavaMoveResult*) out.ptr),
                   false, true);
        }
        guarded_free(irefs);
    }

    {
        Guarded ev1 = guarded_alloc(sizeof(CavaMoveEvent));
        CavaMoveResult res;
        std::memset(&res, 0, sizeof(res));
        int32_t rc = p_resolve(g_handle, &base, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                               (CavaMoveEvent*) ev1.ptr, 1, &res);
        record("resolve", "event_cap=1 (guarded)", rc, false, false);
        if (!guarded_tail_intact(ev1, res.event_count > 0 ? sizeof(CavaMoveEvent) : 0)) {
            guard_bad("resolve", "event array overrun with cap=1");
        }
        guarded_free(ev1);
    }

    guarded_free(out);
    guarded_free(events);
    guarded_free(refs);
}

static void family_region() {
    int rc = upload_legal_region();
    record("region", "legal 8x4x8 upload", rc, false, false);

    Guarded one = guarded_alloc(sizeof(int32_t));
    int32_t baseline[4] = {0, 0, 0, 0};
    p_region_at(g_handle, 0, -1, 0, &baseline[0]);
    p_region_at(g_handle, 7, 2, 7, &baseline[1]);
    p_region_at(g_handle, 8, 0, 0, &baseline[2]);
    p_region_at(g_handle, 0, -2, 0, &baseline[3]);

    int32_t ids[4] = {0, 0, 0, 0};

    struct DimCase { const char* name; int32_t dx, dy, dz, count; };
    const DimCase dims[] = {
        {"dim_x=0", 0, 1, 1, 4},
        {"dim_y=0", 1, 0, 1, 4},
        {"dim_z=0", 1, 1, 0, 4},
        {"dim_x=-1", -1, 1, 1, 4},
        {"dims=INT32_MIN", INT32_MIN, 1, 1, 4},
        {"dims=INT32_MAX", INT32_MAX, INT32_MAX, INT32_MAX, 4},
        {"dims=1,1,1 count=2", 1, 1, 1, 2},
        {"dims=2,1,2 count=0", 2, 1, 2, 0},
        {"dims=2,1,2 count=3", 2, 1, 2, 3},
        {"dims=2,1,2 count=5", 2, 1, 2, 5},
        {"dims=2,1,2 count=-1", 2, 1, 2, -1},
        {"dims=1,1,1<<24 count=4", 1, 1, 1 << 24, 4},
        {"dims=256,256,256 (>16M)", 256, 256, 256, 4},
    };
    const int dim_count = (int) (sizeof(dims) / sizeof(dims[0]));
    for (int i = 0; i < dim_count; ++i) {
        record("region", dims[i].name,
               p_region_upload(g_handle, dims[i].dx, dims[i].dy, dims[i].dz, 0, 0, 0, ids, dims[i].count),
               false, true);
    }
    record("region", "ids=NULL count=4", p_region_upload(g_handle, 2, 1, 2, 0, 0, 0, nullptr, 4), false, true);
    record("region", "ids=NULL count=0", p_region_upload(g_handle, 2, 1, 2, 0, 0, 0, nullptr, 0), false, true);

    record("region", "legal 2x1x2 at INT32_MIN",
           p_region_upload(g_handle, 2, 1, 2, INT32_MIN, INT32_MIN, INT32_MIN, ids, 4), false, false);
    record("region", "legal 2x1x2 at INT32_MAX",
           p_region_upload(g_handle, 2, 1, 2, INT32_MAX, INT32_MAX, INT32_MAX, ids, 4), false, false);

    const int32_t coords[] = {INT32_MIN, INT32_MIN + 1, -1, 0, 1, INT32_MAX - 1, INT32_MAX};
    for (int32_t c : coords) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "query(%d,%d,%d)", c, c, c);
        record("region", buf, p_region_at(g_handle, c, c, c, (int32_t*) one.ptr), false, false);
    }
    record("region", "query out=NULL", p_region_at(g_handle, 0, 0, 0, nullptr), false, true);

    p_region_clear(g_handle);
    upload_legal_region();
    int32_t recheck[4] = {0, 0, 0, 0};
    p_region_at(g_handle, 0, -1, 0, &recheck[0]);
    p_region_at(g_handle, 7, 2, 7, &recheck[1]);
    p_region_at(g_handle, 8, 0, 0, &recheck[2]);
    p_region_at(g_handle, 0, -2, 0, &recheck[3]);
    for (int i = 0; i < 4; ++i) {
        if (baseline[i] != recheck[i]) {
            char buf[128];
            std::snprintf(buf, sizeof(buf), "region baseline #%d differed before the storm: %d vs %d",
                          i, baseline[i], recheck[i]);
            mutation("region", buf);
        }
    }

    for (int i = 0; i < dim_count; ++i) {
        p_region_upload(g_handle, dims[i].dx, dims[i].dy, dims[i].dz, 9, 9, 9, ids, dims[i].count);
    }
    p_region_upload(g_handle, 2, 1, 2, 1, 1, 1, nullptr, 4);

    int32_t after[4] = {0, 0, 0, 0};
    p_region_at(g_handle, 0, -1, 0, &after[0]);
    p_region_at(g_handle, 7, 2, 7, &after[1]);
    p_region_at(g_handle, 8, 0, 0, &after[2]);
    p_region_at(g_handle, 0, -2, 0, &after[3]);
    for (int i = 0; i < 4; ++i) {
        if (recheck[i] != after[i]) {
            char buf[128];
            std::snprintf(buf, sizeof(buf), "region point #%d changed %d -> %d after bad uploads",
                          i, recheck[i], after[i]);
            mutation("region", buf);
        }
    }
    p_region_clear(0xDEADBEEFll);
    int32_t afterForgedClear = 12345;
    p_region_at(g_handle, 0, -1, 0, &afterForgedClear);
    if (afterForgedClear != after[0]) {
        mutation("region", "forged-handle region_clear wiped the real region");
    }
    guarded_free(one);
}

static void family_state_table() {
    std::vector<CavaStateRecord> recs;
    std::vector<CavaCollisionBox> boxes;
    build_state_table(recs, boxes);
    /* The caller MUST keep (pointer, count) same-source: a count that stays inside the
     * callee's accepted range is dereferenced for real, so the buffer has to be that big.
     * Only counts above the callee's own cap are rejected before any dereference.
     * (Getting this wrong is how the FIRST version of this driver crashed -- see the notes
     * document: a fuzz harness that breaks the contract measures its own UB.) */
    boxes.resize(2, boxes[0]);

    record("state_table", "legal 2 recs + 1 box",
           p_state_upload(g_handle, recs.data(), 2, boxes.data(), 1), false, false);

    const int32_t counts[] = {0, -1, INT32_MIN, INT32_MAX, (1 << 20) + 1, 2};
    for (int32_t c : counts) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "record_count=%d", c);
        record("state_table", buf, p_state_upload(g_handle, recs.data(), c, boxes.data(), 1), false, false);
    }
    /* box counts: (1 << 22) is the callee cap; (1 << 22) + 1 is rejected before the pointer
     * is touched, so it is safe with a 2-element buffer. Counts INSIDE the cap are only used
     * up to the size of the buffer we really allocated. */
    const int32_t box_counts[] = {0, -1, INT32_MIN, INT32_MAX, (1 << 22) + 1, 1, 2};
    for (int32_t c : box_counts) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "box_count=%d", c);
        record("state_table", buf, p_state_upload(g_handle, recs.data(), 2, boxes.data(), c), false, false);
    }
    /* boundary: exactly the callee cap for records (1 << 20), with a matching buffer */
    {
        Guarded bigRecs = guarded_alloc(sizeof(CavaStateRecord) * ((size_t) 1 << 20));
        CavaStateRecord* p = (CavaStateRecord*) bigRecs.ptr;
        std::memset(p, 0, sizeof(CavaStateRecord) * ((size_t) 1 << 20));
        record("state_table", "record_count=1<<20 (== cap, full buffer)",
               p_state_upload(g_handle, p, 1 << 20, boxes.data(), 1), false, false);
        guarded_free(bigRecs);
        upload_legal_state_table();
    }
    record("state_table", "records=NULL count=2", p_state_upload(g_handle, nullptr, 2, boxes.data(), 1), false, true);
    record("state_table", "boxes=NULL count=1", p_state_upload(g_handle, recs.data(), 2, nullptr, 1), false, true);
    record("state_table", "records=NULL count=0 boxes=NULL count=0",
           p_state_upload(g_handle, nullptr, 0, nullptr, 0), false, false);

    for (int i = 0; i < 64; ++i) {
        CavaStateRecord r;
        r.flags = (uint32_t) rnd_next();
        r.box_offset = (uint32_t) rnd_next();
        r.box_count = (uint32_t) rnd_next();
        r.path_type_idx = (uint32_t) rnd_next();
        r.malus = (float) rnd_interesting_f64();
        char buf[96];
        std::snprintf(buf, sizeof(buf), "random record #%d", i);
        record("state_table", buf, p_state_upload(g_handle, &r, 1, boxes.data(), 1), false, false);
    }
    for (int i = 0; i < 32; ++i) {
        CavaCollisionBox b;
        b.min_x = (float) rnd_interesting_f64(); b.min_y = (float) rnd_interesting_f64();
        b.min_z = (float) rnd_interesting_f64();
        b.max_x = (float) rnd_interesting_f64(); b.max_y = (float) rnd_interesting_f64();
        b.max_z = (float) rnd_interesting_f64();
        char buf[96];
        std::snprintf(buf, sizeof(buf), "random box #%d", i);
        record("state_table", buf, p_state_upload(g_handle, recs.data(), 2, &b, 1), false, false);
    }
}

static void family_shape_table() {
    std::vector<CavaShapeRecord> recs;
    std::vector<uint64_t> bits;
    build_legal_shape_table(recs, bits);

    record("shape_table", "legal 1 rec + 1 bit word",
           p_shape_upload(g_handle, recs.data(), 1, nullptr, 0, bits.data(), 1), false, false);

    const int32_t counts[] = {0, -1, INT32_MIN, INT32_MAX, (1 << 20) + 1, 1};
    for (int32_t c : counts) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "record_count=%d", c);
        record("shape_table", buf, p_shape_upload(g_handle, recs.data(), c, nullptr, 0, bits.data(), 1), false, false);
    }
    for (int32_t c : counts) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "point_count=%d", c);
        record("shape_table", buf, p_shape_upload(g_handle, recs.data(), 1, nullptr, c, bits.data(), 1), false, false);
    }
    /* bit_word_count: only values <= our 1-word buffer, or above the callee cap
     * ((1 << 25) = kMaxBitWords, rejected before dereferencing), are safe to pass. */
    const int32_t bit_counts[] = {0, -1, INT32_MIN, INT32_MAX, (1 << 25) + 1, 1};
    for (int32_t c : bit_counts) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "bit_word_count=%d", c);
        record("shape_table", buf, p_shape_upload(g_handle, recs.data(), 1, nullptr, 0, bits.data(), c), false, false);
    }
    record("shape_table", "records=NULL count=1",
           p_shape_upload(g_handle, nullptr, 1, nullptr, 0, bits.data(), 1), false, true);
    record("shape_table", "bits=NULL count=1",
           p_shape_upload(g_handle, recs.data(), 1, nullptr, 0, nullptr, 1), false, true);

    for (int i = 0; i < 128; ++i) {
        CavaShapeRecord r;
        r.points_kind = (uint32_t) (rnd_next() & 3);
        r.point_offset = (uint32_t) rnd_interesting_u64();
        r.bit_offset = (uint32_t) rnd_interesting_u64();
        r.bit_words = (uint32_t) rnd_interesting_u64();
        r.size_x = rnd_interesting_i32();
        r.size_y = rnd_interesting_i32();
        r.size_z = rnd_interesting_i32();
        r.reserved0 = (uint32_t) (rnd_next() & 1);
        char buf[96];
        std::snprintf(buf, sizeof(buf), "random record #%d", i);
        record("shape_table", buf, p_shape_upload(g_handle, &r, 1, nullptr, 0, bits.data(), 1), false, false);
    }
}

static void family_random(uint64_t n) {
    Guarded out = guarded_alloc(sizeof(CavaPathNode) * 32);
    Guarded mout = guarded_alloc(sizeof(CavaMoveResult));
    Guarded events = guarded_alloc(sizeof(CavaMoveEvent) * 4);
    Guarded refs = guarded_alloc(sizeof(CavaMoveShapeRef) * 2);
    Guarded srecs = guarded_alloc(sizeof(CavaShapeRecord) * 2);
    Guarded strecs = guarded_alloc(sizeof(CavaStateRecord) * 2);
    Guarded boxes = guarded_alloc(sizeof(CavaCollisionBox) * 2);
    Guarded ids = guarded_alloc(sizeof(int32_t) * 512);
    Guarded one = guarded_alloc(sizeof(int32_t));

    for (uint64_t i = 0; i < n; ++i) {
        const int32_t pick = (int32_t) (rnd_next() % 6);
        char buf[160];
        switch (pick) {
            case 0: {
                CavaPathRequest r;
                std::memset(&r, 0, sizeof(r));
                r.tx = rnd_interesting_i32();
                r.ty = rnd_interesting_i32();
                r.tz = rnd_interesting_i32();
                r.reach_range = rnd_interesting_i32();
                r.max_range = (float) rnd_interesting_f64();
                r.flags = (rnd_next() & 7) ? 0u : (uint32_t) rnd_next();
                r.reserved0 = (rnd_next() & 7) ? 0 : rnd_i32();
                r.reserved2 = (rnd_next() & 7) ? 0 : rnd_i32();
                r.max_visited_nodes = rnd_interesting_i32();
                std::snprintf(buf, sizeof(buf), "rand tx=%d ty=%d tz=%d budget=%d",
                              r.tx, r.ty, r.tz, r.max_visited_nodes);
                record("rand/pathfind", buf, p_pathfind(g_handle, &r, (CavaPathNode*) out.ptr, 32), true, false);
                break;
            }
            case 1: {
                CavaMoveRequest r;
                std::memset(&r, 0, sizeof(r));
                r.min_x = rnd_interesting_f64(); r.min_y = rnd_interesting_f64(); r.min_z = rnd_interesting_f64();
                r.max_x = rnd_interesting_f64(); r.max_y = rnd_interesting_f64(); r.max_z = rnd_interesting_f64();
                r.move_x = rnd_interesting_f64(); r.move_y = rnd_interesting_f64(); r.move_z = rnd_interesting_f64();
                r.step_height = rnd_interesting_f64();
                r.flags = (rnd_next() & 7) ? 0u : (uint32_t) rnd_next();
                r.on_ground = (uint32_t) (rnd_next() & 1);
                r.shape_count = (rnd_next() & 3) ? 1 : rnd_interesting_i32();
                CavaMoveShapeRef ref;
                std::memset(&ref, 0, sizeof(ref));
                ref.shape_token = (int64_t) rnd_next();
                ref.kind = (rnd_next() & 3) ? CAVA_MSHAPE_STATE : (uint32_t) (rnd_next() & 3);
                ref.state_id = (rnd_next() & 3) ? 0u : (uint32_t) rnd_next();
                ref.source = (rnd_next() & 3) ? CAVA_ESHAPE_SRC_BLOCK : (uint32_t) (rnd_next() & 7);
                ref.block_x = rnd_interesting_i32();
                ref.block_y = rnd_interesting_i32();
                ref.block_z = rnd_interesting_i32();
                std::snprintf(buf, sizeof(buf), "rand move min=(%g,%g,%g) mv=(%g,%g,%g)",
                              r.min_x, r.min_y, r.min_z, r.move_x, r.move_y, r.move_z);
                record("rand/resolve", buf, p_resolve(g_handle, &r, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                                      (CavaMoveEvent*) events.ptr, 4, (CavaMoveResult*) mout.ptr),
                       false, false);
                break;
            }
            case 2: {
                int32_t dx = rnd_interesting_i32(), dy = rnd_interesting_i32(), dz = rnd_interesting_i32();
                /* keep cnt inside the buffer we really allocated (see family_state_table) */
                int32_t cnt = (int32_t) (rnd_next() % 512);
                std::snprintf(buf, sizeof(buf), "rand region %d x %d x %d count=%d", dx, dy, dz, cnt);
                record("rand/region", buf,
                       p_region_upload(g_handle, dx, dy, dz, rnd_interesting_i32(), rnd_interesting_i32(),
                                       rnd_interesting_i32(), (const int32_t*) ids.ptr, cnt), false, false);
                break;
            }
            case 3: {
                CavaStateRecord* rs = (CavaStateRecord*) strecs.ptr;
                CavaCollisionBox* bs = (CavaCollisionBox*) boxes.ptr;
                for (int k = 0; k < 2; ++k) {
                    rs[k].flags = (uint32_t) rnd_next();
                    rs[k].box_offset = (uint32_t) rnd_next();
                    rs[k].box_count = (uint32_t) rnd_next();
                    rs[k].path_type_idx = (uint32_t) rnd_next();
                    rs[k].malus = (float) rnd_interesting_f64();
                    bs[k].min_x = (float) rnd_interesting_f64(); bs[k].max_x = (float) rnd_interesting_f64();
                    bs[k].min_y = (float) rnd_interesting_f64(); bs[k].max_y = (float) rnd_interesting_f64();
                    bs[k].min_z = (float) rnd_interesting_f64(); bs[k].max_z = (float) rnd_interesting_f64();
                }
                int32_t rcc = (rnd_next() & 1)
                    ? p_state_upload(g_handle, rs, 2, bs, 2)
                    : p_state_upload(g_handle, rs, (int32_t) (rnd_next() % 3) - 1, bs, 1);
                record("rand/state", "rand state table", rcc, false, false);
                break;
            }
            case 4: {
                CavaShapeRecord* rs = (CavaShapeRecord*) srecs.ptr;
                for (int k = 0; k < 2; ++k) {
                    rs[k].points_kind = (uint32_t) (rnd_next() & 3);
                    rs[k].point_offset = (uint32_t) rnd_next();
                    rs[k].bit_offset = (uint32_t) rnd_next();
                    rs[k].bit_words = (uint32_t) rnd_next();
                    rs[k].size_x = rnd_interesting_i32();
                    rs[k].size_y = rnd_interesting_i32();
                    rs[k].size_z = rnd_interesting_i32();
                    rs[k].reserved0 = (uint32_t) (rnd_next() & 1);
                }
                record("rand/shape", "rand shape table",
                       p_shape_upload(g_handle, rs, (int32_t) (rnd_next() % 3) - 1, nullptr, 0, nullptr, 0),
                       false, false);
                break;
            }
            default: {
                CavaMoveShapeRef* rr = (CavaMoveShapeRef*) refs.ptr;
                for (int k = 0; k < 2; ++k) {
                    rr[k].shape_token = (int64_t) rnd_next();
                    rr[k].kind = (uint32_t) (rnd_next() & 3);
                    rr[k].state_id = (uint32_t) rnd_next();
                    rr[k].block_x = rnd_interesting_i32();
                    rr[k].block_y = rnd_interesting_i32();
                    rr[k].block_z = rnd_interesting_i32();
                    rr[k].source = (uint32_t) (rnd_next() & 7);
                    rr[k].inline_slot = (uint32_t) rnd_next();
                    rr[k].reserved0 = (uint32_t) (rnd_next() & 1);
                    rr[k].reserved1 = (uint32_t) (rnd_next() & 1);
                    rr[k].reserved2 = (uint32_t) (rnd_next() & 1);
                }
                CavaMoveRequest r;
                std::memset(&r, 0, sizeof(r));
                r.min_x = rnd_interesting_f64(); r.min_y = rnd_interesting_f64(); r.min_z = rnd_interesting_f64();
                r.max_x = rnd_interesting_f64(); r.max_y = rnd_interesting_f64(); r.max_z = rnd_interesting_f64();
                r.move_x = rnd_interesting_f64(); r.move_y = rnd_interesting_f64(); r.move_z = rnd_interesting_f64();
                r.step_height = rnd_interesting_f64();
                r.on_ground = (uint32_t) (rnd_next() & 3);
                r.shape_count = (int32_t) (rnd_next() % 3);
                record("rand/refs", "rand refs garbage",
                       p_resolve(g_handle, &r, rr, (int32_t) (rnd_next() % 3), nullptr, 0, nullptr, 0,
                                 nullptr, 0, (CavaMoveEvent*) events.ptr, 4, (CavaMoveResult*) mout.ptr),
                       false, false);
                break;
            }
        }
        record("rand/query", "region_state_id_at",
               p_region_at(g_handle, rnd_interesting_i32(), rnd_interesting_i32(), rnd_interesting_i32(),
                           (int32_t*) one.ptr), false, false);
    }

    guarded_free(out);
    guarded_free(mout);
    guarded_free(events);
    guarded_free(refs);
    guarded_free(srecs);
    guarded_free(strecs);
    guarded_free(boxes);
    guarded_free(ids);
    guarded_free(one);
}

static void family_invariance() {
    int32_t stRc = upload_legal_state_table();
    int32_t regRc = upload_legal_region();
    int32_t profRc = upload_legal_profile();
    record("invariance", "rebuild state table", stRc, false, false);
    record("invariance", "rebuild region", regRc, false, false);
    record("invariance", "rebuild profile", profRc, false, false);

    int32_t nodesBefore = 0;
    uint64_t pfBefore = pathfind_baseline(&nodesBefore);
    char buf[192];
    std::snprintf(buf, sizeof(buf), "pathfind baseline nodes=%d hash=0x%016llX",
                  nodesBefore, (unsigned long long) pfBefore);
    std::fprintf(g_log, "%-16s %s\n", "invariance", buf);

    std::vector<CavaShapeRecord> srecs;
    std::vector<uint64_t> bits;
    build_legal_shape_table(srecs, bits);
    p_shape_upload(g_handle, srecs.data(), 1, nullptr, 0, bits.data(), 1);
    CavaMoveRequest mreq;
    CavaMoveShapeRef mref;
    build_legal_move(mreq, mref);
    CavaMoveResult mresBefore;
    std::memset(&mresBefore, 0, sizeof(mresBefore));
    Guarded mev = guarded_alloc(sizeof(CavaMoveEvent) * 16);
    int32_t mvRcBefore = p_resolve(g_handle, &mreq, &mref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                   (CavaMoveEvent*) mev.ptr, 16, &mresBefore);
    uint64_t mvBits = 0;
    std::memcpy(&mvBits, &mresBefore.delta_x, sizeof(double));
    std::fprintf(g_log, "%-16s resolve baseline rc=%d delta=(%g,%g,%g) events=%d overflow=%d\n",
                 "invariance", mvRcBefore, mresBefore.delta_x, mresBefore.delta_y, mresBefore.delta_z,
                 mresBefore.event_count, mresBefore.event_overflow);
    guarded_free(mev);

    CavaPathRequest badReq;
    std::memset(&badReq, 0, sizeof(badReq));
    badReq.max_range = (float) NAN;
    badReq.max_visited_nodes = -1;
    badReq.flags = 1;
    Guarded o = guarded_alloc(sizeof(CavaPathNode) * 8);
    for (int i = 0; i < 200; ++i) {
        p_pathfind(g_handle, &badReq, (CavaPathNode*) o.ptr, 8);
        p_pathfind(0xDEADBEEFll, &badReq, (CavaPathNode*) o.ptr, 8);
        std::vector<int32_t> badIds(4, 0);
        p_region_upload(g_handle, (i & 1) ? -1 : 2, 1, 2, i, i, i, badIds.data(), 3);
        p_region_upload(g_handle, 2, 1, 2, i, i, i, nullptr, 4);
        p_state_upload(g_handle, nullptr, 2, nullptr, 1);
        p_shape_upload(g_handle, nullptr, 1, nullptr, 0, nullptr, 1);
        CavaShapeRecord broken;
        std::memset(&broken, 0, sizeof(broken));
        broken.size_x = -1;
        p_shape_upload(g_handle, &broken, 1, nullptr, 0, nullptr, 0);
        CavaMoveRequest bad;
        std::memset(&bad, 0, sizeof(bad));
        bad.flags = 1;
        bad.shape_count = 7;
        Guarded res = guarded_alloc(sizeof(CavaMoveResult));
        Guarded ev = guarded_alloc(sizeof(CavaMoveEvent));
        p_resolve(g_handle, &bad, &mref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                  (CavaMoveEvent*) ev.ptr, 1, (CavaMoveResult*) res.ptr);
        guarded_free(res);
        guarded_free(ev);
    }
    guarded_free(o);

    int32_t nodesAfter = 0;
    uint64_t pfAfter = pathfind_baseline(&nodesAfter);
    if (nodesAfter != nodesBefore || pfAfter != pfBefore) {
        std::snprintf(buf, sizeof(buf), "pathfind baseline changed: nodes %d -> %d, hash 0x%016llX -> 0x%016llX",
                      nodesBefore, nodesAfter, (unsigned long long) pfBefore, (unsigned long long) pfAfter);
        mutation("invariance", buf);
    } else {
        std::fprintf(g_log, "%-16s pathfind baseline identical after 1400 bad calls (nodes=%d hash=0x%016llX)\n",
                     "invariance", nodesAfter, (unsigned long long) pfAfter);
    }

    CavaMoveResult mresAfter;
    std::memset(&mresAfter, 0, sizeof(mresAfter));
    Guarded mev2 = guarded_alloc(sizeof(CavaMoveEvent) * 16);
    int32_t mvRcAfter = p_resolve(g_handle, &mreq, &mref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                  (CavaMoveEvent*) mev2.ptr, 16, &mresAfter);
    uint64_t mvBitsAfter = 0;
    std::memcpy(&mvBitsAfter, &mresAfter.delta_x, sizeof(double));
    if (mvRcAfter != mvRcBefore || mvBitsAfter != mvBits) {
        std::snprintf(buf, sizeof(buf), "resolve baseline changed: rc %d -> %d, delta bits 0x%016llX -> 0x%016llX",
                      mvRcBefore, mvRcAfter, (unsigned long long) mvBits, (unsigned long long) mvBitsAfter);
        mutation("invariance", buf);
    } else {
        std::fprintf(g_log, "%-16s resolve baseline bit-identical after the bad-call storm\n", "invariance");
    }
    guarded_free(mev2);
}

static void probe_cap_too_small() {
    upload_legal_state_table();
    upload_legal_region();
    upload_legal_profile();

    struct Try { int32_t tx, ty, tz; float max_range; int32_t reach, budget; };

    const Try tries[] = {
        {5, 0, 5, 16.0f, 1, 1024},
        {7, 2, 7, 32.0f, 1, 4096},
        {7, 0, 7, 64.0f, 0, 4096},
        {20, 0, 20, 64.0f, 1, 4096},
        {-20, 0, -20, 64.0f, 1, 4096},
        {60, 0, 60, 128.0f, 1, 100000},
    };
    Guarded big = guarded_alloc(sizeof(CavaPathNode) * 4096);
    bool done = false;
    for (const Try& t : tries) {
        CavaPathRequest req;
        build_legal_req(req);
        req.tx = t.tx; req.ty = t.ty; req.tz = t.tz;
        req.max_range = t.max_range;
        req.reach_range = t.reach;
        req.max_visited_nodes = t.budget;
        int32_t rc = p_pathfind(g_handle, &req, (CavaPathNode*) big.ptr, 4096);
        std::fprintf(g_log, "%-16s probe target=(%d,%d,%d) range=%g reach=%d -> %d node(s)\n",
                     "cap", t.tx, t.ty, t.tz, (double) t.max_range, t.reach, rc);
        if (rc <= 1) {
            continue;
        }
        /* cap = (node count - 1) with a buffer that is EXACTLY that big and ends on a guard
         * page: a partial write would either be caught by the guard page or by the sentinel. */
        Guarded exact = guarded_alloc(sizeof(CavaPathNode) * (size_t) (rc - 1));
        int32_t rc2 = p_pathfind(g_handle, &req, (CavaPathNode*) exact.ptr, rc - 1);
        char label[128];
        std::snprintf(label, sizeof(label), "cap = nodes-1 = %d (guarded buffer)", rc - 1);
        record("cap", label, rc2, true, true);
        if (!guarded_tail_intact(exact, (rc2 == CAVA_OK) ? sizeof(CavaPathNode) : 0)) {
            guard_bad("cap", "cap-too-small call wrote into the guarded region");
        }
        guarded_free(exact);
        done = true;
        break;
    }
    guarded_free(big);
    if (!done) {
        std::fprintf(g_log, "%-16s SKIP: no probe produced more than 1 node; cap-too-small not covered\n", "cap");
    }
}

/* ------------------------------------------------------------------ */
/* multi-threaded phase: "concurrent section unload"                   */
/*                                                                     */
/* This is the part of the P4 task list that the single-threaded       */
/* families above cannot reach: while one thread uploads and unloads   */
/* ("unloads" = cava_region_clear, the section-unload shape the Java   */
/* side produces when a chunk section leaves the mirror) a second      */
/* thread reads the very same region, a third runs pathfind/resolve    */
/* against it, and a fourth keeps replacing the shared state/shape     */
/* tables.                                                             */
/*                                                                     */
/* The ABI header says NOTHING about a thread model, so the honest     */
/* question is not "is it locked" but "what does it actually do".      */
/* What is measured and asserted here:                                 */
/*   1. the process survives (a crash means the harness never reaches  */
/*      the MT SUMMARY line and the script sees a non-zero exit);      */
/*   2. every return code is legal (>=0) or an ABI error (-1..-7);     */
/*      anything else counts as an undefined return;                   */
/*   3. no worker writes past the declared output capacity (guard page */
/*      + 0xA5 sentinel, same mechanism as the single-threaded phase); */
/*   4. a return code that CONTRADICTS its own output struct counts as */
/*      a torn read and is reported separately from a crash.           */
/*                                                                     */
/* The result is deliberately allowed to vary (region_clear really     */
/* removes the region, so pathfind legitimately answers either "path"  */
/* or "region not uploaded"). The pass criterion is "defined, bounded, */
/* non-corrupting", NOT "same answer every time" -- see                 */
/* docs/CAVA-concurrency-notes.md for what the run actually produced.  */
/* ------------------------------------------------------------------ */

/* Fixed thread work: every thread runs the same number of rounds, so the run is reproducible in
 * its shape even though the interleaving is not. threads_per_role = how many workers per role
 * (1 => 4 threads, 2 => 8 threads, which is what the task asked for). */
static const int kMtRounds = 4000;

static void mt_note_rc(int32_t rc, bool allow_positive, int role) {
    mt_cases.fetch_add(1, std::memory_order_relaxed);
    mt_per_role[role & 7].fetch_add(1, std::memory_order_relaxed);
    /* bucket: 0 => rc==0, 1..7 => -rc, 8 => positive, 9 => other */
    int bucket;
    if (rc == 0) {
        bucket = 0;
    } else if (rc > 0 && allow_positive) {
        bucket = 8;
    } else if (rc <= -1 && rc >= -7) {
        bucket = -rc;
    } else {
        bucket = 9;
        mt_undefined.fetch_add(1, std::memory_order_relaxed);
        std::fprintf(g_log, "%-16s *** UNDEFINED RETURN *** rc=%d role=%d\n", "mt", rc, role);
    }
    mt_rc_hist[bucket].fetch_add(1, std::memory_order_relaxed);
}

/* thread A: upload / unload ("section unload") storm */
static void mt_worker_unload(int rounds, uint64_t seed, int role) {
    std::vector<int32_t> ids((size_t) kRegX * kRegY * kRegZ, 0);
    CavaRng rng(seed);
    for (int i = 0; i < rounds; ++i) {
        const int32_t ox = (int32_t) (rng.next() % 7) - 3;
        const int32_t oz = (int32_t) (rng.next() % 7) - 3;
        for (size_t k = 0; k < ids.size(); ++k) { ids[k] = 0; }
        for (int32_t z = 0; z < kRegZ; ++z) {
            for (int32_t x = 0; x < kRegX; ++x) {
                ids[((size_t) 0 * kRegZ + z) * kRegX + x] = 1;
            }
        }
        int32_t rc = p_region_upload(g_handle, kRegX, kRegY, kRegZ, ox, kRegOY, oz,
                                     ids.data(), (int32_t) ids.size());
        mt_note_rc(rc, false, role);
        if (rc == CAVA_OK) { mt_region_upload_ok.fetch_add(1, std::memory_order_relaxed); }

        if ((rng.next() & 1) != 0) {
            rc = p_region_clear(g_handle);
            mt_note_rc(rc, false, role);
            if (rc == CAVA_OK) { mt_region_clear_ok.fetch_add(1, std::memory_order_relaxed); }
        }
    }
}

/* thread B: read the same coordinates while they are being replaced */
static void mt_worker_read(int rounds, uint64_t seed, int role) {
    Guarded out = guarded_alloc(sizeof(int32_t));
    CavaRng rng(seed);
    static const int32_t xs[8] = {0, 1, 3, 7, 2, 5, 6, 4};
    static const int32_t zs[8] = {0, 7, 3, 1, 6, 2, 5, 4};
    static const int32_t ys[4] = {-1, 0, 1, 2};
    for (int i = 0; i < rounds; ++i) {
        const uint64_t r = rng.next();
        const int32_t x = xs[r & 7] + (int32_t) ((r >> 3) & 7) - 3;
        const int32_t y = ys[(r >> 6) & 3];
        const int32_t z = zs[(r >> 8) & 7] + (int32_t) ((r >> 11) & 7) - 3;
        int32_t* slot = (int32_t*) out.ptr;
        *slot = 0x5A5A5A5A;   /* sentinel: if the callee does not write, we see it */
        const int32_t rc = p_region_at(g_handle, x, y, z, slot);
        mt_note_rc(rc, false, role);
        if (rc == CAVA_OK && *slot == 0x5A5A5A5A) {
            /* a legal query always writes a state id (0 = air), so an untouched slot is torn */
            mt_torn.fetch_add(1, std::memory_order_relaxed);
            std::fprintf(g_log, "%-16s *** TORN *** region_state_id_at rc=0 but the output was untouched\n", "mt");
        }
        if (!guarded_tail_intact(out, sizeof(int32_t))) {
            mt_guard_bad.fetch_add(1, std::memory_order_relaxed);
            std::fprintf(g_log, "%-16s *** SENTINEL/OVERRUN *** region_state_id_at wrote past 4 bytes\n", "mt");
        }
    }
    guarded_free(out);
}

/* thread C: pathfind + resolve while the region table is being replaced underneath */
static void mt_worker_path(int rounds, uint64_t seed, int role) {
    Guarded nodes = guarded_alloc(sizeof(CavaPathNode) * 64);
    Guarded ev = guarded_alloc(sizeof(CavaMoveEvent) * 16);
    Guarded res = guarded_alloc(sizeof(CavaMoveResult));
    CavaRng rng(seed);
    for (int i = 0; i < rounds; ++i) {
        const uint64_t r = rng.next();
        if ((r & 1) == 0) {
            CavaPathRequest req;
            build_legal_req(req);
            req.tx = (int32_t) (r % 9) - 4;
            req.tz = (int32_t) ((r >> 8) % 9) - 4;
            const int32_t rc = p_pathfind(g_handle, &req, (CavaPathNode*) nodes.ptr, 64);
            mt_note_rc(rc, true, role);
            if (rc > 64) {
                mt_torn.fetch_add(1, std::memory_order_relaxed);
                std::fprintf(g_log, "%-16s *** TORN *** pathfind returned %d nodes for cap=64\n", "mt", rc);
            }
            if (rc > 0 && !guarded_tail_intact(nodes, (size_t) rc * sizeof(CavaPathNode))) {
                mt_guard_bad.fetch_add(1, std::memory_order_relaxed);
                std::fprintf(g_log, "%-16s *** SENTINEL/OVERRUN *** pathfind wrote past a %d-node buffer\n",
                             "mt", rc);
            }
        } else {
            CavaMoveRequest req;
            CavaMoveShapeRef ref;
            build_legal_move(req, ref);
            req.move_x = ((double) (int32_t) (r % 21) - 10) / 10.0;
            /* Pre-fill the result with a sentinel so "did the callee write?" is observable.
             * The ABI says an error return must NOT write out at all, so this also tests that
             * rule under concurrency (~114/18000 calls failed with -4 in the first run). */
            std::memset(res.ptr, 0xA5, sizeof(CavaMoveResult));
            const int32_t rc = p_resolve(g_handle, &req, &ref, 1, nullptr, 0, nullptr, 0, nullptr, 0,
                                         (CavaMoveEvent*) ev.ptr, 16, (CavaMoveResult*) res.ptr);
            mt_note_rc(rc, false, role);
            const CavaMoveResult* out = (const CavaMoveResult*) res.ptr;
            if (rc >= 0 && out->status != rc) {
                mt_torn.fetch_add(1, std::memory_order_relaxed);
                std::fprintf(g_log, "%-16s *** TORN *** resolve rc=%d but out->status=%d\n",
                             "mt", rc, out->status);
            }
            if (rc < 0 && out->status != (int32_t) 0xA5A5A5A5) {
                /* the callee wrote to a buffer it is required to leave untouched */
                mt_contract.fetch_add(1, std::memory_order_relaxed);
                std::fprintf(g_log, "%-16s *** CONTRACT *** resolve rc=%d but out was written "
                                    "(status=0x%08X)\n", "mt", rc, (unsigned) out->status);
            }
            if (rc == CAVA_OK) {
                const double* d = &out->delta_x;
                for (int a = 0; a < 3; ++a) {
                    if (d[a] != d[a] || d[a] > 1e7 || d[a] < -1e7) {
                        mt_torn.fetch_add(1, std::memory_order_relaxed);
                        std::fprintf(g_log, "%-16s *** TORN *** resolve delta[%d]=%g is not a sane step\n",
                                     "mt", a, d[a]);
                    }
                }
            }
            if (!guarded_tail_intact(res, sizeof(CavaMoveResult))) {
                mt_guard_bad.fetch_add(1, std::memory_order_relaxed);
                std::fprintf(g_log, "%-16s *** SENTINEL/OVERRUN *** resolve wrote past CavaMoveResult\n", "mt");
            }
        }
    }
    guarded_free(nodes);
    guarded_free(ev);
    guarded_free(res);
}

/* thread D: keep replacing the shared state / shape / profile tables */
static void mt_worker_tables(int rounds, uint64_t seed, int role) {
    std::vector<CavaStateRecord> recs;
    std::vector<CavaCollisionBox> boxes;
    build_state_table(recs, boxes);
    boxes.resize(2, boxes[0]);

    std::vector<CavaShapeRecord> srecs;
    std::vector<uint64_t> bits;
    build_legal_shape_table(srecs, bits);

    CavaRng rng(seed);
    for (int i = 0; i < rounds; ++i) {
        const uint64_t r = rng.next();
        int32_t rc;
        switch (r % 3) {
            case 0:
                rc = p_state_upload(g_handle, recs.data(), (int32_t) recs.size(),
                                    boxes.data(), (int32_t) boxes.size());
                mt_note_rc(rc, false, role);
                break;
            case 1:
                rc = p_shape_upload(g_handle, srecs.data(), (int32_t) srecs.size(),
                                    nullptr, 0, bits.data(), (int32_t) bits.size());
                mt_note_rc(rc, false, role);
                break;
            default:
                rc = upload_legal_profile();
                mt_note_rc(rc, false, role);
                break;
        }
    }
}

struct MtWorker {
    void (*fn)(int, uint64_t, int);
    int rounds;
    uint64_t seed;
    int role;
};

static void mt_thread_entry(MtWorker w) {
    w.fn(w.rounds, w.seed, w.role);
}

/* Returns true when the phase found nothing to report. */
static bool multithread_phase(int threads_per_role, uint64_t seed, unsigned role_mask) {
    int active_roles = 0;
    for (int r = 0; r < 4; ++r) { if ((role_mask & (1u << r)) != 0) { ++active_roles; } }
    if (active_roles == 0) { role_mask = 0xF; active_roles = 4; }
    const int nthreads = threads_per_role * active_roles;
    std::vector<std::thread> pool;
    pool.reserve((size_t) nthreads);
    std::vector<MtWorker> workers;

    /* Establish the shared state the workers contend over BEFORE any thread starts.
     *
     * MEASURED (and the reason this is here): without it, every cava_resolve_move in the path
     * role returned CAVA_ERR_ARG (-4) -- in the mask=4 run exactly 4088 of 4088 resolve calls --
     * because cava_shape_table_upload had never been called and a CavaMoveShapeRef with
     * kind == CAVA_MSHAPE_STATE requires state_id < record_count. That is a legal, contract
     * defined rejection ("state table not uploaded"), NOT a race and NOT thread unsafety.
     * A -4 storm in this phase therefore means "the baseline was not established", while a -4
     * that appears ONLY while the tables role runs is the interesting one. */
    {
        const int32_t rcState = upload_legal_state_table();
        const int32_t rcRegion = upload_legal_region();
        const int32_t rcProfile = upload_legal_profile();
        std::vector<CavaShapeRecord> srecs;
        std::vector<uint64_t> sbits;
        build_legal_shape_table(srecs, sbits);
        const int32_t rcShape = p_shape_upload(g_handle, srecs.data(), (int32_t) srecs.size(),
                                               nullptr, 0, sbits.data(), (int32_t) sbits.size());
        std::fprintf(g_log, "mt-baseline    : state=%d region=%d profile=%d shape=%d (all must be 0)\n",
                     rcState, rcRegion, rcProfile, rcShape);
        if (rcState != CAVA_OK || rcRegion != CAVA_OK || rcProfile != CAVA_OK || rcShape != CAVA_OK) {
            ++g_unexpected_ok;   /* recorded as a failure: the phase has no baseline to contend on */
        }
    }

    std::fprintf(g_log, "\n--- multi-threaded phase: %d threads x %d rounds, role mask 0x%X "
                        "(bit0=unload bit1=read bit2=path bit3=tables), base seed 0x%016llX ---\n",
                 nthreads, kMtRounds, role_mask, (unsigned long long) seed);

    for (int role = 0; role < 4; ++role) {
        if ((role_mask & (1u << role)) == 0) { continue; }
        for (int k = 0; k < threads_per_role; ++k) {
            MtWorker w;
            switch (role) {
                case 0: w.fn = mt_worker_unload; break;
                case 1: w.fn = mt_worker_read;   break;
                case 2: w.fn = mt_worker_path;   break;
                default: w.fn = mt_worker_tables; break;
            }
            w.rounds = kMtRounds;
            w.seed = seed ^ (0x9E3779B97F4A7C15ull * (uint64_t) (role * 16 + k + 1));
            w.role = role;
            workers.push_back(w);
        }
    }
    for (size_t i = 0; i < workers.size(); ++i) {
        pool.push_back(std::thread(mt_thread_entry, workers[i]));
    }
    for (size_t i = 0; i < pool.size(); ++i) {
        pool[i].join();
    }

    const uint64_t cases = mt_cases.load();
    const uint64_t undef = mt_undefined.load();
    const uint64_t torn = mt_torn.load();
    const uint64_t contract = mt_contract.load();
    const uint64_t guard = mt_guard_bad.load();

    std::fprintf(g_log, "mt-threads     : %d (%d per active role, mask 0x%X)\n",
                 nthreads, threads_per_role, role_mask);
    std::fprintf(g_log, "mt-cases       : %llu calls issued from %d threads\n",
                 (unsigned long long) cases, nthreads);
    std::fprintf(g_log, "mt-per-role    : unload=%llu read=%llu path=%llu tables=%llu\n",
                 (unsigned long long) mt_per_role[0].load(),
                 (unsigned long long) mt_per_role[1].load(),
                 (unsigned long long) mt_per_role[2].load(),
                 (unsigned long long) mt_per_role[3].load());
    std::fprintf(g_log, "mt-rc-hist     : ok=%llu positive=%llu ABI(-1..-7)=%llu/%llu/%llu/%llu/%llu/%llu/%llu "
                        "undefined=%llu\n",
                 (unsigned long long) mt_rc_hist[0].load(),
                 (unsigned long long) mt_rc_hist[8].load(),
                 (unsigned long long) mt_rc_hist[1].load(), (unsigned long long) mt_rc_hist[2].load(),
                 (unsigned long long) mt_rc_hist[3].load(), (unsigned long long) mt_rc_hist[4].load(),
                 (unsigned long long) mt_rc_hist[5].load(), (unsigned long long) mt_rc_hist[6].load(),
                 (unsigned long long) mt_rc_hist[7].load(),
                 (unsigned long long) mt_rc_hist[9].load());
    std::fprintf(g_log, "mt-region      : uploads_ok=%llu clears_ok=%llu\n",
                 (unsigned long long) mt_region_upload_ok.load(),
                 (unsigned long long) mt_region_clear_ok.load());
    std::fprintf(g_log, "mt-torn        : %llu\n", (unsigned long long) torn);
    std::fprintf(g_log, "mt-contract    : %llu (error rc but the out param was written)\n",
                 (unsigned long long) contract);
    std::fprintf(g_log, "mt-overruns    : %llu\n", (unsigned long long) guard);
    std::fprintf(g_log, "mt-undefined   : %llu\n", (unsigned long long) undef);
    std::fprintf(g_log, "MT SUMMARY threads=%d rounds=%d calls=%llu undefined_returns=%llu torn=%llu "
                        "contract_violations=%llu overruns=%llu\n",
                 nthreads, kMtRounds, (unsigned long long) cases, (unsigned long long) undef,
                 (unsigned long long) torn, (unsigned long long) contract, (unsigned long long) guard);

    const bool ok = (undef == 0) && (torn == 0) && (contract == 0) && (guard == 0);
    std::fprintf(g_log, "MT RESULT: %s\n", ok ? "PASS" : "FAIL");
    std::printf("MT SUMMARY threads=%d calls=%llu undefined_returns=%llu torn=%llu contract_violations=%llu "
                "overruns=%llu\n",
                nthreads, (unsigned long long) cases, (unsigned long long) undef,
                (unsigned long long) torn, (unsigned long long) contract, (unsigned long long) guard);
    return ok;
}

/* ------------------------------------------------------------------ */
/* main                                                               */
/* ------------------------------------------------------------------ */
int main(int argc, char** argv) {
    const char* dll = (argc > 1) ? argv[1] : "natives/windows-x64/cava.dll";
    uint64_t seed = kDefaultSeed;
    uint64_t random_cases = 5000;
    const char* log_path = nullptr;
    bool safe_probe = false;
    bool mt = false;
    int mt_threads_per_role = 2;   /* 2 per role x 4 roles = 8 threads (the task's number) */
    unsigned mt_role_mask = 0xF;   /* bit i = run role i (0=unload 1=read 2=path 3=tables) */

    for (int i = 2; i < argc; ++i) {
        if (std::strcmp(argv[i], "--seed") == 0 && i + 1 < argc) {
            seed = std::strtoull(argv[++i], nullptr, 0);
        } else if (std::strcmp(argv[i], "--cases") == 0 && i + 1 < argc) {
            random_cases = std::strtoull(argv[++i], nullptr, 0);
        } else if (std::strcmp(argv[i], "--log") == 0 && i + 1 < argc) {
            log_path = argv[++i];
        } else if (std::strcmp(argv[i], "--every") == 0 && i + 1 < argc) {
            g_verbose_every = std::strtoull(argv[++i], nullptr, 0);
        } else if (std::strcmp(argv[i], "--safe-probe") == 0) {
            safe_probe = true;
        } else if (std::strcmp(argv[i], "--threads") == 0 && i + 1 < argc) {
            mt_threads_per_role = (int) std::strtol(argv[++i], nullptr, 0);
            mt = true;
        } else if (std::strcmp(argv[i], "--mt") == 0) {
            mt = true;
        } else if (std::strcmp(argv[i], "--roles") == 0 && i + 1 < argc) {
            /* role mask, e.g. 12 = path+tables only: used to attribute a failure to a role pair */
            mt_role_mask = (unsigned) std::strtoul(argv[++i], nullptr, 0) & 0xFu;
            mt = true;
        }
    }
    g_rng = seed ? seed : kDefaultSeed;

    g_log = (log_path != nullptr) ? std::fopen(log_path, "w") : stdout;
    if (g_log == nullptr) {
        std::fprintf(stderr, "FATAL: cannot open log %s\n", log_path);
        return 4;
    }

#ifdef _WIN32
    g_lib = LoadLibraryA(dll);
    if (g_lib == nullptr) {
        std::fprintf(stderr, "FATAL: LoadLibrary(%s) failed, GetLastError=%lu\n", dll, GetLastError());
        return 3;
    }
#else
    g_lib = dlopen(dll, RTLD_NOW);
    if (g_lib == nullptr) {
        std::fprintf(stderr, "FATAL: dlopen(%s) failed: %s\n", dll, dlerror());
        return 3;
    }
#endif

    fn_build_id f_build_id = (fn_build_id) sym("cava_build_id");
    fn_abi_touch f_touch = (fn_abi_touch) sym("cava_abi_touch");
    fn_abi_version f_abi = (fn_abi_version) sym("cava_abi_version");
    fn_layout_report f_report = (fn_layout_report) sym("cava_layout_report");
    fn_open f_open = (fn_open) sym("cava_open");
    p_pathfind = (fn_pathfind) sym("cava_pathfind");
    p_mob_upload = (fn_mob_upload) sym("cava_mob_profile_upload");
    p_mob_clear = (fn_mob_clear) sym("cava_mob_profile_clear");
    p_state_upload = (fn_state_upload) sym("cava_state_table_upload");
    p_region_upload = (fn_region_upload) sym("cava_region_upload");
    p_region_clear = (fn_region_clear) sym("cava_region_clear");
    p_region_at = (fn_region_at) sym("cava_region_state_id_at");
    p_shape_upload = (fn_shape_upload) sym("cava_shape_table_upload");
    p_resolve = (fn_resolve) sym("cava_resolve_move");
    p_close = (fn_close) sym("cava_close");

    if (f_build_id == nullptr || f_report == nullptr || f_open == nullptr || p_pathfind == nullptr
        || p_resolve == nullptr || p_region_upload == nullptr || p_state_upload == nullptr
        || p_shape_upload == nullptr || f_touch == nullptr) {
        std::fprintf(stderr, "FATAL: %s is missing required ABI symbols (not a cava dll?)\n", dll);
        return 3;
    }

    std::fprintf(g_log, "=== cava ABI fuzz ===\n");
    std::fprintf(g_log, "dll        : %s\n", dll);
    std::fprintf(g_log, "build_id   : %s\n", f_build_id());
    std::fprintf(g_log, "abi_version: %d\n", f_abi());
    std::fprintf(g_log, "seed       : 0x%016llX\n", (unsigned long long) seed);
    std::fprintf(g_log, "random     : %llu cases\n", (unsigned long long) random_cases);

    if (safe_probe) {
        std::fprintf(g_log, "\n--- safe-probe: cava_layout_report(NULL) ---\n");
        std::printf("[safe-probe] calling cava_layout_report(NULL) ...\n");
        std::fflush(stdout);
        int32_t rc = f_report(nullptr);
        std::printf("[safe-probe] cava_layout_report(NULL) -> %d (%s)\n", rc, rc_name(rc));
        std::printf("[safe-probe] process is STILL ALIVE after the call (no abort)\n");
        std::fprintf(g_log, "%-16s cava_layout_report(NULL) -> %d (%s)\n", "safe-probe", rc, rc_name(rc));
        std::fprintf(g_log, "%-16s still alive => the assertion was recorded and a code was returned, no abort\n",
                     "safe-probe");
        if (g_log != stdout) {
            std::fclose(g_log);
        }
        return 0;
    }

    CavaLayoutReport* report = (CavaLayoutReport*) std::malloc(sizeof(CavaLayoutReport));
    if (report == nullptr) {
        std::fprintf(stderr, "FATAL: cannot allocate the layout report\n");
        return 4;
    }
    int32_t entries = f_report(report);
    if (entries < 0) {
        std::fprintf(stderr, "FATAL: cava_layout_report returned %d\n", entries);
        return 3;
    }
    uint64_t sum = 0;
    for (int32_t i = 0; i < entries && i < CAVA_LAYOUT_REPORT_CAP; ++i) {
        sum = (sum + (uint64_t) report->entries[i].layout_hash) & 0xFFFFFFFFull;
    }
    std::fprintf(g_log, "entries    : %d\n", entries);
    std::fprintf(g_log, "layout_sum : 0x%08llX (per-entry u32 sum, same formula as the Java side)\n",
                 (unsigned long long) sum);

    CavaOpenParams params;
    std::memset(&params, 0, sizeof(params));
    params.abi_version = CAVA_ABI_VERSION;
    params.flags = CAVA_OPEN_FLAG_DETERMINISTIC;
    params.layout_hash_sum = sum;
    CavaOpenResult ores;
    std::memset(&ores, 0, sizeof(ores));
    int32_t openRc = f_open(&params, &g_handle, &ores);
    std::fprintf(g_log, "cava_open  : rc=%d (%s) handle=%lld native_sum=0x%08llX\n", openRc, rc_name(openRc),
                 (long long) g_handle, (unsigned long long) ores.native_layout_sum);
    if (openRc != CAVA_OK || g_handle == 0) {
        std::fprintf(stderr, "FATAL: cava_open failed (rc=%d) -- cannot fuzz\n", openRc);
        return 3;
    }
    std::fprintf(g_log, "\n");

    family_handles();
    family_pathfind();
    family_region();
    family_state_table();
    family_shape_table();
    probe_cap_too_small();
    family_invariance();

    uint64_t before_random = g_cases;
    family_random(random_cases);
    std::fprintf(g_log, "\nrandom sweep: %llu cases (seed 0x%016llX)\n",
                 (unsigned long long) (g_cases - before_random), (unsigned long long) seed);

    /* Multi-threaded phase (opt-in; the script passes --threads 2 for 8 worker threads).
     * It runs LAST on purpose: it replaces and clears the shared tables/region, so any family
     * that depends on the single-threaded baseline would have to rebuild it. */
    bool mtOk = true;
    if (mt) {
        mtOk = multithread_phase(mt_threads_per_role, seed, mt_role_mask);
    } else {
        std::fprintf(g_log, "\n(multi-threaded phase skipped: pass --threads N to run it)\n");
    }

    std::fprintf(g_log, "\n");
    std::fprintf(g_log, "SUMMARY dll=%s cases=%llu crashes=%llu undefined_returns=%llu "
                        "state_mutations=%llu overruns=%llu unexpected_ok=%llu seed=0x%016llX abi_touch=%lld\n",
                 dll, (unsigned long long) g_cases, (unsigned long long) g_crashes,
                 (unsigned long long) g_undefined, (unsigned long long) g_state_mutations,
                 (unsigned long long) g_guard_violations, (unsigned long long) g_unexpected_ok,
                 (unsigned long long) seed, (long long) f_touch());

    const bool pass = (g_undefined == 0) && (g_state_mutations == 0) && (g_guard_violations == 0)
                      && (g_unexpected_ok == 0) && mtOk;
    std::fprintf(g_log, "RESULT: %s\n", pass ? "PASS" : "FAIL");
    if (g_log != stdout) {
        std::fclose(g_log);
    }
    std::printf("SUMMARY dll=%s cases=%llu crashes=%llu undefined_returns=%llu state_mutations=%llu "
                "overruns=%llu unexpected_ok=%llu\n",
                dll, (unsigned long long) g_cases, (unsigned long long) g_crashes,
                (unsigned long long) g_undefined, (unsigned long long) g_state_mutations,
                (unsigned long long) g_guard_violations, (unsigned long long) g_unexpected_ok);
    std::printf("RESULT: %s\n", pass ? "PASS" : "FAIL");
    return pass ? 0 : 1;
}
