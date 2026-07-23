# Touhou Little Maid Smart Combat（女仆智能战斗附属模组）

适用于 **Minecraft 1.21.1 + NeoForge** 的 [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid/tree/1.21)（1.5.3+）附属模组。

为女仆新增工作模式 **「智能战斗」（Smart Combat）**，在原版“战斗”模式基础上提供：

1. **高生命值优先索敌**：在可攻击的敌对生物中，优先锁定**当前生命值最高**的目标（生命值相同则比较最大生命值，再比较距离）。
2. **基于物品栏的动态策略**：每 0.75 秒扫描一次女仆背包，针对**当前攻击目标**估算每件武器的实际 DPS，并自动换装：
   - **主手**：在近战武器（剑/斧/重锤/模组武器）、弓、弩、三叉戟之间选择对当前目标 DPS 最高者；
   - **副手**：近战形态下装备盾牌（敌人近身时女仆会举盾格挡），远程形态下装备不死图腾；
   - **盔甲**：头盔/胸甲/护腿/靴子四个部位分别换上背包中防护评分最高者。
3. **走位自适应**：主手为近战武器时贴身追击；为弓/弩时进入射程后站桩射击并走位；三叉戟近身平砍、远程投掷。

## DPS 估算模型

| 武器类型 | 估算公式 |
| --- | --- |
| 近战 | `(女仆空手攻击 + 武器攻击修饰 + 附魔加成) × (女仆攻速 + 武器攻速修饰)` |
| 弓 | `9 × max(1, 女仆基础攻击/2) + 力量附魔加成`，周期 = 20 tick 蓄力 + 射击冷却属性 |
| 弩 | 平均箭伤 9（多重射击 ×1.3），周期 = 装填时长（含快速装填）+ 攻击延迟 |
| 三叉戟（投掷） | 8 + 穿刺加成（对水生生物），周期 = 三叉戟冷却属性 |

伤害会进一步按**目标当前护甲/护甲韧性**（原版减伤公式）折算，并乘以**距离因子**
（近战按接近耗时打折，远程超出射程大幅打折）。附魔加成涵盖锋利/亡灵杀手/节肢杀手/穿刺/力量/多重射击/快速装填/无限，
耐久即将耗尽的武器会被大幅降权。换装设有增益阈值（约 8%），避免两件相近装备之间来回切换。

> 所有数值为用于相对比较的估算值，不追求逐点精确。

## 构建

需要 JDK 21 与约 8GB 可用内存（NeoForge 开发环境需要对 Minecraft 反编译/重编译）：

```bash
./gradlew build
```

产物位于 `build/libs/tlm_smart_combat-1.0.0.jar`。

## 安装

1. 安装 Minecraft 1.21.1 + NeoForge 21.1.x；
2. 安装 Touhou Little Maid 1.5.3+（NeoForge 版）；
3. 将本模组 jar 放入 `mods` 文件夹；
4. 进入游戏后，在女仆的任务列表中选择 **「智能战斗」** 即可。

## 代码结构

```
io.github.tlmsmartcombat
├── TlmSmartCombat              # @Mod 主类
├── SmartCombatExtension        # @LittleMaidExtension 扩展入口，注册任务
├── task/TaskSmartCombat        # 智能战斗任务（索敌、攻击行为编排、远程攻击执行）
├── ai/
│   ├── SmartEquipBehavior      # 周期性触发装备优化的 Brain 行为
│   ├── SmartCombatMoveTask     # 按武器形态切换近战/远程走位
│   ├── SmartBowAttackTask      # 弓射击（排除弩，避免与 MaidCrossbowAttack 冲突）
│   └── SmartShieldTask         # 近战形态下举盾（远程形态禁用）
└── strategy/EquipOptimizer     # DPS 估算与主手/副手/盔甲换装逻辑
```

实现基于 Touhou Little Maid 官方扩展 API（`@LittleMaidExtension` + `ILittleMaid.addMaidTask`），
并复用本体的攻击行为（`MaidMeleeAttack`、`MaidShootTargetTask`、`MaidCrossbowAttack`、
`MaidTridentTargetTask`、`MaidAttackStrafingTask`、`MaidUseShieldTask` 等），与本体行为保持一致。
