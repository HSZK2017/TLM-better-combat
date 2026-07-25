package io.github.tlmsmartcombat.compat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.github.tlmsmartcombat.strategy.EquipOptimizer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 容器物资交换工具，复用 maid_storage_manager 的 {@code InvUtil} 设计模式。
 * <p>
 * 不依赖任何外部 mod，直接通过 Forge 的 {@link IItemHandler} capability
 * 访问容器，并借用女仆的 TLM 背包接口完成物资搬运。
 * 若安装了 maid_storage_manager，则尊重其 NoAccess 标记（禁止访问的容器会被跳过）。
 */
public final class StorageCompat {
    private static final ResourceLocation ITEM_HANDLER_TYPE =
            new ResourceLocation("maid_storage_manager", "item_handler");

    private StorageCompat() {
    }

    // ------------------------------------------------------------------
    // 容器扫描
    // ------------------------------------------------------------------

    /**
     * 以女仆位置为中心扫描半径内的方块，返回最近的可访问容器坐标。
     *
     * @return 最近的容器坐标，未找到或全部不可访问则返回 null
     */
    public static BlockPos findNearestContainer(EntityMaid maid, double radius) {
        ServerLevel level = (ServerLevel) maid.level();
        BlockPos maidPos = maid.blockPosition();
        double closestSqr = radius * radius;
        BlockPos closest = null;
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r; dy <= r; dy++) {
                    BlockPos pos = maidPos.offset(dx, dy, dz);
                    double distSqr = maidPos.distSqr(pos);
                    if (distSqr > radius * radius) {
                        continue;
                    }
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    if (isValidStorage(level, pos) && isAccessible(level, maid, pos)) {
                        if (closest == null || distSqr < closestSqr) {
                            closestSqr = distSqr;
                            closest = pos.immutable();
                        }
                    }
                }
            }
        }
        return closest;
    }

    private static boolean isValidStorage(Level level, BlockPos pos) {
        return getItemHandler(level, pos) != null;
    }

    /**
     * 获取指定坐标方块的物品处理器（Forge capability），无方块实体或无该能力时返回 null。
     */
    @Nullable
    public static IItemHandler getItemHandler(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
    }

    /**
     * 扫描半径内所有可访问容器，按距离从近到远排序返回。
     */
    public static java.util.List<BlockPos> findContainers(EntityMaid maid, double radius) {
        ServerLevel level = (ServerLevel) maid.level();
        BlockPos maidPos = maid.blockPosition();
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r; dy <= r; dy++) {
                    BlockPos pos = maidPos.offset(dx, dy, dz);
                    double distSqr = maidPos.distSqr(pos);
                    if (distSqr > radius * radius) {
                        continue;
                    }
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    if (isValidStorage(level, pos) && isAccessible(level, maid, pos)) {
                        found.add(pos.immutable());
                    }
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(maidPos::distSqr));
        return found;
    }

    /**
     * 容器是否可被女仆访问（尊重 maid_storage_manager 的 NoAccess 标记）。
     */
    private static boolean isAccessible(ServerLevel level, EntityMaid maid, BlockPos pos) {
        if (!ModList.get().isLoaded("maid_storage_manager")) {
            return true;
        }
        return MaidStorageAccess.isTargetValid(level, maid, pos);
    }

    /**
     * 公开的访问权检测：容器是否可被女仆访问。
     * 供 {@link CraftCompat} 取回合成产物时复用。
     */
    public static boolean isAccessibleStorage(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return isAccessible(level, maid, pos);
    }

    // ------------------------------------------------------------------
    // 物资拉取
    // ------------------------------------------------------------------

    /**
     * 从容器中提取战斗物资拉入女仆背包。
     * 盔甲限制为最多 1 套（四部位各一件），超越部分不提取。
     *
     * @return 尝试拉入的物品堆叠数
     */
    public static int pullCombatGear(EntityMaid maid, BlockPos containerPos) {
        Level level = maid.level();
        IItemHandler container = getItemHandler(level, containerPos);
        if (container == null) {
            return 0;
        }
        CombinedInvWrapper maidInv = maid.getAvailableBackpackInv();
        Set<EquipmentSlot> armorSlotsTaken = EnumSet.noneOf(EquipmentSlot.class);
        int count = 0;
        for (int slot = 0; slot < container.getSlots(); slot++) {
            ItemStack stack = container.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!isCombatGear(stack)) {
                continue;
            }
            EquipmentSlot armorSlot = getArmorSlot(stack);
            if (armorSlot != null) {
                if (armorSlotsTaken.contains(armorSlot)) {
                    continue;
                }
                armorSlotsTaken.add(armorSlot);
            }
            ItemStack extracted = container.extractItem(slot, stack.getCount(), false);
            if (extracted.isEmpty()) {
                continue;
            }
            ItemStack leftover = insertIntoInv(maidInv, extracted);
            if (!leftover.isEmpty()) {
                ItemStack returned = insertIntoInv(container, leftover);
                if (!returned.isEmpty()) {
                    container.insertItem(slot, returned, false);
                }
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * 返回物品对应的护甲槽位，非护甲返回 null。
     */
    private static EquipmentSlot getArmorSlot(ItemStack stack) {
        if (stack.getItem() instanceof ArmorItem armor) {
            return armor.getEquipmentSlot();
        }
        if (hasModifierForSlot(stack, EquipmentSlot.HEAD)) return EquipmentSlot.HEAD;
        if (hasModifierForSlot(stack, EquipmentSlot.CHEST)) return EquipmentSlot.CHEST;
        if (hasModifierForSlot(stack, EquipmentSlot.LEGS)) return EquipmentSlot.LEGS;
        if (hasModifierForSlot(stack, EquipmentSlot.FEET)) return EquipmentSlot.FEET;
        return null;
    }

    private static boolean hasModifierForSlot(ItemStack stack, EquipmentSlot slot) {
        for (Map.Entry<Attribute, AttributeModifier> entry : stack.getAttributeModifiers(slot).entries()) {
            if (entry.getValue().getAmount() > 0) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 物资存入
    // ------------------------------------------------------------------

    /**
     * 将女仆背包中所有非战斗物资存入容器。
     *
     * @return 存入的物品堆叠数
     */
    public static int pushNonCombatItems(EntityMaid maid, BlockPos containerPos) {
        Level level = maid.level();
        IItemHandler container = getItemHandler(level, containerPos);
        if (container == null) {
            return 0;
        }
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        int count = 0;
        for (int slot = 0; slot < backpack.getSlots(); slot++) {
            ItemStack stack = backpack.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (isCombatGear(stack)) {
                continue;
            }
            ItemStack extracted = backpack.extractItem(slot, stack.getCount(), false);
            if (extracted.isEmpty()) {
                continue;
            }
            ItemStack leftover = insertIntoInv(container, extracted);
            if (!leftover.isEmpty()) {
                backpack.setStackInSlot(slot, leftover);
                break;
            }
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 物品归类
    // ------------------------------------------------------------------

    /**
     * 判断物品是否为战斗物资：武器 / 盔甲 / 盾牌 / 弹药 / 图腾。
     */
    public static boolean isCombatGear(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (EquipOptimizer.isMeleeWeapon(stack)) {
            return true;
        }
        if (stack.getItem() instanceof BowItem
            || stack.getItem() instanceof CrossbowItem
            || stack.getItem() instanceof TridentItem) {
            return true;
        }
        if (stack.getItem() instanceof ShieldItem
            || stack.getUseAnimation() == UseAnim.BLOCK) {
            return true;
        }
        if (stack.is(Items.TOTEM_OF_UNDYING)) {
            return true;
        }
        if (stack.getItem() instanceof ProjectileWeaponItem) {
            return true;
        }
        if (isAmmoItem(stack)) {
            return true;
        }
        if (getArmorSlot(stack) != null) {
            return true;
        }
        return false;
    }

    private static boolean isAmmoItem(ItemStack stack) {
        return stack.is(Items.ARROW)
               || stack.is(Items.SPECTRAL_ARROW)
               || stack.is(Items.TIPPED_ARROW);
    }

    // ------------------------------------------------------------------
    // 物品栏插入
    // ------------------------------------------------------------------

    /**
     * 向 IItemHandler 中插入物品，先合并已有同类，再找空位。
     *
     * @return 未能装入的剩余物品
     */
    public static ItemStack insertIntoInv(IItemHandler inv, ItemStack stack) {
        if (stack.isEmpty()) {
            return stack;
        }
        ItemStack rest = stack.copy();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack inSlot = inv.getStackInSlot(i);
            if (ItemStack.isSameItemSameTags(inSlot, rest)) {
                rest = inv.insertItem(i, rest, false);
                if (rest.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        for (int i = 0; i < inv.getSlots(); i++) {
            rest = inv.insertItem(i, rest, false);
            if (rest.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        return rest;
    }

    // ------------------------------------------------------------------
    // maid_storage_manager 访问权检测（软依赖，延迟加载）
    // ------------------------------------------------------------------

    private static final class MaidStorageAccess {
        private MaidStorageAccess() {
        }

        static boolean isTargetValid(ServerLevel level, EntityMaid maid, BlockPos pos) {
            studio.fantasyit.maid_storage_manager.storage.Target target =
                    new studio.fantasyit.maid_storage_manager.storage.Target(
                            ITEM_HANDLER_TYPE, pos, (net.minecraft.core.Direction) null);
            return studio.fantasyit.maid_storage_manager.util.StorageAccessUtil.isValidTarget(
                    level, maid, target, false);
        }
    }
}
