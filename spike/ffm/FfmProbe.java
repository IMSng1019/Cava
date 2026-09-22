import java.lang.foreign.*;
import static java.lang.foreign.ValueLayout.*;

public class FfmProbe {
    public static void main(String[] args) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();
        var strlen = lookup.find("strlen");
        System.out.println("nativeLinker ok=" + (linker != null) + " strlen found=" + strlen.isPresent());
        if (strlen.isPresent()) {
            var mh = linker.downcallHandle(strlen.get(), FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                // JDK21: no allocateFrom(String); use allocate(long,long) + setString
                MemorySegment s = arena.allocate(16, 1);
                s.setUtf8String(0, "hello");
                long n = (long) mh.invokeExact(s);
                System.out.println("strlen(\"hello\") = " + n);
            }
        }
        // key JDK21 facts to record
        System.out.println("Arena has byteSize()? " + hasMethod(Arena.class, "byteSize"));
        System.out.println("Arena has allocateFrom? " + hasMethod(Arena.class, "allocateFrom"));
        System.out.println("Linker.Option has critical()? " + hasMethod(Class.forName("java.lang.foreign.Linker$Option"), "critical"));
        System.out.println("SegmentAllocator.allocateArray? " + hasMethod(SegmentAllocator.class, "allocateArray"));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment oneInt = a.allocate(JAVA_INT, 10);
            System.out.println("arena.allocate(JAVA_INT,10).byteSize() = " + oneInt.byteSize() + "  (value intent, NOT count!)");
            MemorySegment tenInts = a.allocateArray(JAVA_INT, 10);
            System.out.println("arena.allocateArray(JAVA_INT,10).byteSize() = " + tenInts.byteSize());
        }
        System.out.println("OK");
    }
    static boolean hasMethod(Class<?> c, String name) {
        for (var m : c.getMethods()) if (m.getName().equals(name)) return true;
        return false;
    }
}
