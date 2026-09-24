/* CavaArtifactProbe.java -- fingerprint a cava.dll WITHOUT the mod on the classpath.
 *
 * Used by two callers:
 *   - tools/CrashProbe.java            (prints the fingerprint just before the deliberate crash,
 *                                       so the hs_err log's "Dynamic libraries" section and the
 *                                       report's build-id/layout claims can be checked against
 *                                       a real run)
 *   - tools/hs-err-report.ps1          (answers "which cava.dll is this crash log talking about",
 *                                       independently of what the log text says)
 *
 * It deliberately re-implements the layout_hash_sum fold instead of importing
 * cava.ffm.CavaLayouts: the artifact under test may be an OLD build whose Java mirror no longer
 * matches, and a probe that needs the build tree to agree with the binary is useless exactly
 * when it is needed (i.e. after a crash). The fold formula is the frozen one from
 * docs/CAVA-工程接口契约.md section 2.3:
 *
 *     h = 0x811C9DC5
 *     per field: h ^= lo32(offset); h *= 0x01000193
 *                h ^= lo32(size);   h *= 0x01000193
 *                h ^= hi32(offset); h *= 0x01000193
 *                h ^= hi32(size);   h *= 0x01000193
 *     layout_hash_sum = uint32 wrapping sum of every entry's layout_hash
 *
 * C structs read here (frozen, native/include/cava_abi.h):
 *   CavaLayoutEntry  { i32 abi_version; i32 reserved0; u64 struct_size; u64 struct_align;
 *                      u32 field_count; u32 layout_hash; u64 offsets[32]; u64 sizes[32]; } = 544 B
 *     offsets MEASURED by dumping the raw report (build/p4c-crash/tmp/LayoutDump.java), and
 *     identical to native/tests/vectors/layout_expected.txt which that same build generates
 *     from offsetof/sizeof:
 *       abi_version @0, reserved0 @4, struct_size @8, struct_align @16,
 *       field_count @24, layout_hash @28, field_offsets @32, field_sizes @288
 *   CavaLayoutReport { i32 abi_version; i32 build_flags; i32 platform; i32 pointer_size;
 *                      i32 entry_count; i32 reserved0; u64 build_id_hash; entries[64]; } = 34848 B
 *       entry_count @16, build_id_hash @24, entries @32
 *
 * Two measured traps are recorded here because both cost a debugging round:
 *   1. build_id_hash sits at 24, so entries start at 32. Reading the entry base 4 bytes high
 *      (my first attempt: a "padded" read at 36) makes field_count read the high word of
 *      struct_size and collapses the summed value to nonsense (0x8A). The report is NOT padded
 *      after build_id_hash -- there is no runtime alignment question here at all, because the
 *      native side fills a caller-provided buffer and CavaLayoutReport's declared alignment
 *      only matters to the compiler that lays out the struct.
 *   2. Inside an entry, field_sizes is at +288 and the u64s ARE 8-byte aligned (entry base 32,
 *      so entry 1 starts at 576 = 8*72, and 576+288 = 864 = 8*108). u64() below still reads the
 *      halves as two ints: an earlier revision of this probe passed the wrong base and got
 *      IllegalArgumentException("Misaligned access") from FFM, and reading halves costs nothing
 *      while removing that whole failure mode. The offsets above are the measured ones and are
 *      cross-checked at runtime: tools/CavaArtifactProbe prints "recomputed-from-fields" next to
 *      "sum-of-stored" and they must agree (both print 0x1C12265E on the shipped artifact).
 *
 * Output is one machine-readable line:
 *   build_id=<...> abi_version=<n> layout_entries=<n> layout_sum=0x<%08X> build_id_hash=0x<%08X> safe=<0|1> platform=<n> sha256=<hex>
 *
 * NOTE: intentionally ASCII-only.
 */

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;

public final class CavaArtifactProbe {

    /** CavaLayoutReport: first CavaLayoutEntry starts here (measured: entry 0 dword 32). */
    public static final long REPORT_ENTRY_OFFSET = 32L;
    /** CavaLayoutReport.entry_count. */
    public static final long REPORT_ENTRY_COUNT_OFFSET = 16L;
    /** CavaLayoutReport.build_id_hash (i32 x6 then u64 => 24, 8-byte aligned). */
    public static final long REPORT_BUILD_ID_HASH_OFFSET = 24L;
    public static final long REPORT_BUILD_FLAGS_OFFSET = 4L;
    public static final long REPORT_PLATFORM_OFFSET = 8L;
    public static final long REPORT_POINTER_SIZE_OFFSET = 12L;
    public static final long ENTRY_SIZE = 544L;
    public static final long ENTRY_FIELD_COUNT_OFFSET = 24L;
    public static final long ENTRY_LAYOUT_HASH_OFFSET = 28L;
    public static final long ENTRY_FIELD_OFFSETS_OFFSET = 32L;
    /** field_sizes follows the 8 x u64 header + 32 x u64 offsets: 32 + 32*8 = 288. */
    public static final long ENTRY_FIELD_SIZES_OFFSET = 288L;
    public static final int  REPORT_CAP = 64;
    /** sizeof(CavaLayoutReport); allocating more than this is harmless, less is UB. */
    public static final long REPORT_SIZE = 34848L;

    public static final class Info {
        public String buildId = "";
        public int abiVersion = -1;
        public int layoutEntries = -1;
        public long layoutSum = -1;
        public long buildIdHash = -1;
        public int buildFlags = -1;
        public int platform = -1;
        public int pointerSize = -1;
        public String sha256 = "";

        public boolean safeBuild() {
            return (buildFlags & 1) != 0;   // CAVA_BUILD_FLAG_SAFE_ASSERTS
        }

        @Override
        public String toString() {
            return "build_id=" + buildId
                    + " abi_version=" + abiVersion
                    + " layout_entries=" + layoutEntries
                    + " layout_sum=0x" + String.format("%08X", layoutSum)
                    + " build_id_hash=0x" + String.format("%08X", buildIdHash)
                    + " flags=0x" + Integer.toHexString(buildFlags)
                    + " safe=" + (safeBuild() ? 1 : 0)
                    + " platform=" + platform
                    + " pointer_size=" + pointerSize
                    + " sha256=" + sha256;
        }
    }

    /** Loads the DLL (System.load) and reads its identity through the frozen ABI. */
    public static Info inspect(Path dll) throws Throwable { return inspect(dll, false); }

    /** @param dump also print every entry's raw (offset,size) table -- diagnosis only. */
    public static Info inspect(Path dll, boolean dump) throws Throwable {
        Path abs = dll.toAbsolutePath();
        Info info = new Info();
        info.sha256 = sha256(abs);
        System.load(abs.toString());

        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();
        if (lookup.find("cava_build_id").isEmpty()) {
            // documented on this machine: System.load() does not always make the symbols
            // visible to defaultLookup(); the mod falls back the same way.
            lookup = SymbolLookup.libraryLookup(abs, Arena.ofShared());
        }
        MethodHandle buildId = linker.downcallHandle(need(lookup, "cava_build_id"),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        MethodHandle abiVersion = linker.downcallHandle(need(lookup, "cava_abi_version"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        MethodHandle layoutReport = linker.downcallHandle(need(lookup, "cava_layout_report"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        MemorySegment p = (MemorySegment) buildId.invokeExact();
        info.buildId = (p == null || p.equals(MemorySegment.NULL))
                ? "" : p.reinterpret(4096).getUtf8String(0);
        info.abiVersion = (int) abiVersion.invokeExact();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment report = arena.allocate(REPORT_SIZE, 8);
            int rc = (int) layoutReport.invokeExact(report);
            if (rc < 0) {
                throw new IllegalStateException("cava_layout_report -> " + rc);
            }
            info.layoutEntries = report.get(ValueLayout.JAVA_INT, REPORT_ENTRY_COUNT_OFFSET);
            info.buildFlags = report.get(ValueLayout.JAVA_INT, REPORT_BUILD_FLAGS_OFFSET);
            info.platform = report.get(ValueLayout.JAVA_INT, REPORT_PLATFORM_OFFSET);
            info.pointerSize = report.get(ValueLayout.JAVA_INT, REPORT_POINTER_SIZE_OFFSET);
            // The value is a 32-bit FNV-1a widened to u64; read its low word (offset 24 is
            // 8-aligned so JAVA_LONG would work too, but the int read is unambiguous).
            info.buildIdHash = report.get(ValueLayout.JAVA_INT, REPORT_BUILD_ID_HASH_OFFSET) & 0xFFFFFFFFL;

            long sum = 0;
            long sumStored = 0;
            int n = (int) Math.min(rc, info.layoutEntries);
            for (int i = 0; i < n; i++) {
                long base = REPORT_ENTRY_OFFSET + (long) i * ENTRY_SIZE;
                long fields = report.get(ValueLayout.JAVA_INT, base + ENTRY_FIELD_COUNT_OFFSET) & 0xFFFFFFFFL;
                long stored = report.get(ValueLayout.JAVA_INT, base + ENTRY_LAYOUT_HASH_OFFSET) & 0xFFFFFFFFL;
                long h = 0x811C9DC5L;
                sumStored = (sumStored + stored) & 0xFFFFFFFFL;
                for (long f = 0; f < fields && f < 32; f++) {
                    // The report is packed: CavaLayoutEntry starts at report offset 36, so every
                    // u64 inside it lands on a 4-byte (not 8-byte) boundary and FFM rejects a
                    // misaligned JAVA_LONG read. Read the two halves as ints -- all values here
                    // are well under 2^32 anyway (offsets/sizes are small).
                    long off = u64(report, base + ENTRY_FIELD_OFFSETS_OFFSET + f * 8);
                    long sz = u64(report, base + ENTRY_FIELD_SIZES_OFFSET + f * 8);
                    h = fold(h, off & 0xFFFFFFFFL);
                    h = fold(h, sz & 0xFFFFFFFFL);
                    h = fold(h, (off >>> 32) & 0xFFFFFFFFL);
                    h = fold(h, (sz >>> 32) & 0xFFFFFFFFL);
                }
                sum = (sum + h) & 0xFFFFFFFFL;
            }
            info.layoutSum = sum;
            System.out.printf("layout-sum check: recomputed-from-fields=0x%08X sum-of-stored=0x%08X %s%n",
                    sum, sumStored, (sum == sumStored) ? "AGREE" : "*** DISAGREE ***");
            if (sum != sumStored) {
                System.out.println("  (a disagreement means this probe is reading the report at the "
                        + "wrong offsets, or the DLL's own layout_hash fields are inconsistent)");
            }
            if (dump) {
                for (int i = 0; i < n; i++) {
                    long base = REPORT_ENTRY_OFFSET + (long) i * ENTRY_SIZE;
                    long fields = report.get(ValueLayout.JAVA_INT, base + ENTRY_FIELD_COUNT_OFFSET) & 0xFFFFFFFFL;
                    long stored = report.get(ValueLayout.JAVA_INT, base + ENTRY_LAYOUT_HASH_OFFSET) & 0xFFFFFFFFL;
                    System.out.printf("entry[%2d] base=%d fields=%d stored=%08X size=%d align=%d%n",
                            i, base, fields, stored,
                            u64(report, base + 8L), u64(report, base + 16L));
                    for (long f = 0; f < fields && f < 32; f++) {
                        System.out.printf("    f[%2d] off@%d=%d size@%d=%d%n", f,
                                base + ENTRY_FIELD_OFFSETS_OFFSET + f * 8,
                                u64(report, base + ENTRY_FIELD_OFFSETS_OFFSET + f * 8),
                                base + ENTRY_FIELD_SIZES_OFFSET + f * 8,
                                u64(report, base + ENTRY_FIELD_SIZES_OFFSET + f * 8));
                    }
                }
            }
            if (n > 0 && sum == 0) {
                // A zero sum means the report was read at the wrong offsets (this happened once:
                // see the header note). Fail loudly instead of printing a plausible-looking 0.
                throw new IllegalStateException("layout_hash_sum == 0 with " + n
                        + " entries: the CavaLayoutReport offsets in this probe are wrong");
            }
        }
        return info;
    }

    /** Reads a (possibly 4-byte aligned) u64 field as two ints, avoiding FFM alignment traps. */
    private static long u64(MemorySegment s, long at) {
        long lo = s.get(ValueLayout.JAVA_INT, at) & 0xFFFFFFFFL;
        long hi = s.get(ValueLayout.JAVA_INT, at + 4L) & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }

    private static MemorySegment need(SymbolLookup lookup, String symbol) {
        Optional<MemorySegment> s = lookup.find(symbol);
        if (s.isEmpty()) {
            throw new IllegalStateException("symbol missing: " + symbol);
        }
        return s.get();
    }

    private static long fold(long h, long v) {
        h = (h ^ v) & 0xFFFFFFFFL;
        return (h * 0x01000193L) & 0xFFFFFFFFL;
    }

    public static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = Files.readAllBytes(p);
        byte[] d = md.digest(buf);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: CavaArtifactProbe <cava.dll> [--dump]");
            System.exit(2);
        }
        Path p = Path.of(args[0]);
        if (!Files.isRegularFile(p)) {
            System.err.println("missing: " + p.toAbsolutePath());
            System.exit(2);
        }
        boolean dump = args.length == 2 && "--dump".equals(args[1]);
        Info info = inspect(p, dump);
        System.out.println(info);
    }
}
