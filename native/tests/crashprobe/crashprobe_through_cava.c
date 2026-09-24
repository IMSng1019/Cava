/* crashprobe_through_cava.c -- a deliberate crash whose PROBLEM FRAME lands in a module named
 * cava.dll.  (P4-C, crash forensics.)
 *
 * Why it exists: tools/hs-err-report.ps1 must return CAVA_NATIVE_FAULT when the problem frame is
 * inside cava.dll. Without this probe that branch could only be exercised against a hand-written
 * fixture, i.e. against my own idea of what a Cava crash looks like.
 *
 * How it works WITHOUT touching the frozen ABI and WITHOUT loading the real library:
 *   - the faulting body lives in a byte-identical copy of crashprobe.dll that the build script
 *     copies to the file name "cava.dll" inside build/p4c-crash/ (never in natives/windows-x64/,
 *     which is a shared output directory);
 *   - this file is built as "crashprobe_cava_proxy.dll" and, when called, loads that "cava.dll"
 *     and calls its exported cava_crash_probe_null_deref().
 *
 * Result in the crash log:
 *   # Problematic frame:
 *   # C  [cava.dll+0x1540]
 *   Dynamic libraries:
 *   0x... - 0x...   J:\mc\Cava\build\p4c-crash\cava.dll
 * i.e. exactly the shape a real Cava native fault would have, so the parser's positive branch is
 * exercised by a REAL crash log instead of a fixture.
 *
 * MEASURED traps (each cost a round):
 *   1. LoadLibraryA("cava.dll") does NOT search the directory this DLL lives in, and the JVM's
 *      working directory is not that directory either -- the plain name fails.
 *   2. GetModuleFileNameA((HMODULE)&some_static_function, ...) is WRONG: a module's HMODULE is
 *      its LOAD BASE, which equals the address of a function only if that function sits at
 *      offset 0. With the wrong handle the path came out empty and the stub never loaded.
 *      The correct self-handle is the HINSTANCE DllMain receives (captured below).
 *   3. A MessageBox in this file is a trap of its own: it BLOCKS a non-interactive test run
 *      (observed: the run took 4.2 s and then reported "returned normally"), which makes a
 *      broken probe look like a passing one. Diagnostics go to a file + stderr instead.
 *
 * NOTE: intentionally ASCII-only.
 */

#include <windows.h>
#include <stdint.h>
#include <stdio.h>

typedef void (*fn_null_deref)(void);

static HMODULE g_self = NULL;

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID reserved) {
    (void) reserved;
    if (reason == DLL_PROCESS_ATTACH) { g_self = (HMODULE) inst; }
    return TRUE;
}

/* Failure diagnostics -> a file next to the module (no dialog that could hang the test). */
static void report(const char* what, DWORD err, const char* detail) {
    char path[MAX_PATH];
    char self[MAX_PATH];
    DWORD n = (g_self != NULL) ? GetModuleFileNameA(g_self, self, MAX_PATH) : 0;
    if (n == 0 || n >= MAX_PATH) { return; }
    char* slash = self + n;
    while (slash > self && *slash != '\\' && *slash != '/') { --slash; }
    *(slash + 1) = '\0';
    if (lstrlenA(self) + 24 >= MAX_PATH) { return; }
    lstrcpyA(path, self);
    lstrcatA(path, "crashprobe_error.log");
    FILE* f = fopen(path, "a");
    if (f == NULL) { return; }
    fprintf(f, "%s err=%lu detail=%s\n", what, (unsigned long) err, (detail != NULL) ? detail : "");
    fclose(f);
}

/* Returns the stub only if it really exports the probe symbol.
 *
 * MEASURED trap: the JVM in tools/crash-probe.ps1 also loads the REAL natives/windows-x64/cava.dll
 * for fingerprinting, and "cava.dll" is therefore ALREADY IN THE PROCESS. GetModuleHandleA(
 * "cava.dll") happily returns that real library -- and then GetProcAddress for
 * cava_crash_probe_null_deref fails with error 127, the proxy returns normally, and no crash log
 * is produced. The stub must therefore be found by path from the top, never by name, and the
 * result must be validated before use. */
static HMODULE load_stub(void) {
    HMODULE m;

    /* absolute path first: <directory of this DLL>\cava.dll */
    char self[MAX_PATH];
    DWORD n = (g_self != NULL) ? GetModuleFileNameA(g_self, self, MAX_PATH) : 0;
    if (n == 0 || n >= MAX_PATH) {
        report("GetModuleFileNameA(self) failed", GetLastError(), "");
        return NULL;
    }
    char* slash = self + n;
    while (slash > self && *slash != '\\' && *slash != '/') { --slash; }
    if (slash <= self) { return NULL; }
    *(slash + 1) = '\0';
    char full[MAX_PATH];
    if (lstrlenA(self) + 10 >= MAX_PATH) { return NULL; }
    lstrcpyA(full, self);
    lstrcatA(full, "cava.dll");
    m = LoadLibraryA(full);
    if (m != NULL) { return m; }
    report("LoadLibraryA(stub) failed", GetLastError(), full);

    /* last resort: an already-loaded module named cava.dll that actually exports the probe */
    m = GetModuleHandleA("cava.dll");
    if (m != NULL 
            && GetProcAddress(m, "cava_crash_probe_null_deref") != NULL) {
        return m;
    }
    return NULL;
}

/* Kept as a plain exported function: FFM binds it with FunctionDescriptor.ofVoid(). */
#ifdef __cplusplus
extern "C"
#endif
__declspec(dllexport) void crashprobe_through_cava(void) {
    HMODULE stub = load_stub();
    if (stub == NULL) {
        report("stub not loaded -- NO CRASH PRODUCED", 0, "");
        return;
    }
    fn_null_deref f = (fn_null_deref) GetProcAddress(stub, "cava_crash_probe_null_deref");
    if (f == NULL) {
        report("GetProcAddress(cava_crash_probe_null_deref) failed", GetLastError(), "");
        return;
    }
    f();
    report("the stub returned without faulting -- probe is broken", 0, "");
}
