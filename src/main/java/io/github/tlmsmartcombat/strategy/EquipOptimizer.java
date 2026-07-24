package io.github.tlmsmartcombat.strategy;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitAttribute;
import com.github.tartaricacid.touhoulittlemaid.util.ItemsUtil;
import io.github.tlmsmartcombat.TlmSmartCombat;
import io.github.tlmsmartcombat.compat.SlashBladeCompat;
import io.github.tlmsmartcombat.compat.TruePowerCompat;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;

import javax.annotation.Nullable;

/**
 * 女仆战斗装备优化器。
 * <p>
 * 周期性扫描女仆背包，针对“当前攻击目标”评估每一件可用武器的预期 DPS，
 * 并把最优组合换上装备栏：
 * <ul>
 *     <li>主手：近战武器 / 弓 / 弩 / 三叉戟 中对当前目标 DPS 最高者；</li>
 *     <li>副手：近战时盾牌，远程时不死图腾；</li>
 *     <li>盔甲：四个部位分别取背包中防护评分最高者。</li>
 * </ul>
 * DPS 计算考虑：武器面板伤害与攻速、伤害类附魔（锋利/亡灵杀手/节肢杀手/穿刺/力量/多重射击/快速装填）、
 * 目标的护甲与护甲韧性削减、目标距离（近战接近惩罚、远程射程惩罚）以及武器剩余耐久。
 * <p>
 * 所有数值均为估算值，用于相对比较，而非精确模拟。
 */
public final class EquipOptimizer {
    /**
     * 主手换装增益阈值：新武器评分需超过当前武器的一定比例才换，避免来回抖动
     */
    private static final double WEAPON_SWAP_RATIO = 1.08;
    private static final double WEAPON_SWAP_ABS = 0.25;
    /**
     * 副手 / 盔甲换装阈值
     */
    private static final double OFFHAND_SWAP_MARGIN = 20;
    private static final double ARMOR_SWAP_MARGIN = 1.0;
    /**
     * 近战武器的默认攻击距离（格），用于距离惩罚估算
     */
    private static final double MELEE_REACH_ESTIMATE = 3.5;

    private EquipOptimizer() {
    }

    /**
     * 执行一次完整的装备优化。
     *
     * @param maid  女仆
     * @param level 服务端世界
     */
    public static void optimize(EntityMaid maid, ServerLevel level) {
        LivingEntity target = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET)
                .filter(LivingEntity::isAlive)
                .orElse(null);
        boolean usingItem = maid.isUsingItem();
        if (usingItem) {
            ItemStack mainHand = maid.getMainHandItem();
            // 远程武器弹药耗尽或近战/拔刀剑耐久将尽 — 强制释放以允许换装
            if (isStuckRangedWeapon(maid, mainHand) || isNearlyBroken(mainHand)) {
                maid.stopUsingItem();
                usingItem = false;
            }
        }
        if (!usingItem) {
            optimizeMainHand(maid, target);
            optimizeOffHand(maid, target);
        }
        optimizeArmor(maid);
    }

    /**
     * 当前武器耐久是否已降至临界值（≤5），应尽快更换。
     * 涵盖拔刀剑和普通近战武器。
     */
    private static boolean isNearlyBroken(ItemStack stack) {
        return stack.isDamageableItem()
               && stack.getMaxDamage() - stack.getDamageValue() <= 5;
    }

    /**
     * 当前主手物品是否为一个卡在使用状态中的远程武器（弓 / 弩无弹药且无无限附魔）。
     * <p>
     * 处于此状态时女仆无法开火，但又因 isUsingItem 被保护而无法触发换装，
     * 形成"死拉弓不射箭"的循环。
     */
    public static boolean isStuckRangedWeapon(EntityMaid maid, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof BowItem) {
            return !isUsableWeapon(maid, stack);
        }
        if (stack.getItem() instanceof CrossbowItem) {
            return !CrossbowItem.isCharged(stack) && !isUsableWeapon(maid, stack);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 主手武器
    // ------------------------------------------------------------------

    private static void optimizeMainHand(EntityMaid maid, @Nullable LivingEntity target) {
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        ItemStack current = maid.getMainHandItem();
        double currentScore = scoreMainHand(maid, current, target);
        boolean currentUsable = isUsableWeapon(maid, current);

        int bestSlot = -1;
        double bestScore = currentUsable ? currentScore * WEAPON_SWAP_RATIO + WEAPON_SWAP_ABS : currentScore;
        ItemStack bestStack = ItemStack.EMPTY;

        for (int slot = 0; slot < backpack.getSlots(); slot++) {
            ItemStack stack = backpack.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (classify(stack) == WeaponKind.NONE) {
                continue;
            }
            if (!isUsableWeapon(maid, stack)) {
                continue;
            }
            double score = scoreMainHand(maid, stack, target);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
                bestStack = stack;
            }
        }

        if (bestSlot >= 0) {
            swapBackpackToHand(maid, backpack, bestSlot, InteractionHand.MAIN_HAND);
            TlmSmartCombat.LOGGER.debug("[SmartCombat] {} 主手换装: {} -> {} (评分 {})",
                    maid.getName().getString(), current.getHoverName().getString(),
                    bestStack.getHoverName().getString(), String.format("%.2f", bestScore));
        }
    }

    /**
     * 为物品栏中的一件武器估算对当前目标的 DPS 评分。
     */
    public static double scoreMainHand(EntityMaid maid, ItemStack stack, @Nullable LivingEntity target) {
        if (stack.isEmpty()) {
            return 0;
        }
        WeaponKind kind = classify(stack);
        double score = switch (kind) {
            case MELEE -> meleeDps(maid, stack, target);
            case SLASH_BLADE -> slashBladeDps(maid, stack, target);
            case BOW -> bowDps(maid, stack, target);
            case CROSSBOW -> crossbowDps(maid, stack, target);
            case TRIDENT -> Math.max(meleeDps(maid, stack, target), thrownTridentDps(maid, stack, target));
            case NONE -> 0;
        };
        // 耐久惩罚：即将损坏的武器大幅降权，耐久越充足略微加分（用于同分时的稳定选择）
        if (stack.isDamageableItem()) {
            int remaining = stack.getMaxDamage() - stack.getDamageValue();
            if (remaining <= 2) {
                score *= 0.05;
            } else {
                score += 0.1 * remaining / (double) stack.getMaxDamage();
            }
        }
        return score;
    }

    private static WeaponKind classify(ItemStack stack) {
        if (SlashBladeCompat.isSlashBladeItem(stack)) {
            return WeaponKind.SLASH_BLADE;
        }
        if (stack.getItem() instanceof TridentItem) {
            return WeaponKind.TRIDENT;
        }
        if (stack.getItem() instanceof CrossbowItem) {
            return WeaponKind.CROSSBOW;
        }
        if (stack.getItem() instanceof BowItem) {
            return WeaponKind.BOW;
        }
        if (isMeleeWeapon(stack)) {
            return WeaponKind.MELEE;
        }
        return WeaponKind.NONE;
    }

    /**
     * 是否为近战武器：带有适用于主手的攻击伤害属性修饰符（剑 / 斧 / 锄 / 重锤以及模组武器等）
     */
    public static boolean isMeleeWeapon(ItemStack stack) {
        boolean[] found = {false};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * 当前状态下该武器是否能真正投入战斗（弓弩需要有弹药或有不少于一个弹药的同时有无限附魔）
     */
    public static boolean isUsableWeapon(EntityMaid maid, ItemStack stack) {
        WeaponKind kind = classify(stack);
        return switch (kind) {
            case MELEE, TRIDENT, SLASH_BLADE -> true;
            case BOW -> hasAmmo(maid, stack) || (hasAmmo(maid, stack) && enchantLevel(maid, Enchantments.INFINITY, stack) > 0);
            case CROSSBOW -> hasAmmo(maid, stack);
            case NONE -> false;
        };
    }

    /**
     * 背包（含双手）中是否存在该弹射武器可用的弹药
     */
    public static boolean hasAmmo(EntityMaid maid, ItemStack weaponStack) {
        if (!(weaponStack.getItem() instanceof ProjectileWeaponItem projectile)) {
            return false;
        }
        CombinedInvWrapper inv = maid.getAvailableInv(true);
        return ItemsUtil.findStackSlot(inv, projectile.getAllSupportedProjectiles()) > 0;
    }

    // ------------------------------------------------------------------
    // DPS 估算
    // ------------------------------------------------------------------

    /**
     * 近战 DPS：单次伤害 × 攻击速度，与女仆实际近战 AI 的伤害公式一致
     * <p>
     * 女仆近战攻击使用属性 Attributes.ATTACK_DAMAGE（含主手武器修饰），
     * 冷却由 Attributes.ATTACK_SPEED 决定。
     */
    private static double meleeDps(EntityMaid maid, ItemStack stack, @Nullable LivingEntity target) {
        double damageMod = attributeAmount(stack, Attributes.ATTACK_DAMAGE, EquipmentSlot.MAINHAND);
        double speedMod = attributeAmount(stack, Attributes.ATTACK_SPEED, EquipmentSlot.MAINHAND);

        double baseDamage = attrValueWithoutItem(maid, Attributes.ATTACK_DAMAGE, EquipmentSlot.MAINHAND);
        double baseSpeed = attrValueWithoutItem(maid, Attributes.ATTACK_SPEED, EquipmentSlot.MAINHAND);

        double damage = Math.max(0, baseDamage + damageMod);
        damage += enchantDamageBonus(maid, stack, target);
        double speed = Mth.clamp(baseSpeed + speedMod, 0, 20);

        double perHit = applyArmorReduction(damage, target);
        return perHit * speed * distanceFactor(maid, target, true);
    }

    /**
     * 弓 DPS 估算。
     * <p>
     * 依据 TLM 的实现：满弦箭伤害 ≈ 9 × max(1, 女仆基础攻击 / 2)，力量附魔再加成；
     * 射击周期 = 1 秒蓄力 + 射击冷却属性。
     */
    private static double bowDps(EntityMaid maid, ItemStack bow, @Nullable LivingEntity target) {
        double base = attrValueWithoutItem(maid, Attributes.ATTACK_DAMAGE, EquipmentSlot.MAINHAND);
        double damage = 9.0 * Math.max(1, base / 2);
        int power = enchantLevel(maid, Enchantments.POWER, bow);
        if (power > 0) {
            damage += 0.5 * power + 0.5;
        }
        double cycleTicks = 20 + attributeValue(maid, InitAttribute.MAID_SHOOT_COOLDOWN, 2);
        if (cycleTicks <= 0) {
            cycleTicks = 1;
        }
        return applyArmorReduction(damage, target) * (20.0 / cycleTicks) * distanceFactor(maid, target, false);
    }

    /**
     * 弩 DPS 估算。
     * <p>
     * 弩走原版 performCrossbowAttack 逻辑，箭伤害平均约 9；
     * 周期 = 装填时长（含快速装填附魔）+ 攻击延迟（受弩攻速属性影响）；
     * 多重射击按期望多命中 0.3 支箭估算。
     */
    private static double crossbowDps(EntityMaid maid, ItemStack crossbow, @Nullable LivingEntity target) {
        double damage = 9.0;
        int multishot = enchantLevel(maid, Enchantments.MULTISHOT, crossbow);
        if (multishot > 0) {
            damage *= 1.3;
        }
        double chargeTicks = CrossbowItem.getChargeDuration(crossbow, maid);
        double delayTicks = 30 / Math.max(0.1, attributeValue(maid, InitAttribute.MAID_CROSSBOW_ATTACK_SPEED, 1));
        double cycleTicks = chargeTicks + delayTicks;
        return applyArmorReduction(damage, target) * (20.0 / cycleTicks) * distanceFactor(maid, target, false);
    }

    /**
     * 投掷三叉戟 DPS 估算。
     * <p>
     * TLM 的三叉戟投掷不消耗本体（掷出复制品），伤害 8 + 穿刺加成（对水生生物），
     * 周期由三叉戟冷却属性决定（默认 20 tick）。
     */
    private static double thrownTridentDps(EntityMaid maid, ItemStack trident, @Nullable LivingEntity target) {
        double damage = 8;
        int impaling = enchantLevel(maid, Enchantments.IMPALING, trident);
        if (impaling > 0 && target != null && target.getType().is(EntityTypeTags.SENSITIVE_TO_IMPALING)) {
            damage += 2.5 * impaling;
        }
        double cycleTicks = Math.max(1, attributeValue(maid, InitAttribute.MAID_TRIDENT_COOLDOWN, 20));
        return applyArmorReduction(damage, target) * (20.0 / cycleTicks) * distanceFactor(maid, target, false);
    }

    /**
     * 拔刀剑 DPS 估算。
     * <p>
     * 拔刀剑的真实面板伤害存放在 BladeState（基础攻击修正 + 攻击增幅）中，
     * 原版物品属性修饰符不能反映真实伤害，优先使用 BladeState 数值；
     * 读取失败时回退为普通近战估算。
     * <p>
     * 攻速方面：安装 TPOM 时拔刀剑连击 AI 每 4 tick 即可出手一次（约 5 次/秒），
     * 远高于原版攻速属性；未安装时按原版近战攻速估算。
     */
    private static double slashBladeDps(EntityMaid maid, ItemStack stack, @Nullable LivingEntity target) {
        double meleeFallback = meleeDps(maid, stack, target);
        double bladeDamage = SlashBladeCompat.getBladeAttackDamage(stack);
        if (bladeDamage <= 0) {
            return meleeFallback;
        }
        double baseSpeed = attrValueWithoutItem(maid, Attributes.ATTACK_SPEED, EquipmentSlot.MAINHAND);
        double speed = Mth.clamp(baseSpeed + attributeAmount(stack, Attributes.ATTACK_SPEED, EquipmentSlot.MAINHAND), 0, 20);
        if (TruePowerCompat.isLoaded()) {
            // TPOM 连击 AI 固定每 4 tick 出手一次
            speed = Math.max(speed, 5.0);
        }
        double damage = bladeDamage + enchantDamageBonus(maid, stack, target);
        double blade = applyArmorReduction(damage, target) * speed * distanceFactor(maid, target, true);
        return Math.max(meleeFallback, blade);
    }

    /**
     * 伤害类附魔加成估算（锋利 / 亡灵杀手 / 节肢杀手）。
     * 无目标时仅计算通用加成（锋利）。
     */
    private static double enchantDamageBonus(EntityMaid maid, ItemStack stack, @Nullable LivingEntity target) {
        double bonus = 0;
        int sharpness = enchantLevel(maid, Enchantments.SHARPNESS, stack);
        if (sharpness > 0) {
            bonus += 0.5 * sharpness + 0.5;
        }
        if (target != null) {
            int smite = enchantLevel(maid, Enchantments.SMITE, stack);
            if (smite > 0 && target.getType().is(EntityTypeTags.SENSITIVE_TO_SMITE)) {
                bonus += 2.5 * smite;
            }
            int bane = enchantLevel(maid, Enchantments.BANE_OF_ARTHROPODS, stack);
            if (bane > 0 && target.getType().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) {
                bonus += 2.5 * bane;
            }
        }
        return bonus;
    }

    /**
     * 目标护甲削减，按原版公式：伤害 × (1 - min(20, max(护甲/5, 护甲 - 伤害/(2+韧性/4))) / 25)
     */
    private static double applyArmorReduction(double damage, @Nullable LivingEntity target) {
        if (target == null || damage <= 0) {
            return damage;
        }
        double armor = target.getArmorValue();
        AttributeInstance toughnessAttr = target.getAttribute(Attributes.ARMOR_TOUGHNESS);
        double toughness = toughnessAttr == null ? 0 : toughnessAttr.getValue();
        double reduction = Math.min(20, Math.max(armor / 5, armor - damage / (2 + toughness / 4))) / 25;
        return damage * (1 - reduction);
    }

    /**
     * 距离因子：近战需要考虑接近目标的时间成本；远程超出射程则大打折扣。
     */
    private static double distanceFactor(EntityMaid maid, @Nullable LivingEntity target, boolean melee) {
        if (target == null) {
            return 1;
        }
        double distance = maid.distanceTo(target);
        if (melee) {
            if (distance <= MELEE_REACH_ESTIMATE) {
                return 1;
            }
            // 距离越远，接近耗时占比越高，有效 DPS 越低
            return Mth.clamp(MELEE_REACH_ESTIMATE / distance, 0.3, 1);
        }
        float range = maid.searchRadius();
        return distance <= range ? 1 : 0.2;
    }

    // ------------------------------------------------------------------
    // 副手
    // ------------------------------------------------------------------

    private static void optimizeOffHand(EntityMaid maid, @Nullable LivingEntity target) {
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        ItemStack mainHand = maid.getMainHandItem();
        // 主手是弓弩时，举盾会与拉弓/装填争抢“使用物品”状态，此时副手只考虑图腾等功能物品
        boolean rangedMainHand = mainHand.getItem() instanceof ProjectileWeaponItem;

        ItemStack current = maid.getOffhandItem();
        double currentScore = offhandScore(current, rangedMainHand);

        int bestSlot = -1;
        double bestScore = currentScore + OFFHAND_SWAP_MARGIN;
        ItemStack bestStack = ItemStack.EMPTY;

        for (int slot = 0; slot < backpack.getSlots(); slot++) {
            ItemStack stack = backpack.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            double score = offhandScore(stack, rangedMainHand);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
                bestStack = stack;
            }
        }

        // 当前副手物品在新形态下价值归零（例如切到弓后副手盾牌已无用），
        // 若背包里没有更好的选择，则把无用的副手物品收回背包，空出副手
        if (bestSlot >= 0) {
            swapBackpackToHand(maid, backpack, bestSlot, InteractionHand.OFF_HAND);
            TlmSmartCombat.LOGGER.debug("[SmartCombat] {} 副手换装: {} -> {}",
                    maid.getName().getString(), current.getHoverName().getString(),
                    bestStack.getHoverName().getString());
        } else if (currentScore <= 0 && !current.isEmpty() && hasEmptySlot(backpack)) {
            putHandBackToBackpack(maid, backpack, InteractionHand.OFF_HAND);
        }
    }

    /**
     * 副手物品评分：盾牌（近战形态）> 不死图腾 > 其他
     */
    private static double offhandScore(ItemStack stack, boolean rangedMainHand) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (isShield(stack)) {
            if (rangedMainHand) {
                return 0;
            }
            double score = 100;
            // 耐久将尽的盾牌降权
            if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 2) {
                score *= 0.2;
            }
            return score;
        }
        if (stack.is(Items.TOTEM_OF_UNDYING)) {
            return 50;
        }
        return 0;
    }

    /**
     * 是否为可格挡的盾牌：原版盾牌或使用时动作为格挡的模组盾牌
     */
    private static boolean isShield(ItemStack stack) {
        return stack.getItem() instanceof ShieldItem || stack.getUseAnimation() == UseAnim.BLOCK;
    }

    private static boolean hasEmptySlot(CombinedInvWrapper inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void putHandBackToBackpack(EntityMaid maid, CombinedInvWrapper inv, InteractionHand hand) {
        ItemStack stack = maid.getItemInHand(hand);
        if (stack.isEmpty()) {
            return;
        }
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).isEmpty()) {
                inv.setStackInSlot(i, stack);
                maid.setItemInHand(hand, ItemStack.EMPTY);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // 盔甲
    // ------------------------------------------------------------------

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static void optimizeArmor(EntityMaid maid) {
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        for (EquipmentSlot armorSlot : ARMOR_SLOTS) {
            ItemStack current = maid.getItemBySlot(armorSlot);
            double currentScore = armorScore(maid, current, armorSlot);

            int bestSlot = -1;
            double bestScore = currentScore + ARMOR_SWAP_MARGIN;

            for (int slot = 0; slot < backpack.getSlots(); slot++) {
                ItemStack stack = backpack.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                if (getArmorSlot(maid, stack) != armorSlot) {
                    continue;
                }
                double score = armorScore(maid, stack, armorSlot);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = slot;
                }
            }

            if (bestSlot >= 0) {
                swapBackpackToArmor(maid, backpack, bestSlot, armorSlot);
                TlmSmartCombat.LOGGER.debug("[SmartCombat] {} 盔甲换装: {} 栏位换上评分 {} 的装备",
                        maid.getName().getString(), armorSlot.getName(), String.format("%.2f", bestScore));
            }
        }
    }

    /**
     * 判断物品是否为可穿盔甲并返回对应部位；非盔甲返回 null
     */
    @Nullable
    private static EquipmentSlot getArmorSlot(EntityMaid maid, ItemStack stack) {
        EquipmentSlot slot = maid.getEquipmentSlotForItem(stack);
        if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
            return slot;
        }
        return null;
    }

    /**
     * 盔甲评分（公开）：护甲值 + 韧性 ×2 + 击退抗性 ×4 + 保护类附魔加权 + 耐久修正。
     * <p>
     * 供外部兼容层（如 {@link io.github.tlmsmartcombat.compat.CraftCompat}）评估合成品护甲价值。
     */
    public static double armorScore(EntityMaid maid, ItemStack stack, EquipmentSlot armorSlot) {
        if (stack.isEmpty()) {
            return 0;
        }
        double armor = attributeAmount(stack, Attributes.ARMOR, armorSlot);
        double toughness = attributeAmount(stack, Attributes.ARMOR_TOUGHNESS, armorSlot);
        double knockbackResistance = attributeAmount(stack, Attributes.KNOCKBACK_RESISTANCE, armorSlot);

        int protection = enchantLevel(maid, Enchantments.PROTECTION, stack);
        int blast = enchantLevel(maid, Enchantments.BLAST_PROTECTION, stack);
        int projectile = enchantLevel(maid, Enchantments.PROJECTILE_PROTECTION, stack);
        int fire = enchantLevel(maid, Enchantments.FIRE_PROTECTION, stack);
        int thorns = enchantLevel(maid, Enchantments.THORNS, stack);
        int unbreaking = enchantLevel(maid, Enchantments.UNBREAKING, stack);

        double enchantBonus = protection * 1.5 + (blast + projectile + fire) * 1.0 + thorns * 0.5 + unbreaking * 0.2;
        double score = armor + toughness * 2 + knockbackResistance * 4 + enchantBonus;

        if (stack.isDamageableItem()) {
            int remaining = stack.getMaxDamage() - stack.getDamageValue();
            if (remaining <= 2) {
                score *= 0.1;
            } else {
                score += 0.5 * remaining / (double) stack.getMaxDamage();
            }
        }
        return score;
    }

    // ------------------------------------------------------------------
    // 换装工具
    // ------------------------------------------------------------------

    private static void swapBackpackToHand(EntityMaid maid, CombinedInvWrapper backpack, int slot, InteractionHand hand) {
        ItemStack inSlot = backpack.getStackInSlot(slot);
        ItemStack extracted = backpack.extractItem(slot, inSlot.getCount(), false);
        ItemStack current = maid.getItemInHand(hand);
        if (!current.isEmpty()) {
            backpack.setStackInSlot(slot, current);
        }
        // 换手前终止正在使用的旧物品（拔刀剑 combo / 蓄力弓 等），
        // 避免 verifyEquippedItem -> stopUsingItem 在中间打断导致的武器状态残留
        maid.stopUsingItem();
        maid.setItemInHand(hand, extracted);
    }

    private static void swapBackpackToArmor(EntityMaid maid, CombinedInvWrapper backpack, int slot, EquipmentSlot armorSlot) {
        ItemStack inSlot = backpack.getStackInSlot(slot);
        ItemStack extracted = backpack.extractItem(slot, inSlot.getCount(), false);
        ItemStack current = maid.getItemBySlot(armorSlot);
        if (!current.isEmpty()) {
            backpack.setStackInSlot(slot, current);
        }
        maid.setItemSlot(armorSlot, extracted);
    }

    // ------------------------------------------------------------------
    // 属性 / 附魔读取工具
    // ------------------------------------------------------------------

    /**
     * 物品在指定装备槽位上对某属性的修饰量（加法修饰直接累加，乘法修饰按经验系数折算）
     */
    private static double attributeAmount(ItemStack stack, Holder<Attribute> attribute, EquipmentSlot slot) {
        double[] sums = {0, 0};
        stack.forEachModifier(slot, (attr, modifier) -> {
            if (attr.is(attribute)) {
                if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                    sums[0] += modifier.amount();
                } else {
                    sums[1] += modifier.amount();
                }
            }
        });
        // 乘法修饰在武器上极少出现，按对典型基础值（8 点）的倍率折算为等效加值
        return sums[0] + sums[1] * 8;
    }

    /**
     * 女仆某属性的当前值，扣除指定手部物品带来的修饰，得到“空手”值，
     * 用于估算换上候选武器后的属性。
     */
    private static double attrValueWithoutItem(EntityMaid maid, Holder<Attribute> attribute, EquipmentSlot handSlot) {
        AttributeInstance instance = maid.getAttribute(attribute);
        if (instance == null) {
            return 0;
        }
        double value = instance.getValue();
        ItemStack held = maid.getItemBySlot(handSlot);
        if (!held.isEmpty()) {
            double[] flat = {0};
            held.forEachModifier(handSlot, (attr, modifier) -> {
                if (attr.is(attribute) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                    flat[0] += modifier.amount();
                }
            });
            value -= flat[0];
        }
        return value;
    }

    private static double attributeValue(EntityMaid maid, Holder<Attribute> attribute, double fallback) {
        AttributeInstance instance = maid.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    /**
     * 读取物品上指定附魔的等级
     */
    private static int enchantLevel(EntityMaid maid, ResourceKey<Enchantment> key, ItemStack stack) {
        RegistryAccess access = maid.level().registryAccess();
        Holder<Enchantment> holder = access.registryOrThrow(Registries.ENCHANTMENT).getHolder(key).orElse(null);
        return holder == null ? 0 : stack.getEnchantments().getLevel(holder);
    }

    public enum WeaponKind {
        NONE(false), MELEE(true), SLASH_BLADE(true), BOW(true), CROSSBOW(true), TRIDENT(true);

        private final boolean weaponLike;

        WeaponKind(boolean weaponLike) {
            this.weaponLike = weaponLike;
        }

        public boolean isWeaponLike() {
            return weaponLike;
        }
    }

    /**
     * 公开的分类方法（供 CraftCompat 评估合成品）。
     */
    public static WeaponKind classifyPublic(ItemStack stack) {
        return classify(stack);
    }

    /**
     * 判断一个 DPS 评分能否进入女仆当前持有武器（双手 + 背包）的前 N 名。
     * <p>
     * 持有武器不足 N 件时直接视为可进入；否则要求评分超过第 N 名一定余量
     * （{@value #TOP_RANK_MARGIN}），避免与同分物品反复交换。
     */
    public static boolean wouldRankTopWeapons(EntityMaid maid, double score, int n, @Nullable LivingEntity target) {
        if (n <= 0) {
            return false;
        }
        java.util.List<Double> scores = new java.util.ArrayList<>();
        collectWeaponScore(maid, maid.getMainHandItem(), target, scores);
        collectWeaponScore(maid, maid.getOffhandItem(), target, scores);
        CombinedInvWrapper backpack = maid.getAvailableBackpackInv();
        for (int i = 0; i < backpack.getSlots(); i++) {
            collectWeaponScore(maid, backpack.getStackInSlot(i), target, scores);
        }
        if (scores.size() < n) {
            return true;
        }
        scores.sort(java.util.Comparator.reverseOrder());
        return score > scores.get(n - 1) + TOP_RANK_MARGIN;
    }

    private static final double TOP_RANK_MARGIN = 0.05;

    private static void collectWeaponScore(EntityMaid maid, ItemStack stack, @Nullable LivingEntity target, java.util.List<Double> out) {
        if (stack.isEmpty() || classify(stack) == WeaponKind.NONE) {
            return;
        }
        out.add(scoreMainHand(maid, stack, target));
    }
}
