package com.ayin90723.adventure_power.util;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

import java.lang.reflect.Constructor;

/**
 * {@code PersistentEntitySectionManager$Callback} 构造器反射工厂（v1.4.9 容器重建链）。
 * <p>
 * <b>为什么不用 @Invoker 构造器 mixin</b>：Mixin 注解处理器要求构造器 invoker 的返回
 * 类型精确为目标类本身——目标类是包私有内部类无法声明（{@code PersistentEntitySectionManager$Callback}
 * 对本模组包不可见），接口形态（返回 {@code EntityInLevelCallback}）编译期即被 AP 拒绝。
 * 改走反射构造器：构造器名 {@code <init>} 生产/开发双环境一致（构造器永不混淆），
 * 参数类引用（PESM/EntityAccess/long/EntitySection）是类型引用不受成员混淆影响，
 * {@code setAccessible(true)} 跨包访问包私有构造器（与 HealthUtil 反射
 * {@code SynchedEntityData$DataItem.value} 同款先例）。
 * <p>
 * 构造器为非 static 内部类形态：源码 3 参 {@code (T entity, long key, EntitySection section)}，
 * 字节码 4 参（首参为外部 {@code PersistentEntitySectionManager} 引用，javap 核实）——
 * 反射按 4 参声明。
 * <p>
 * 用途：容器审计发现 {@code Entity.levelCallback} 被拧成 NULL 或被换装（非本实体的
 * Callback）时，手工重建 Callback 对象装回——跨 section 移动的 onMove→updateStatus
 * 链依赖它（{@code ContainerRebuilder} 公共尾步）。
 */
public final class PesmCallbackFactory {

    /** 构造器缓存（volatile check-then-act 无锁 lazy init——唯一调用方在 ServerTick
     *  主线程，无竞态；失败重试语义靠每次调用重解析，静态预初始化会把 ClassNotFound
     *  变成 ExceptionInInitializerError 硬失败，不可取。复查修 P3-6 注释对齐）。 */
    private static volatile Constructor<?> CALLBACK_CTOR;

    private PesmCallbackFactory() {
    }

    /**
     * 构造 {@code PersistentEntitySectionManager$Callback}。
     *
     * @param pesm    外部 PersistentEntitySectionManager（内部类隐式首参）
     * @param entity  归属实体
     * @param key     section 坐标 long key（{@code SectionPos.asLong}）
     * @param section 实体所在 EntitySection
     * @return 新建的 Callback（以 {@code EntityInLevelCallback} 接口形态返回——目标类包私有）；
     *         反射初始化失败返回 null（调用方按重建失败处理）
     */
    public static EntityInLevelCallback create(PersistentEntitySectionManager<?> pesm, EntityAccess entity,
                                               long key, EntitySection<?> section) {
        try {
            Constructor<?> ctor = CALLBACK_CTOR;
            if (ctor == null) {
                Class<?> clz = Class.forName(
                    "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback");
                ctor = clz.getDeclaredConstructor(PersistentEntitySectionManager.class,
                    EntityAccess.class, long.class, EntitySection.class);
                ctor.setAccessible(true);
                CALLBACK_CTOR = ctor;
            }
            return (EntityInLevelCallback) ctor.newInstance(pesm, entity, key, section);
        } catch (Exception e) {
            return null;
        }
    }
}
