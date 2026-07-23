package io.github.tlmsmartcombat.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import io.github.tlmsmartcombat.TlmSmartCombat;
import io.github.tlmsmartcombat.compat.StorageCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

/**
 * 战斗间隙补给行为：当敌人远离且附近有容器时，从容器中提取武器 / 盔甲，
 * 并将非战斗物资存入容器。
 * <p>
 * 触发条件：
 * <ul>
 *     <li>没有攻击目标，或攻击目标距离超过 16 格；</li>
 *     <li>24 格范围内存在可访问的容器；</li>
 *     <li>女仆当前未使用物品（避免打断拉弓 / 进食等）。</li>
 * </ul>
 * 执行周期：常规每 {@value #CHECK_INTERVAL} tick；连续两次扫描无容器则延长至
 * {@value #LONG_INTERVAL} tick 以降低开销。
 * <p>
 * 货物判定：战斗物资 = 武器 / 盔甲 / 盾牌 / 弹药 / 图腾；
 * 其余一切（建材、食物、杂物等）视为非战斗物资并由女仆存入容器。
 */
public class SmartResupplyBehavior extends Behavior<EntityMaid> {
    private static final int CHECK_INTERVAL = 60;
    private static final int LONG_INTERVAL = 600;
    private static final double ENEMY_FAR_THRESHOLD = 16.0;
    private static final double CONTAINER_SCAN_RANGE = 24.0;

    private long nextCheckTime = 0;
    private boolean lastForceLongCooldown = false;

    public SmartResupplyBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED
        ));
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (level.getGameTime() < this.nextCheckTime) {
            return false;
        }
        // 正在使用物品时不打扰
        if (maid.isUsingItem()) {
            return false;
        }
        // 攻击目标太近时专注于战斗
        if (hasCloseEnemy(maid)) {
            return false;
        }
        return true;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        BlockPos container = StorageCompat.findNearestContainer(maid, CONTAINER_SCAN_RANGE);
        if (container == null) {
            // 无容器时拉长检查间隔以降低开销
            this.lastForceLongCooldown = true;
            this.nextCheckTime = gameTime + LONG_INTERVAL;
            return;
        }
        this.lastForceLongCooldown = false;
        int pulled = StorageCompat.pullCombatGear(maid, container);
        int pushed = StorageCompat.pushNonCombatItems(maid, container);
        if (pulled > 0 || pushed > 0) {
            TlmSmartCombat.LOGGER.debug("[SmartCombat] {} 容器补给: 取得 {} 件, 存入 {} 件",
                    maid.getName().getString(), pulled, pushed);
        }
        this.nextCheckTime = gameTime + CHECK_INTERVAL;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return false;
    }

    private static boolean hasCloseEnemy(EntityMaid maid) {
        return maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                .filter(LivingEntity::isAlive)
                .filter(target -> maid.distanceTo(target) <= ENEMY_FAR_THRESHOLD)
                .isPresent();
    }
}
