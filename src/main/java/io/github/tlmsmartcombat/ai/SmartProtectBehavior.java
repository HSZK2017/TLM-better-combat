package io.github.tlmsmartcombat.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import io.github.tlmsmartcombat.compat.TruePowerCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * 主人护卫行为：在战斗中保护主人免受弹射物与近战攻击。
 * <p>
 * 不依赖女仆持有武器或拥有 ATTACK_TARGET —— 即使女仆仅持盾牌 / 不死图腾 /
 * 空手，也能扫描主人周围的威胁并执行护卫。
 * <p>
 * 触发条件：
 * <ul>
 *     <li>主人 12 格内有飞向主人的弹射物；</li>
 *     <li>或主人 8 格内存在敌对生物。</li>
 * </ul>
 * 护卫动作：
 * <ol>
 *     <li><b>弹射物拦截</b>：移动到拦截点并举盾 / 拔刀剑格挡；</li>
 *     <li><b>近战护卫</b>：移动到主人与最近敌对生物之间的位置（距主人 2 格朝敌）；</li>
 *     <li><b>肉身格挡</b>：未持盾且未持拔刀剑时获得 30 秒力量 I + 迅捷 I。</li>
 * </ol>
 * 优先级 4（高于战斗行为）。
 */
public class SmartProtectBehavior extends Behavior<EntityMaid> {
    private static final double PROJECTILE_SCAN_RANGE = 12.0;
    private static final double ARROW_INTERCEPT_THRESHOLD = 3.0;
    private static final double MELEE_SCAN_RANGE = 8.0;
    private static final double BODYGUARD_DISTANCE = 2.0;
    private static final int BUFF_DURATION = 600;
    private static final long BUFF_COOLDOWN = 600;
    private static final float MOVE_SPEED = 0.8f;

    private long lastBuffTick = 0;

    public SmartProtectBehavior() {
        // ATTACK_TARGET 仅 REGISTERED，因为女仆可能未持武器而没有攻击目标
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED
        ));
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        LivingEntity owner = maid.getOwner();
        if (owner == null) {
            return false;
        }
        if (hasProjectileThreat(level, owner)) {
            return true;
        }
        return findNearestHostileToOwner(level, maid, owner) != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return checkExtraStartConditions(level, maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        LivingEntity owner = maid.getOwner();
        if (owner == null) {
            return;
        }

        // 优先：拦截飞向主人的弹射物
        AbstractArrow arrow = findThreatArrow(level, owner);
        if (arrow != null) {
            Vec3 intercept = computeInterceptPoint(arrow, owner);
            setWalkTarget(maid, intercept);
            // 面向箭矢来向，确保盾牌能格挡
            maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                    new EntityTracker(arrow, true));
            raiseGuard(maid);
            return;
        }

        // 其次：近战护卫，站到主人与最近敌对生物之间
        LivingEntity enemy = findNearestHostileToOwner(level, maid, owner);
        if (enemy != null) {
            Vec3 guardPos = computeBodyguardPosition(owner, enemy);
            setWalkTarget(maid, guardPos);
            // 面向敌人，便于举盾 / 肉盾
            maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                    new EntityTracker(enemy, true));
            applyBodyTankBuff(maid, gameTime);
        }
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.stopUsingItem();
    }

    // ------------------------------------------------------------------
    // 弹射物检测
    // ------------------------------------------------------------------

    private static boolean hasProjectileThreat(ServerLevel level, LivingEntity owner) {
        return findThreatArrow(level, owner) != null;
    }

    private static AbstractArrow findThreatArrow(ServerLevel level, LivingEntity owner) {
        Vec3 ownerEye = owner.getEyePosition();
        AABB scanBox = new AABB(owner.blockPosition()).inflate(PROJECTILE_SCAN_RANGE);
        List<AbstractArrow> arrows = level.getEntitiesOfClass(AbstractArrow.class, scanBox,
                a -> a.getOwner() != owner && a.isAlive() && a.getDeltaMovement().lengthSqr() > 0.01);
        for (AbstractArrow arrow : arrows) {
            if (willPassNearOwner(arrow, ownerEye)) {
                return arrow;
            }
        }
        return null;
    }

    private static boolean willPassNearOwner(AbstractArrow arrow, Vec3 ownerEye) {
        Vec3 arrowPos = arrow.position();
        Vec3 arrowDir = arrow.getDeltaMovement();
        if (arrowDir.lengthSqr() < 0.01) {
            return false;
        }
        arrowDir = arrowDir.normalize();
        Vec3 toOwner = ownerEye.subtract(arrowPos);
        double projection = toOwner.dot(arrowDir);
        if (projection <= 0) {
            return false;
        }
        Vec3 closest = arrowPos.add(arrowDir.scale(projection));
        return closest.distanceTo(ownerEye) < ARROW_INTERCEPT_THRESHOLD;
    }

    private static Vec3 computeInterceptPoint(AbstractArrow arrow, LivingEntity owner) {
        Vec3 arrowPos = arrow.position();
        Vec3 arrowDir = arrow.getDeltaMovement().normalize();
        Vec3 ownerEye = owner.getEyePosition();
        Vec3 toOwner = ownerEye.subtract(arrowPos);
        double t = Math.max(0, toOwner.dot(arrowDir));
        return arrowPos.add(arrowDir.scale(t)).add(arrowDir.scale(-1.5));
    }

    // ------------------------------------------------------------------
    // 近战护卫
    // ------------------------------------------------------------------

    /**
     * 扫描主人周围的可攻击敌对生物，返回距主人最近者。
     * 优先选择锁定主人或最近伤害过主人的生物。
     */
    private static LivingEntity findNearestHostileToOwner(ServerLevel level, EntityMaid maid, LivingEntity owner) {
        AABB scanBox = new AABB(owner.blockPosition()).inflate(MELEE_SCAN_RANGE);
        List<LivingEntity> hostiles = level.getEntitiesOfClass(LivingEntity.class, scanBox,
                e -> e.isAlive() && e != owner && maid.canAttack(e));
        return hostiles.stream()
                .min(Comparator.comparingDouble(e -> priorityOfHostile(owner, e)))
                .orElse(null);
    }

    /**
     * 越低越优先：锁定主人（0）、最近伤主人（1）、距离（距离值）。
     */
    private static int priorityOfHostile(LivingEntity owner, LivingEntity e) {
        if (e instanceof Mob mob && mob.getTarget() == owner) {
            return 0;
        }
        if (owner.getLastHurtByMob() == e && owner.tickCount - owner.getLastHurtByMobTimestamp() < 100) {
            return 1;
        }
        return (int) (owner.distanceTo(e) + 2);
    }

    private static Vec3 computeBodyguardPosition(LivingEntity owner, LivingEntity enemy) {
        Vec3 ownerPos = owner.position();
        Vec3 dirToEnemy = enemy.position().subtract(ownerPos).normalize();
        return ownerPos.add(dirToEnemy.scale(BODYGUARD_DISTANCE));
    }

    // ------------------------------------------------------------------
    // 动作执行
    // ------------------------------------------------------------------

    private static void setWalkTarget(EntityMaid maid, Vec3 targetPos) {
        maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(targetPos, MOVE_SPEED, 0));
    }

    private static void raiseGuard(EntityMaid maid) {
        if (maid.isUsingItem()) {
            return;
        }
        if (TruePowerCompat.isTruePowerBladeActive(maid)) {
            return;
        }
        ItemStack offhand = maid.getOffhandItem();
        if (isShield(offhand)) {
            maid.startUsingItem(InteractionHand.OFF_HAND);
        } else if (isShield(maid.getMainHandItem())) {
            maid.startUsingItem(InteractionHand.MAIN_HAND);
        }
    }

    private static boolean isShield(ItemStack stack) {
        return stack.getItem() instanceof ShieldItem || stack.getUseAnimation() == UseAnim.BLOCK;
    }

    private void applyBodyTankBuff(EntityMaid maid, long gameTime) {
        if (hasShield(maid) || TruePowerCompat.isTruePowerBladeActive(maid)) {
            return;
        }
        if (gameTime - this.lastBuffTick < BUFF_COOLDOWN) {
            return;
        }
        this.lastBuffTick = gameTime;
        maid.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, BUFF_DURATION, 0));
        maid.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, BUFF_DURATION, 0));
    }

    private static boolean hasShield(EntityMaid maid) {
        return isShield(maid.getOffhandItem()) || isShield(maid.getMainHandItem());
    }
}
