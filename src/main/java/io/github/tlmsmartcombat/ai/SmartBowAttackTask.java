package io.github.tlmsmartcombat.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidShootTargetTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

/**
 * 弓类武器射击行为。
 * <p>
 * 继承 TLM 的 {@link MaidShootTargetTask}，但额外排除弩：
 * 原版的射击行为对一切 {@link ProjectileWeaponItem}（含弩）都会触发，
 * 而弩的装填 / 发射流程由 MaidCrossbowAttack 负责，二者同时激活会争抢
 * “使用物品”状态导致行为异常，因此这里限定为“非弩的弹射武器”。
 */
public class SmartBowAttackTask extends MaidShootTargetTask {
    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid owner) {
        ItemStack mainHand = owner.getMainHandItem();
        if (!(mainHand.getItem() instanceof ProjectileWeaponItem) || mainHand.getItem() instanceof CrossbowItem) {
            return false;
        }
        return super.checkExtraStartConditions(level, owner);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return this.checkExtraStartConditions(level, maid);
    }
}
