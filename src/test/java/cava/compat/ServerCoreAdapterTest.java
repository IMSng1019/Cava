package cava.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** ServerCore 适配器：短路语义（纯函数）+ 接口形状（真实 jar 探测）。 */
class ServerCoreAdapterTest {

    @Test
    void addVelocityShortCircuitMatchesBytecode() {
        // javap -c ...EntityMixin.servercore$ignorePushingWhileInactive(DDD, CallbackInfo):
        //   if (servercore$isInactive != 0 && this.world.isClient == 0) ci.cancel();
        assertTrue(ServerCoreAdapter.shouldCancelAddVelocity(true, false));
        assertFalse(ServerCoreAdapter.shouldCancelAddVelocity(true, true), "客户端世界不取消");
        assertFalse(ServerCoreAdapter.shouldCancelAddVelocity(false, false));
        assertFalse(ServerCoreAdapter.shouldCancelAddVelocity(false, true));
    }

    @Test
    void reflectiveIsInactiveIsFailSafeWithoutServerCore() {
        // 没装 ServerCore（或接口不可解析）时必须返回 false = 按原版语义继续，不抛异常
        assertFalse(ServerCoreAdapter.isInactive(null));
        assertFalse(ServerCoreAdapter.isInactive("不是实体"));
    }

    @Test
    void probeJarReadsRealInterfaceShape() {
        Path jar = TestPaths.modpackJar("servercore").orElse(null);
        Assumptions.assumeTrue(jar != null, "本机没有 servercore jar，跳过");
        Optional<ServerCoreAdapter.Schema> s = ServerCoreAdapter.probeJar(jar);
        assertTrue(s.isPresent(), "必须能从真实 jar 里探出接口");
        ServerCoreAdapter.Schema sc = s.get();
        assertTrue(sc.inactiveInterfacePresent());
        assertTrue(sc.activationEntityPresent());
        assertTrue(sc.mixinEntityPresent());

        // 逐条对齐 javap 实测结果 —— 任务书里「Inactive 有 servercore$isInactive」的说法是错的
        assertEquals(List.of("servercore$inactiveTick"), sc.inactiveInterfaceMethods(),
                "Inactive 接口只声明 servercore$inactiveTick（javap 实证）");
        assertFalse(sc.isInactiveOnInactiveInterface(), "Inactive 上**没有** servercore$isInactive");
        assertTrue(sc.isInactiveOnActivationEntity(), "servercore$isInactive 在 ActivationEntity 上");
        assertTrue(sc.activationEntityMethods().contains("servercore$setInactive"));
        assertTrue(sc.activationEntityMethods().contains("servercore$getActivationType"));
        assertTrue(sc.addVelocityTargetInRefmap(),
                "refmap 里 push(DDD)V 必须映射到 method_5762（Entity.addVelocity，旧称 push）");
        assertTrue(sc.refmapName().contains("servercore-common-refmap.json"), sc.refmapName());
        assertTrue(sc.usable());
    }

    @Test
    void missingJarIsHandledGracefully() {
        assertTrue(ServerCoreAdapter.probeJar(null).isEmpty());
        assertTrue(ServerCoreAdapter.probeJar(Path.of("no-such.jar")).isEmpty());
        assertFalse(ServerCoreAdapter.reportLine(Optional.empty(), false).contains("null"));
    }
}
