package io.github.tlmsmartcombat.compat;

import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * 拔刀剑（SlashBlade: Resharped）软依赖桥接。
 * <p>
 * 外部类方法不直接引用拔刀剑的任何类型；所有拔刀剑 API 调用都隔离在
 * {@link Inner} 中，只有在确认 mod 已安装后才会触发该类加载，
 * 因此在未安装拔刀剑的环境中调用本类的方法是安全的。
 */
public final class SlashBladeCompat {
    private static final String MOD_ID = "slashblade";

    private SlashBladeCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    /**
     * 该物品是否为拔刀剑（含各类名刀 / 妖刀等衍生刀）
     */
    public static boolean isSlashBladeItem(ItemStack stack) {
        return !stack.isEmpty() && isLoaded() && Inner.isSlashBladeItem(stack);
    }

    /**
     * 拔刀剑面板攻击伤害估算（基础攻击修正 + 攻击增幅），无法读取时返回 -1。
     * <p>
     * 拔刀剑的实际伤害由 BladeState 决定，原版物品属性修饰符往往不能反映真实面板，
     * 因此评分时需要单独读取。
     */
    public static double getBladeAttackDamage(ItemStack stack) {
        if (!isSlashBladeItem(stack)) {
            return -1;
        }
        return Inner.getBladeAttackDamage(stack);
    }

    private static final class Inner {
        private Inner() {
        }

        static boolean isSlashBladeItem(ItemStack stack) {
            return mods.flammpfeil.slashblade.capability.slashblade.BladeStateAccess.of(stack).isPresent();
        }

        static double getBladeAttackDamage(ItemStack stack) {
            return mods.flammpfeil.slashblade.capability.slashblade.BladeStateAccess.of(stack)
                    .map(state -> (double) (state.getBaseAttackModifier() + state.getAttackAmplifier()))
                    .orElse(-1.0);
        }
    }
}
