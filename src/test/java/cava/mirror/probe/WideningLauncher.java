package cava.mirror.probe;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 探针启动器（**只在测试里用，不进 jar**）。
 *
 * <p>为什么需要它：普通 JUnit 进程里没有 Fabric 的 access widener，MC 自己的
 * {@code SimpleRegistry} 会因 {@code RegistryEntry$Reference.setRegistryKey} 是包私有而
 * {@code IllegalAccessError}。这里用一个**把所有 net.minecraft 成员加宽成 public 的类加载器**
 * 复现"能被 bootstrap"的环境（只在探针里用，不影响产品代码）。
 */
public final class WideningLauncher {

    private static final class WideningLoader extends URLClassLoader {
        WideningLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // net.minecraft.** 必须由本加载器加载（这样才走加宽）；探针主体也必须 child-first，
            // 否则父加载器会从同一个输出目录先把它加载走，里面的 MC 引用就没被加宽。
            if (name.startsWith("net.minecraft.") || name.equals("cava.mirror.probe.McProbeMain")) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = findClass(name);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.startsWith("net.minecraft.")) {
                return super.findClass(name);
            }
            String path = name.replace('.', '/') + ".class";
            try (java.io.InputStream in = super.findResource(path).openStream()) {
                byte[] bytes = in.readAllBytes();
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(0);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    private int widen(int access) {
                        return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                    }

                    @Override
                    public void visit(int version, int access, String n, String sig, String sup, String[] itf) {
                        super.visit(version, widen(access), n, sig, sup, itf);
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                        return super.visitMethod(widen(access), n, d, sig, ex);
                    }

                    @Override
                    public FieldVisitor visitField(int access, String n, String d, String sig, Object v) {
                        return super.visitField(widen(access), n, d, sig, v);
                    }
                }, 0);
                byte[] out = cw.toByteArray();
                return defineClass(name, out, 0, out.length);
            } catch (Exception e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // args[0] = 子加载器 URL 列表（; 分隔），args[1] = 要跑的类名
        if (args.length >= 3) {
            System.setProperty("cava.native.path", args[2]);
        }
        String[] urls = args[0].split(";");
        URL[] list = new URL[urls.length];
        for (int i = 0; i < urls.length; i++) {
            list[i] = new File(urls[i]).toURI().toURL();
        }
        ClassLoader parent = WideningLauncher.class.getClassLoader();
        WideningLoader loader = new WideningLoader(list, parent);
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> target = Class.forName(args[1], true, loader);
        target.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
    }

    private WideningLauncher() {
    }
}
