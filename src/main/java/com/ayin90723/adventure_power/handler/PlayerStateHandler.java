package com.ayin90723.adventure_power.handler;

import com.ayin90723.adventure_power.config.ModConfig;
import com.ayin90723.adventure_power.util.AbilityIds;
import com.ayin90723.adventure_power.AdventurePower;
import com.ayin90723.adventure_power.ability.ResilienceAbility;
import com.ayin90723.adventure_power.ability.AbilityRegistry;
import com.ayin90723.adventure_power.capability.AdventureProgressCapability;
import com.ayin90723.adventure_power.capability.IAdventureProgress;
import com.ayin90723.adventure_power.util.AbilityGate;
import com.ayin90723.adventure_power.util.PersistentDataKeys;
import com.ayin90723.adventure_power.util.ProgressCache;
import com.ayin90723.adventure_power.util.RejectHealthManipUtil;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 玩家状态类能力效果处理器。
 * <p>
 * 管理 4 种冒险能力的运行时效果：
 * <ul>
 *   <li>灵魂绑定 (soul_bind) — 死亡保 Buff + 经验</li>
 *   <li>净魂 (purified_soul) — 免疫负面效果</li>
 *   <li>翱翔 (soar) — 创造飞行</li>
 *   <li>受击坚韧 (resilience) — 受伤叠层减伤</li>
 * </ul>
 * <p>
 * 门禁检查：所有能力统一需要 isAdventurer() 或 isFullyUnlocked()，
 * 里程碑归属由 isAbilityEnabled() 内置硬门禁判定（解锁即用）。
 */
@Mod.EventBusSubscriber(modid = AdventurePower.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerStateHandler {

    // ========================================================================
    //  灵魂绑定 — persistentData 键
    // ========================================================================

    /** 死亡前保存的 Buff 列表 NBT 键 */
    private static final String SOUL_BIND_BUFFS_KEY = PersistentDataKeys.SOUL_BIND_BUFFS;

    /** 死亡前保存的经验值 NBT 键 */
    private static final String SOUL_BIND_EXP_KEY = PersistentDataKeys.SOUL_BIND_EXP;

    // ========================================================================
    //  不朽装备觉醒 — 属性加成
    // ========================================================================

    private static final UUID AWAKEN_UNDYING_ARMOR_UUID =
        UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID AWAKEN_UNDYING_WEAPON_UUID =
        UUID.fromString("b2c3d4e5-f6a7-8901-bcde-f12345678901");

    /** 庇护激活前的原始移动速度（恢复时还原，不覆盖其他模组修改） */
    private static final Map<UUID, Double> ORIGINAL_MOVE_SPEED = new HashMap<>();

    // ========================================================================
    //  1. 灵魂绑定 (SoulBind) — 死亡保 Buff + 经验
    // ========================================================================

    /**
     * 玩家死亡前保存正面效果和经验到 persistentData。
     * <p>
     * 优先级 LOWEST：让死亡抗拒（DeathDefy，HIGHEST）等可能取消死亡的逻辑先执行，
     * 仅在死亡确认不被取消时才保存与清零，避免"死亡被救却已清零经验、key 残留"的错乱。
     * LOWEST 为事件分发最末一级：非 receiveCanceled 的监听器在事件被取消时会被 Forge
     * 直接跳过（不调用），故本监听器执行时必然未被任何更早优先级取消——LOWEST 只是
     * 让本监听器相对其他 LOW/NORMAL 监听器更晚执行（它们读经验时尚未清零，语义更优）。
     * <p>
     * 清零经验等级防止死亡掉落经验球（原版 {@code LivingEntity.dropExperience} 对玩家亦生效，
     * 被玩家击杀时会掉落）。经验已先保存到 persistentData，重生时按精确值恢复，故清零不丢数据。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        // 死亡已被取消（如死亡抗拒 HIGHEST 救回）时不保存/清零：
        // 非 receiveCanceled 的监听器在事件被取消时会被 Forge 直接跳过（不调用本方法），
        // 本守卫为防御性冗余——确保觉醒分支不会把经验清零存入 NBT 而玩家实际存活
        // （经验永久丢失，无消费路径）。
        if (event.isCanceled()) return;

        AbilityGate.getActiveProgress(player, AbilityIds.SOUL_BIND).ifPresent(progress -> {
            // 只保存正面效果（与能力描述"保留正面效果"一致），负面效果随死亡清除
            CompoundTag buffsTag = new CompoundTag();
            ListTag effectList = new ListTag();
            for (MobEffectInstance effect : player.getActiveEffects()) {
                if (effect.getEffect().getCategory() == MobEffectCategory.BENEFICIAL) {
                    effectList.add(effect.save(new CompoundTag()));
                }
            }
            buffsTag.put(PersistentDataKeys.SOUL_BIND_EFFECTS, effectList);
            player.getPersistentData().put(SOUL_BIND_BUFFS_KEY, buffsTag);

            // 觉醒：额外保留经验（非觉醒掉经验，原版行为）
            if (progress.isFullyUnlocked()) {
                CompoundTag expTag = new CompoundTag();
                expTag.putInt(PersistentDataKeys.SOUL_BIND_EXP_LEVEL, player.experienceLevel);
                expTag.putFloat(PersistentDataKeys.SOUL_BIND_EXP_PROGRESS, player.experienceProgress);
                expTag.putInt(PersistentDataKeys.SOUL_BIND_EXP_TOTAL, player.totalExperience);
                player.getPersistentData().put(SOUL_BIND_EXP_KEY, expTag);

                // 清零经验等级防止死亡掉落经验球（否则重生恢复 + 捡经验球 = 双倍）。
                // 仅觉醒清零：非觉醒不保存经验，复活无经验恢复，掉经验球捡回=原版正常。
                player.experienceLevel = 0;
                player.experienceProgress = 0.0F;
                player.totalExperience = 0;
            }
        });
    }

    /**
     * 死亡重生后恢复保存的 Buff 和经验。
     * <p>
     * 从原始（死亡）玩家的 persistentData 中读取，写入新玩家。
     * 只处理死亡导致的 clone（维度切换等无保存数据，自然跳过）。
     * <p>
     * 非死亡 clone（末地返回传送门等）也需要转移 soul_bind 残留 key，
     * 并恢复翱翔飞行能力（维度切换后 mayfly 被重置）。
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player original = event.getOriginal();
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        if (event.isWasDeath()) {
            // 死亡 clone：恢复保存的正面 Buff 和经验
            CompoundTag buffsTag = original.getPersistentData().getCompound(SOUL_BIND_BUFFS_KEY);
            if (!buffsTag.isEmpty()) {
                ListTag effectList = buffsTag.getList(PersistentDataKeys.SOUL_BIND_EFFECTS, Tag.TAG_COMPOUND);
                for (int i = 0; i < effectList.size(); i++) {
                    MobEffectInstance effect = MobEffectInstance.load(effectList.getCompound(i));
                    if (effect != null) {
                        player.addEffect(effect);
                    }
                }
                original.getPersistentData().remove(SOUL_BIND_BUFFS_KEY);
            }

            // 精确恢复经验三字段（直接赋值，避免 giveExperiencePoints 的 increaseScore 副作用与 level 重算偏差）
            if (original.getPersistentData().contains(SOUL_BIND_EXP_KEY, Tag.TAG_COMPOUND)) {
                CompoundTag expTag = original.getPersistentData().getCompound(SOUL_BIND_EXP_KEY);
                player.experienceLevel = expTag.getInt(PersistentDataKeys.SOUL_BIND_EXP_LEVEL);
                player.experienceProgress = expTag.getFloat(PersistentDataKeys.SOUL_BIND_EXP_PROGRESS);
                player.totalExperience = expTag.getInt(PersistentDataKeys.SOUL_BIND_EXP_TOTAL);
                original.getPersistentData().remove(SOUL_BIND_EXP_KEY);

                // 防御性同步：显式发包确保客户端立即显示正确经验，
                // 兜住 respawn 时序或其他 mod 干扰导致的客户端显示不同步
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.connection.send(new ClientboundSetExperiencePacket(
                        player.experienceProgress, player.totalExperience, player.experienceLevel));
                }
            }
        } else {
            // 非死亡 clone（维度切换）：防御性清理 original 残留的 soul_bind key
            // （死亡写入但未消费，如被其他模组取消死亡事件后走维度切换）
            original.getPersistentData().remove(SOUL_BIND_BUFFS_KEY);
            original.getPersistentData().remove(SOUL_BIND_EXP_KEY);
        }

        // 转移 Buff 黑名单 + 首次发放标记：Forge Clone 仅自动复制 "PlayerPersisted" 子 key，
        // 根级自定义 key 死亡/维度切换都会丢失，需手动转移
        CompoundTag origPersistData = original.getPersistentData();
        if (origPersistData.contains(PersistentDataKeys.BUFF_BLACKLIST_KEY)) {
            player.getPersistentData().put(PersistentDataKeys.BUFF_BLACKLIST_KEY,
                origPersistData.getCompound(PersistentDataKeys.BUFF_BLACKLIST_KEY).copy());
        }
        if (origPersistData.contains(PersistentDataKeys.GOT_BEGIN_KEY)) {
            player.getPersistentData().putBoolean(PersistentDataKeys.GOT_BEGIN_KEY,
                origPersistData.getBoolean(PersistentDataKeys.GOT_BEGIN_KEY));
        }
        if (origPersistData.contains(PersistentDataKeys.VERIFIED_BEGIN_ITEM_KEY)) {
            player.getPersistentData().putBoolean(PersistentDataKeys.VERIFIED_BEGIN_ITEM_KEY,
                origPersistData.getBoolean(PersistentDataKeys.VERIFIED_BEGIN_ITEM_KEY));
        }

        // 维度切换后恢复翱翔飞行能力（mayfly 被重置）
        // 非死亡 clone（维度切换）时保留原有的飞行状态，防止飞行中穿越传送门后坠落
        boolean wasFlying = !event.isWasDeath() && original.getAbilities().flying;
        restoreSoarFlight(player, wasFlying);

        // Clone 时清理不朽装备觉醒属性（会由 tick 重新应用）
        removeUndyingGearAwakened(player);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerLoggedOutEvent event) {
        removeUndyingGearAwakened(event.getEntity());
        // 清理 ATTR_OWNER 中该玩家条目：玩家登出不触发 setRemoved，
        // 否则 value 强引用会阻止 ServerPlayer 被 GC（内存泄漏）
        RejectHealthManipUtil.clearOwner(event.getEntity());
        // 不清理 ORIGINAL_MOVE_SPEED：庇护期间登出后,重登时 tickSanctuarySpeed（门禁前执行）
        // 的 else 分支需 remove 恢复原始速度
    }

    // ========================================================================
    //  觉醒玩家名 — 前缀 + 金色（聊天/Tab 列表/头顶名牌/击杀消息全走 getDisplayName()）
    // ========================================================================

    /**
     * 全部里程碑达成（持有冒险的终点）的玩家，显示名加金色称号前缀。
     * Forge 1.20.1 的 Player.getDisplayName() 经 ForgeHooks.getDisplayName post 本事件：
     * 服务端（聊天广播/玩家列表/死亡消息）与客户端（头顶名牌渲染）均触发，两端一致显示。
     * 每次事件传入的 displayname 都是原始名字（getName()），前缀不叠加；
     * ProgressCache 按 tick 缓存 progress，避免名牌每帧渲染时重复 resolve。
     * 前缀内容由 lang 键 name.adventure_power.awakened_prefix 控制（zh/en 可自定义，如 [觉醒冒险者]）。
     * <p>
     * 优先级 HIGH：保证先于多数叠加式模组（权限/称号插件，NORMAL/LOW）执行——
     * 它们在当前 displayname 上追加时保留本模组前缀；完全覆盖式的模组
     * （setDisplayname 全新组件丢弃原值）无法防御，属事件机制固有边界。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onNameFormat(net.minecraftforge.event.entity.player.PlayerEvent.NameFormat event) {
        Player player = event.getEntity();
        IAdventureProgress progress = ProgressCache.get(player);
        if (progress == null) return;
        if (!progress.isFullyUnlocked()) return;
        // 称号前缀 + 整体金色（copy 防修改原 Component；先 withStyle 让前缀继承金色）
        event.setDisplayname(Component.translatable("name.adventure_power.awakened_prefix")
            .append(event.getDisplayname().copy().withStyle(ChatFormatting.GOLD))
            .withStyle(ChatFormatting.GOLD));
    }

    /**
     * 维度切换后强制重发客户端重建即丢的状态（v1.4.0 修复翱翔失效 + 夜视同步慢）。
     * <p>
     * <b>根因</b>：三条维度切换路径的发包行为不同（字节码核实）——
     * <ul>
     *   <li>传送门 {@code ServerPlayer.changeDimension}（m_5489_）：无条件重发
     *       {@code ClientboundPlayerAbilitiesPacket} ✓ + 重发全部活跃效果包
     *       （getActiveEffects + ClientboundUpdateMobEffectPacket 循环）✓</li>
     *   <li>死亡重生 {@code PlayerList.respawn}（m_11289_）：重发 abilities ✓
     *       （另有 {@code restoreSoarFlight} 兜底；效果重生后由 onTick 立即重施加）</li>
     *   <li><b>末地出口 {@code ServerPlayer.teleportTo}（m_8999_，六参——跨维度
     *       /execute in run tp 同走此方法）：不重发 abilities ✗ 也不重发效果包 ✗</b>——
     *       客户端收 {@code ClientboundRespawnPacket} 后重建 LocalPlayer（abilities
     *       重置默认 mayfly=false、效果表为空），服务端实体未变（mayfly 仍 true、
     *       夜视实例还活着且剩余时长未低于刷新阈值 400 tick 不会补发）→ 客户端
     *       "翱翔失效"（按空格无反应）+ "夜视丢失最长 ~100 秒"直到下次同步</li>
     * </ul>
     * 本监听挂 {@code PlayerChangedDimensionEvent}（传送门与末地出口两路径均 fire，
     * 已字节码验证）：翱翔玩家无条件重发当前 abilities；全视之眼玩家无条件重发夜视。
     */
    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (progress.isAbilityEnabled(AbilityIds.SOAR)) {
                player.onUpdateAbilities();
            }
            if (progress.isAbilityEnabled(AbilityIds.ALL_SEEING)) {
                AllSeeingHandler.resendNightVision(player);
            }
        });
    }

    /** 游戏模式切换后恢复翱翔飞行能力（原版会在切回生存时重置 mayfly）。
     *  立即同步，不等 tick handler，避免竞态条件。 */
    @SubscribeEvent
    public static void onPlayerChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        var progressOpt = AdventureProgressCapability.getAdventureProgress(player);
        if (progressOpt.isEmpty()) return;
        var progress = progressOpt.get();

        if (progress.isAbilityEnabled(AbilityIds.SOAR) && !player.getAbilities().mayfly
            && !player.getAbilities().instabuild && !player.isSpectator()) {
            player.getAbilities().mayfly = true;
            progress.setSoarGrantedFlight(true);
            // 创造飞行中退回生存：自动开启 flying 防坠落（地面保留双击空格触发）
            if (!player.onGround() && !player.getAbilities().flying) {
                player.getAbilities().flying = true;
            }
            player.onUpdateAbilities();
        }
    }

    /** 翱翔能力开启时恢复飞行许可（维度切换后 Player.Abilities 被重置）。
     *  @param restoreFlying 是否同时恢复 flying 标志（维度切换时保留飞行中状态） */
    private static void restoreSoarFlight(Player player, boolean restoreFlying) {
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (progress.isAbilityEnabled(AbilityIds.SOAR)) {
                boolean changed = false;
                if (!player.getAbilities().mayfly) {
                    player.getAbilities().mayfly = true;
                    progress.setSoarGrantedFlight(true);
                    changed = true;
                }
                if (restoreFlying && !player.getAbilities().flying) {
                    player.getAbilities().flying = true;
                    changed = true;
                }
                if (changed) {
                    player.onUpdateAbilities();
                }
            }
        });
    }

    /**
     * 翱翔能力开关时立即同步 mayfly 状态（由 AbilityTogglePacket 调用，不等下一 tick）。
     * 业务逻辑从网络层抽出至此，保持网络包只做转发。
     * @param enabled true=启用翱翔（授 mayfly），false=禁用（回收 mayfly）
     */
    public static void applySoarState(Player player, boolean enabled) {
        if (player.level().isClientSide()) return;
        AdventureProgressCapability.getAdventureProgress(player).ifPresent(progress -> {
            if (enabled) {
                if (!player.getAbilities().mayfly && !player.getAbilities().instabuild
                    && !player.isSpectator()) {
                    player.getAbilities().mayfly = true;
                    // 同步标记：与 tick 对账授予一致，防同 tick 连点 toggle（off→on→off）时回收判断失真
                    progress.setSoarGrantedFlight(true);
                    player.onUpdateAbilities();
                }
            } else {
                // 与 tick 对账路径一致的精准回收：仅回收翱翔自己授予的飞行
                // （soarGrantedFlight 标记），不没收装备鞘翅环/其他模组提供的飞行
                if (player.getAbilities().mayfly && progress.isSoarGrantedFlight()
                    && !player.getAbilities().instabuild && !player.isSpectator()) {
                    player.getAbilities().mayfly = false;
                    player.getAbilities().flying = false;
                    player.getAbilities().setFlyingSpeed(0.05F);  // 与 tick 回收路径一致，重置飞行速度
                    player.onUpdateAbilities();
                }
                // 无条件清标记（与 tick 回收路径一致）：关闭时即使没在飞也不残留，
                // 防后续其他来源授予的 mayfly 被误判为翱翔给的而没收
                if (progress.isSoarGrantedFlight()) {
                    progress.setSoarGrantedFlight(false);
                }
            }
        });
    }

    // ========================================================================
    //  环境免疫 (EnvImmunity) — 免疫所有环境伤害
    // ========================================================================

    /**
     * 拦截所有环境伤害（火焰/岩浆/仙人掌/冰冻/溺水/摔落/闪电/钟乳石/甜浆果等）。
     * <p>
     * 使用 DamageTypeTags 判断伤害类型，覆盖原版全部环境伤害。
     * 生物攻击（mob_attack / player_attack / arrow 等）不受影响。
     */
    @SubscribeEvent
    public static void onEnvDamage(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AbilityGate.getActiveProgress(player, AbilityIds.ENV_IMMUNITY).ifPresent(progress -> {
            DamageSource source = event.getSource();

            // 觉醒：免疫所有无源伤害（不仅是标签覆盖的环境伤害）
            if (progress.isFullyUnlocked()
                && source.getEntity() == null
                && source.getDirectEntity() == null) {
                event.setCanceled(true);
                return;
            }

            // 非觉醒：仅排除生物造成的伤害（即使附带火焰/魔法属性也不拦截，那是战斗伤害）
            if (source.getEntity() != null) return;

            // 拦截所有标记为非生物的环境伤害
            if (source.is(DamageTypeTags.IS_FIRE)
                || source.is(DamageTypeTags.IS_FREEZING)
                || source.is(DamageTypeTags.IS_LIGHTNING)
                || source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypeTags.IS_DROWNING)) {
                event.setCanceled(true);
                return;
            }

            // 拦截标签未覆盖的环境伤害类型
            String msgId = source.getMsgId();
            if ("cactus".equals(msgId)
                || "sweetBerryBush".equals(msgId)
                || "stalagmite".equals(msgId)
                || "inWall".equals(msgId)
                || "hotFloor".equals(msgId)
                || "starve".equals(msgId)
                || "wither".equals(msgId)
                || "dryOut".equals(msgId)) {
                event.setCanceled(true);
            }
        });
    }

    // ========================================================================
    //  1.5 旅者庇护 (Sanctuary) — 无敌期伤害拦截
    // ========================================================================

    /**
     * 庇护无敌：无敌期内取消所有攻击事件。
     * <p>
     * HIGHEST 优先级——在伤害处理中优先于其他防御（灵巧闪避/伤害抗性等）。
     * v1.4.0 预更新：取消 BYPASSES_INVULNERABILITY 豁免（原与原版无敌语义一致
     * 放行 /kill、虚空等穿透无敌伤害）——庇护无敌期为绝对无敌，与真血体系的
     * kill()/die() 拦截语义一致。注：/kill 指令走 kill()→die() 不经攻击事件，
     * 该事件覆盖不到，由 TrueHealthMixin kill() 拦截兜底。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onSanctuaryDamage(LivingAttackEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;

        AbilityGate.getActiveProgress(player, AbilityIds.ACTIVE_SKILL).ifPresent(progress -> {
            if (!progress.isSanctuaryInvulnerable(player.level().getGameTime())) return;
            event.setCanceled(true);
        });
    }

    // ========================================================================
    //  2. 净魂 (PurifiedSoul) — 免疫负面效果
    // ========================================================================

    /**
     * 净魂通过 {@link #onTick} 中每 tick 清除残留负面效果实现。
     * <p>
     * 不再使用 {@code MobEffectEvent.Applicable}——该事件在 Forge 1.20.1 中不可取消，
     * RevelationFix 等模组的 Mixin 会对其 {@code setCanceled()} 抛出
     * {@link UnsupportedOperationException}。
     * tick 兜底方案延迟最多 1 tick，玩家无感知。
     */

    // ========================================================================
    //  3 & 4. 翱翔 (Soar) + 净魂兜底 + 受击坚韧兜底 — 每 Tick
    // ========================================================================

    /**
     * 每 tick 处理：
     * <ul>
     *   <li>净魂：清除残留的负面效果（兜底，覆盖 Applicable 未捕获的路径）</li>
     *   <li>翱翔：维持 mayfly = true（死亡/维度切换后重置），能力关闭时回收</li>
     * </ul>
     * <p>
     * 受击坚韧的 tick 逻辑（超时归零）已在
     * {@link PlayerTickHandler#onTick} 中处理，此处不重复。
     */
    /** 门禁后业务（由 PlayerTickDispatcher 调用）：净魂 / 翱翔 / 不朽装备觉醒 / 庇护移动速度 */
    public static void onTick(Player player, IAdventureProgress progress) {

        // ---- 净魂兜底 + 觉醒虚弱光环 ----
        if (progress.isAbilityEnabled(AbilityIds.PURIFIED_SOUL)) {
            // 先快速判断有无有害效果，无则跳过（大多数 tick 玩家无负面效果），
            // 有则收集到独立 list 再 remove（避免遍历 activeEffects 视图时修改触发 CME）
            List<MobEffect> toRemove = null;
            for (MobEffectInstance e : player.getActiveEffects()) {
                if (e.getEffect().getCategory() == MobEffectCategory.HARMFUL) {
                    if (toRemove == null) toRemove = new ArrayList<>();
                    toRemove.add(e.getEffect());
                }
            }
            if (toRemove != null) {
                for (MobEffect effect : toRemove) {
                    // 绕过 Remove 事件强制清除（见 removeHarmfulBypassEvents 注释）：
                    // 整合包其他模组取消事件时 removeEffect 会静默失败，负面效果清不掉
                    removeHarmfulBypassEvents(player, effect);
                }
            }

            // 觉醒：周期性给周围敌对生物施加虚弱光环（等级/时长由配置控制）
            if (progress.isFullyUnlocked()
                && (player.level().getGameTime() + player.getId()) % ModConfig.AWAKEN_PURIFIED_SOUL_AURA_INTERVAL.get() == 0) {
                int radius = ModConfig.AWAKEN_PURIFIED_SOUL_RADIUS.get();
                int weaknessAmp = ModConfig.AWAKEN_PURIFIED_SOUL_WEAKNESS_AMPLIFIER.get();
                int weaknessDur = ModConfig.AWAKEN_PURIFIED_SOUL_WEAKNESS_DURATION.get();
                AABB aabb = player.getBoundingBox().inflate(radius);
                // 直接按 Monster 类型收集（v1.4.0 审查优化）：原先拉取半径内全部
                // LivingEntity 再 instanceof 过滤，与 SwiftHandler 的同款扫描对齐
                //（Player 不实现 Monster 接口，无需再排除自身）
                List<net.minecraft.world.entity.monster.Monster> targets = player.level()
                    .getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, aabb,
                        net.minecraft.world.entity.monster.Monster::isAlive);
                // 刷新余量：时长的 60%，且至少覆盖到下一次施加（避免配置短时长时断档）
                int refreshThreshold = Math.min(weaknessDur * 3 / 5,
                    ModConfig.AWAKEN_PURIFIED_SOUL_AURA_INTERVAL.get());
                for (net.minecraft.world.entity.monster.Monster target : targets) {
                    MobEffectInstance existing = target.getEffect(MobEffects.WEAKNESS);
                    if (existing == null || existing.getAmplifier() < weaknessAmp
                        || existing.getDuration() < refreshThreshold) {
                        target.addEffect(new MobEffectInstance(
                            MobEffects.WEAKNESS, weaknessDur, weaknessAmp,
                            false, false, true));
                    }
                }
            }
        }

        // ---- 翱翔 ----
        if (progress.isAbilityEnabled(AbilityIds.SOAR)) {
            // 守卫与其余三处授予路径（changeGameMode/restoreSoarFlight/applySoarState）一致：
            // 创造/旁观模式已有 mayfly 或不应被授予，避免标记污染
            if (!player.getAbilities().mayfly && !player.getAbilities().instabuild
                && !player.isSpectator()) {
                player.getAbilities().mayfly = true;
                // 不自动开启 flying，让玩家自己双击空格
                player.onUpdateAbilities();
            }
            // 标记飞行由翱翔授予，用于关闭时精准回收
            progress.setSoarGrantedFlight(true);
            // 觉醒：飞行速度 +50%——仅觉醒覆盖（非觉醒保持原版 0.05，不覆盖其他模组对
            // flyingSpeed 的设定）；写入后同步客户端（setFlyingSpeed 不会自动发包，
            // 客户端 LocalPlayer 保持旧速度直到下次 abilities 同步）
            if (progress.isFullyUnlocked()) {
                double targetSpeed = 0.05 * ModConfig.AWAKEN_SOAR_SPEED.get();
                if (Math.abs(player.getAbilities().getFlyingSpeed() - targetSpeed) > 0.0001) {
                    player.getAbilities().setFlyingSpeed((float) targetSpeed);
                    player.onUpdateAbilities();
                }
            }
        } else {
            // 能力关闭/未解锁时回收翱翔授予的飞行，不没收装备或其他模组提供的飞行
            if (player.getAbilities().mayfly && progress.isSoarGrantedFlight()
                && !player.getAbilities().instabuild && !player.isSpectator()) {
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                player.getAbilities().setFlyingSpeed(0.05F);  // 恢复原版飞行速度
                player.onUpdateAbilities();
            }
            // 无条件清标记：关闭时即使没在飞（mayfly=false 使回收分支不执行）也不残留，
            // 否则之后其他来源（套装等）授予 mayfly 时会被本分支误判为翱翔给的而没收
            if (progress.isSoarGrantedFlight()) {
                progress.setSoarGrantedFlight(false);
            }
        }

        // ---- 不朽装备觉醒：属性加成 ----
        if (progress.isAbilityEnabled(AbilityIds.UNDYING_GEAR) && progress.isFullyUnlocked()) {
            applyUndyingGearAwakened(player);
        } else {
            removeUndyingGearAwakened(player);
        }

        // 旅者庇护速度维护在 tickSanctuarySpeed（由 PlayerTickDispatcher 在门禁前调用，
        // 恢复分支不能受 adventurer 门禁限制，见该方法注释）
    }

    /**
     * 旅者庇护移动速度维护：非觉醒锁定移动（base=0），觉醒减速移动。
     * <p>
     * <b>必须由 {@link PlayerTickDispatcher} 在 adventurer 门禁之前调用</b>（v1.4.0 审查修复）：
     * 玩家在庇护激活的数秒窗口内卸下冒险饰品后 adventurer/fullyUnlocked 均失效，
     * 若本逻辑只在门禁后执行，base=0 的恢复分支永不可达 → 移动速度永久锁死，
     * 直到重新佩戴饰品。门禁前调用保证 else 恢复分支对任何持有 progress 的玩家始终可达
     * （未激活玩家 inSanctuary 恒 false，仅做 O(1) 的残留查询，无副作用）。
     */
    static void tickSanctuarySpeed(Player player, IAdventureProgress progress) {
        long sanctuaryNow = player.level().getGameTime();
        boolean inSanctuary = progress.getSanctuaryInvulEnd() > sanctuaryNow
            && progress.isAbilityEnabled(AbilityIds.ACTIVE_SKILL);
        var sanctuarySpeedAttr = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (inSanctuary && sanctuarySpeedAttr != null) {
            double target = progress.isFullyUnlocked()
                ? 0.1 * ModConfig.AWAKEN_SANCTUARY_SPEED.get()
                : 0.0;
            if (Math.abs(sanctuarySpeedAttr.getBaseValue() - target) > 0.001) {
                // 首次激活：记录原始移动速度（恢复时还原，不覆盖其他模组修改）
                ORIGINAL_MOVE_SPEED.putIfAbsent(player.getUUID(), sanctuarySpeedAttr.getBaseValue());
                sanctuarySpeedAttr.setBaseValue(target);
            }
        } else if (!inSanctuary && sanctuarySpeedAttr != null) {
            Double original = ORIGINAL_MOVE_SPEED.remove(player.getUUID());
            if (original != null) {
                sanctuarySpeedAttr.setBaseValue(original);
            } else {
                // 无记录（服务端重启后静态 Map 清空，而 player.dat 可能已落盘庇护目标值）：
                // 残留判定——当前值 ≈ 本模组庇护写入值（0 或 0.1×配置，均远低于原版 0.1）
                // 则判定为本模组残留，回归原版默认 0.1；否则视为其他模组的修改，不覆盖。
                // 与 ExplorationAbilityHandler 的 maxHealth/reach 残留判定同模式。
                double sanctuaryTarget = progress.isFullyUnlocked()
                    ? 0.1 * ModConfig.AWAKEN_SANCTUARY_SPEED.get()
                    : 0.0;
                if (Math.abs(sanctuarySpeedAttr.getBaseValue() - sanctuaryTarget) <= 0.001) {
                    sanctuarySpeedAttr.setBaseValue(0.1);
                }
            }
        }
    }

    // ========================================================================
    //  4. 受击坚韧 (Resilience) — 受伤叠层 + 减伤
    // ========================================================================

    /**
     * 净魂强制移除负面效果 —— 绕过可被取消的 {@code MobEffectEvent.Remove} 事件。
     * <p>
     * 整合包中部分模组会取消效果移除/过期事件（自然到期的效果走同样可取消的
     * {@code MobEffectEvent.Expired}，被取消后会以 0 时长永久残留在效果表，
     * 客户端显示 0:00 且牛奶 / effect clear 均无法清除），导致
     * {@code player.removeEffect()} 静默失败、负面效果（如凋零）清不掉。
     * 净魂语义为"免疫所有负面效果"，理应最强：走 {@code removeEffectNoUpdate}
     * 直删效果表 + 反射调用 {@code onEffectRemoved}（SRG {@code m_7285_}，虚方法
     * 多态到 ServerPlayer 覆写）——与原版 removeEffect 的差异仅为不经过可被
     * 取消的 Remove 事件，其余副作用（属性修饰符清除 / 自身与乘客的客户端
     * 移除包 / EFFECTS_CHANGED 触发器）完整保留。
     */
    private static void removeHarmfulBypassEvents(Player player, MobEffect effect) {
        MobEffectInstance removed = player.removeEffectNoUpdate(effect);
        if (removed == null) return;
        try {
            if (EFFECT_REMOVED_METHOD == null) {
                try {
                    EFFECT_REMOVED_METHOD = LivingEntity.class.getDeclaredMethod("m_7285_", MobEffectInstance.class);
                } catch (NoSuchMethodException e) {
                    EFFECT_REMOVED_METHOD = LivingEntity.class.getDeclaredMethod("onEffectRemoved", MobEffectInstance.class);
                }
                EFFECT_REMOVED_METHOD.setAccessible(true);
            }
            EFFECT_REMOVED_METHOD.invoke(player, removed);
        } catch (Exception e) {
            // 效果已从效果表删除（removeEffectNoUpdate 先行），反射仅负责副作用同步；
            // 失败时客户端可能残留旧图标，重启自清
            LOGGER.error("[净魂] 强制移除 {} 时反射 onEffectRemoved 失败", effect, e);
        }
    }

    /** LivingEntity.onEffectRemoved（SRG m_7285_，MCP 回退 onEffectRemoved）反射缓存 */
    private static java.lang.reflect.Method EFFECT_REMOVED_METHOD;

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    /**
     * 玩家受伤时：
     * <ol>
     *   <li>基于已有层数按比例减伤（每层 5%）</li>
     *   <li>叠层 + 1（上限由 {@link AbilityRegistry#get} 的 value 决定）</li>
     *   <li>更新最后受伤时间戳</li>
     * </ol>
     * <p>
     * 优先级 LOW 以确保在大多数伤害修改之后执行，拿到最终伤害值。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        // 已被更高优先级取消的伤害（未实际掉血）不叠层、不减伤——白叠层是免费收益
        if (event.isCanceled()) return;

        AbilityGate.getActiveProgress(player, AbilityIds.RESILIENCE).ifPresent(progress -> {
            long currentTime = player.level().getGameTime();

            // 基于已有层数减伤
            int stacks = progress.getResilienceStacks();
            if (stacks > 0) {
                float reduction = stacks * ModConfig.RESILIENCE_DAMAGE_REDUCTION_PER_STACK.get().floatValue();
                float newAmount = event.getAmount() * (1.0F - reduction);
                event.setAmount(Math.max(newAmount, 0.0F));
            }

            // 叠层（上限由能力里程碑配置决定，觉醒 +6）
            // 判空 + instanceof：与 CombatAbilityHandler 淬魂的标准模式一致，
            // 避免注册表/数据包异常时此处成为全模组唯一 NPE/CCE 路径
            int maxStacks = 0;
            var resilienceAbility = AbilityRegistry.get(AbilityIds.RESILIENCE);
            if (resilienceAbility instanceof ResilienceAbility ra) {
                maxStacks = (int) ra.value(AbilityGate.effectiveCount(progress, AbilityIds.RESILIENCE), progress.isFullyUnlocked());
            }
            if (stacks < maxStacks) {
                progress.setResilienceStacks(stacks + 1);
            }

            // 更新时间戳（供 PlayerTickHandler.onTick 超时归零使用）
            progress.setLastHurtTime(currentTime);
        });
    }

    // ========================================================================
    //  不朽装备觉醒 — 属性加成
    // ========================================================================

    private static void applyUndyingGearAwakened(Player player) {
        var armorAttr = player.getAttribute(Attributes.ARMOR);
        if (armorAttr != null) {
            var existing = armorAttr.getModifier(AWAKEN_UNDYING_ARMOR_UUID);
            // count equipped armor pieces
            int pieces = 0;
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot.getType() == EquipmentSlot.Type.ARMOR
                    && !player.getItemBySlot(slot).isEmpty()) {
                    pieces++;
                }
            }
            double bonus = pieces * ModConfig.AWAKEN_UNDYING_ARMOR_BONUS.get();
            // 注意：值未变时不能 return——武器加成分支仍需检查
            // （否则武器 modifier 被外部移除/配置热重载后永不补挂，直到穿脱甲或 Clone）
            if (existing != null && Math.abs(existing.getAmount() - bonus) <= 0.001) {
                // value unchanged，跳过重挂
            } else {
                if (existing != null) {
                    armorAttr.removeModifier(AWAKEN_UNDYING_ARMOR_UUID);
                }
                armorAttr.addPermanentModifier(new AttributeModifier(
                    AWAKEN_UNDYING_ARMOR_UUID, "awakened_undying_armor", bonus,
                    AttributeModifier.Operation.ADDITION));
            }
        }

        var atkAttr = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atkAttr != null) {
            var existing = atkAttr.getModifier(AWAKEN_UNDYING_WEAPON_UUID);
            double weaponBonus = ModConfig.AWAKEN_UNDYING_WEAPON_BONUS.get();
            if (existing != null && Math.abs(existing.getAmount() - weaponBonus) <= 0.001) {
                return;
            }
            if (existing != null) {
                atkAttr.removeModifier(AWAKEN_UNDYING_WEAPON_UUID);
            }
            atkAttr.addPermanentModifier(new AttributeModifier(
                AWAKEN_UNDYING_WEAPON_UUID, "awakened_undying_weapon", weaponBonus,
                AttributeModifier.Operation.MULTIPLY_BASE));
        }
    }

    private static void removeUndyingGearAwakened(Player player) {
        var armorAttr = player.getAttribute(Attributes.ARMOR);
        if (armorAttr != null && armorAttr.getModifier(AWAKEN_UNDYING_ARMOR_UUID) != null) {
            armorAttr.removeModifier(AWAKEN_UNDYING_ARMOR_UUID);
        }
        var atkAttr = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atkAttr != null && atkAttr.getModifier(AWAKEN_UNDYING_WEAPON_UUID) != null) {
            atkAttr.removeModifier(AWAKEN_UNDYING_WEAPON_UUID);
        }
    }
}
