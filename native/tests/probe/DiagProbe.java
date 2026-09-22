/* DiagProbe.java —— isValidDiagonalSuccessor 的 flag5 极性的**判定性实验**。
 *
 * 背景：三方产物（oracle 参照实现 LandMaker.java / oracle spec §2.4 的改写 / captain 广播 #5 第 6 条）
 * 一度都写成 "&& flag5"，而字节码是 "&& !flag5"。光靠读跳转容易看反，所以让 javac 自己回答：
 * 把两种候选源码形状各编一遍，看哪个的字节码形状与原版一致。
 *
 * 复现（PowerShell 5.1）：
 *     New-Item -ItemType Directory -Force -Path build\probe\out | Out-Null
 *     & 'C:\Program Files\Java\jdk-21\bin\javac.exe' -d build\probe\out native\tests\probe\DiagProbe.java
 *     & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c -classpath build\probe\out DiagProbe
 *
 * 实测输出（chainFlag / chainNotFlag 的关键三条指令）：
 *     chainFlag    ( ... || flag() )  ->  25: invokestatic flag:()Z ; 28: ifeq  58
 *     chainNotFlag ( ... || !flag() ) ->  25: invokestatic flag:()Z ; 28: ifne  58
 * 原版 LandPathNodeMaker.isValidDiagonalSuccessor 偏移 154/179 是
 *     iload 5 (flag5) ; ifeq 188        （188 = iconst_0; ireturn，由偏移 131 的 iflt 188 独立佐证）
 * => 与 chainFlag 同形 => 析取项是 flag5 => 拒绝条件是 **!flag5**（任一侧各自独立拒绝）。
 */
public class DiagProbe {
    static int yHost = 0, yA = 0, yB = 0;
    static float fDiag = 0f, fxA = 0f, fxB = 0f;
    static double width = 1.0;

    static boolean flag() { return width < 0.5 && yA == yB; }

    /** 候选 A：第三析取项 = flag（oracle 参照实现的写法）。*/
    static boolean chainFlag() {
        return fDiag >= 0.0f
            && (yB < yHost || fxB >= 0.0f || flag())
            && (yA < yHost || fxA >= 0.0f || flag());
    }

    /** 候选 B：第三析取项 = !flag。*/
    static boolean chainNotFlag() {
        return fDiag >= 0.0f
            && (yB < yHost || fxB >= 0.0f || !flag())
            && (yA < yHost || fxA >= 0.0f || !flag());
    }
}
