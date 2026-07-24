package io.github.tlmsmartcombat.compat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
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

    /**
     * 扫描女仆附近的刀架并取下强力拔刀剑：
     * 刀架上的拔刀剑 DPS 若进入女仆持有武器前 3 名则取下收入背包。
     * 多余拔刀剑的存放由 {@link #placeOnEmptyRacks} 统一处理。
     */
    public static void lootBladeRacks(EntityMaid maid, double radius) {
        if (!isLoaded()) return;
        Inner.lootBladeRacks(maid, radius);
    }

    /**
     * 把拔刀剑放到附近的空刀架上（由近及远，每个刀架一把）。
     *
     * @return 未能放上刀架的剩余拔刀剑
     */
    public static java.util.List<ItemStack> placeOnEmptyRacks(EntityMaid maid, double radius,
                                                              java.util.List<ItemStack> blades) {
        if (!isLoaded() || blades.isEmpty()) return blades;
        return Inner.placeOnEmptyRacks(maid, radius, blades);
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

        static void lootBladeRacks(EntityMaid maid, double radius) {
            var level = (net.minecraft.server.level.ServerLevel) maid.level();
            var target = maid.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET)
                    .filter(net.minecraft.world.entity.LivingEntity::isAlive).orElse(null);
            var stands = level.getEntitiesOfClass(
                    mods.flammpfeil.slashblade.entity.BladeStandEntity.class,
                    maid.getBoundingBox().inflate(radius),
                    e -> e.isAlive() && !e.getItem().isEmpty());
            for (var stand : stands) {
                ItemStack blade = stand.getItem();
                double bladeScore = io.github.tlmsmartcombat.strategy.EquipOptimizer
                        .scoreMainHand(maid, blade, target);
                if (bladeScore <= 0) continue;
                // DPS 不在持有武器前三之内 → 不取
                if (!io.github.tlmsmartcombat.strategy.EquipOptimizer
                        .wouldRankTopWeapons(maid, bladeScore, 3, target)) continue;

                // 取下刀架上的拔刀剑收入背包
                stand.setItem(ItemStack.EMPTY);
                ItemStack rest = StorageCompat.insertIntoInv(maid.getAvailableBackpackInv(), blade.copy());
                if (!rest.isEmpty()) {
                    maid.spawnAtLocation(rest);
                }
                stand.playSound(net.minecraft.sounds.SoundEvents.ITEM_FRAME_REMOVE_ITEM, 1.0F, 1.0F);
            }
        }

        static java.util.List<ItemStack> placeOnEmptyRacks(EntityMaid maid, double radius,
                                                           java.util.List<ItemStack> blades) {
            var level = (net.minecraft.server.level.ServerLevel) maid.level();
            var stands = level.getEntitiesOfClass(
                    mods.flammpfeil.slashblade.entity.BladeStandEntity.class,
                    maid.getBoundingBox().inflate(radius),
                    e -> e.isAlive() && e.getItem().isEmpty());
            if (stands.isEmpty()) return blades;
            stands.sort(java.util.Comparator.comparingDouble(maid::distanceToSqr));
            java.util.List<ItemStack> remaining = new java.util.ArrayList<>();
            int idx = 0;
            for (ItemStack blade : blades) {
                if (idx < stands.size()) {
                    var stand = stands.get(idx++);
                    stand.setItem(blade);
                    stand.playSound(net.minecraft.sounds.SoundEvents.ITEM_FRAME_ADD_ITEM, 1.0F, 1.0F);
                } else {
                    remaining.add(blade);
                }
            }
            return remaining;
        }
    }
}
