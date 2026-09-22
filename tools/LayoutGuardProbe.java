import java.lang.foreign.*;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

/** Captain-owned throwaway probe: does cava_open actually enforce the ABI version and layout hash? */
public class LayoutGuardProbe {
    static final long GOOD_SUM = 0x6149fd30L;

    public static void main(String[] args) throws Throwable {
        Path lib = Path.of(args.length > 0 ? args[0] : "J:/mc/Cava/natives/windows-x64/cava.dll");
        System.load(lib.toAbsolutePath().toString());
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();
        if (lookup.find("cava_open").isEmpty()) {
            System.out.println("(defaultLookup missed the symbols -> libraryLookup fallback, same as CavaBindings)");
            lookup = SymbolLookup.libraryLookup(lib, Arena.ofShared());
        }

        MemoryLayout P = MemoryLayout.structLayout(
                JAVA_INT.withName("abi_version"), JAVA_INT.withName("flags"),
                JAVA_LONG.withName("layout_hash_sum"), JAVA_LONG.withName("reserved0"), JAVA_LONG.withName("reserved1"));
        MemoryLayout R = MemoryLayout.structLayout(
                JAVA_INT.withName("status"), JAVA_INT.withName("abi_version"),
                JAVA_LONG.withName("native_layout_sum"), JAVA_LONG.withName("reserved0"));

        var hOpen = linker.downcallHandle(lookup.find("cava_open").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        var hClose = linker.downcallHandle(lookup.find("cava_close").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

        // each row: {abi_version, layout_sum, label, expectation}
        Object[][] cases = {
            { 1,  GOOD_SUM,    "abi=1  sum=correct      ",  0  },
            { 1,  0xDEADBEEFL, "abi=1  sum=wrong        ", -2  },
            { 2,  GOOD_SUM,    "abi=2  sum=correct      ", -1  },
            { 99, GOOD_SUM,    "abi=99 sum=correct      ", -1  },
            { 0,  GOOD_SUM,    "abi=0  sum=correct      ", -1  },
            { 1,  0xDB2A07EDL, "abi=1  sum=bytewise-var ", -2  },
        };
        int bad = 0;
        for (Object[] c : cases) {
            int abi = (Integer) c[0];
            long sum = (Long) c[1];
            String label = (String) c[2];
            int expect = (Integer) c[3];
            long handle;
            int rc;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment params = arena.allocate(P);
                MemorySegment outHandle = arena.allocate(JAVA_LONG);
                MemorySegment result = arena.allocate(R);
                params.set(JAVA_INT, 0, abi);
                params.set(JAVA_INT, 4, 0);
                params.set(JAVA_LONG, 8, sum);
                params.set(JAVA_LONG, 16, 0L);
                params.set(JAVA_LONG, 24, 0L);
                outHandle.set(JAVA_LONG, 0, -1L);
                result.fill((byte) 0);
                rc = (int) hOpen.invokeExact(params, outHandle, result);
                handle = outHandle.get(JAVA_LONG, 0);
                long nsum = result.get(JAVA_LONG, 8);
                boolean ok = (rc == expect);
                if (!ok) bad++;
                System.out.printf("%s -> status=%-3d handle=%-12d native_sum=0x%08X  expect=%-3d  %s%n",
                        label, rc, handle, nsum, expect, ok ? "OK" : "*** UNEXPECTED ***");
            }
            if (rc == 0 && handle != 0) {
                int c1 = (int) hClose.invokeExact(handle);
                int c2 = (int) hClose.invokeExact(handle);
                int cb = (int) hClose.invokeExact(0x1234L);
                System.out.printf("   close=%d closeAgain=%d closeForged=%d%n", c1, c2, cb);
            }
        }
        System.out.println(bad == 0 ? "PROBE_RESULT: ALL GUARDS AS EXPECTED" : "PROBE_RESULT: " + bad + " UNEXPECTED");
    }
}