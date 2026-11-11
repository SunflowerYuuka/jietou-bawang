# PrincessConnect 模组设计规格（一期垂直切片）

> **状态**：设计已定稿，待评审后进入实施计划
> **日期**：2026-11-11
> **工程根**：`K:\AI\Minecraft\PrincessConnet`
> **上游约束**：`K:\AI\Minecraft\MC模组开发指南.md`（§8 模块化、§9 兼容性、§10 外接接口 均为硬性要求）

---

## 1. 目标与一句话玩法

**在 Minecraft 1.21.1 + NeoForge 上，做一个「角色卡驱动」的公主连结主题战斗 mod：**

玩家平时用原版或其他 mod 的武器砍怪，队伍里 5 个角色的被动效果**附着在这些攻击上**；UB 槽攒满后，从 5 个 UB 组成的轮盘里选一个，对应角色出场演出并打出大效果。角色靠制作获得，靠带出战升级、靠送料理提升好感度，好感度三阶会强化附着效果与 UB。

**一期只做 1 个角色，把这条链完整跑通。**

### 1.1 这个形态为什么成立

最初的分歧是"攻击类动作模组要做动作+特效，成本高；buff 类太简单"。解法不是二选一，而是**把成本挪到最高光的那 3 秒**：

- 玩家平时的战斗动作 = 原版动作，零新增动作资产，且天然兼容其他 mod 的武器
- 每个角色的动作资产 = 一个模型 + 两套 UB 动画，仅此而已
- buff 之所以"简单"，是因为没有层数与阈值；加入「叠层 → 阈值 → 引爆」后，buff 变成需要管理的资源

**结论：便宜的地方是看不见的，贵的地方是最高光的。**

---

## 2. 范围

### 2.1 一期做（IN）

- 1 个角色，全链路可玩
- 角色定义走 datapack，`api.v1` 完整契约
- 附着管线：trigger × effect 组合、叠层引爆、维度递减
- UB 槽、UB 轮盘（tap/hold + 数字键 + 鼠标切换预设）
- UB 演出（GeckoLib 实体 + 两套动画）
- 角色卡合成 → 右键入册
- 等级（带出战）+ 好感度（送料理）三阶
- 服务端/客户端配置项

### 2.2 一期不做（OUT）

| 不做 | 理由 |
|---|---|
| 抽卡 / 扭蛋机 / 保底井 | 只有 1 个角色时抽卡在数学上退化为"必定获得"，纯浪费 |
| 星级 / hook 数量解锁 | 已由用户决策删除；hook 数量固定在角色定义里 |
| 多角色 | 垂直切片的定义就是 1 个 |
| 多人专属玩法（PvP 平衡、队伍共享） | 只要保证不崩、不卡、不不同步即可 |
| example-addon 子工程 | 改由"自举验证"替代（见 §6.3） |
| 自定义 GameRule / SavedData | 已改为 ModConfig（见 §8.1） |
| 结构 / 维度 / 世界生成 | 指南 §9.4 明确这是存档风险区 |

---

## 3. 决策记录

| # | 议题 | 决定 |
|---|---|---|
| 1 | 好玩点 | 战斗系统深度 + 收集养成 |
| 2 | 角色存在形式 | 不召唤队友；角色是挂在玩家身上的"被动 + 附着效果"，只在放 UB 时生成演出实体 |
| 3 | 平时循环 | 原版战斗 + 角色被动附着 + 5 槽 UB 轮盘 |
| 4 | 一期范围 | 垂直切片：1 个角色跑通全流程 |
| 5 | 技术路线 | **方案 A**：Attachment + 事件钩子（零第三方依赖，存档最稳） |
| 6 | 目标版本 | **仅 1.21.1**，`minecraft_version_range=[1.21.1,1.21.2)` |
| 7 | Java | **JDK 21**（已装，默认 java）。**不需要 JDK 25** |
| 8 | 外接接口 | **完整 `api.v1` 契约**（A1–A10 全套），用自举验证代替 example-addon |
| 9 | 递减开关实现 | **ModConfig `SERVER`**，不用命令 + SavedData |
| 10 | 递减系数 | 配置项 `diminishingBase`，默认 0.7 |
| 11 | UB 是否吃递减 | **不吃**（一次只能放一个，天然互斥）；改用全局 CD 控制 |
| 12 | 轮盘交互 | **A（径向菜单）+ 数字键直放**；滚轮切换作为可选配置项 |
| 13 | 演出实现 | **GeckoLib 4 `required`**，一期不做软降级 |
| 14 | 演出期间玩家 | **不锁定**，仅可选轻微镜头反馈（默认关） |
| 15 | 角色获取 | **合成角色卡 → 右键入册（消耗卡）** |
| 16 | 养成维度 | 仅**等级**（数值）+ **好感度**（三阶：白板 / 强化附着 / 强化 UB） |
| 17 | 动画数量 | 每角色 **2 套**（基础 UB / 强化 UB），共用 1 个模型 |
| 18 | modid / 包名 | **`astrum`** / `com.astrum.pcrd`。**modid 发布后永久不可改** |
| 19 | 一期角色 | **姬塔（Djeeta）** |
| 20 | 姬塔的"队友 TP 回复" | 映射为**范围内友方玩家充能**（`area_charge`），而非抽象队伍槽位；单人时自然退化为自身充能 |

---

## 4. 数据模型

### 4.1 分层

| 层 | 载体 | 变更频率 | 是否进存档 |
|---|---|---|---|
| 静态定义 | datapack registry `astrum:character` | 不变 | 否（数据包） |
| 玩家进度 | `AttachmentType` on `ServerPlayer` | 极低 | **是**（带 `dataVersion`） |
| UB 运行时 | `AttachmentType` on `ServerPlayer` | **每 tick** | 是（但很小） |
| 叠层运行时 | `AttachmentType` on `LivingEntity` | 每次命中 | **否**（`serialize=false`） |

### 4.2 静态定义（`CharacterDef`，datapack JSON）

存放路径：`data/astrum/astrum/character/<id>.json`（registry key = `astrum:character`）

> ⚠️ **路径是 namespace 双写，不是笔误。** NeoForge 的 `CommonHooks.prefixNamespace` 对非 `minecraft` 命名空间返回 `namespace + "/" + path`，`Registries.elementsDirPath` 直接返回该值，所以 `astrum:character` 的实际目录是 `data/<pack_ns>/astrum/character/`。写成 `data/astrum/character/` 会让注册表**静默为空**（size=0、零日志、零报错），M1 首测即踩过此坑。
> 对照：普通 datapack 内容（`data/astrum/recipe/`、`data/astrum/loot_table/`、`data/astrum/damage_type/`）**不需要**双写，只有 datapack **registry** 需要。

```json
{
  "schemaVersion": 1,
  "displayName": "…",
  "dimension": "output",
  "portrait": "astrum:textures/gui/portrait/xxx.png",
  "model": "astrum:geo/xxx.geo.json",
  "effects": [
    {
      "trigger": "melee_hit",
      "effect": "apply_effect",
      "effectParams": { "effect": "minecraft:poison", "duration": 100, "amplifier": 0 },
      "chance": 1.0
    },
    {
      "trigger": "melee_hit",
      "effect": "stack",
      "effectParams": { "stackId": "poison", "max": 5 }
    },
    {
      "trigger": "stack_full",
      "triggerParams": { "stackId": "poison" },
      "effect": "detonate",
      "effectParams": { "damagePerStack": 2.0, "radius": 3.0 }
    }
  ],
  "ub": {
    "base":     { "animation": "idle_burst", "damage": 20.0, "radius": 4.0 },
    "enhanced": { "animation": "idle_burst_plus", "damage": 20.0, "radius": 4.0, "extra": "armor_break" }
  },
  "growth": { "expBase": 100, "expPerLevel": 1.15, "maxLevel": 30 },
  "affection": {
    "tier2Threshold": 3,
    "tier3Threshold": 10,
    "tier2HookModifiers": [ { "hookIndex": 0, "mult": 1.5 } ]
  }
}
```

**trigger 枚举**：`melee_hit` / `crit` / `projectile_hit` / `kill` / `low_health` / `combo_n` / `block` / `stack_full`
**effect 枚举**：`apply_effect` / `extra_damage` / `lifesteal` / `gain_charge` / `area_charge` / `splash` / `stack` / `detonate`

> `gain_charge` = 给自己充能；`area_charge` = 给**半径内的友方玩家**充能。
> **友方判定（分层，由配置 `allyMode` 控制）**：
> - `ALL_PLAYERS`（默认）：除自己外所有 `ServerPlayer` 都算友方，`includeSelf` 决定是否含自己
> - `SAME_TEAM_ONLY`：仅同计分板队伍
>
> **不得用 `isAlliedTo()` 作为唯一判据**——它在无计分板队伍时返回 `false`，会让局域网合作玩家互相充不到电。
> **非玩家实体没有 UB 系统，不参与充能**。
> 单人世界里没有其它玩家 → 该效果退化为自身充能，不会变成空效果（判定依据是"运行时范围内是否真有友方"，不是"是否单人"）。

**维度（dimension）枚举**（用于递减分组）：`output` / `survival` / `charge` / `control` / `resource`

### 4.3 玩家状态（三个 Attachment，故意拆开）

| Attachment | 类型 | 说明 |
|---|---|---|
| `ROSTER` | `Map<ResourceLocation, CharacterProgress>` | `CharacterProgress` = `record(level, exp, affection)` |
| `PARTY` | `List<Optional<ResourceLocation>>`，长度 5，**有序** | 顺序 = 递减优先级 = 轮盘位次 |
| `UB_STATE` | `record(float charge, int selectedSlot, long cdEndTick)` | 每 tick 变，需要高频但极小的同步包 |

拆三个而不是一个大对象的原因：**同步频率不同**。若合并，每 tick 推送充能就会序列化整本角色册。

- **禁止**同一角色占用两个槽（界面与数据层双重拦截）
- `ROSTER` 必须带 `dataVersion`，读旧版本时走迁移分支（指南 §9.4）
- 玩家 Attachment 存在 `playerdata/<uuid>.dat`，**跨维度共享**，换维度不丢

### 4.4 叠层运行时

- 挂在**目标实体**上的 `AttachmentType<StackState>`，`serialize = false`
- key 含攻击者 UUID：多人打同一目标时层数按玩家隔离
- **惰性过期**：记录带 `lastHitTick`，只在被攻击时判过期。**全项目零 tick 轮询**（性能红线）

---

## 5. 战斗系统

### 5.1 附着管线

**三条硬规则：**

1. **不改数值，只追加事件**。debuff 用 `MobEffectInstance` 表达；"追加伤害"用**独立 DamageType + 单独一次 `hurt()`**，绝不在伤害事件里直接改写 `amount`（否则会被对方的护甲/减伤/暴击倍率二次放大）
2. **自己的伤害源直接跳过**。事件入口若发现是本 mod 的 DamageType 立即 `return`，否则附加伤害会再次触发附着 → **无限递归**
3. **同 tick 内的 `extra_damage` 合并成一次 `hurt()`**，一个 DamageType。既是性能优化，也是递归保险丝

> ⚠️ **待验证**：NeoForge 1.21 中 `LivingIncomingDamageEvent` 与 `LivingDamageEvent.Pre/Post` 的可写性差异，需在 M1 开始前用日志实测。三条规则与该差异无关，可先立住。

### 5.2 五个效果同时触发 + 维度递减

PCR 的爽点是五人同时在打，所以**默认全部触发**，不用数量限制。

不爆炸靠**维度分离**：同维度才乘算，不同维度只互补。递减在**同维度内**按 `PARTY` 槽位顺序计算：

```
槽1 A(output)  → ×1.00
槽2 B(survival)→ ×1.00   不同维度，不参与递减
槽3 C(output)  → ×0.70   同维度第 2 名
槽4 D(charge)  → ×1.00
槽5 E(output)  → ×0.49   同维度第 3 名
```

倍率 = `clamp(base^(n-1), 0.1, 1.0)`，`base` 来自服务端配置。

**只削数值，不削机制**：
> 递减作用于「量」（伤害、回血、充能速度、持续时间），**绝不作用于「质」**（是否触发、叠层上限、引爆这个行为本身）。

否则第 5 名角色会被玩家认为"放了等于没放"。

### 5.3 数值护栏

| 护栏 | 数值 | 理由 |
|---|---|---|
| 单次 UB 伤害 ≈ 该角色平时 15–25 秒的附着总输出 | 15–25s | 否则玩家只堆 UB，附着系统沦为攒槽工具 |
| 同维度倍率下限 | 0.1 | 配置项填 0 会导致效果全废 |
| 每角色每类 trigger 每次攻击最多触发一次 | 1 | 防止同一事件被重复计入 |

### 5.4 UB

| 项 | 规则 |
|---|---|
| 充能来源 | 造成伤害（主）+ 受到伤害（次，≈1/3 系数）+ 击杀（固定）+ 每秒微量自然回复（兜底） |
| 消耗 | 放出一个 = 清空 100%（不做分段消耗） |
| 递减 | **不适用**（一次只能放一个，天然互斥） |
| 冷却 | **全局共享一条**，配置项 `ubGlobalCooldownTicks`。CD 期间**照常充能**，满了也放不出，UI 显示冷却环 |

CD 期间或槽未满时按释放键必须给明确反馈（音效 + 槽位抖动），**禁止静默失败**。

### 5.5 轮盘

轮盘位次 = `PARTY` 槽位顺序。因此「排序」这一个动作同时决定：谁吃满倍率、谁在轮盘哪个位置。两个系统天然咬合。

**输入状态机**（同一个键区分点按与长按，必须松开时判定，否则长按首帧就会误放）：

```
按下释放键 → 记 t0，状态 PENDING
├─ 松开 且 now-t0 < 200ms  → TAP  ：释放当前预设 UB
└─ now-t0 ≥ 200ms          → WHEEL：
     ├─ 鼠标移入某格        → 切换预设（不释放，松手后生效）
     ├─ 按 1..5            → 立即释放该槽 UB，关闭 WHEEL
     └─ 松开释放键          → 关闭 WHEEL（不释放）
```

**必须处理**：WHEEL 激活期间屏蔽 `1`–`5` 的快捷栏切换，否则按 3 会同时切到第 3 格物品。

**表现要求**：
- 环半径 = 屏高 35–45%，环带宽度 ≈ 12%，**环内区域完全留空**（露出准星与敌人）
- 环带背景 alpha 0.25–0.35，图标与文字不透明
- 不暂停世界、不锁玩家移动，仅捕获鼠标并隐藏光标
- 选择结果打包成 payload 发服务端，**服务端校验**（槽满 / CD / 合法槽位）后执行

**默认释放键不要用 `R`**（JEI 配方键）和鼠标中键，建议 `V`，且必须可改。

### 5.6 演出

```
服务端：收到 UB 请求 → 校验 → 扣充能、置 CD
        → 生成演出实体（noGravity / invulnerable / 无碰撞）
        → 一次性结算伤害（合并后单次 hurt）
        → 广播给范围内客户端

演出实体：播 N tick 动画 → 自然结束时 discard
          兜底：maxLifetime 到点强制自毁

客户端：渲染实体 + 可选镜头微推 / 音效 / 屏幕特效
```

三个必须做对：
1. 演出实体不可交互、不可被攻击、不参与世界 tick 逻辑（否则挡路、被别的玩家打、掉虚空）
2. **必须有 `maxLifetime` 兜底自毁**（区块卸载/传送/卡顿导致动画中断时，否则永久留下一个角色站那）
3. 伤害在服务端一次性结算，**不等动画播完**（否则像"伤害有延迟"，且动画被打断就丢伤害）

---

## 6. 养成

### 6.1 两个维度

| 维度 | 怎么涨 | 买到什么 |
|---|---|---|
| **等级** | **带它出战**：角色在队伍中、玩家参与击杀时，按玩家造成的伤害占比分摊经验 | 数值：附着倍率、UB 伤害、充能速度 |
| **好感度** | **送料理**（用 MC 原生材料：糖 / 可可豆 / 蜂蜜 / 蛋糕等，不新增怪物掉落） | 三阶，见下 |

等级经验曲线放 datapack（`growth` 字段）。设计意图：**"练角色" = "用角色"**，不能囤材料一次性点满。

### 6.2 好感度三阶

| 阶 | 效果 | 动画 |
|---|---|---|
| 1 | 白板（无附着加成），**但基础 UB 可用** | 基础 UB 动画 |
| 2 | 强化现有 hook 的数值（`hookIndex` + `mult`），**不新增 hook** | 基础 UB 动画 |
| 3 | 强化 UB：换效果 + **换一整套动画** | 强化 UB 动画 |

> 2 阶只改数值不改数量——否则会把已删除的"星级解锁 hook 数"重新引进来。

**体验风险（已知）**：1 阶是白板，5 个 1 阶角色 = 平时纯原版。因此 **1→2 阶门槛必须很低**（几次料理即可），让白板期只是战力低谷期，而不是"这 mod 没效果"。

**必须做的 UI 细节**：轮盘里 3 阶角色的图标要换成强化版，否则玩家看不出 3 阶的意义。

### 6.3 获取：合成角色卡 → 右键入册

- 合成产出 `CharacterCardItem`（带 `characterId` 的 DataComponent）
- 右键使用 → 写入 `ROSTER`，**卡消耗**
- 队伍界面从 `ROSTER` 里选人，支持拖拽排序
- 理由：卡可交易/掉落/整合包分发，而人不会因为卡丢在箱子里而丢失

---

### 6.4 一期角色：姬塔（Djeeta）

原作数据来源：[萌娘百科·姬塔](https://zh.moegirl.org.cn/%E5%A7%AC%E5%A1%94(%E5%85%AC%E4%B8%BB%E8%BF%9E%E7%BB%93))（6★）。定位【物理】前卫，攻击距离 245。

**为什么她是好的一期角色**：她的核心设计是"用技能回复 TP、提前放出 UB"，天生落在 `charge` 维度上，正好演示本系统的充能管线；同时又带单体高爆发，能验证 `output` 维度。

| 原作 | 原作效果 | 我们的映射 |
|---|---|---|
| **UB 星河一天**（6★） | 对单体造成特大幅度 **4 段**物理伤害 | `ub.enhanced`：4 段单体高伤 |
| **UB 星河一天**（3★） | 单体特大伤害 + 自身物攻大幅提升 18s | `ub.base`：1 段单体伤害 + 自身物攻增益 |
| **剑意迸发** | 中幅度回复自身 TP | `melee_hit` → `gain_charge` |
| **广域斩击**（专武强化） | 单体大幅物伤 + 中幅度自回 TP + 小幅度回复其他队友 TP | `combo_n(3)` → `splash` + `area_charge` |
| **龙卷的攻刃 / 剑圣的赋性**（EX） | 战斗开始提升自身物攻 | 角色定义里的常驻倍率，由等级放大 |

```json
{
  "schemaVersion": 1,
  "id": "astrum:djeeta",
  "displayName": "姬塔",
  "dimension": "charge",
  "effects": [
    { "trigger": "melee_hit", "effect": "gain_charge",
      "effectParams": { "amount": 2.0 } },
    { "trigger": "combo_n", "triggerParams": { "hits": 3 }, "effect": "splash",
      "effectParams": { "radius": 3.0, "damage": 4.0 } },
    { "trigger": "combo_n", "triggerParams": { "hits": 3 }, "effect": "area_charge",
      "effectParams": { "radius": 8.0, "amount": 3.0, "includeSelf": true } }
  ],
  "ub": {
    "base":     { "animation": "tempest_blade", "hits": 1, "damage": 20.0,
                  "selfAtkBuff": 1.3, "buffTicks": 360 },
    "enhanced": { "animation": "seiga_itten",   "hits": 4, "damage": 20.0,
                  "teamChargeBonus": 0.2 }
  },
  "growth": { "expBase": 100, "expPerLevel": 1.15, "maxLevel": 30 },
  "affection": {
    "tier2Threshold": 3,
    "tier3Threshold": 8,
    "tier2HookModifiers": [ { "hookIndex": 1, "mult": 1.5 } ]
  }
}
```

> 上例里 `combo_n` 用两个 effect 表达同一次触发——本系统允许一个 trigger 挂多个 effect，不需要新增 `ub_cast` 触发器。

**好感度三阶（对齐原作数值）**：

| 阶 | 原作依据 | 我们的效果 |
|---|---|---|
| 1 | 好感 1 | 白板（基础 UB 可用） |
| 2 | 好感 2–7：物攻、物暴递增 | `hookIndex 1`（广域斩击）倍率 ×1.5 + 暴击率提升 |
| 3 | 好感 8：TP 自动回复 +60 | `ub.enhanced`（4 段星河一天）+ 全队充能加成 |

---

## 7. 兼容性约束（指南 §9 落地）

| 面 | 约束 |
|---|---|
| 版本 | `minecraft_version_range=[1.21.1,1.21.2)`；`javaVersion="[21,)"`；只做 1.21.1，新版当新分支 |
| 依赖 | GeckoLib `type="required"`，`versionRange="[4.7,)"`（留大区间，锁太死会拒启动），`side="BOTH"` |
| 命名空间 | 只注册 `astrum:` 命名空间；**不覆盖**原版任何 `data/` json |
| Mixin | **全项目禁用**（全部走事件/扩展点） |
| 端隔离 | 所有 `net.minecraft.client.*` 只在 `impl/client/`，用 `Dist.CLIENT` 限定；common 侧不留客户端类 |
| 网络 | 服务端权威，客户端只读；payload 两端都注册 + 尺寸校验 |
| HUD | 位置可配置、可整体关闭、默认避开 hotbar 与右上角（Jade/AppleSkin 等也画 HUD） |
| 存档 | `ROSTER` 带 `dataVersion`；Codec 字段只增不删；改名用 `optionalFieldOf` 兼容旧名 |
| **世界级状态** | **禁止用 `level.getDataStorage()`**——它按 `ServerLevel`（维度）各存一份，会静默分叉。要全局就用 ModConfig |

---

### 7.4 单人世界 + 局域网联机（LAN / 单人联机 mod）

场景：玩家「开放到局域网」，或用第三方"单人联机"mod 让同一存档被多人游玩。此时：

- 服务端是 `IntegratedServer`（不是专服），**但它仍然是服务端**——权威、tick、网络包、ModConfig 全按服务端逻辑走
- 主机玩家同时是客户端
- 加入的玩家**默认不是 OP**

因此必须遵守：

| 规则 | 说明 |
|---|---|
| **禁止用 `isDedicatedServer()` 做功能分支** | 只用 `level.isClientSide()` / `Dist` 判端。否则 LAN 主机会走进"单人分支"，加入的玩家直接不同步 |
| **不假设"单人 = 没有其他玩家"** | 任何单人特判都改成"运行时范围内是否真的有友方玩家" |
| **不假设"服务端一定有 OP"** | LAN 主机未开作弊时 `/astrum` 需 LV2 而不可用。调试兜底是直接改配置文件，不是靠命令 |
| ModConfig 路径 | 集成服务的服务端配置在 `<存档目录>/serverconfig/astrum-server.toml`，与专服 `world/serverconfig/` 是同一机制；改完**建议重进世界**才稳妥 |
| 存档 | 所有玩家的数据都在同一份存档的 `playerdata/` 下，Attachment 正常随档走 |

**`area_charge` 在 LAN 下的两个新问题（已加护栏）：**

1. **友方判定不能只靠 `isAlliedTo()`**——无计分板队伍时它返回 `false`，局域网合作玩家会互相充不到电。改用 `allyMode`，默认 `ALL_PLAYERS`。
2. **N 个玩家都带姬塔会形成充能闭环**——互相充能，充能速率随人数线性上涨。两道闸：同一施放者对同一接收者的 `area_charge` 有内部 CD；每个接收者每秒从 `area_charge` 获得的充能有上限。

**演出实体的多人并发**：限制同世界演出实体数量（`maxActiveShowcaseEntities`，默认 8），超限时**只结算伤害、不生成实体**——功能不断，画面不炸。

---

## 8. 配置

### 8.1 为什么用 ModConfig（不用命令 + SavedData）

用户决策。补充理由：`ModConfig.Type.SERVER` 是 **per-save**（`<存档>/serverconfig/`），**不是 per-dimension**；而 SavedData 是每维度一份，会静默分叉。加上 ModConfig 是加载器原生、纯文本、整合包作者熟悉，避免第二套配置机制。

### 8.2 服务端配置

```toml
[balance]
enableDiminishingReturns = true      # 同维度递减开关
diminishingBase = 0.7                # 倍率 = base^(n-1)，clamp [0.1, 1.0]

[ub]
ubGlobalCooldownTicks = 100
ubChargePerDamage = 1.0
ubChargePerDamageTaken = 0.33
ubChargePerKill = 20.0
ubChargePerSecond = 0.2

[wheel]
scrollSwitchPolicy = "ALLOW"         # ALLOW / DENY / FORCE

[multiplayer]
allyMode = "ALL_PLAYERS"                 # ALL_PLAYERS / SAME_TEAM_ONLY
areaChargePairCooldownTicks = 20         # 同一施放者→同一接收者的 area_charge 内部 CD
areaChargeReceiveCapPerSecond = 6.0      # 每个接收者每秒从 area_charge 获得的上限
maxActiveShowcaseEntities = 8            # 同世界演出实体上限，超限只结算伤害不生成实体
```

### 8.3 客户端配置

```toml
[wheel]
enableScrollSwitching = false
wheelScale = 0.40
wheelOpacity = 0.30

[camera]
ubCameraPush = false                 # 演出时轻微镜头反馈，默认关
```

### 8.4 两个必须做的护栏

1. **配置值必须在使用处懒读取**，不得静态缓存。否则配置未加载就取到默认值，且永久生效（NeoForge 配置经典坑）。
2. **系数必须 clamp**（`base` → `[0.1, 1.0]`）。开放成配置项后一定有人填 0（效果全废）或 2.0（数值爆炸）或负数。

> 调试期注意：默认值只在**首次生成文件**时写入。改了代码里的默认值，老存档的 toml 不会跟着变，得手动删掉 `world/serverconfig/` 下那个文件。

---

## 9. 外接接口 `api.v1`

### 9.1 包结构

```
com.astrum.pcrd.api.v1                ← 对方只看这个包
  ├─ AstrumApi.java                  唯一门面
  ├─ CharacterDef / EffectHook / UbDef / GrowthDef / AffectionDef.java   record + CODEC
  ├─ CharacterProgress.java          record
  ├─ RegistryKeys.java               CHARACTERS 的 ResourceKey
  └─ event/  UbChargeEvent, UbActivatedEvent, CharacterEquippedEvent

com.astrum.pcrd.impl                 ← 可 import api，反向禁止
  ├─ registry/ attach/ combat/ ub/ item/ network/ command/ config/ client/
  └─ Princess.java
```

### 9.2 A1–A10 落实

| 条款 | 本项目形态 |
|---|---|
| A1 物理隔离 | `api.v1` 不 import `impl`；`impl` 依赖 `api` |
| A2 版本化包名 | 从第一天就叫 `api.v1`；破坏性改动开 `v2` |
| A3 只暴露稳定类型 | 只给 record / enum / ResourceKey / Registry，**不给要对方实现的接口** |
| A4 一个门面 | `PrincessApi.characters()` 返回 `Registry<CharacterDef>`，注册完成后填入 |
| A5 稳定性标注 | 一期全标 `@ApiStatus.Experimental`；impl 内标 `@ApiStatus.Internal` |
| A6 失败不外抛 | 一律 `Optional` / 空集合 / 默认值 + 日志；**单个坏 JSON 只跳过，不崩游戏** |

> ⚠️ **A6 的真实边界（Task 4 实测后收紧）**：`RegistryDataLoader` 在任何 codec 之前先执行 `JsonParser.parseReader`，且把 `catch (Exception)` 的条目汇总后统一 `throw new IllegalStateException("Failed to load registries")`。因此：
> - **语义错误**（未知枚举值、缺字段、类型不符）→ 由我们的 lenient codec 拦住 → **跳过该条 + 一条 WARN，游戏正常**。这是 A6 覆盖的范围。
> - **语法错误**（少括号、逗号、引号不配对）→ `JsonSyntaxException` 在 codec 之前抛出 → **世界加载失败，进错误画面**。**这是数据包注册表机制的固有行为，任何 codec 都无法拦截。**
>
> 结论：A6 承诺的是「语义层面不崩」；作者手写 JSON 的语法错误无法被 mod 兜底。**验收必须用语义错误，不能用语法错误**（否则看起来像回归）。缓解手段：datapack 作者工具/示例文件保持完整，README 写明这一点。
| A7 注册式优于命令式 | 加内容 = 往注册表注册；事件只用于通知/允许干预 |
| A8 文档 + 示例 | README 给 10 行 JSON 示例；用**自举验证**替代 example-addon |
| A9 弃用流程 | 现在只写规则：`@Deprecated` → 保留 ≥1 个 minor 版本 → 才删 |
| A10 不渗 MC 内部类 | **硬约束**：api 签名只允许 `ResourceLocation` / `ResourceKey` / `Holder` / 基本类型 / 自己的 record。`Level`、`Entity`、`ServerPlayer`、`ItemStack` 一律不进签名 |

> **A10 最容易被破**（写效果时很自然想把 `Level` 传进去"方便点"）。规则：**api 层只描述"数据长什么样"，不描述"怎么执行"**；执行在 impl。

### 9.3 自举验证

mod 自带的 1 个角色**不硬编码**，而是作为一份 datapack JSON 通过公开 API 路径加载。只要自己这个角色能跑通，就证明 API 可用——API 一旦泄漏或不够用，第一个崩的是我们自己。

---

## 10. 文件结构

```
K:\AI\Minecraft\PrincessConnet\
├─ gradle.properties                       版本三件套（1.21.1 / NeoForge / MDG）
├─ build.gradle
├─ docs/superpowers/specs/                 本文件
└─ src/main/
   ├─ java/com/astrum/pcrd/
   │  ├─ api/v1/        （见 §9.1）
   │  ├─ impl/
   │  │   ├─ registry/   datapack registry 引导 + Codec 校验
   │  │   ├─ attach/     ROSTER / PARTY / UB_STATE / STACK
   │  │   ├─ combat/     事件入口、惰性叠层、递减倍率、伤害合并
   │  │   ├─ ub/         充能、CD、轮盘状态、演出执行
   │  │   ├─ item/       CharacterCardItem
   │  │   ├─ network/    充能同步 / 释放请求 / 队伍同步
   │  │   ├─ command/    /astrum（调试用）
   │  │   ├─ config/     ServerConfig / ClientConfig
   │  │   └─ client/     UB 槽 HUD、轮盘渲染与输入、演出实体渲染
   │  └─ Astrum.java
   └─ resources/
      ├─ data/astrum/astrum/character/*.json ← datapack registry 路径（namespace 双写，见 §4.2）
      ├─ data/astrum/recipe/*.json
      ├─ data/astrum/loot_table/*.json
      ├─ assets/astrum/{geo,animations,textures,lang,models}/
      └─ META-INF/neoforge.mods.toml
```

**模块化层级：L1 功能分包（指南 §8 强制）**。验收标准：删掉 `impl/` 下任意一个 feature 子包后项目仍能编译启动。

---

## 11. 里程碑与验收

| 里程碑 | 内容 | 验收标准 |
|---|---|---|
| **M0 能启动** | MDG 生成工程 → 改 modid → 空 mod | `runClient` 进游戏，mod 列表里有它 |
| **M1 数据能读** | `CharacterDef` + Codec + datapack registry + 1 个角色 JSON | 进世界后日志打印角色名与三阶表；坏 JSON 只跳出不崩（**注：`/reload` 不重读 datapack registry，改动需重进世界**） |
| **M2 一条链跑通** | Attachment + 攻击触发 + 1 个 MobEffect + UB 充能 + HUD 数字 | 砍怪能看到 buff 与充能增长；**换维度不归零** |
| **M3 完整切片** | 叠层引爆 + 轮盘 + 演出实体 + 角色卡合成 + 等级/好感度 | 从合成卡到放大招全流程可玩 |

**M0–M2 只用原版 API，GeckoLib 到 M3 才引入**——避免前三个阶段被依赖问题卡住。

每个里程碑都要跑指南 §9.6 的矩阵：`runClient` + `runServer` + 旧存档能进 + 装 JEI 不崩。

**额外加一条 LAN 验收（从 M2 起，凡涉及网络/UI 的里程碑都要跑）**：

> 开一个单人世界 →「开放到局域网」→ 用第二个客户端加入（可同机开第二个 `runClient` 实例）。
> 通过标准：两个客户端的 UB 槽与队伍界面**数值一致**、加入方能看到主机放 UB 的演出、主机视角一切正常。
> 这条专门抓 §7.4 那类"主机走了单人分支"的 bug——它只在有人加入时才暴露，专服测试也抓不到。

---

## 12. 已知风险与坑

| # | 风险 | 应对 |
|---|---|---|
| 1 | **递归**：附加伤害再次触发附着 | 事件入口按 DamageType 短路；同 tick 合并为单次 hurt |
| 2 | **数值爆炸**：5 个同维度角色全触发 | 同维度 `base^(n-1)` 递减 + 只削数值不削机制 |
| 3 | **性能**：每 tick 遍历实体判过期 | 惰性过期，零 tick 轮询（红线） |
| 4 | **数值失衡**：UB 成为唯一输出口径 | UB 单次伤害 ≈ 15–25 秒附着总输出 |
| 5 | **演出实体残留** | `maxLifetime` 强制自毁 + 不可交互 |
| 6 | **数字键冲突**：轮盘期间按 1–5 误切快捷栏 | WHEEL 激活时屏蔽快捷栏输入 |
| 7 | **配置读不到**：静态缓存配置值 | 使用处懒读取 |
| 8 | **维度分叉**：SavedData 每维度一份 | 世界级状态一律走 ModConfig |
| 9 | **1 阶白板期太长** | 1→2 阶门槛设到"几次料理" |
| 10 | **GeckoLib 版本滞后** | 依赖 `versionRange` 留大区间；M3 才引入 |
| 11 | **`api.v1` 被 impl 结构污染** | api 只描述数据；签名禁 MC 内部类 |
| 12 | **LAN 主机走进"单人分支"**，加入的玩家不同步 | 禁止 `isDedicatedServer()` 分支，只用 `isClientSide()` |
| 13 | **`isAlliedTo` 无队伍返回 false**，LAN 合作玩家充不到电 | `allyMode` 默认 `ALL_PLAYERS` |
| 14 | **多人同带姬塔形成充能闭环** | 成对 CD + 每秒接收上限 |
| 15 | **多人同时 UB 导致演出实体堆积** | `maxActiveShowcaseEntities`，超限只结算伤害 |

---

## 13. 待定事项（进计划前需确认）

| # | 事项 | 现状 |
|---|---|---|
| 1 | **modid / 包名** | ✅ 已定：`astrum` / `com.astrum.pcrd` |
| 2 | **一期角色** | ✅ 已定：姬塔（见 §6.4） |
| 3 | **好感度阈值具体数值** | 占位：tier2=3、tier3=8（对齐原作好感 2–7 / 好感 8） |
| 4 | **NeoForge 伤害事件可写性** | 待 M1 实测（见 §5.1 警告） |
| 5 | **姬塔的模型与两套动画资产** | 未开始：需 Blockbench `.geo.json` + 2 套 `.animation.json` |

---

## 14. 交接说明（新窗口续接用）

> 本文件是自包含的。新会话只需读它 + `K:\AI\Minecraft\MC模组开发指南.md`，即可继续。

三行启动语：

```
项目在 K:\AI\Minecraft\PrincessConnet，规格见 docs/superpowers/specs/2026-11-11-pcr-character-mod-design.md，
硬约束读 K:\AI\Minecraft\MC模组开发指南.md（§8 模块化、§9 兼容性、§10 外接接口）。
目标：MC 1.21.1 + NeoForge，modid=astrum，包名 com.astrum.pcrd，垂直切片只做姬塔（Djeeta）。
方案 A（Attachment + 事件钩子），JDK 21 已装（不需 JDK 25）。
下一步：写实施计划（writing-plans），或直接开工 M0（MDG 生成工程）。
注意：NeoForge 依赖必须走 127.0.0.1:7890 代理，代理没开要先问我。
```
