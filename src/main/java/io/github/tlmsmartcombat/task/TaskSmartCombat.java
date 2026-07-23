package io.github.tlmsmartcombat.task;

import com.github.tartaricacid.touhoulittlemaid.api.task.FunctionCallSwitchResult;
import com.github.tartaricacid.touhoulittlemaid.api.task.IRangedAttackTask;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.MaidConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidAttackStrafingTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidAttackTridentTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidCrossbowAttack;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidTridentTargetTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitSounds;
import com.github.tartaricacid.touhoulittlemaid.util.ItemsUtil;
import com.github.tartaricacid.touhoulittlemaid.util.SoundUtil;
import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;
import io.github.tlmsmartcombat.TlmSmartCombat;
import io.github.tlmsmartcombat.ai.SmartBowAttackTask;
import io.github.tlmsmartcombat.ai.SmartCombatMoveTask;
import io.github.tlmsmartcombat.ai.SmartEquipBehavior;
import io.github.tlmsmartcombat.ai.SmartMeleeAttackTask;
import io.github.tlmsmartcombat.ai.SmartShieldTask;
import io.github.tlmsmartcombat.compat.SlashBladeCompat;
import io.github.tlmsmartcombat.compat.TruePowerCompat;
import io.github.tlmsmartcombat.strategy.EquipOptimizer;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.StartAttacking;
import net.minecraft.world.entity.ai.behavior.StopAttackingIfTargetInvalid;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 智能战斗任务。
 * <p>
 * 与原版“战斗 / 弓 / 弩 / 三叉戟”等单一武器任务不同，本任务：
 * <ol>
 *     <li>索敌时在可攻击的敌对生物中优先选择<b>当前生命值最高</b>的目标；</li>
 *     <li>由 {@link SmartEquipBehavior} 周期性评估背包中的武器与盔甲，
 *     自动为主手选择对当前目标 DPS 最优的武器（近战 / 拔刀剑 / 弓 / 弩 / 三叉戟），
 *     为副手选择盾牌或图腾，并穿戴最优盔甲；</li>
 *     <li>移动与攻击行为根据主手武器形态自动在近战贴身与远程走位间切换；</li>
 *     <li>主手为拔刀剑时：若安装了 True POWER of Maid，则由其拔刀剑战斗 AI
 *     （连击 / 幻影剑 / 瞬步）接管攻击与移动；否则回退为 TLM 原版近战 AI。</li>
 * </ol>
 */
public class TaskSmartCombat implements IRangedAttackTask {
    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(TlmSmartCombat.MOD_ID, "smart_combat");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return Items.NETHERITE_SWORD.getDefaultInstance();
    }

    @Nullable
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return SoundUtil.attackSound(maid, InitSounds.MAID_ATTACK.get(), 0.5f);
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        BehaviorControl<EntityMaid> equipTask = new SmartEquipBehavior();
        BehaviorControl<EntityMaid> supplementedTask = StartAttacking.create(this::hasUsableWeapon, this::findHighestHealthTarget);
        BehaviorControl<EntityMaid> findTargetTask = StopAttackingIfTargetInvalid.create(target -> !hasUsableWeapon(maid) || farAway(target, maid));
        BehaviorControl<EntityMaid> moveToTargetTask = new SmartCombatMoveTask(0.6f);
        BehaviorControl<EntityMaid> meleeAttackTask = SmartMeleeAttackTask.create(20);
        BehaviorControl<EntityMaid> bowAttackTask = new SmartBowAttackTask();
        BehaviorControl<EntityMaid> crossbowAttackTask = new MaidCrossbowAttack();
        BehaviorControl<EntityMaid> tridentAttackTask = new MaidTridentTargetTask();
        BehaviorControl<EntityMaid> strafingTask = new MaidAttackStrafingTask();
        BehaviorControl<EntityMaid> tridentStrafingTask = new MaidAttackTridentTask();
        BehaviorControl<EntityMaid> shieldTask = new SmartShieldTask();

        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> tasks = Lists.newArrayList(
                Pair.of(4, equipTask),
                Pair.of(5, supplementedTask),
                Pair.of(5, findTargetTask),
                Pair.of(5, moveToTargetTask),
                Pair.of(5, meleeAttackTask),
                Pair.of(5, bowAttackTask),
                Pair.of(5, crossbowAttackTask),
                Pair.of(5, tridentAttackTask),
                Pair.of(5, strafingTask),
                Pair.of(5, tridentStrafingTask),
                Pair.of(5, shieldTask)
        );
        TruePowerCompat.addSlashBladeTasks(tasks);
        return tasks;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        BehaviorControl<EntityMaid> equipTask = new SmartEquipBehavior();
        BehaviorControl<EntityMaid> supplementedTask = StartAttacking.create(this::hasUsableWeapon, this::findHighestHealthTarget);
        BehaviorControl<EntityMaid> findTargetTask = StopAttackingIfTargetInvalid.create(target -> !hasUsableWeapon(maid) || farAway(target, maid));
        BehaviorControl<EntityMaid> meleeAttackTask = SmartMeleeAttackTask.create(20);
        BehaviorControl<EntityMaid> bowAttackTask = new SmartBowAttackTask();
        BehaviorControl<EntityMaid> crossbowAttackTask = new MaidCrossbowAttack();
        BehaviorControl<EntityMaid> tridentAttackTask = new MaidTridentTargetTask();

        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> tasks = Lists.newArrayList(
                Pair.of(4, equipTask),
                Pair.of(5, supplementedTask),
                Pair.of(5, findTargetTask),
                Pair.of(5, meleeAttackTask),
                Pair.of(5, bowAttackTask),
                Pair.of(5, crossbowAttackTask),
                Pair.of(5, tridentAttackTask)
        );
        TruePowerCompat.addSlashBladeTasks(tasks);
        return tasks;
    }

    // ------------------------------------------------------------------
    // 索敌：优先锁定生命值最高的敌对生物
    // ------------------------------------------------------------------

    /**
     * 在记忆的可攻击生物中，挑选当前生命值最高者作为攻击目标。
     * 生命值相同时依次比较最大生命值与距离（更近者优先）。
     */
    private Optional<LivingEntity> findHighestHealthTarget(EntityMaid maid) {
        var memory = maid.getBrain().getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES);
        if (memory.isEmpty()) {
            return Optional.empty();
        }
        LivingEntity best = null;
        for (LivingEntity candidate : memory.get()) {
            if (!candidate.isAlive()) {
                continue;
            }
            if (!maid.canAttack(candidate)) {
                continue;
            }
            if (!maid.isWithinRestriction(candidate.blockPosition())) {
                continue;
            }
            if (!maid.canSee(candidate)) {
                continue;
            }
            if (farAway(candidate, maid)) {
                continue;
            }
            if (best == null || compareTargets(maid, candidate, best) > 0) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 目标价值比较：当前生命值 > 最大生命值 > 距离更近
     *
     * @return 正数表示 a 优于 b
     */
    private static int compareTargets(EntityMaid maid, LivingEntity a, LivingEntity b) {
        int cmp = Float.compare(a.getHealth(), b.getHealth());
        if (cmp != 0) {
            return cmp;
        }
        cmp = Float.compare(a.getMaxHealth(), b.getMaxHealth());
        if (cmp != 0) {
            return cmp;
        }
        return Double.compare(maid.distanceToSqr(b), maid.distanceToSqr(a));
    }

    private boolean hasUsableWeapon(EntityMaid maid) {
        return EquipOptimizer.isUsableWeapon(maid, maid.getMainHandItem());
    }

    private boolean farAway(LivingEntity target, EntityMaid maid) {
        if (!target.isAlive()) {
            return true;
        }
        // 远程武器按对应射程判定，近战武器按女仆工作范围判定
        if (isRangedWeapon(maid.getMainHandItem())) {
            return maid.distanceTo(target) > this.searchRadius(maid);
        }
        boolean homeMode = maid.isHomeModeEnable();
        float radius = maid.getRestrictRadius();
        // TPOM 接管拔刀剑时，其连击 / 瞬步的有效交战距离远大于普通近战
        if (TruePowerCompat.isTruePowerBladeActive(maid)) {
            radius *= 4;
        }
        if (!homeMode && maid.getOwner() != null) {
            return maid.getOwner().distanceTo(target) > radius;
        }
        return maid.distanceTo(target) > radius;
    }

    private static boolean isRangedWeapon(ItemStack stack) {
        return stack.getItem() instanceof ProjectileWeaponItem || stack.getItem() instanceof TridentItem;
    }

    // ------------------------------------------------------------------
    // 射程与视野
    // ------------------------------------------------------------------

    @Override
    public boolean canSee(EntityMaid maid, LivingEntity target) {
        return IRangedAttackTask.targetConditionsTest(maid, target, rangeConfig(maid));
    }

    @Override
    public AABB searchDimension(EntityMaid maid) {
        if (isRangedWeapon(maid.getMainHandItem())) {
            float searchRange = this.searchRadius(maid);
            if (maid.hasRestriction()) {
                return new AABB(maid.getRestrictCenter()).inflate(searchRange);
            } else {
                return maid.getBoundingBox().inflate(searchRange);
            }
        }
        return IRangedAttackTask.super.searchDimension(maid);
    }

    @Override
    public float searchRadius(EntityMaid maid) {
        ItemStack mainHand = maid.getMainHandItem();
        if (mainHand.getItem() instanceof CrossbowItem) {
            return MaidConfig.CROSS_BOW_RANGE.get();
        }
        if (mainHand.getItem() instanceof TridentItem) {
            return MaidConfig.TRIDENT_RANGE.get();
        }
        if (mainHand.getItem() instanceof ProjectileWeaponItem) {
            return MaidConfig.BOW_RANGE.get();
        }
        return IRangedAttackTask.super.searchRadius(maid);
    }

    private static ModConfigSpec.IntValue rangeConfig(EntityMaid maid) {
        ItemStack mainHand = maid.getMainHandItem();
        if (mainHand.getItem() instanceof CrossbowItem) {
            return MaidConfig.CROSS_BOW_RANGE;
        }
        if (mainHand.getItem() instanceof TridentItem) {
            return MaidConfig.TRIDENT_RANGE;
        }
        return MaidConfig.BOW_RANGE;
    }

    // ------------------------------------------------------------------
    // 远程攻击执行（由对应攻击行为回调）
    // ------------------------------------------------------------------

    @Override
    public void performRangedAttack(EntityMaid shooter, LivingEntity target, float distanceFactor) {
        ItemStack mainHand = shooter.getMainHandItem();
        if (mainHand.getItem() instanceof CrossbowItem) {
            shooter.performCrossbowAttack(shooter, 1.6F);
            return;
        }
        if (mainHand.getItem() instanceof TridentItem) {
            this.throwTrident(shooter, target);
            return;
        }
        if (mainHand.getItem() instanceof BowItem) {
            this.shootArrow(shooter, target, distanceFactor);
        }
    }

    /**
     * 弓箭射击，逻辑与 TLM 的 TaskBowAttack 一致：箭无视重力保证命中，
     * 伤害与女仆基础攻击力挂钩，支持无限附魔不消耗箭。
     */
    private void shootArrow(EntityMaid shooter, LivingEntity target, float distanceFactor) {
        AbstractArrow arrow = this.createArrow(shooter, distanceFactor);
        if (arrow == null) {
            return;
        }
        ItemStack mainHandItem = shooter.getMainHandItem();
        if (mainHandItem.getItem() instanceof BowItem) {
            double x = target.getX() - shooter.getX();
            double y = target.getEyeY() - shooter.getEyeY();
            double z = target.getZ() - shooter.getZ();
            float distance = shooter.distanceTo(target);
            float velocity = Mth.clamp(distance / 10f, 1.6f, 3.2f);
            float inaccuracy = 1 - Mth.clamp(distance / 100f, 0, 0.9f);
            arrow.setNoGravity(true);
            arrow.shoot(x, y, z, velocity, inaccuracy);
            mainHandItem.hurtAndBreak(1, shooter, EquipmentSlot.MAINHAND);
            shooter.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (shooter.getRandom().nextFloat() * 0.4F + 0.8F));
            shooter.level().addFreshEntity(arrow);
        }
    }

    @Nullable
    private AbstractArrow createArrow(EntityMaid maid, float chargeTime) {
        ItemStack mainHandItem = maid.getMainHandItem();
        if (!(mainHandItem.getItem() instanceof BowItem bowItem)) {
            return null;
        }
        CombinedInvWrapper handler = maid.getAvailableInv(true);
        int slot = ItemsUtil.findStackSlot(handler, bowItem.getAllSupportedProjectiles());
        if (slot < 0) {
            return null;
        }
        ItemStack arrowStack = handler.getStackInSlot(slot);
        // getMobArrow 内部已处理 customArrow 等钩子
        AbstractArrow arrow = ProjectileUtil.getMobArrow(maid, arrowStack, chargeTime, mainHandItem);

        // 无无限附魔时消耗一支箭，并允许回收
        if (enchantLevel(maid.level().registryAccess(), Enchantments.INFINITY, mainHandItem) <= 0) {
            arrowStack.shrink(1);
            handler.setStackInSlot(slot, arrowStack);
            arrow.pickup = AbstractArrow.Pickup.ALLOWED;
        }

        // 箭伤害与女仆基础攻击力挂钩（与 TLM 本体一致）
        AttributeInstance attackDamage = maid.getAttribute(Attributes.ATTACK_DAMAGE);
        double attackValue = 2.0;
        if (attackDamage != null) {
            attackValue = attackDamage.getBaseValue();
        }
        float multiplier = (float) (attackValue / 2.0f);
        arrow.setBaseDamage(arrow.getBaseDamage() * multiplier);
        return arrow;
    }

    /**
     * 三叉戟投掷，逻辑与 TLM 的 TaskTridentAttack 一致：
     * 掷出不含忠诚附魔的复制品（防止返回后无法拾取），本体保留并损耗耐久。
     */
    private void throwTrident(EntityMaid shooter, LivingEntity target) {
        ItemStack tridentItem = shooter.getMainHandItem().copy();

        Holder<Enchantment> loyalty = enchantHolder(shooter.level().registryAccess(), Enchantments.LOYALTY);
        if (loyalty != null && tridentItem.getEnchantments().getLevel(loyalty) > 0) {
            EnchantmentHelper.updateEnchantments(tridentItem, mutable -> mutable.set(loyalty, 0));
        }

        ThrownTrident thrownTrident = new ThrownTrident(shooter.level(), shooter, tridentItem);
        double x = target.getX() - shooter.getX();
        double y = target.getEyeY() - shooter.getEyeY();
        double z = target.getZ() - shooter.getZ();
        float distance = shooter.distanceTo(target);
        float velocity = Mth.clamp(distance / 10f, 1.6f, 3.2f);
        float inaccuracy = 1 - Mth.clamp(distance / 100f, 0, 0.9f);

        thrownTrident.setNoGravity(true);
        thrownTrident.shoot(x, y, z, velocity, inaccuracy);
        thrownTrident.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;

        shooter.getMainHandItem().hurtAndBreak(1, shooter, EquipmentSlot.MAINHAND);
        shooter.level().addFreshEntity(thrownTrident);
        shooter.playSound(SoundEvents.TRIDENT_THROW.value(), 1.0F, 1.0F);
    }

    // ------------------------------------------------------------------
    // 其他任务接口
    // ------------------------------------------------------------------

    @Override
    public List<Pair<String, Predicate<EntityMaid>>> getConditionDescription(EntityMaid maid) {
        return Lists.newArrayList(
                Pair.of("has_weapon", this::hasUsableWeapon),
                Pair.of("has_ammo", m -> {
                    ItemStack mainHand = m.getMainHandItem();
                    return !(mainHand.getItem() instanceof ProjectileWeaponItem)
                           || EquipOptimizer.isUsableWeapon(m, mainHand);
                })
        );
    }

    @Override
    public boolean isWeapon(EntityMaid maid, ItemStack stack) {
        return EquipOptimizer.isMeleeWeapon(stack)
               || SlashBladeCompat.isSlashBladeItem(stack)
               || stack.getItem() instanceof ProjectileWeaponItem
               || stack.getItem() instanceof TridentItem;
    }

    /**
     * 通过 Function Call（女仆 AI 聊天）切换到本任务时，立即执行一次装备优化
     */
    @Override
    public FunctionCallSwitchResult onFunctionCallSwitch(EntityMaid maid) {
        if (maid.level() instanceof ServerLevel serverLevel) {
            EquipOptimizer.optimize(maid, serverLevel);
        }
        return FunctionCallSwitchResult.OK;
    }

    @Override
    public String getMaidActionSummary() {
        return "Smart combat: focus the hostile mob with the highest health, " +
               "and automatically swap the main hand / off hand / armor to the best " +
               "equipment in the backpack for max DPS against the current target.";
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    @Nullable
    private static Holder<Enchantment> enchantHolder(RegistryAccess access, ResourceKey<Enchantment> key) {
        return access.registryOrThrow(Registries.ENCHANTMENT).getHolder(key).orElse(null);
    }

    private static int enchantLevel(RegistryAccess access, ResourceKey<Enchantment> key, ItemStack stack) {
        Holder<Enchantment> holder = enchantHolder(access, key);
        return holder == null ? 0 : stack.getEnchantments().getLevel(holder);
    }
}
