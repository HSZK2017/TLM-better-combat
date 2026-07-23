package io.github.tlmsmartcombat;

import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Touhou Little Maid 附属模组：智能战斗
 * <p>
 * 为女仆添加“智能战斗”工作模式：
 * <ul>
 *     <li>优先锁定生命值最高的敌对生物进行攻击；</li>
 *     <li>根据背包物品实时评估各武器对当前目标的 DPS，动态切换主手武器；</li>
 *     <li>依据战斗形态为副手选择盾牌 / 不死图腾；</li>
 *     <li>自动穿戴背包中防护性能最优的盔甲。</li>
 * </ul>
 */
@Mod(TlmSmartCombat.MOD_ID)
public final class TlmSmartCombat {
    public static final String MOD_ID = "tlm_smart_combat";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public TlmSmartCombat() {
        // 任务的注册通过 {@link SmartCombatExtension} 上的 @LittleMaidExtension 注解完成，
        // 由 Touhou Little Maid 本体扫描并加载，此处无需额外初始化。
    }
}
