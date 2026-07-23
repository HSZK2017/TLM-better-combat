package io.github.tlmsmartcombat.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidUseShieldTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.github.tlmsmartcombat.compat.TruePowerCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

/**
 * 智能举盾行为。
 * <p>
 * 继承 TLM 的 {@link MaidUseShieldTask}（敌人靠近时举副手盾牌格挡），
 * 额外限定：主手为弓 / 弩时不举盾，因为拉弓与装填同样占用“使用物品”状态，
 * 举盾会打断射击；主手为拔刀剑且安装了 True POWER of Maid 时也不举盾，
 * 由 TPOM 自带的拔刀剑格挡体系负责防御。
 */
public class SmartShieldTask extends MaidUseShieldTask {
    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (!super.checkExtraStartConditions(level, maid)) {
            return false;
        }
        if (TruePowerCompat.isTruePowerBladeActive(maid)) {
            return false;
        }
        ItemStack mainHand = maid.getMainHandItem();
        return !(mainHand.getItem() instanceof ProjectileWeaponItem);
    }
}
