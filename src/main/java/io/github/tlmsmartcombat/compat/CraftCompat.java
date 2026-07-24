package io.github.tlmsmartcombat.compat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import io.github.tlmsmartcombat.TlmSmartCombat;
import io.github.tlmsmartcombat.strategy.EquipOptimizer;
import io.github.tlmsmartcombat.strategy.EquipOptimizer.WeaponKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * maid_storage_manager 软依赖桥接。
 * <p>
 * 核心功能：
 * <ul>
 * <li>评估女仆物流清单中可合成装备对当前装备的 DPS/防御优势；</li>
 * <li>当优势存在且周围无敌对生物时，触发合成（虚拟请求列表 + 切换任务）；</li>
 * <li>合成期间每 tick 把物流清单饰品中的配方注入女仆的合成记忆
 * （MSM 原生流程只会读取箱子里发现的合成指南，缺少这一步规划器必然失败）；</li>
 * <li>合成结束（成功 / 失败 / 遇敌中止）后清理虚拟清单并切回智能战斗任务。</li>
 * </ul>
 */
public final class CraftCompat {
    private static final String MOD_ID = "maid_storage_manager";
    static final ResourceLocation STORAGE_TASK_UID =
            ResourceLocation.fromNamespaceAndPath("maid_storage_manager", "storage_manage");

    private CraftCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    /**
     * 指定半径内是否有敌对生物（检查女仆及主人周围）。
     * <p>
     * 注意：不能使用 maid.canAttack —— 它依赖女仆当前任务的攻击规则，
     * 女仆切到非攻击任务（如仓库管理员）后会对包括主人在内的几乎所有生物返回 true，
     * 导致自动合成被误判为"遇敌"而立刻中止。
     * 这里直接以原版敌对生物标记 {@link Enemy} 判定（与 TLM 的默认敌对分类一致）。
     */
    public static boolean hasNearbyHostile(EntityMaid maid, double radius) {
        ServerLevel level = (ServerLevel) maid.level();
        Predicate<LivingEntity> isHostile = e -> e.isAlive()
                && e instanceof Enemy
                && !(e instanceof TamableAnimal tamable && tamable.isTame());
        // 检查女仆周围
        if (!level.getEntitiesOfClass(LivingEntity.class,
                maid.getBoundingBox().inflate(radius), isHostile).isEmpty()) {
            return true;
        }
        // 检查主人周围
        LivingEntity owner = maid.getOwner();
        if (owner != null) {
            return !level.getEntitiesOfClass(LivingEntity.class,
                    owner.getBoundingBox().inflate(radius), isHostile).isEmpty();
        }
        return false;
    }

    /**
     * 扫描女仆饰品栏中所有物流清单，找出 DPS/防御优于当前装备的最佳合成目标物品。
     *
     * @return 最佳合成物品栈，若无则返回 null
     */
    @Nullable
    public static ItemStack findBestUpgrade(EntityMaid maid, ServerLevel level) {
        if (!isLoaded()) return null;
        return Inner.findBestUpgrade(maid, level);
    }

    /**
     * 为指定目标物品启动合成，并将女仆任务切换至仓库管理员。
     */
    public static void startCrafting(EntityMaid maid, ItemStack targetStack) {
        if (!isLoaded()) return;
        Inner.startCrafting(maid, targetStack);
    }

    /**
     * 女仆是否正处于我们发起的自动合成流程中
     */
    public static boolean isAutoCrafting(EntityMaid maid) {
        return maid.getPersistentData().getBoolean(Inner.TAG_AUTO_CRAFT);
    }

    /**
     * 自动合成失败冷却中（缺材料 / 缺少合成计算器等），期间不再重复发起
     */
    public static boolean isOnCooldown(EntityMaid maid, ServerLevel level) {
        long until = maid.getPersistentData().getLong(Inner.TAG_CRAFT_COOLDOWN);
        return until > 0 && level.getGameTime() < until;
    }

    /**
     * 注册自动合成的每 tick 状态机监听器。
     * 由 {@link io.github.tlmsmartcombat.TlmSmartCombat} 在模组初始化时调用。
     */
    public static void registerReturnCheck() {
        NeoForge.EVENT_BUS.addListener(EntityTickEvent.Post.class, event -> {
            if (event.getEntity() instanceof EntityMaid maid && !maid.level().isClientSide) {
                if (isLoaded()) {
                    Inner.tickAutoCraft(maid);
                }
            }
        });
    }

    private static final class Inner {
        private static final String TAG_AUTO_CRAFT = "tlm_smart_combat:auto_craft";
        private static final String TAG_CRAFT_TARGET = "tlm_smart_combat:craft_target";
        private static final String TAG_CRAFT_COUNT = "tlm_smart_combat:craft_count";
        private static final String TAG_CRAFT_COOLDOWN = "tlm_smart_combat:craft_cooldown";
        private static final String VIRTUAL_MARKER = "tlm_smart_combat";
        /**
         * 合成失败 / 条件不满足后的重试冷却：5 分钟
         */
        private static final int COOLDOWN_FAIL_TICKS = 6000;
        /**
         * 合成结束后取回产物的容器扫描半径（格）
         */
        private static final double RETRIEVE_RADIUS = 24.0;
        /**
         * 成品耐久替换阈值：女仆已拥有的同款物品剩余耐久低于该值时才允许重新合成
         */
        private static final int DURABILITY_REPLACE_THRESHOLD = 10;

        private Inner() {
        }

        /**
         * 女仆（双手 / 盔甲栏 / 背包）是否已拥有与候选物同款且耐久充足（或不可损坏）的成品。
         * 已有成品时不再重复合成，避免背包被同种物品填满。
         */
        static boolean ownsIntactProduct(EntityMaid maid, ItemStack candidate) {
            if (isIntactDuplicate(maid.getMainHandItem(), candidate)) return true;
            if (isIntactDuplicate(maid.getOffhandItem(), candidate)) return true;
            for (EquipmentSlot slot : new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                if (isIntactDuplicate(maid.getItemBySlot(slot), candidate)) return true;
            }
            var inv = maid.getAvailableInv(false);
            for (int i = 0; i < inv.getSlots(); i++) {
                if (isIntactDuplicate(inv.getStackInSlot(i), candidate)) return true;
            }
            return false;
        }

        private static boolean isIntactDuplicate(ItemStack owned, ItemStack candidate) {
            if (owned.isEmpty() || !ItemStack.isSameItem(owned, candidate)) return false;
            if (!owned.isDamageableItem()) return true;
            return owned.getMaxDamage() - owned.getDamageValue() >= DURABILITY_REPLACE_THRESHOLD;
        }

        @Nullable
        static ItemStack findBestUpgrade(EntityMaid maid, ServerLevel level) {
            var bauble = maid.getMaidBauble();
            LivingEntity target = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                    .filter(LivingEntity::isAlive).orElse(null);
            ItemStack currentWeapon = maid.getMainHandItem();

            double currentWeaponScore = EquipOptimizer.scoreMainHand(maid, currentWeapon, target);
            double bestWeaponScore = currentWeaponScore;
            ItemStack bestWeaponStack = null;

            // 盔甲评分
            double[] currentArmorScores = new double[4];
            EquipmentSlot[] armorSlots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            for (int a = 0; a < 4; a++) {
                currentArmorScores[a] = EquipOptimizer.armorScore(maid, maid.getItemBySlot(armorSlots[a]), armorSlots[a]);
            }
            double[] bestArmorScores = currentArmorScores.clone();
            ItemStack[] bestArmorStacks = new ItemStack[4];

            int guideCount = 0;
            int outputCount = 0;
            for (int i = 0; i < bauble.getSlots(); i++) {
                ItemStack baubleStack = bauble.getStackInSlot(i);
                if (baubleStack.isEmpty()) continue;
                if (!(baubleStack.getItem() instanceof studio.fantasyit.maid_storage_manager.items.LogisticsGuide)) continue;

                var craftData = studio.fantasyit.maid_storage_manager.items.LogisticsGuide
                        .getCraftGuideData(baubleStack, level.registryAccess());
                if (craftData == null) {
                    TlmSmartCombat.LOGGER.info("[SmartCombat] {} 物流清单槽 {} 不含合成指南，跳过",
                            maid.getName().getString(), i);
                    continue;
                }

                List<ItemStack> outputs = craftData.outputs;
                if (outputs == null || outputs.isEmpty()) {
                    TlmSmartCombat.LOGGER.info("[SmartCombat] {} 物流清单槽 {} 的合成指南无输出物品，跳过",
                            maid.getName().getString(), i);
                    continue;
                }
                guideCount++;
                outputCount += outputs.size();

                for (ItemStack output : outputs) {
                    if (output.isEmpty()) continue;

                    // 女仆已拥有同款且耐久充足的成品 → 不再重复合成
                    // （若成品耐久低于阈值则允许合成新品替换）
                    if (ownsIntactProduct(maid, output)) continue;

                    // 按武器（含拔刀剑）评分
                    WeaponKind kind = EquipOptimizer.classifyPublic(output);
                    if (kind.isWeaponLike()) {
                        double score = EquipOptimizer.scoreMainHand(maid, output, target);
                        if (score > bestWeaponScore) {
                            bestWeaponScore = score;
                            bestWeaponStack = output.copy();
                        }
                    }

                    // 按盔甲评分：仅评估该物品实际可穿戴的部位
                    EquipmentSlot fitted = maid.getEquipmentSlotForItem(output);
                    if (fitted.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                        for (int a = 0; a < 4; a++) {
                            if (armorSlots[a] != fitted) continue;
                            double score = EquipOptimizer.armorScore(maid, output, fitted);
                            if (score > bestArmorScores[a]) {
                                bestArmorScores[a] = score;
                                bestArmorStacks[a] = output.copy();
                            }
                        }
                    }
                }
            }
            TlmSmartCombat.LOGGER.info(
                    "[SmartCombat] {} 装备评估：合成指南 {} 份 / 产物 {} 种，当前武器评分 {}，最优可合成武器评分 {}",
                    maid.getName().getString(), guideCount, outputCount,
                    String.format("%.2f", currentWeaponScore), String.format("%.2f", bestWeaponScore));

            // 优先武器，其次盔甲（取最高评分差异者）
            if (bestWeaponStack != null) return bestWeaponStack;
            ItemStack bestArmor = null;
            double bestArmorGain = 0;
            for (int a = 0; a < 4; a++) {
                if (bestArmorStacks[a] != null) {
                    double gain = bestArmorScores[a] - currentArmorScores[a];
                    if (gain > bestArmorGain) {
                        bestArmorGain = gain;
                        bestArmor = bestArmorStacks[a];
                    }
                }
            }
            return bestArmor;
        }

        static void startCrafting(EntityMaid maid, ItemStack targetStack) {
            var data = maid.getPersistentData();
            if (data.getBoolean(TAG_AUTO_CRAFT)) return;

            // 缺少便携式合成计算器时 MSM 的规划器必然失败，提前中止并进入冷却
            if (!studio.fantasyit.maid_storage_manager.Config.craftingNoCalculator
                    && studio.fantasyit.maid_storage_manager.items.PortableCraftCalculatorBauble
                    .getCalculator(maid).isEmpty()) {
                data.putLong(TAG_CRAFT_COOLDOWN, maid.level().getGameTime() + COOLDOWN_FAIL_TICKS);
                TlmSmartCombat.LOGGER.warn(
                        "[tlm_smart_combat] Maid {} has no portable craft calculator, auto crafting skipped",
                        maid.getName().getString());
                return;
            }

            var storageTask = TaskManager.getTaskMap().get(STORAGE_TASK_UID);
            if (storageTask == null) return;

            // 背包满时 MSM 的请求流程会立刻终止（或转入存放日程），发起前必须确保有空位
            if (!hasFreeSlot(maid)) {
                data.putLong(TAG_CRAFT_COOLDOWN, maid.level().getGameTime() + COOLDOWN_FAIL_TICKS);
                TlmSmartCombat.LOGGER.warn(
                        "[tlm_smart_combat] Maid {} backpack is full, auto crafting skipped",
                        maid.getName().getString());
                return;
            }

            TlmSmartCombat.LOGGER.info("[SmartCombat] {} 开始自动合成：{}",
                    maid.getName().getString(), targetStack.getHoverName().getString());

            // 清理背包中残留的、由我们生成的虚拟请求清单，避免之后被反复拾取处理
            purgeMarkedLists(maid);
            // 把当前主手物品（通常是武器）先收进背包，避免被请求清单直接覆盖丢失
            stowMainHand(maid);

            // 创建虚拟请求列表（结束后 MSM 会自动销毁）
            ItemStack reqItem = studio.fantasyit.maid_storage_manager.util.RequestItemUtil
                    .makeVirtualItemStack(List.of(targetStack), null, null, "AI");
            CompoundTag marker = new CompoundTag();
            marker.putBoolean(VIRTUAL_MARKER, true);
            studio.fantasyit.maid_storage_manager.items.RequestListItem.setVirtualData(reqItem, marker);

            // 设置到主手
            maid.setItemInHand(InteractionHand.MAIN_HAND, reqItem);

            // 标记自动合成并记录目标，用于结束判定
            data.putBoolean(TAG_AUTO_CRAFT, true);
            data.putString(TAG_CRAFT_TARGET,
                    BuiltInRegistries.ITEM.getKey(targetStack.getItem()).toString());
            data.putInt(TAG_CRAFT_COUNT, Math.max(1, targetStack.getCount()));

            // 切换任务
            maid.setTask(storageTask);
        }

        /**
         * 自动合成状态机，每 tick 由实体 tick 事件驱动：
         * <ol>
         * <li>任务被外部切换 → 仅清理状态；</li>
         * <li>发现敌对生物 → 中止合成（销毁虚拟清单）并切回战斗；</li>
         * <li>合成进行中 → 定期注入物流清单饰品中的配方；</li>
         * <li>清单被回收且无合成计划 → 判定成功 / 失败，清理并切回战斗。</li>
         * </ol>
         */
        static void tickAutoCraft(EntityMaid maid) {
            var data = maid.getPersistentData();
            if (!data.getBoolean(TAG_AUTO_CRAFT)) return;

            // 任务被外部切换（如玩家手动切换）→ 仅清理状态
            if (!STORAGE_TASK_UID.equals(maid.getTask().getUid())) {
                data.remove(TAG_AUTO_CRAFT);
                data.remove(TAG_CRAFT_TARGET);
                data.remove(TAG_CRAFT_COUNT);
                return;
            }

            // 敌对生物出现 → 立即中止合成并切回战斗
            if (hasNearbyHostile(maid, 16.0)) {
                TlmSmartCombat.LOGGER.info("[SmartCombat] {} 检测到敌对生物，中止自动合成",
                        maid.getName().getString());
                ItemStack hand = maid.getMainHandItem();
                if (studio.fantasyit.maid_storage_manager.items.RequestListItem.isVirtual(hand)) {
                    studio.fantasyit.maid_storage_manager.util.RequestItemUtil
                            .stopJobAndStoreOrThrowItem(maid, null, null);
                }
                studio.fantasyit.maid_storage_manager.util.MemoryUtil.getCrafting(maid).clearPlan();
                purgeMarkedLists(maid);
                data.remove(TAG_AUTO_CRAFT);
                data.remove(TAG_CRAFT_TARGET);
                data.remove(TAG_CRAFT_COUNT);
                switchToCombat(maid);
                return;
            }

            // 关键：把物流清单饰品中记录的配方注入女仆的合成记忆。
            // MSM 原生流程只会读取箱子里发现的合成指南物品，
            // 没有配方的合成规划器必然判定失败（"无法开始合成"的根因）。
            if (maid.tickCount % 10 == 0) {
                injectBaubleCraftGuides(maid);
            }

            // 结束检测：主手清单已被 MSM 回收，且没有正在执行的合成计划
            boolean holdingList = maid.getMainHandItem().getItem()
                    instanceof studio.fantasyit.maid_storage_manager.items.RequestListItem;
            boolean hasPlan = studio.fantasyit.maid_storage_manager.util.MemoryUtil
                    .getCrafting(maid).hasPlan();
            if (!holdingList && !hasPlan) {
                String targetId = data.getString(TAG_CRAFT_TARGET);
                boolean success = !targetId.isEmpty() && containsItem(maid, targetId);
                if (!success && !targetId.isEmpty()) {
                    // MSM 在合成成功后会进入"回存背包"日程，把产物倒入存储容器。
                    // 这里立刻从附近容器中把产物取回女仆背包，使其能直接用于战斗。
                    int want = Math.max(1, data.getInt(TAG_CRAFT_COUNT));
                    success = retrieveFromNearbyContainer(maid, targetId, want);
                    if (success) {
                        TlmSmartCombat.LOGGER.info("[SmartCombat] {} 已从容器取回合成产物",
                                maid.getName().getString());
                    }
                }
                if (!success) {
                    // 未获得目标物 → 合成失败（缺材料等），进入冷却避免空转
                    data.putLong(TAG_CRAFT_COOLDOWN, maid.level().getGameTime() + COOLDOWN_FAIL_TICKS);
                }
                TlmSmartCombat.LOGGER.info("[SmartCombat] {} 自动合成结束：{}",
                        maid.getName().getString(), success ? "成功" : "失败（未获得目标物，进入冷却）");
                purgeMarkedLists(maid);
                data.remove(TAG_AUTO_CRAFT);
                data.remove(TAG_CRAFT_TARGET);
                data.remove(TAG_CRAFT_COUNT);
                switchToCombat(maid);
            }
        }

        /**
         * 将女仆饰品栏中所有物流清单内嵌的合成指南注入 MSM 的合成记忆
         */
        static void injectBaubleCraftGuides(EntityMaid maid) {
            var bauble = maid.getMaidBauble();
            var crafting = studio.fantasyit.maid_storage_manager.util.MemoryUtil.getCrafting(maid);
            var existing = crafting.getCraftGuides();
            for (int i = 0; i < bauble.getSlots(); i++) {
                ItemStack stack = bauble.getStackInSlot(i);
                if (stack.isEmpty()) continue;
                if (!(stack.getItem() instanceof studio.fantasyit.maid_storage_manager.items.LogisticsGuide)) continue;
                var guideData = studio.fantasyit.maid_storage_manager.items.LogisticsGuide
                        .getCraftGuideData(stack, maid.level().registryAccess());
                if (guideData == null) continue;
                if (!existing.contains(guideData)) {
                    crafting.addCraftGuide(guideData);
                }
            }
        }

        /**
         * 删除背包中所有由本模组生成的虚拟请求清单（不清除玩家或其他来源的清单）
         */
        static void purgeMarkedLists(EntityMaid maid) {
            var inv = maid.getAvailableInv(false);
            ItemStack hand = maid.getMainHandItem();
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.isEmpty() || stack == hand) continue;
                if (!(stack.getItem() instanceof studio.fantasyit.maid_storage_manager.items.RequestListItem)) continue;
                if (!studio.fantasyit.maid_storage_manager.items.RequestListItem.isVirtual(stack)) continue;
                CompoundTag vd = studio.fantasyit.maid_storage_manager.items.RequestListItem.getVirtualData(stack);
                if (vd != null && vd.getBoolean(VIRTUAL_MARKER)) {
                    inv.setStackInSlot(i, ItemStack.EMPTY);
                }
            }
        }

        /**
         * 把主手物品收进背包；背包满时丢在脚下，绝不让物品凭空消失
         */
        static void stowMainHand(EntityMaid maid) {
            ItemStack hand = maid.getMainHandItem();
            if (hand.isEmpty()) return;
            var inv = maid.getAvailableInv(false);
            ItemStack rest = hand;
            for (int i = 0; i < inv.getSlots() && !rest.isEmpty(); i++) {
                rest = inv.insertItem(i, rest, false);
            }
            maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (!rest.isEmpty()) {
                maid.spawnAtLocation(rest);
            }
        }

        /**
         * 背包是否还有空位（发起合成的前提：MSM 在背包满时会立即终止请求流程）
         */
        static boolean hasFreeSlot(EntityMaid maid) {
            var inv = maid.getAvailableInv(false);
            for (int i = 0; i < inv.getSlots(); i++) {
                if (inv.getStackInSlot(i).isEmpty()) return true;
            }
            return false;
        }

        /**
         * 背包或主手中是否已存在指定物品
         */
        static boolean containsItem(EntityMaid maid, String itemId) {
            ResourceLocation key = ResourceLocation.tryParse(itemId);
            if (key == null) return true;
            var item = BuiltInRegistries.ITEM.get(key);
            if (maid.getMainHandItem().is(item)) return true;
            var inv = maid.getAvailableInv(false);
            for (int i = 0; i < inv.getSlots(); i++) {
                if (inv.getStackInSlot(i).is(item)) return true;
            }
            return false;
        }

        /**
         * 从附近容器中取回指定物品放入女仆背包。
         * <p>
         * MSM 合成成功后的"回存背包"日程会把产物存入容器；
         * 该方法在合成流程结束时立即把产物取回，实现"合成的装备直接用于战斗"。
         * 由近及远扫描 {@value #RETRIEVE_RADIUS} 格内的可访问容器，取够 count 即停。
         *
         * @return 是否取回了至少一个目标物品
         */
        static boolean retrieveFromNearbyContainer(EntityMaid maid, String itemId, int count) {
            ResourceLocation key = ResourceLocation.tryParse(itemId);
            if (key == null) return false;
            var item = BuiltInRegistries.ITEM.get(key);
            ServerLevel level = (ServerLevel) maid.level();
            BlockPos center = maid.blockPosition();

            // 收集半径内的容器位置，按距离从近到远排序
            List<BlockPos> positions = new ArrayList<>();
            int r = (int) Math.ceil(RETRIEVE_RADIUS);
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos pos = center.offset(dx, dy, dz);
                        if (center.distSqr(pos) <= RETRIEVE_RADIUS * RETRIEVE_RADIUS && level.isLoaded(pos)) {
                            positions.add(pos.immutable());
                        }
                    }
                }
            }
            positions.sort(Comparator.comparingDouble(center::distSqr));

            int remaining = count;
            for (BlockPos pos : positions) {
                IItemHandler container = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
                if (container == null) continue;
                // 尊重 maid_storage_manager 的访问权标记
                if (!StorageCompat.isAccessibleStorage(level, maid, pos)) continue;
                for (int slot = 0; slot < container.getSlots() && remaining > 0; slot++) {
                    ItemStack stack = container.getStackInSlot(slot);
                    if (!stack.is(item)) continue;
                    ItemStack extracted = container.extractItem(slot, remaining, false);
                    if (extracted.isEmpty()) continue;
                    remaining -= extracted.getCount();
                    ItemStack rest = StorageCompat.insertIntoInv(maid.getAvailableInv(false), extracted);
                    if (!rest.isEmpty()) {
                        // 背包装不下则退回容器
                        container.insertItem(slot, rest, false);
                        return remaining < count;
                    }
                }
                if (remaining <= 0) break;
            }
            return remaining < count;
        }

        static void switchToCombat(EntityMaid maid) {
            var combatTask = TaskManager.getTaskMap()
                    .get(io.github.tlmsmartcombat.task.TaskSmartCombat.UID);
            if (combatTask != null) {
                maid.setTask(combatTask);
            }
        }
    }
}
