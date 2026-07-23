package io.github.tlmsmartcombat.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import io.github.tlmsmartcombat.compat.TruePowerCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;

import java.util.Optional;

/**
 * 智能战斗移动行为：根据当前主手武器形态决定走位方式。
 * <ul>
 *     <li>近战武器：贴近目标进入近战距离后停下；</li>
 *     <li>弓 / 弩：目标在射程内且可见时原地射击（由走位行为负责微调），否则向目标靠近；</li>
 *     <li>三叉戟：近身处按近战处理，远处按远程处理（站定投掷）。</li>
 * </ul>
 * 当 True POWER of Maid 已安装且主手持有拔刀剑时，本行为让位给
 * TPOM 的拔刀剑移动 AI（含瞬步接近），避免双方争抢 WALK_TARGET。
 */
public class SmartCombatMoveTask extends Behavior<EntityMaid> {
    private final float speedModifier;

    public SmartCombatMoveTask(float speedModifier) {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT));
        this.speedModifier = speedModifier;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (TruePowerCompat.isTruePowerBladeActive(maid)) {
            return false;
        }
        return maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                .filter(LivingEntity::isAlive)
                .isPresent();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return this.checkExtraStartConditions(level, maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        Optional<LivingEntity> memory = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
        if (memory.isEmpty()) {
            return;
        }
        LivingEntity target = memory.get();
        if (this.useRangedMovement(maid, target)) {
            this.tickRanged(maid, target);
        } else {
            this.tickMelee(maid, target);
        }
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    /**
     * 当前是否应当按远程武器的走位方式移动
     */
    private boolean useRangedMovement(EntityMaid maid, LivingEntity target) {
        ItemStack mainHand = maid.getMainHandItem();
        if (mainHand.getItem() instanceof ProjectileWeaponItem) {
            return true;
        }
        // 三叉戟：近身时按近战贴身，否则站定投掷
        return mainHand.getItem() instanceof TridentItem && !maid.isWithinMeleeAttackRange(target);
    }

    private void tickRanged(EntityMaid maid, LivingEntity target) {
        boolean canSee = maid.canSee(target);
        float range = maid.searchRadius();
        if (canSee && maid.distanceTo(target) <= range) {
            // 已进入舒适射击位置，清除移动目标，交给走位（Strafing）行为微调
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        } else {
            maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(new EntityTracker(target, false), this.speedModifier, 0));
        }
    }

    private void tickMelee(EntityMaid maid, LivingEntity target) {
        if (maid.isWithinMeleeAttackRange(target)) {
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        } else {
            maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(new EntityTracker(target, false), this.speedModifier, 0));
        }
    }
}
