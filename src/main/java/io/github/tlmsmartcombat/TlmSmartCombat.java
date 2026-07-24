package io.github.tlmsmartcombat;

import io.github.tlmsmartcombat.compat.CraftCompat;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Touhou Little Maid 附属模组：智能战斗
 * <p>
 * 为女仆添加"智能战斗"工作模式：
 * <ul>
 *     <li>优先护卫主人、其次锁定生命值最高的敌对生物进行攻击；</li>
 *     <li>根据背包物品实时评估各武器对当前目标的 DPS，动态切换主手武器；</li>
 *     <li>依据战斗形态为副手选择盾牌 / 不死图腾；</li>
 *     <li>自动穿戴背包中防护性能最优的盔甲；</li>
 *     <li>空闲时扫描物流清单自动合成更优装备。</li>
 * </ul>
 */
@Mod(TlmSmartCombat.MOD_ID)
public final class TlmSmartCombat {
    public static final String MOD_ID = "tlm_smart_combat";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public TlmSmartCombat() {
        // 注册 maid_storage_manager 任务自动切换回战斗的监听器
        CraftCompat.registerReturnCheck();
    }
}
