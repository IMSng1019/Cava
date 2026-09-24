/* CrashProbe.java -- P4-C crash forensics: make the JVM die on purpose through FFM.
 *
 * It is the Java half of native/tests/crashprobe/crashprobe.c:
 *
 *   crashprobe.c  exports cava_crash_probe_null_deref(void)  -> NULl write
 *   CrashProbe    binds it with a java.lang.foreign downcall and calls it
 *
 * Why FFM and not JNI: the project calls native code ONLY through java.lang.foreign, so the
 * stack shape of the resulting hs_err log is the one that will actually appear in production
 * (a clean "J ... <symbol>" Java frame directly above a "C ... module!symbol+offset" frame).
 *
 * Usage:
 *   java --enable-preview --enable-native-access=ALL-UNNAMED -cp <dir> CrashProbe <dll> [--load-cava=<dll>]
 *
 *   <dll>            crashprobe.dll (or crashprobe_null2.dll for the negative control)
 *   --load-cava=...  optional: System.load + fingerprint a real cava.dll FIRST, so that the
 *                    crash log also contains a cava.dll line in "Dynamic libraries". That is
 *                    what makes the "is cava.dll in the stack? which build was it?" part of
 *                    tools/hs-err-report.ps1 testable on a REAL log instead of a fixture.
 *
 * Deliberate shape of the run:
 *   - the stderr marker line is printed BEFORE the crash so the transcript proves the crash
 *     happened during the downcall and not somewhere else;
 *   - there is no try/catch anywhere: a Java-level catch cannot stop a native access
 *     violation anyway, and swallowing the failure would defeat the point;
 *   - the JVM must exit NON-ZERO. If this program ever exits 0, the probe is broken.
 *
 * NOTE: intentionally ASCII-only (PowerShell 5.1 + this machine's console encodings).
 */

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class CrashProbe {

    public static void main(String[] args) throws Throwable {
        String dll = null;
        String loadCava = null;
        String symbol = "cava_crash_probe_null_deref";
        for (String a : args) {
            if (a.startsWith("--load-cava=")) {
                loadCava = a.substring("--load-cava=".length());
            } else if (a.startsWith("--symbol=")) {
                symbol = a.substring("--symbol=".length());
            } else if (dll == null) {
                dll = a;
            }
        }
        if (dll == null) {
            System.err.println("usage: CrashProbe <crashprobe.dll> [--load-cava=<cava.dll>] [--symbol=<name>]");
            System.exit(2);
        }
        Path dllPath = Path.of(dll).toAbsolutePath();
        if (!Files.isRegularFile(dllPath)) {
            System.err.println("crash-probe: missing " + dllPath);
            System.exit(2);
        }

        System.err.println("crash-probe: java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        System.err.println("crash-probe: loading " + dllPath);
        System.load(dllPath.toString());

        if (loadCava != null) {
            // Load + fingerprint the REAL library so the crash log carries a cava.dll line.
            Path cavaPath = Path.of(loadCava).toAbsolutePath();
            System.load(cavaPath.toString());
            CavaArtifactProbe.Info info = CavaArtifactProbe.inspect(cavaPath);
            System.err.println("crash-probe: cava loaded -> " + info);
        }

        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();
        Optional<MemorySegment> found = lookup.find(symbol);
        if (found.isEmpty()) {
            System.err.println("crash-probe: defaultLookup() cannot see " + symbol
                    + " (same trap as documented in docs/CAVA-gates.md) -- falling back to libraryLookup");
            lookup = SymbolLookup.libraryLookup(dllPath, java.lang.foreign.Arena.ofShared());
            found = lookup.find(symbol);
        }
        if (found.isEmpty()) {
            System.err.println("crash-probe: symbol not found: " + symbol);
            System.exit(2);
        }
        MethodHandle h = linker.downcallHandle(found.get(), FunctionDescriptor.ofVoid());

        System.err.println("crash-probe: invoking " + symbol + "() NOW -- the JVM must die here");
        System.err.flush();
        h.invokeExact();

        // Unreachable in a working probe. Reaching this line means the DLL did NOT crash and
        // every conclusion drawn from "the JVM crashed" would be worthless.
        System.err.println("crash-probe: *** BUG IN THE PROBE *** " + symbol + " returned normally; "
                + "no hs_err log will be written. Exit 3.");
        System.exit(3);
    }
}
