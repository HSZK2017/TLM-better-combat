# Touhou Little Maid Smart Combat（女仆智能战斗附属模组）

适用于 **Minecraft 1.20.1 + Forge** 的 [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid/tree/1.20)（1.5.3+）附属模组。

为女仆新增工作模式 **「智能战斗」（Smart Combat）**：以"护卫主人"为核心的智能索敌、按当前目标实时
估算 DPS 的动态换装、按武器形态自适应走位，并可在空闲时借助
[maid storage manager](https://modrinth.com/mod/maid-storage-manager)（可选联动）的仓库管理员流程
**自动合成更优装备**。

## 功能总览

### 1. 索敌：主人护卫 + 高生命值优先

- 每 10 tick 扫描主人周围 16 格：**正在攻击主人**或**最近伤害过主人**的目标获得最高优先级；
- 其余可攻击目标按 **当前生命值** 排序（厚血 BOSS 优先），同生命值时近距优先；
- 威胁主人的目标不受常规交战距离限制，优先回防。

### 2. 动态装备优化

每 0.75 秒（15 tick）扫描女仆背包，针对**当前攻击目标**估算每件装备的实战评分并换装：

- **主手**：近战武器（剑/斧/重锤/模组武器）、拔刀剑、弓、弩、三叉戟中取 DPS 最高者；
- **副手**：近战形态持盾（敌人近身自动举盾格挡），远程形态持不死图腾；
- **盔甲**：四部位分别换上防护评分最高者（护甲 + 韧性×2 + 击退抗性×4 + 保护类附魔加权）。

换装设有增益阈值（约 8% + 0.25），避免两件相近装备来回抖动；耐久将尽的武器大幅降权，
卡壳的远程武器（无箭拉弓）会被强制释放以允许换装。

### 3. 走位与攻击自适应

- 近战：贴身追击，攻击冷却由攻速属性决定；
- 弓：进入射程站桩射击、走位微调（排除弩，避免与弩行为争抢"使用物品"状态）；
- 弩 / 三叉戟：复用 TLM 本体行为（装填-发射 / 近战平砍-远程投掷）；
- **拔刀剑联动**：安装 [SlashBlade: Resharped](https://modrinth.com/mod/slashblade-resharped) 时识别
  拔刀剑并按其真实面板（BladeState）评估 DPS；再安装 True POWER of Maid 时，
  攻击 / 移动 / 格挡全部让位给 TPOM 的拔刀剑战斗 AI（连击 / 幻影剑 / 瞬步），未安装则回退为普通近战。

### 4. 主人护卫（SmartProtectBehavior）

不依赖武器与攻击目标：即使女仆空手 / 仅持盾牌，也会扫描飞向主人的弹射物与逼近主人的敌人，
主动挡在主人身前格挡。

### 5. 空闲自动合成装备（maid storage manager 联动，可选）

当女仆与主人 16 格内**无敌对生物**（以原版 `Enemy` 标准判定）且安装了
maid storage manager 时，周期性（3 秒）评估女仆饰品栏**物流清单**中记录的合成配方：

- **评估**：对每个可合成产物按当前目标估算 DPS / 盔甲评分，与女仆当前装备比较，
  武器优先于盔甲，取提升最大者；
- **去重**：女仆（双手/盔甲栏/背包）已有同款且耐久 ≥10 的成品时跳过；
  成品耐久 <10 时才允许合成替换品。箱子中有现货时，MSM 请求流程会先直接取货而非合成；
- **执行**：创建虚拟请求清单交予主手、切换至"仓库管理员"任务，按 MSM 原生流程
  找现货 → 规划合成 → 到配方记录的容器取料 → 合成。
  配方数据由本模组每 tick 从物流清单饰品注入女仆的合成记忆（MSM 原生只读取箱子里的合成指南）；
- **回收产物**：MSM 合成成功后的"回存背包"日程会把产物倒入容器，
  本模组在流程结束时立即从附近容器（24 格内，由近及远）把产物取回女仆背包，
  随后切回智能战斗并自动装备；
- **安全**：发现敌对生物立即中止合成切回战斗；失败（缺材料等）进入 5 分钟冷却；
  发起前检查背包空位与**便携式合成计算器**饰品（MSM 合成规划的必要条件，
  亦可在 MSM 配置中设 `crafting.no_calculator=true` 免除）。

### 6. 静默容器扫描（Mixin，MSM 联动）

通过 Mixin（仅在 maid storage manager 存在时应用）使女仆**常态查看容器内容**（View 上下文）
不再触发箱子的开合动画与音效；仅在真正存取物品（自动合成取料 / 存放产物）时才触发箱子开关，
减少对玩家的干扰。

## DPS 估算模型

| 武器类型 | 估算公式 |
| --- | --- |
| 近战 | `(女仆空手攻击 + 武器攻击修饰 + 附魔加成) × (女仆攻速 + 武器攻速修饰)` |
| 拔刀剑 | `BladeState 真实面板 + 附魔加成`，攻速：TPOM 连击约 5 次/秒，否则按原版近战 |
| 弓 | `9 × max(1, 女仆基础攻击/2) + 力量附魔加成`，周期 = 20 tick 蓄力 + 射击冷却属性 |
| 弩 | 平均箭伤 9（多重射击 ×1.3），周期 = 装填时长（含快速装填）+ 攻击延迟 |
| 三叉戟（投掷） | 8 + 穿刺加成（对水生生物），周期 = 三叉戟冷却属性 |

伤害会进一步按**目标当前护甲/护甲韧性**（原版减伤公式）折算，并乘以**距离因子**
（近战按接近耗时打折，远程超出射程大幅打折）。附魔加成涵盖锋利/亡灵杀手/节肢杀手/穿刺/力量/多重射击/快速装填/无限，
耐久即将耗尽的武器会被大幅降权。

> 所有数值为用于相对比较的估算值，不追求逐点精确。

## 构建

需要 JDK 17 与约 8GB 可用内存（ForgeGradle 开发环境需要对 Minecraft 反编译/重编译）：

```bash
./gradlew build
```

产物位于 `build/libs/tlm_smart_combat-1.1.2.jar`。

## 安装

1. 安装 Minecraft 1.20.1 + Forge 47.x；
2. 安装 Touhou Little Maid 1.5.3+（Forge 版）；
3. 将本模组 jar 放入 `mods` 文件夹；
4. 进入游戏后，在女仆的任务列表中选择 **「智能战斗」** 即可。

### 可选联动

| Mod | 作用 |
| --- | --- |
| SlashBlade: Resharped | 拔刀剑识别与真实面板 DPS 评估 |
| True POWER of Maid | 拔刀剑战斗 AI（连击/瞬步/格挡）接管 |
| maid storage manager | 空闲自动合成装备、容器访问权检测、静默扫描 |

## 代码结构

```
io.github.tlmsmartcombat
├── TlmSmartCombat              # @Mod 主类（注册自动合成状态机监听器）
├── SmartCombatExtension        # @LittleMaidExtension 扩展入口，注册任务
├── task/TaskSmartCombat        # 智能战斗任务（索敌、行为编排、远程攻击执行）
├── ai/
│   ├── SmartEquipBehavior      # 周期性触发装备优化
│   ├── SmartProtectBehavior    # 主人护卫（弹射物/近战格挡）
│   ├── SmartCombatMoveTask     # 按武器形态切换近战/远程走位
│   ├── SmartMeleeAttackTask    # 近战（TPOM 拔刀剑激活时让位）
│   ├── SmartBowAttackTask      # 弓射击（排除弩）
│   ├── SmartShieldTask         # 近战形态举盾（远程/TPOM 拔刀剑禁用）
│   └── SmartCraftBehavior      # 空闲时评估并发起自动合成
├── strategy/EquipOptimizer     # DPS/盔甲评分模型与换装逻辑
├── compat/
│   ├── CraftCompat             # MSM 联动核心：评估/发起/状态机/配方注入/产物取回
│   ├── StorageCompat           # 容器扫描与访问权检测（尊重 MSM NoAccess 标记）
│   ├── QuietViewScans          # 静默查看助手登记（供 mixin 使用）
│   ├── SlashBladeCompat        # 拔刀剑软依赖桥接
│   └── TruePowerCompat         # TPOM 软依赖桥接
└── mixin/                      # MSM 静默扫描 mixin（LoadingModList 门控，软依赖安全）
    ├── MixinPlugin
    ├── MsmViewScanQuietMixin   # 查看上下文不再触发开箱动画
    └── MsmViewScanStopMixin    # 查看上下文结束不再触发关箱动画
```

实现基于 Touhou Little Maid 官方扩展 API（`@LittleMaidExtension` + `ILittleMaid.addMaidTask`），
复用本体的攻击行为（`MaidCrossbowAttack`、`MaidTridentTargetTask`、`MaidAttackStrafingTask`、
`MaidUseShieldTask` 等）；所有可选联动（拔刀剑 / TPOM / MSM）均采用软依赖隔离，
未安装对应 mod 时相关代码不会被加载。
