package io.github.tlmsmartcombat.ai;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.github.tlmsmartcombat.TlmSmartCombat;
import io.github.tlmsmartcombat.compat.CraftCompat;
import io.github.tlmsmartcombat.compat.SlashBladeCompat;
import io.github.tlmsmartcombat.compat.StorageCompat;
import io.github.tlmsmartcombat.strategy.EquipOptimizer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能拾取行为：战斗间隙扫描女仆附近的刀架与容器，优化装备储备。
 * <ul>
 * <li>刀架（拔刀剑）：刀架上的拔刀剑 DPS 进入持有武器前三名则取下收入背包；
 * 之后若背包拔刀剑超过三把，则把 DPS 最低的一把放上该刀架（见
 * {@link SlashBladeCompat#lootBladeRacks}）；</li>
 * <li>容器：存在 DPS 高于当前装备的武器 / 防具时取出放入背包，
 * 并把自己背包中评分最低的一件同类装备放入该容器；
 * 容器中的拔刀剑若 DPS 进入持有武器前三名则直接取入背包。</li>
 * </ul>
 * 为避免干扰战斗，仅在 16 格内无敌对生物且未进行自动合成时执行，
 * 内置 {@value #CHECK_INTERVAL} tick 冷却。
 */
public class SmartLootBehavior extends Behavior<EntityMaid> {
    private static final int CHECK_INTERVAL = 100;
    private static final double SCAN_RADIUS = 16.0;
    /**
     * 武器换取阈值：评分需超过当前武器的比例 + 绝对值，避免近似装备间反复搬运
     */
    private static final double WEAPON_LOOT_RATIO = 1.08;
    private static final double WEAPON_LOOT_ABS = 0.25;
    /**
     * 盔甲换取阈值
     */
    private static final double ARMOR_LOOT_MARGIN = 1.0;

    private long nextCheckTime = 0;

    public SmartLootBehavior() {
        super(Map.of());
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (level.getGameTime() < this.nextCheckTime) {
            return false;
        }
        if (maid.isUsingItem()) {
            return false;
        }
        if (CraftCompat.isAutoCrafting(maid)) {
            return false;
        }
        return !CraftCompat.hasNearbyHostile(maid, 16.0);
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        this.nextCheckTime = gameTime + CHECK_INTERVAL;

        // 刀架交互（仅安装拔刀剑时）：取下强力拔刀剑
        SlashBladeCompat.lootBladeRacks(maid, SCAN_RADIUS);

        // 容器交互：取优存劣
        LivingEntity target = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                .filter(LivingEntity::isAlive).orElse(null);
        int moves = 0;
        List<BlockPos> containers = StorageCompat.findContainers(maid, SCAN_RADIUS);
        for (BlockPos pos : containers) {
            IItemHandler container = StorageCompat.getItemHandler(level, pos);
            if (container == null) continue;
            moves += lootContainer(maid, target, container);
        }

        // 拔刀剑整理：持有超过三把时只保留 DPS 最高的三把，多余的存入刀架或箱子
        moves += storeExcessSlashBlades(level, maid, target, containers);

        if (moves > 0) {
            TlmSmartCombat.LOGGER.info("[SmartCombat] {} 容器装备整理：交换 {} 件",
                    maid.getName().getString(), moves);
        }
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return false;
    }

    private static int lootContainer(EntityMaid maid, LivingEntity target, IItemHandler container) {
        int moves = 0;
        for (int slot = 0; slot < container.getSlots(); slot++) {
            ItemStack stack = container.getStackInSlot(slot);
            if (stack.isEmpty()) continue;

            // 拔刀剑：DPS 进入持有武器前三名 → 直接取入背包
            if (SlashBladeCompat.isSlashBladeItem(stack)) {
                double score = EquipOptimizer.scoreMainHand(maid, stack, target);
                if (score > 0 && EquipOptimizer.wouldRankTopWeapons(maid, score, 3, target)) {
                    ItemStack extracted = container.extractItem(slot, 1, false);
                    if (!stash(maid, container, slot, extracted)) continue;
                    moves++;
                }
                continue;
            }

            // 武器：DPS 高于当前主手（含阈值）→ 取出，并放入背包中评分最低的武器
            if (EquipOptimizer.classifyPublic(stack).isWeaponLike()) {
                double score = EquipOptimizer.scoreMainHand(maid, stack, target);
                double current = EquipOptimizer.scoreMainHand(maid, maid.getMainHandItem(), target);
                if (score > current * WEAPON_LOOT_RATIO + WEAPON_LOOT_ABS) {
                    ItemStack extracted = container.extractItem(slot, 1, false);
                    if (!stash(maid, container, slot, extracted)) continue;
                    depositLowestWeapon(maid, container, target, score);
                    moves++;
                }
                continue;
            }

            // 盔甲：评分高于当前对应部位 → 取出，并放入背包中评分最低的盔甲
            EquipmentSlot fitted = maid.getEquipmentSlotForItem(stack);
            if (fitted.getType() == EquipmentSlot.Type.ARMOR) {
                double score = EquipOptimizer.armorScore(maid, stack, fitted);
                double current = EquipOptimizer.armorScore(maid, maid.getItemBySlot(fitted), fitted);
                if (score > current + ARMOR_LOOT_MARGIN) {
                    ItemStack extracted = container.extractItem(slot, 1, false);
                    if (!stash(maid, container, slot, extracted)) continue;
                    depositLowestArmor(maid, container, score);
                    moves++;
                }
            }
        }
        return moves;
    }

    /**
     * 拔刀剑整理：女仆持有（双手 + 背包）超过三把拔刀剑时，
     * 只保留 DPS 最高的三把，多余的依次尝试放上空刀架、存入附近容器；
     * 实在无处可放的保留在背包中。
     *
     * @return 成功存放的拔刀剑数量
     */
    private static int storeExcessSlashBlades(ServerLevel level, EntityMaid maid,
                                              LivingEntity target, List<BlockPos> containers) {
        if (!SlashBladeCompat.isLoaded()) return 0;

        // 收集所有持有拔刀剑的评分：双手记为不可移出，背包记录槽位
        record BladeEntry(double score, int backpackSlot) {}
        List<BladeEntry> blades = new ArrayList<>();
        ItemStack mainHand = maid.getMainHandItem();
        if (SlashBladeCompat.isSlashBladeItem(mainHand)) {
            blades.add(new BladeEntry(EquipOptimizer.scoreMainHand(maid, mainHand, target), -1));
        }
        ItemStack offHand = maid.getOffhandItem();
        if (SlashBladeCompat.isSlashBladeItem(offHand)) {
            blades.add(new BladeEntry(EquipOptimizer.scoreMainHand(maid, offHand, target), -1));
        }
        var inv = maid.getAvailableBackpackInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (SlashBladeCompat.isSlashBladeItem(stack)) {
                blades.add(new BladeEntry(EquipOptimizer.scoreMainHand(maid, stack, target), i));
            }
        }
        if (blades.size() <= 3) return 0;

        // 按 DPS 降序排列，前三名保留，其余若为背包物品则移出存放
        blades.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<ItemStack> excess = new ArrayList<>();
        for (int i = 3; i < blades.size(); i++) {
            BladeEntry entry = blades.get(i);
            if (entry.backpackSlot() < 0) continue; // 手上的不强制移出
            ItemStack extracted = inv.extractItem(entry.backpackSlot(), 1, false);
            if (!extracted.isEmpty()) {
                excess.add(extracted);
            }
        }
        if (excess.isEmpty()) return 0;

        int before = excess.size();
        // 先尝试放上附近的空刀架
        excess = SlashBladeCompat.placeOnEmptyRacks(maid, SCAN_RADIUS, excess);
        // 再尝试存入附近容器
        if (!excess.isEmpty()) {
            for (BlockPos pos : containers) {
                IItemHandler container = StorageCompat.getItemHandler(level, pos);
                if (container == null) continue;
                List<ItemStack> remaining = new ArrayList<>();
                for (ItemStack blade : excess) {
                    ItemStack rest = StorageCompat.insertIntoInv(container, blade);
                    if (!rest.isEmpty()) {
                        remaining.add(rest);
                    }
                }
                excess = remaining;
                if (excess.isEmpty()) break;
            }
        }
        // 无处可放的退回背包
        for (ItemStack rest : excess) {
            StorageCompat.insertIntoInv(inv, rest);
        }
        return before - excess.size();
    }

    /**
     * 把物品收入女仆背包；失败（背包满）则退回容器。
     *
     * @return 是否成功收入
     */
    private static boolean stash(EntityMaid maid, IItemHandler container, int slot, ItemStack stack) {
        if (stack.isEmpty()) return false;
        ItemStack rest = StorageCompat.insertIntoInv(maid.getAvailableBackpackInv(), stack);
        if (!rest.isEmpty()) {
            container.insertItem(slot, rest, false);
            return false;
        }
        return true;
    }

    /**
     * 把女仆背包中评分最低的武器放入容器（不存放刚取回的物品：评分低于取出物才存放）
     */
    private static void depositLowestWeapon(EntityMaid maid, IItemHandler container,
                                            LivingEntity target, double takenScore) {
        var inv = maid.getAvailableBackpackInv();
        int lowestSlot = -1;
        double lowestScore = Double.MAX_VALUE;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty() || !EquipOptimizer.classifyPublic(stack).isWeaponLike()) continue;
            double score = EquipOptimizer.scoreMainHand(maid, stack, target);
            if (score < lowestScore) {
                lowestScore = score;
                lowestSlot = i;
            }
        }
        if (lowestSlot < 0 || lowestScore >= takenScore) return;
        ItemStack lowest = inv.extractItem(lowestSlot, 1, false);
        ItemStack rest = StorageCompat.insertIntoInv(container, lowest);
        if (!rest.isEmpty()) {
            // 容器放不下则退回背包
            StorageCompat.insertIntoInv(inv, rest);
        }
    }

    /**
     * 把女仆背包中评分最低的盔甲放入容器（不存放刚取回的物品：评分低于取出物才存放）
     */
    private static void depositLowestArmor(EntityMaid maid, IItemHandler container, double takenScore) {
        var inv = maid.getAvailableBackpackInv();
        int lowestSlot = -1;
        double lowestScore = Double.MAX_VALUE;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            EquipmentSlot fitted = maid.getEquipmentSlotForItem(stack);
            if (fitted.getType() != EquipmentSlot.Type.ARMOR) continue;
            double score = EquipOptimizer.armorScore(maid, stack, fitted);
            if (score < lowestScore) {
                lowestScore = score;
                lowestSlot = i;
            }
        }
        if (lowestSlot < 0 || lowestScore >= takenScore) return;
        ItemStack lowest = inv.extractItem(lowestSlot, 1, false);
        ItemStack rest = StorageCompat.insertIntoInv(container, lowest);
        if (!rest.isEmpty()) {
            StorageCompat.insertIntoInv(inv, rest);
        }
    }
}
