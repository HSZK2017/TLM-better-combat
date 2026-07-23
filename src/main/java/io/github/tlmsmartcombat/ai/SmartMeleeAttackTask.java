package io.github.tlmsmartcombat.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.github.tlmsmartcombat.compat.TruePowerCompat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ProjectileWeaponItem;

/**
 * 近战攻击行为，逻辑与 TLM 的 MaidMeleeAttack 一致
 * （攻击冷却由 {@link Attributes#ATTACK_SPEED} 属性决定）。
 * <p>
 * 与原版唯一的区别：当 True POWER of Maid 已安装且女仆主手持有拔刀剑时，
 * 本行为让位给 TPOM 的拔刀剑连击 AI，避免两套攻击逻辑重复出手。
 */
public class SmartMeleeAttackTask {
    private SmartMeleeAttackTask() {
    }

    public static OneShot<EntityMaid> create(int cooldownBetweenAttacks) {
        return BehaviorBuilder.create(context -> context.group(
                context.registered(MemoryModuleType.LOOK_TARGET),
                context.present(MemoryModuleType.ATTACK_TARGET),
                context.absent(MemoryModuleType.ATTACK_COOLING_DOWN),
                context.present(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES)
        ).apply(context, (lookTarget,
                          attackTarget,
                          attackCoolingDown,
                          nearestVisibleLivingEntities
        ) -> (level, maid, gameTime) -> {
            LivingEntity target = context.get(attackTarget);
            if (!TruePowerCompat.isTruePowerBladeActive(maid)
                && !isHoldingUsableProjectileWeapon(maid)
                && maid.isWithinMeleeAttackRange(target)
                && context.get(nearestVisibleLivingEntities).contains(target)
            ) {
                lookTarget.set(new EntityTracker(target, true));
                maid.swing(InteractionHand.MAIN_HAND);
                maid.doHurtTarget(target);
                double attackSpeed = maid.getAttributeValue(Attributes.ATTACK_SPEED);
                if (attackSpeed > 0) {
                    attackCoolingDown.setWithExpiry(true, (long) (cooldownBetweenAttacks / attackSpeed));
                } else {
                    attackCoolingDown.setWithExpiry(true, cooldownBetweenAttacks);
                }
                return true;
            } else {
                return false;
            }
        }));
    }

    private static boolean isHoldingUsableProjectileWeapon(EntityMaid maid) {
        return maid.isHolding(itemStack -> {
            Item item = itemStack.getItem();
            return item instanceof ProjectileWeaponItem projectile && maid.canFireProjectileWeapon(projectile);
        });
    }
}
