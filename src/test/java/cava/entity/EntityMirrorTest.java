package cava.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 实体 SoA 镜像的打包 / 读取 / 跳过统计验证（三条关注点之一：<b>inactive 跳过</b>）。
 *
 * <p>全部用内存假世界跑，不需要服务器、不需要 bootstrap MC。
 */
class EntityMirrorTest {

    /** 假实体：字段只能通过 {@link #setX} 之类的方法改，镜像拿到的只有 {@link EntitySample}。 */
    static final class FakeEntity {
        int id;
        int typeId;
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;
        int flags;
        boolean inactive;
        boolean unsamplable;

        /** 写回计数器：镜像若以任何方式改实体，都必须走这里。 */
        int writes;

        void setX(double value) {
            writes++;
            this.x = value;
        }

        void setVelocity(double sx, double sy, double sz) {
            writes++;
            this.vx = sx;
            this.vy = sy;
            this.vz = sz;
        }
    }

    /** 假世界：只读源。 */
    static final class FakeWorld implements EntitySource {
        final List<FakeEntity> entities = new ArrayList<>();
        int sizeCalls;
        int sampleCalls;

        FakeEntity add(int id) {
            FakeEntity e = new FakeEntity();
            e.id = id;
            e.typeId = 100 + id;
            e.x = id * 1.5;
            e.y = 64.0;
            e.z = -id * 0.25;
            e.vx = 0.1 * id;
            e.vy = -0.08;
            e.vz = id;
            e.minX = e.x - 0.3;
            e.minY = e.y;
            e.minZ = e.z - 0.3;
            e.maxX = e.x + 0.3;
            e.maxY = e.y + 1.8;
            e.maxZ = e.z + 0.3;
            e.flags = EntityFlags.ON_GROUND;
            entities.add(e);
            return e;
        }

        @Override
        public int size() {
            sizeCalls++;
            return entities.size();
        }

        @Override
        public boolean sample(int index, EntitySample out) {
            sampleCalls++;
            FakeEntity e = entities.get(index);
            if (e.unsamplable) {
                out.id = e.id;
                return false;
            }
            out.handle = e;
            out.id = e.id;
            out.typeId = e.typeId;
            out.setPosition(e.x, e.y, e.z);
            out.setVelocity(e.vx, e.vy, e.vz);
            out.setBoundingBox(e.minX, e.minY, e.minZ, e.maxX, e.maxY, e.maxZ);
            out.flags = e.flags;
            return true;
        }
    }

    private static final InactivityProbe FAKE_PROBE = entity -> ((FakeEntity) entity).inactive;

    @Test
    void packsEveryFieldBitExactInSourceOrder() {
        FakeWorld world = new FakeWorld();
        FakeEntity a = world.add(1);
        a.setX(-0.0); // 负零：只有逐位搬运才能原样保留
        world.add(2);

        EntityMirror mirror = new EntityMirror();
        mirror.pack(42, world, InactivityProbe.NONE);

        assertEquals(42, mirror.tick());
        assertEquals(2, mirror.size());
        assertEquals(1, mirror.id(0));
        assertEquals(2, mirror.id(1));
        assertEquals(101, mirror.typeId(0));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(mirror.x(0)),
                "-0.0 的原始位模式必须原样保留（差分用位模式，不是十进制）");
        assertEquals(world.entities.get(1).z, mirror.z(1));
        assertEquals(world.entities.get(1).vz, mirror.vz(1));
        assertEquals(EntityFlags.ON_GROUND, mirror.flags(0));
        assertTrue(mirror.flag(0, EntityFlags.ON_GROUND));
        assertFalse(mirror.flag(0, EntityFlags.SERVERCORE_INACTIVE));

        EntitySample read = new EntitySample();
        mirror.readInto(1, read);
        assertEquals(2, read.id);
        assertEquals(world.entities.get(1).maxY, read.maxY);
        assertEquals(world.entities.get(1).vy, read.vy);
    }

    /**
     * ServerCore 激活范围：未激活实体<b>照样打包</b>（它仍然参与碰撞），
     * 只置观测位 + 记台账。把它从镜像里剔掉会改变碰撞结果。
     */
    @Test
    void inactiveEntitiesArePackedFlaggedAndLogged() {
        FakeWorld world = new FakeWorld();
        world.add(1);
        FakeEntity inactive = world.add(2);
        inactive.inactive = true;
        world.add(3);

        EntityMirror mirror = new EntityMirror();
        mirror.pack(7, world, FAKE_PROBE);

        assertEquals(3, mirror.size(), "inactive 实体不能被剔掉");
        assertFalse(mirror.flag(0, EntityFlags.SERVERCORE_INACTIVE));
        assertTrue(mirror.flag(1, EntityFlags.SERVERCORE_INACTIVE), "位要置上");
        assertFalse(mirror.flag(2, EntityFlags.SERVERCORE_INACTIVE));

        TickSkipLedger ledger = mirror.ledger();
        assertEquals(7, ledger.tick());
        assertEquals(1, ledger.size());
        assertEquals(2, ledger.id(0));
        assertEquals(SkipReason.SERVERCORE_INACTIVE, ledger.reason(0));
        assertEquals(1, ledger.count(SkipReason.SERVERCORE_INACTIVE));
        assertEquals(List.of(2), ledger.idsWith(SkipReason.SERVERCORE_INACTIVE));
    }

    /** 已移除 / 读不出来的行：记账但不进镜像。 */
    @Test
    void removedAndUnsamplableRowsAreLoggedNotPacked() {
        FakeWorld world = new FakeWorld();
        world.add(1);
        FakeEntity removed = world.add(2);
        removed.flags = EntityFlags.REMOVED;
        FakeEntity broken = world.add(3);
        broken.unsamplable = true;

        EntityMirror mirror = new EntityMirror();
        mirror.pack(1, world, InactivityProbe.NONE);

        assertEquals(1, mirror.size());
        assertEquals(1, mirror.id(0));
        assertEquals(1, mirror.ledger().count(SkipReason.REMOVED));
        assertEquals(List.of(2), mirror.ledger().idsWith(SkipReason.REMOVED));
        assertEquals(1, mirror.ledger().count(SkipReason.UNSAMPLABLE));
        assertEquals(List.of(3), mirror.ledger().idsWith(SkipReason.UNSAMPLABLE));
    }

    /** <b>不回写</b>：打包是纯观测。假实体的写计数器必须保持 0。 */
    @Test
    void packNeverWritesBackToEntities() {
        FakeWorld world = new FakeWorld();
        world.add(1);
        FakeEntity e = world.add(2);
        e.inactive = true;
        world.add(3);

        EntityMirror mirror = new EntityMirror();
        for (int tick = 0; tick < 5; tick++) {
            mirror.pack(tick, world, FAKE_PROBE);
        }

        int writes = 0;
        for (FakeEntity f : world.entities) {
            writes += f.writes;
        }
        assertEquals(0, writes, "镜像绝不能改实体（不要主动唤醒、不要回写）");
        assertEquals(5, world.sizeCalls);
        assertEquals(15, world.sampleCalls);
    }

    /** id -> 行号索引（差分脚本要用）；含缺失 id。 */
    @Test
    void indexOfIdFindsRowsAndReportsMissing() {
        FakeWorld world = new FakeWorld();
        for (int id : new int[] {10, 20, 30, 40, 50}) {
            world.add(id);
        }
        EntityMirror mirror = new EntityMirror();
        mirror.pack(0, world, InactivityProbe.NONE);
        assertEquals(0, mirror.indexOfId(10));
        assertEquals(3, mirror.indexOfId(40));
        assertEquals(4, mirror.indexOfId(50));
        assertEquals(-1, mirror.indexOfId(11));
        assertEquals(-1, mirror.indexOfId(0));
    }

    /**
     * 冻结核对：连续两 tick inactive 的实体，12 个 double 必须逐位不变。
     * 第 4 tick 人为改动位置 -> 必须被抓出来（这是"位置不变是预期行为"的可执行版本）。
     */
    @Test
    void frozenCheckDetectsAMovedInactiveEntity() {
        FakeWorld world = new FakeWorld();
        world.add(1);
        FakeEntity inactive = world.add(2);

        EntityTickObserver observer = new EntityTickObserver(FAKE_PROBE);

        observer.tick(1, world); // 还没 inactive
        assertEquals(0, observer.lastFrozen().pairsChecked());

        inactive.inactive = true;
        observer.tick(2, world); // 本 tick 刚进入 inactive：允许位置变化
        assertEquals(0, observer.lastFrozen().pairsChecked(), "新进入 inactive 的那一 tick 没有可比对的对");
        assertEquals(1, observer.lastFrozen().newlyInactive());

        observer.tick(3, world);
        assertEquals(1, observer.lastFrozen().pairsChecked());
        assertEquals(0, observer.lastFrozen().violations());
        assertTrue(observer.lastFrozen().ok());

        inactive.setX(inactive.x + 1.0); // 不该发生的事
        observer.tick(4, world);
        assertEquals(1, observer.lastFrozen().violations(), "位置变了必须被抓出来");
        assertEquals(List.of(2), observer.lastFrozen().violatingIds());
        assertFalse(observer.lastFrozen().ok());
    }

    /** 上一 tick 的 inactive 快照必须在 pack 清台账<b>之前</b>取到（{@link EntityTickObserver} 固化）。 */
    @Test
    void observerKeepsPreviousTickSnapshotAcrossPack() {
        FakeWorld world = new FakeWorld();
        FakeEntity inactive = world.add(1);
        inactive.inactive = true;

        EntityTickObserver observer = new EntityTickObserver(FAKE_PROBE);
        observer.tick(1, world);
        assertEquals(1, observer.ledger().count(SkipReason.SERVERCORE_INACTIVE));
        assertNotNull(observer.previousInactive());
        assertEquals(0, observer.previousInactive().size(), "第 1 tick 的『上一 tick』是空的");
        assertTrue(observer.previousMirror().size() == 0);

        observer.tick(2, world);
        assertEquals(1, observer.previousInactive().size(), "第 2 tick 能看到第 1 tick 的快照");
        assertTrue(observer.previousInactive().contains(1));
        assertEquals(1, observer.previousMirror().size());

        assertTrue(observer.reportLine().contains("servercore-inactive=1"));
    }
}
