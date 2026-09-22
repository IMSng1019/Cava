package cava.compat;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 极小的 class 文件读取器（不引 ASM / 不引字节码库）。
 *
 * <p><b>为什么要它</b>：兼容层必须"探测"而不是"凭记忆写类名方法名"。
 * 探测的最强证据是<b>别人 jar 里的 class 文件本身</b>：接口到底声明了哪个方法、
 * 某个 mixin 类在不在 mixins.json 里、某个插件类里有哪些门控前缀字符串。
 * 这些都不需要把对方的类加载进 JVM（对方是 MC mod，直接 Class.forName 会连带加载 Minecraft 类型）。
 *
 * <p>只解析到"名字与描述符"这一层：常量池 + 访问标志 + 接口/字段/方法表，属性一律跳过。
 * 因此不受注解、泛型签名、StackMapTable 的影响。
 *
 * <p>本类不引用任何 Minecraft / Fabric 类型。
 */
public final class ClassFileProbe {

    private static final int MAGIC = 0xCAFEBABE;

    public static final int ACC_PUBLIC = 0x0001;
    public static final int ACC_INTERFACE = 0x0200;
    public static final int ACC_ABSTRACT = 0x0400;

    private ClassFileProbe() {
    }

    /** 一个方法或字段。 */
    public record Member(String name, String descriptor, int access) {

        public boolean isPublic() {
            return (access & ACC_PUBLIC) != 0;
        }

        public String signature() {
            return name + descriptor;
        }
    }

    /** 一个 class 文件里我们关心的全部信息。 */
    public static final class Info {

        private final String internalName;
        private final String superName;
        private final List<String> interfaces;
        private final List<Member> methods;
        private final List<Member> fields;
        private final List<String> utf8;
        private final int access;
        private final int major;
        private final int minor;

        Info(String internalName, String superName, List<String> interfaces, List<Member> methods,
                List<Member> fields, List<String> utf8, int access, int major, int minor) {
            this.internalName = internalName;
            this.superName = superName;
            this.interfaces = List.copyOf(interfaces);
            this.methods = List.copyOf(methods);
            this.fields = List.copyOf(fields);
            this.utf8 = List.copyOf(utf8);
            this.access = access;
            this.major = major;
            this.minor = minor;
        }

        /** 形如 {@code me/wesley1808/servercore/.../Inactive}。 */
        public String internalName() {
            return internalName;
        }

        /** 点号形式，未知名返回空串。 */
        public String className() {
            return internalName == null ? "" : internalName.replace('/', '.');
        }

        public String superName() {
            return superName;
        }

        public List<String> interfaces() {
            return interfaces;
        }

        public List<Member> methods() {
            return methods;
        }

        public List<Member> fields() {
            return fields;
        }

        /** 常量池里所有 UTF-8 常量（去重，保持首次出现顺序）。 */
        public List<String> utf8Constants() {
            return utf8;
        }

        public int access() {
            return access;
        }

        public int major() {
            return major;
        }

        public int minor() {
            return minor;
        }

        public boolean isInterface() {
            return (access & ACC_INTERFACE) != 0;
        }

        public List<String> methodNames() {
            List<String> out = new ArrayList<>(methods.size());
            for (Member m : methods) {
                out.add(m.name());
            }
            return out;
        }

        public boolean declaresMethod(String name) {
            for (Member m : methods) {
                if (m.name().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        public Optional<Member> method(String name) {
            for (Member m : methods) {
                if (m.name().equals(name)) {
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        }

        /** 任意常量池字符串里是否出现某段文本（探测"门控前缀"这类证据用）。 */
        public boolean hasConstantContaining(String needle) {
            for (String s : utf8) {
                if (s.contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        /** 常量池里所有以 prefix 开头、以 suffix 结尾的字符串。 */
        public List<String> constantsPrefixed(String prefix, String suffix) {
            List<String> out = new ArrayList<>();
            for (String s : utf8) {
                if (s.startsWith(prefix) && s.endsWith(suffix)) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    /** 解析一段 class 文件字节。 */
    public static Info read(byte[] classBytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException(String.format(Locale.ROOT, "不是 class 文件（magic=0x%08X）", magic));
            }
            int minor = in.readUnsignedShort();
            int major = in.readUnsignedShort();
            int cpCount = in.readUnsignedShort();
            Object[] cp = new Object[cpCount];
            List<String> utf8 = new ArrayList<>();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> {
                        String s = in.readUTF();
                        cp[i] = s;
                        utf8.add(s);
                    }
                    case 3, 4 -> in.skipNBytes(4);
                    case 5, 6 -> {
                        in.skipNBytes(8);
                        i++; // long / double 占两个槽
                    }
                    case 7 -> cp[i] = new int[]{in.readUnsignedShort()}; // Class -> name_index
                    case 8 -> in.skipNBytes(2);
                    case 9, 10, 11, 12, 18 -> in.skipNBytes(4);
                    case 15 -> in.skipNBytes(3);
                    case 16 -> in.skipNBytes(2);
                    case 17 -> in.skipNBytes(4);
                    case 19, 20 -> in.skipNBytes(2);
                    default -> throw new IOException("未知常量池标签 " + tag + "（槽位 " + i + "）");
                }
            }
            int access = in.readUnsignedShort();
            String thisClass = classNameAt(cp, in.readUnsignedShort());
            String superClass = classNameAt(cp, in.readUnsignedShort());
            int ifaceCount = in.readUnsignedShort();
            List<String> interfaces = new ArrayList<>(ifaceCount);
            for (int i = 0; i < ifaceCount; i++) {
                interfaces.add(classNameAt(cp, in.readUnsignedShort()));
            }
            List<Member> fields = readMembers(in, cp);
            List<Member> methods = readMembers(in, cp);
            return new Info(thisClass, superClass, interfaces, methods, fields, utf8, access, major, minor);
        }
    }

    private static List<Member> readMembers(DataInputStream in, Object[] cp) throws IOException {
        int count = in.readUnsignedShort();
        List<Member> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int access = in.readUnsignedShort();
            String name = utf8At(cp, in.readUnsignedShort());
            String desc = utf8At(cp, in.readUnsignedShort());
            int attrs = in.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                in.readUnsignedShort();          // attribute_name_index
                long len = Integer.toUnsignedLong(in.readInt());
                in.skipNBytes(len);
            }
            out.add(new Member(name, desc, access));
        }
        return out;
    }

    private static String classNameAt(Object[] cp, int index) {
        if (index <= 0 || index >= cp.length) {
            return null;
        }
        Object o = cp[index];
        if (o instanceof int[] ref) {
            return utf8At(cp, ref[0]);
        }
        return o instanceof String s ? s : null;
    }

    private static String utf8At(Object[] cp, int index) {
        if (index <= 0 || index >= cp.length) {
            return null;
        }
        return cp[index] instanceof String s ? s : null;
    }

    /** 从 jar 里读一个类（internalName 用 {@code a/b/C} 形式）。 */
    public static Optional<Info> readFromJar(Path jar, String internalName) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(internalName + ".class");
            if (e == null) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(e)) {
                return Optional.of(read(in.readAllBytes()));
            }
        }
    }

    /** jar 里所有顶层/嵌套类名（{@code a/b/C} 形式，去掉 .class）。 */
    public static List<String> listClasses(Path jar) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                String name = it.nextElement().getName();
                if (name.endsWith(".class") && !name.equals("module-info.class")) {
                    out.add(name.substring(0, name.length() - ".class".length()));
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    /** jar 里是否存在该类。 */
    public static boolean hasClass(Path jar, String internalName) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(internalName + ".class") != null;
        }
    }

    public static boolean isReadableJar(Path p) {
        return p != null && Files.isRegularFile(p);
    }
}
