/* crashprobe.c -- a DELIBERATELY broken native library (P4-C, crash forensics).
 *
 * It exists for exactly one purpose: to make the JVM die a HARD death so that the HotSpot
 * error handler writes an hs_err_pid<PID>.log, which tools/hs-err-report.ps1 then has to
 * classify. Without a real crash log the parser would only ever be tested against a
 * hand-written fixture, i.e. against my own idea of the format instead of the real one.
 *
 * !! THIS FILE MUST NEVER BE COMPILED INTO cava.dll !!
 *   - it is NOT referenced by native/CMakeLists.txt (the root build);
 *   - it is NOT referenced by native/tests/CMakeLists.txt;
 *   - native/tests/crashprobe/build-crashprobe.ps1 builds it STANDALONE into build/p4c-crash/.
 *
 * Two flavours of the same mistake:
 *   crashprobe.dll   exports cava_crash_probe_null_deref()  -> writes through NULL
 *   crashprobe_null2.dll / othermod.dll  byte-identical copies under a different file name,
 *   used as the "the problem frame is in ANOTHER module" negative control.
 *
 * The symbol name is chosen so that a grep for "cava_" in the crash log FINDS it. That is the
 * trap the report has to survive: a module/symbol whose name merely CONTAINS "cava" is not
 * Cava. The report decides by MODULE, not by text.
 *
 * MEASURED trap: compiling this file with g++ (C++ mode) mangles the exports to
 * _Z27cava_crash_probe_null_derefv unless they are inside extern "C". With the mangled name
 * SymbolLookup.find("cava_crash_probe_null_deref") cannot resolve the symbol, the probe exits 2,
 * and no crash log is ever produced -- a silent no-op that would make this whole chain vacuous.
 *
 * Build (see build-crashprobe.ps1):
 *   C:\mingw64\bin\g++.exe -O0 -g -shared -o crashprobe.dll crashprobe.c
 *
 * NOTE: intentionally ASCII-only.
 */

#include <stdint.h>

#if defined(_WIN32)
#  define CAVA_PROBE_EXPORT __declspec(dllexport)
#else
#  define CAVA_PROBE_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* Volatile so the compiler is not allowed to delete the store. -O0 already keeps it, but a
 * future reader building this at -O2 must still get a real fault (a deleted store would make
 * the probe a silent no-op and the whole chain would "pass" while testing nothing). */
CAVA_PROBE_EXPORT void cava_crash_probe_null_deref(void) {
    volatile int32_t* p = (volatile int32_t*) (uintptr_t) 0;
    *p = 0x5A5A5A5A;   /* write to address 0 => access violation => hs_err_pid<PID>.log */
}

/* A second entry point that faults inside a different statement. Not used by the default run;
 * kept because a future variant (abort/SIGABRT) can be wired here without touching the Java
 * side, and the report must print both signal shapes correctly. */
CAVA_PROBE_EXPORT void cava_crash_probe_abort(void) {
    volatile int32_t* p = (volatile int32_t*) (uintptr_t) 0;
    if (p == (volatile int32_t*) (uintptr_t) 0) {
        *p = 1;
    }
}

#ifdef __cplusplus
}
#endif
