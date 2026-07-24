package io.github.tlmsmartcombat.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.github.tlmsmartcombat.TlmSmartCombat;
import io.github.tlmsmartcombat.compat.CraftCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * 智能合成行为：当周围 16 格内无敌对生物时，扫描女仆的物流清单，
 * 评估可合成装备的 DPS/防御是否优于当前装备，若更优则触发自动合成。
 * <p>
 * 为避免每 tick 都执行昂贵扫描，本行为内置 {@value #COOLDOWN} tick 冷却。
 * 冷期内即便条件不满足也不检查，降低服务器负载。
 */
public class SmartCraftBehavior extends Behavior<EntityMaid> {
    private static final int COOLDOWN = 60;
    private long nextEvalTime = 0;
    private ItemStack cachedBest = null;

    public SmartCraftBehavior() {
        super(Map.of());
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (level.getGameTime() < this.nextEvalTime) {
            return false;
        }
        this.nextEvalTime = level.getGameTime() + COOLDOWN;

        // 周围有敌对生物 → 交战状态
        if (CraftCompat.hasNearbyHostile(maid, 16.0)) {
            this.cachedBest = null;
            TlmSmartCombat.LOGGER.info("[SmartCombat] {} 自动合成评估：附近存在敌对生物，跳过",
                    maid.getName().getString());
            return false;
        }
        // 已有自动合成在进行，或上次合成失败处于冷却期 → 不再重复发起
        if (CraftCompat.isAutoCrafting(maid)) {
            this.cachedBest = null;
            TlmSmartCombat.LOGGER.info("[SmartCombat] {} 自动合成评估：上一次合成仍在进行，跳过",
                    maid.getName().getString());
            return false;
        }
        if (CraftCompat.isOnCooldown(maid, level)) {
            this.cachedBest = null;
            TlmSmartCombat.LOGGER.info("[SmartCombat] {} 自动合成评估：失败冷却中，跳过",
                    maid.getName().getString());
            return false;
        }
        // 仅在存储管理 mod 已安装且女仆持有物流清单时才评估
        this.cachedBest = CraftCompat.findBestUpgrade(maid, level);
        TlmSmartCombat.LOGGER.info("[SmartCombat] {} 自动合成评估结果：{}",
                maid.getName().getString(),
                this.cachedBest == null ? "无可合成的更优装备" : "发现更优装备 " + this.cachedBest.getHoverName().getString());
        return this.cachedBest != null;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        if (this.cachedBest != null) {
            CraftCompat.startCrafting(maid, this.cachedBest);
            this.cachedBest = null;
        }
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return false;
    }
}
