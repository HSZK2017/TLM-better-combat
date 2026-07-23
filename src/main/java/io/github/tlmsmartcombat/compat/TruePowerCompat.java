package io.github.tlmsmartcombat.compat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * True POWER of Maid（TPOM）软依赖桥接。
 * <p>
 * 当 TPOM 安装时，主手持拔刀剑的女仆使用 TPOM 的拔刀剑战斗 AI
 * （移动 / 连击 / 幻影剑），否则回退为 TLM 原版近战 AI（拔刀剑视为普通近战武器）。
 * <p>
 * 与 {@link SlashBladeCompat} 相同，TPOM 类型全部隔离在 {@link Inner} 中延迟加载。
 */
public final class TruePowerCompat {
    private static final String MOD_ID = "true_power_of_maid";

    private TruePowerCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    /**
     * 当前是否应当由 TPOM 接管女仆的拔刀剑战斗（TPOM 已安装且主手持有拔刀剑）
     */
    public static boolean isTruePowerBladeActive(EntityMaid maid) {
        return isLoaded() && SlashBladeCompat.isSlashBladeItem(maid.getMainHandItem());
    }

    /**
     * 向任务列表追加 TPOM 的拔刀剑战斗行为（移动、攻击、幻影剑）。
     * 这些行为自身均带有“主手持有拔刀剑”的启动条件，与其他武器形态互不干扰。
     */
    public static void addSlashBladeTasks(List<Pair<Integer, BehaviorControl<? super EntityMaid>>> tasks) {
        if (!isLoaded()) {
            return;
        }
        Inner.addSlashBladeTasks(tasks);
    }

    private static final class Inner {
        private Inner() {
        }

        static void addSlashBladeTasks(List<Pair<Integer, BehaviorControl<? super EntityMaid>>> tasks) {
            tasks.add(Pair.of(5, net.mrqx.slashblade.maidpower.entity.ai.MaidSlashBladeMove.create(0.6F)));
            tasks.add(Pair.of(5, net.mrqx.slashblade.maidpower.entity.ai.MaidSlashBladeAttack.create()));
            tasks.add(Pair.of(5, new net.mrqx.slashblade.maidpower.entity.ai.MaidMirageBladeBehavior()));
        }
    }
}
