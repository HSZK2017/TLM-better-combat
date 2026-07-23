package io.github.tlmsmartcombat.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import io.github.tlmsmartcombat.strategy.EquipOptimizer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;

/**
 * 装备优化行为：周期性重新评估女仆的最佳装备组合。
 * <p>
 * 该行为不占用任何记忆模块（MemoryModule），每 {@value #CHECK_INTERVAL} tick 触发一次。
 * 注意：原版 {@link Behavior} 在启动当帧只调用 {@link #start}，下一帧会先检查
 * {@link #canStillUse} 再决定是否 {@link #tick}。由于本行为 canStillUse 恒为 false，
 * tick 永远不会被调用，因此优化逻辑必须放在 {@link #start} 中执行（单次触发、开销可控）。
 * <p>
 * 注意：此 Behavior 实例由任务的 createBrainTasks 为每只女仆单独创建，
 * 因此实例字段天然是“按女仆”隔离的。
 */
public class SmartEquipBehavior extends Behavior<EntityMaid> {
    private static final int CHECK_INTERVAL = 15;

    private long nextCheckTime = 0;

    public SmartEquipBehavior() {
        super(ImmutableMap.of());
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (level.getGameTime() >= this.nextCheckTime) {
            return true;
        }
        // 紧急触发：当前武器卡在使用状态且无法开火（如弓 / 弩弹药耗尽）
        if (maid.isUsingItem() && EquipOptimizer.isStuckRangedWeapon(maid, maid.getMainHandItem())) {
            return true;
        }
        return false;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        this.nextCheckTime = gameTime + CHECK_INTERVAL;
        // 原版 Behavior 在 canStillUse 为 false 时不会调用 tick，优化逻辑必须在这里执行
        EquipOptimizer.optimize(maid, level);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        // 一次性行为：start 当帧完成全部工作，下一帧立即结束
        return false;
    }
}
