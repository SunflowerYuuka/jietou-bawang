# 整分支终审 — Astrum `d123166`（发布前，只读判断）

- 分支 `main`，HEAD `d123166`，631 测试 / 71 测试类全绿（已从 `build/test-results/test/*.xml` 复核：`tests=631 failures=0 errors=0 skipped=0`），`guard` 0 FAIL。
- **但工作树不干净**，简报里"tree clean"这一条与事实不符：有 4 个未跟踪文件（三个 100 KB+ 的特效 png 和 `tools/fx_from_ai.py`）。已发布的 `astrum-0.1.0-FINAL.jar` 已开箱核对，那三张 png **不在里面**，所以发布物本身没被污染；但仓库状态需要人在发布前做个决定（见"发布前必须处理"第 7 条）。
- 本文件**不改任何代码**。每条结论都带文件:行，读者可自行复核。

---

## 结论

**ship-with-caveats（可以发，但发布文档与工作区状态必须先处理）。**

代码本体是我在这个项目里见过的最干净的一次交付：六条"一条规则一份实现"里有五条真的是单份，存档隔离（quarantine）这条最危险的路径既有自动化测试也有真机探针，631 个测试里绝大部分是有信号的。

但**没有任何代码级丢档/崩服缺陷被发现**的同时，`README.md` 把核心玩法回路写错了：它教玩家"按 B 把角色放进 1–5 号位"，而 B 在代码里**永远不改队伍**（只改预设），全篇也没有第二处说明队伍到底怎么编。新玩家按文档走，第 3 步就会卡在"角色进不了队伍、按 V 永远 SLOT_EMPTY"。这不是文档小瑕疵，这是首小时体验的直接杀手，而修它只需要改三行字。

---

## 文档与代码不一致处

| # | 文档声称 | 代码实际 | 谁对 | 严重度 |
|---|---|---|---|---|
| 1 | `README.md:53`「按 `B` 呼出**轮盘**，把角色放进 1–5 号位」 | `B` 是**预设轮盘**：`ClientEvents.java:415-420` 的 `SET_PRESET` 只调 `WheelPreset.select(...)`，注释明写 "no cast: the wheel key never casts"；`WheelInputMachine` 的 PRESET 分支没有 party_edit 通路。队伍真正的写入口只有角色册界面（`CharacterBookScreen.java:466/473/492` 发 `party_edit`）和 `/astrum party set`（`AstrumCommand.java:414`） | **代码对**。README 错，且错在"唯一可用的组队路径完全没写" | **高** |
| 2 | `README.md:55`「伤害在动画的**命中帧**结算，**动画被打断则那一发不结算伤害**」 | `ShowcaseEntity.java:361-365` 的 `onRemovedFromLevel` 会在实体被移除时 `settleNow("interrupted clip (entity removed)")`：打断**仍然结算**，这是 §5.6「动画被打断就丢伤害」的正面保证。README 把保证写成了反保证 | **代码对**。README 说的与实现相反 | **中**（玩家会为避免"丢伤害"而不用 UB） |
| 3 | `README.md:52`「册子里能看等级、经验条、好感阶数与**全部技能说明**」 | `CharacterBookScreen` 全文不读 `CharacterDef.effects()`（grep：`effects()` 的生产读者只有 `HookEvaluator.java:94`）。界面画的是等级/经验条/好感阶/下一阶阈值/喂食入口 | 代码对；README 承诺了不存在的功能 | 中 |
| 4 | `docs/acceptance.md:167` 第 5 行「按 `B`，把姬塔放进 1 号位 … ✅ 走真实 `party_edit` 包」 | 同 #1：`B` 不产生 `party_edit`。该 PASS 行的证据是一个**直接发送 payload 的探针**，与"按 B"这个动作无关 | 代码对；这一行把没做过的键操作写成 PASS，违反该文件自己的闸门定义（"不是「应该能用」，而是实际跑过的一次观测"） | **高**（发布闸门失真） |
| 5 | 规格 `§13` 待定表第 6 行（`spec:785`）「姬塔 6★ 素材已定位（`100211/100231/100261` + 对应 card）」 | 规格 `§9.4`（`spec:645-647`）已修正为 `105701` / `105711`/`105731`/`105761`。`1002xx` 是**ユイ（优衣）**，是 ledger 记录过的第一次错误 | `§9.4` 对，`§13` 是未清理的旧值。两节自相矛盾 | **高**（包作者按 §13 做，会把优衣的头像挂在姬塔名下——本项目已犯过两次同类错误） |
| 6 | 规格 `§5.6`（`spec:297`）「一次性结算伤害（**合并后单次 hurt**）」 | `UbDamageSettlement.java:133-150` 按 `plan.hits()` 逐次 `hurt()`：基础 1 次、强化 4 次独立 20.0（ledger 实测日志也是"FOUR separate applications"）。合并成单次的是**附着管线**（`DamageBatch`），UB 不在那条管线上 | **代码对**（4 段大招必须打 4 次才读得出"4 段"）。规格这行是把 §5.1 的合并规则错贴到 UB 流程上 | 中（这一行会让下一位实现者去"修正"一个正确设计） |
| 7 | 规格 `§5.4`（`spec:223`）充能来源含「**每秒微量自然回复（兜底）**」，`§8.2` 也声明了 `ubChargePerSecond = 0.2` | `ChargeKernel.naturalRegen` 在 `src/main` **零调用**（只有测试调）。配置项存在、界面可见、默认 0.2、实际不生效 | 两边都"对"，但是**声明了不存在的行为**。规格 §8.3 自己写着"配置声明的范围必须等于代码实际允许的范围，否则界面会骗人" | 中 |
| 8 | 规格 `§6.1`（`spec:319`）等级买到「数值：附着倍率、**UB 伤害**、**充能速度**」 | 等级只进 `MultiplierChain`（附着倍率）。UB 伤害明确不乘（`UbDamage.java:33`、ledger 已记为可接受裁定）；充能速度没有任何等级因子 | 代码 = 已记录的口径；规格这一格未同步。UB 伤害那一半 ledger 记过，**充能速度这一半连裁定都没有** | 中 |
| 9 | 规格 `§9.4`（`spec:688-694`）要求 `CharacterDef` 带 `maxStar` + `icon1st/3rd/6th` + `cardArt3rd/6th` | 代码里这些字段**一个都没有**（grep 全库 0 命中）。ledger 记为"PENDING api.v1 change" | 两边都对：一期确实没做。但 §9.4 用的是契约语气（"这必须能由 datapack 表达"），发布后附属 mod 作者会按它写 | 低-中 |
| 10 | 规格 `§9.1`（`spec:594`）列 `event/ UbChargeEvent, UbActivatedEvent, **CharacterEquippedEvent**`；`§9.2 A4`（`spec:608`）写 `**PrincessApi**.characters()` | 仓库里 `api/v1/event/` 只有前两个；门面叫 `AstrumApi`（`AstrumApi.java:28`）。`CharacterEquippedEvent` 被裁定不做（ledger T14）；`PrincessApi` 是计划自审里已指出的笔误 | 代码对；规格两处旧名残留 | 低 |
| 11 | `AstrumApi.java:19-21` javadoc「**After the world is left or the server stops** — `Optional.empty()` again」 | 远端客户端断线时不会发 `ServerStoppedEvent`（ledger T4 min 记录过），所以连过远程服的客户端会**继续拿旧注册表** | 文档过强；代码是"离开单人世界会清，离开远程服不清"。这是**已发布的 A6 契约文字**，附属 mod 会依赖它 | 中 |
| 12 | 计划 `Task 12`（`plan:760`）文件清单含 `WheelScreen.java`、`WheelInput.java`；`plan:819` 同一句里既写"透明度 0.55"又写"环带 alpha 0.30"；`plan:329` 仍是"5 个测试全绿" | 那两个类已被删除（非模态覆盖层重写），alpha 以 `0.55`→`0.75` 收尾，Task 3 测试早已 12 个 | 代码/规格对；计划是历史文档，但它是被列为权威的三份之一 | 低（计划本身是过程记录，不建议回填，建议在文件头加一句"已完成，以 spec 为准"） |
| 13 | `CharacterBookScreen.java:40` javadoc「喂食入口（`DishFeedHook` until Task 15 installs a handler）」 | Task 15 已交付，但 `DishFeedHook.install(...)` **全库零调用**，所以 `available()` 恒 false，`KEY_FEED_AVAILABLE` 分支永远不渲染，界面永远走"手持料理右键"文案 | 代码行为正确（另一条路已通），注释与一个死分支是残留 | 低 |
| 14 | `AffectionKernel.java:107-108` javadoc「Moved here from `AstrumCommand`, **which now delegates to it**」 | `AstrumCommand.java:233-242` **自己有一份完整实现**，没有 delegate（详见下一节） | 文档错，代码里有两份 | 中 |
| 15 | ledger `progress.md:1057-1058`「The 4 non-item textures are not referenced by code yet; **their wiring belongs with Task 13 (GeckoLib effects) and the wheel-plate swap**」 | Task 13 已交付，但 `ub_slash.png` / `ub_shockwave.png` / `ub_frame.png` / `wheel_plate.png` **四张全部仍然是零引用**（全库 `src/main/java` 对这 4 个名字 0 命中）。`ShowcaseModel` 只引用 `textures/entity/djeeta.png`；`WheelOverlay` 手画扇形而不贴 `wheel_plate`；`UbHud` 手画环而不贴 `ub_frame` | 台账的承诺没兑现。这 4 张（共 82 KB）进了 jar 但永远不会被画出来 | 低-中（体积/维护，不是功能） |
| 16 | 任务简报称「tree clean」 | **工作树不干净。** 4 个未跟踪文件：`src/main/resources/assets/astrum/textures/effect/ub_burst.png`（223 KB）、`ub_flare.png`（124 KB）、`ub_strike.png`（138 KB）、`tools/fx_from_ai.py` | 这不是代码缺陷，但**必须先决定去留**：它们在 `build.gradle` 的 `srcDir` 里，一旦 `git add` 就会进 jar（且 486 KB 与 `b6deb87`「352 KB → 57 KB」的瘦身方向相反）；不 add 则它们是某条未完成工作流的遗留物。已发布 jar `astrum-0.1.0-FINAL.jar` 里**没有**这三个 png（已开 jar 核对），所以发布物本身是干净的 | 中（发布卫生） |

---

## 单一真相源核查

| 规则 | 结论 | 证据 |
|---|---|---|
| **`GrowthFacts`**（经验曲线 + 好感阶梯） | **single** | `ExperienceKernel.java:79-80` 与 `AffectionKernel.java:57-58` 都是一行委托；界面与同步包直接读 `GrowthFacts`（`CharacterBookScreen.java:371-373`、`AstrumNetwork.java:420-423`）。`expToNext` 的 `Math.pow` 全库只出现在 `GrowthFacts.java:73` 一处。`AffectionKernelTest.theKernelsLadderIsGrowthFactsLadder` 还会在阶梯被硬编码时变红 |
| **`PartyService.edit` / `PartyRules`**（队伍写入） | **single** | 全库 `setData(Attachments.PARTY, ...)` 只有 `PartyService.java:108` 一处；两个入口都调它（`AstrumCommand.java:414`、`AstrumNetwork.java:258`）。"这个角色在哪个槽"只有 `PartyRules.slotOf`（`:157`）；去重只有 `Party` 紧凑构造器（`Party.java:76-92`）+ `Party.with` 的移动语义 |
| **`MultiplierChain.effective`**（hook 倍率） | **single** | 唯一调用点 `HookContext.java:95`，其唯一调用者是 `HookEvaluator.java:70`，其唯一调用者是 `CombatEvents.java:518`。八个执行器全部只读 `ctx.multiplier()`，没有任何一处二次相乘（`GuardedAreaChargeSinkTest.theAmountHandedToTheCreditStepIsTheRequestUnmodified` 专门钉这条）。UB 刻意不进这条链，理由写在 `UbDamage.java:33` 与 `UbDamageSettlement.java:72-76` |
| **`AllySelector`**（谁是友方） | **single** | 两个调用点：`GuardedAreaChargeSink.java:114`（area_charge）与 `UbDamage.java:334`（UB 不伤队友），输入都被规约成同一个 `Candidate`。全库 `isAlliedTo` **0 次出现**（只在 javadoc 里被点名说不能用作唯一判据） |
| **`CastGate`**（能不能放） | **single** | 唯一判定 `UbService.java:113` → `UbTransaction.decide` → `CastGate.refusal`。聊天命令不重写规则，直接调 `UbService.tryCast`（`AstrumCommand.java:509`）。客户端**不做**任何本地闸门（`WheelOverlay`/`CastPadOverlay` 只画，不判），所以不存在"客户端说能放、服务端拒绝"的第二套逻辑 |
| **`WheelSlots.slotForDigit`**（槽位换算） | **single** | 生产调用者只有 `AstrumCommand.java:207`（`slotIndex` 委托）与 `ClientEvents.java:441`（`pickedSlot`）。`WheelDigits` 只做"哪个键码是数字键"，不做换算；`WheelInputMachine.onDigit` 保持 1..5 语义。`WheelSlotsTest` 逐位写死 `1→0 … 5→4` 而不是复述公式 |

**但是：同一类"一条规则一份实现"在好感度上限上破了一次。**

`AffectionKernel.clampAffection`（`AffectionKernel.java:122-131`）与 `AstrumCommand.clampAffection`（`AstrumCommand.java:233-242`）是**两份实现，且行为不同**：

- 内核版结尾 `Math.min(Integer.MAX_VALUE, updated)`；
- 命令版结尾 `(int) updated`，**没有上界保护**。

后果（**边界要说清，不能夸大**）：姬塔 `tier3Threshold = 8`，命令版的 `if (tier3Threshold > 0 && updated > tier3Threshold)` 会先把结果压到 8，所以对**已发布角色**这条路径进不去。真正的分歧窗口是"数据包里定义了一个 `tier3Threshold <= 0`（没有 3 阶）的角色"：此时 `/astrum affection add 2147483647` 从 `current=1` 出发得到 `1 + 2147483647 = 2147483648`，`(int)` 强转成 `-2147483648`，于是**负数好感度被写进存档**（`AstrumCommand.java:551-554`）；内核那份会答 `Integer.MAX_VALUE`。也就是说：**今天不会发生，但只要有人加一个 5★ 上限、无 3 阶的角色就会发生**，而"非 6★ 角色上限 5★、没有 6★ 档"是规格 §9.4 明确要支持的形状。

所以这不是发布阻塞项，而是一份**已经漂移的副本**——正是本项目被咬过三次的那个模式。`AffectionKernelTest.theCapSurvivesAnOverflowingDelta` 覆盖的只是内核那一份。

---

## 测试质量问题

631 个测试大约 8.8k 行测试代码对 17.1k 行主代码。**大部分是有信号的**：`SettleOnceTest` 是 mutation-tested 的，`RosterCodecTest` 的隔离测试打的是真 behaviours（写回原 tag），`ShippedGrowth` 从 `djeeta.json` 反解而不是重打一遍常数，`UbDamageTest.theStrikeFrameIsInsideTheClipAndNearItsMiddle` 是穷举 1..200 而不是只测 28。下面是**没有信号**或**名不副实**的部分，具体到测试名。

**A. 生产代码里最大的几块，一个直接测试都没有**（grep 引用数 = 0）：

| 文件 | 行数 | 覆盖情况 |
|---|---|---|
| `CombatEvents.java` | 576 | 只有 `paysExperience` 这一个纯谓词被测（`ContributionRecordTest`）。`onDamageLanded`/`onDeath`/`onBlock`/`flush`/`collect` 全部无测试——也就是"命中→倍率→批合并→单次 hurt"这条主链路**只靠探针日志**。ledger 自己写过："moving the fuse return above the credit block or deleting onDamagePre would still pass all 118 tests"（T9 deferred），这条至今成立 |
| `PartyService.java` | 116 | 0 测试。决策半边 `PartyRules` 有 18 个测试，但"两个入口真的都走这里"只是 grep 结论，没有测试能防止将来有人在命令里重写一遍 |
| `AstrumNetwork.java` | 438 | 0 测试（三个 payload 的编解码各自被测）。每 tick 的三条 diff 推送、登出清理、隔离标记推送都无测试 |
| `CharacterBookScreen.java` | 567 | 0 测试。`BookLayout` 有 339 行测试（几何），但"点格子选人、点槽放入、右键清空、禁写隔离态"这四个交互无测试 |
| `WheelOverlay.java` | 223 | 0 测试（渲染，只能人眼） |
| `TriggerRuntime.java` | 50 | 0 测试。八个触发器只有 `TriggerFacts` 真值表被测，**combo 链本身**没测；`HookEvaluator.evaluate` 的三个 lambda（随机 roll、真 executor、warn-once）从未被测试调用（ledger T10 明确记录） |
| `DishFeed.feed` | 39（方法） | 0 次直接调用。`DishClickTest` 只测"哪些点击算菜"和拒绝文案，`DishThrottleTest` 只测节流表；"喂谁、先算后扣、写 `withProgress`"这一整条**没有任何测试**，而它是好感度唯一的玩家入口 |

**B. 名字比它检查的多（点名）：**

- `PartyMultipliersTest.aQuarantinedRosterDegradesToNeutralMultipliersWithoutThrowing`（`:128`）——名字读起来是"战斗在隔离存档下取中性倍率"，实际断言的是一个**测试本地私有 helper** `combatMultipliers(...)`（`:160-166`）。把 `CombatEvents.collect` 里的隔离分支删掉，这个测试照样绿。ledger 自己写了"there are zero production callers ... Do not treat it as safety evidence"，但测试名仍在向未来的读者承诺安全证据。
- `HudLayoutTest.chargeColourIsOpaqueAndEndsOnTheDocumentedColours`（`:99-105`）——`assertEquals(HudLayout.CHARGE_LOW, HudLayout.chargeColor(0.0))`：把常量与"返回该常量的函数"对比，任何颜色都能过。真正有信号的只有 `>>> 24 == 0xFF` 那两行。
- `WheelGeometryTest.radiusFractionIsClampedIntoTheSpecWindow`（`:80-84`）与 `bandAndAlphaAreClampedIntoTheirSpecWindow`（`:97-99`）——同样是"常量 vs 常量"。**缓解**：同文件 `:87-92`、`:104-108` 用字面量 `0.20/0.33/0.27`、`0.0/1.0/0.75` 再钉了一遍，所以这部分整体仍可信；但上面那几行本身是零信息。
- `ConfigDefaultsTest.theDeclaredWheelScaleWindowIsTheOneTheClampEnforces`（`:157-170`）——把配置 range 与 `WheelGeometry.RADIUS_FRACTION_MIN/MAX` 对比，是常量对常量；只有 `:165-169` 的字面量断言在防回归。
- `CastPadGeometryTest:35-36`、`PartyMultipliersTest:105`——`CHIPS == Party.SLOTS`、`SLOTS == Party.SLOTS` 纯恒等式，任何值都过。
- `AstrumTest.modIdIsAstrum`——`assertEquals("astrum", Astrum.MOD_ID)`，编译期常量自证。ledger 已记为"tautological, harmless"。
- `LangParityTest.eachBindingLabelDescribesOnlyItsOwnAction`（`:182-186`）——断言"释放键文案里含 ub/UB/释放""轮盘键含 轮盘/wheel"。一条文案只要含 `UB` 就能通过，包括 "UB 已被移除"。这个测试防的是"两个键共用一个标签"（有信号），不防措辞。

**C. 断言在"常量的副本"上而非行为上的（较轻，逐条点名）：** `ShowcaseLifetimeTest:173` 断言 `placementKeepsTheCastersFeetHeight` 等于 `PLACEMENT_HEIGHT`（0.0），等于在测"常量是 0"；`WheelIconTest.aCharacterWithoutArtStillHasSomethingToDraw` 断言 `rings() == BASE_RINGS`，等于在测常量。两者都还有别的真断言陪着，所以不致命。

**D. 一处"测试在测一个它自己搭的世界"：** `AreaChargeTuningTest.aZeroCooldownInTheTomlFileIsHonouredWhenNoOverrideIsSet`（`:85-107`）用反射构造包私有 `LoadedConfig(CommentedConfig, Path, ModConfig)`。它真的走了 `SPEC.acceptConfig`，所以是有信号的；但它在 NeoForge 换构造签名那天会红成"测试坏了"而不是"功能坏了"，且 `@BeforeEach/@AfterEach` 的 clear 依赖方法执行顺序（ledger 已记）。

---

## 只经人眼验证的部分

以下每一项**没有任何自动化测试能证明它**，且台账里也明确写了"未人肉目视"或"只能由人判断"。发布前应逐项看一次。

| # | 项目 | 该看什么 |
|---|---|---|
| 1 | **角色册界面**（297×227 面板，`BookLayout`/`NineSlice`） | 三种主题（默认蓝 / 樱 / 森）各开一次：面板是否**真的**由九宫格拼出（接缝、1px 错位）、18px slice 对 20px padding 的那 2px 边距是否显得紧、分隔线被**拉伸**（非平铺）在 297 宽下有没有水波纹、三块（角色册/编队/养成）在同一屏是否挤。数值上"矩形恰好铺满"已有探针证明，**观感没有** |
| 2 | **演出实体外观**（`ShowcaseModel`：6 cuboid 盒子人 + 518 B 皮肤） | `tempest_blade` 与 `seiga_itten` 各放一次：是否读得出"举剑→挥砍"；皮肤是否覆盖每个 cube 的每个面（漏面在游戏里是纯色块）；`seiga_itten` 是"四次独立 hurt 落在同一 tick"，**视觉上是否像 4 段**——ledger 把这条列为"玩家最可能第一个报的问题" |
| 3 | **创造模式物品栏**（`AstrumCreativeTab`） | tab 是否在物品栏条里出现、位置是否合理（默认位置，别人装 mod 会挤）、图标是否是角色册本身、7 个物品顺序是否 card→book→dish。台账有 15 项探针证据，但"条里好不好找"只有人知道 |
| 4 | **Mods 列表 logo**（`logoFile = logo.png`，128×128 banner） | 列表里是否真的显示 banner 而不是默认图标。探针只证明"按 NeoForge 的解析路径能取到 128×128 的图"，**没有一次人眼看过 Mods 界面** |
| 5 | **JEI / REI 共存** | 完全没装过。acceptance 第 4 行诚实标 HUMAN。要看：无 mandatory dependency 报错、无 mixin 崩溃、搜 `character_card`/`character_book`/`rice_bowl` 三个配方可见、开着 JEI 时按 B/V 轮盘与 HUD 仍正常 |
| 6 | **真实跨机器 LAN** | 只在本机 loopback 上验过（一端真实 dedicated server + 一端真实 client 进程）。"开放到局域网"按钮（需点 GUI）与两台机器之间的真实网络**从未跑过** |
| 7 | **主人离开的演出实体销毁路径** | `ShowcaseManager.onPlayerLoggedOut` 存在，但**没有任何测试、也没有任何探针**跑过它（grep 测试目录：0 命中）。现在它的正确性只等于"这段代码看起来会 discard" |
| 8 | **死亡不掉档**（`copyOnDeath`） | 见下节，只经一次真机探针 |
| 9 | **`K` 键设置界面**（`ConfigurationScreen` 由 spec 自动生成） | 三个分页标题与措辞、改完是否立刻生效、服务端项那句"单人即时生效；手改文件需重进世界"是否真的显示成中文而不是 raw key |
| 10 | **真实网络下的偏移动画/音效反馈** | 拒绝时的槽位抖动与音效、`enableUbHud=false` 时确实不再出声（这条曾被修过一次：音效原本在 `UbHud.render` 里） |

---

## 发布前必须处理

**先说清楚：本次审查没有发现任何"丢档 / 崩服 / 首个存档被写坏"级别的代码缺陷。** 隔离写入、`copyOnDeath`、`withProgress` 只改已拥有项、未知角色降级、`defineInList` 的 null 崩溃、NaN 逃逸——这些台账里点名的坑，代码里全部是关着的，且有测试或探针。下面七条里前六条是**文档/发布物**层面的，第七条是**发布卫生**，但前三条是"玩家第一小时"的实际组成部分。

1. **`README.md:53` — 组队路径写错，新玩家第一步就卡死。**
   文档让玩家"按 B 把角色放进 1–5 号位"，而 B 只改预设（`ClientEvents.java:415-420`）。玩家会按 B、按 1、发现槽位依旧是空的，然后按 V 得到 `SLOT_EMPTY` 的抖动与音效。正确路径（角色册里先点角色、再点槽位）**全篇没有任何一处提到**。
   后果：核心回路无法被文档复现。修法：改成"打开角色册，点一个角色，再点槽位"，并把 B 描述为"只换预设"。

2. **`README.md:55` — 与实现相反。**
   文档说"动画被打断则那一发不结算伤害"，实现是打断**仍然结算**（`ShowcaseEntity.java:361-365`，这是规格 §5.6 的要求）。玩家会以为放 UB 有丢伤害风险而不敢用。
   后果：把一个安全设计宣传成危险的。

3. **`README.md:52` — 承诺了不存在的功能。**
   "全部技能说明"没有实现（界面不读 `effects()`）。玩家打开册子找不到技能说明，会认为界面坏了。

4. **`docs/acceptance.md:167` — 把没做过的键操作写成 PASS。**
   该文件开头自己定义"PASS 必须带证据（…实际跑过的一次观测）"，而这一行的证据是直接发 payload 的探针，与"按 B"无关。这一行是发布闸门里唯一一处**把未执行的动作标成已完成**，它会让人对另外四行 PASS 的可靠性也打折扣。
   后果：闸门失真比缺一行更贵。

5. **规格 `§13` 第 6 行（`spec:785`）仍写 `100211/100231/100261`（优衣的 id）。**
   与同文件 §9.4 的 `105701/105711/105731/105761` 自相矛盾。包作者照 §13 做会把优衣的头像挂在姬塔名下——而这个项目**已经因为"推导 id 而不看素材"错过两次**（`spec:650-655` 自己写的教训）。
   后果：发布内容错人。

6. **四个"声明了但没有任何实现"的配置项，对应三个不存在的机制**（玩家可见的死键，属规格 §8.3 明令禁止的"界面骗人"）：
   - `ServerConfig` `ubChargePerSecond`（`ServerConfig.java:181`）—— `ChargeKernel.naturalRegen` 零生产调用，规格 §5.4 承诺的"兜底自然回复"不存在；
   - `ClientConfig` `ubCameraPush`（`ClientConfig.java:121`）—— 零读者，无任何镜头逻辑；
   - `ClientConfig` `enableScrollSwitching`（`:68`）+ `ServerConfig` `scrollSwitchPolicy`（`:198`）—— **滚轮切换完全没有实现**（全库无滚轮输入监听，除角色册分页外）。而"滚轮切换作为可选配置项"是规格决策 #12，`scrollSwitchPolicy` 还专门有 ALLOW/DENY/FORCE 三个取值。
   后果：这些旋钮在设置界面里可以被玩家拖动、在 TOML 里可以填、永远没有任何效果。要么实现，要么从 spec/界面里撤掉（撤掉是一行级别的代码改动，但本审查不改代码）。

7. **工作区有 4 个未跟踪文件，发布前必须定去留。**
   `src/main/resources/assets/astrum/textures/effect/ub_burst.png`（223 KB）、`ub_flare.png`（124 KB）、`ub_strike.png`（138 KB）、`tools/fx_from_ai.py`。
   已开箱核对 `astrum-0.1.0-FINAL.jar`：**这三张 png 不在 jar 里**，所以现有发布物是干净的。但它们躺在 `src/main/resources` 下，`build.gradle` 的 `sourceSets.main.resources` 会把它们打进任何**未来**重新构建的 jar（合计 +486 KB，与 `b6deb87`「352 KB → 57 KB」的瘦身意图相反）。
   后果：要么提交（那就同时把 `fx_from_ai.py` 里写死的 `K:\AI\Minecraft\dsh-image-gen` 绝对路径改成相对路径，否则脚本对别人不可用），要么删掉/加进 `.gitignore`。不能就这么留着——下一个构建者会以为它们是已交付资产。

---

## 已知但可接受的债务

来自 `.superpowers/sdd/2026-11-11-pcr-djeeta-vertical-slice/progress.md`，逐条按 ledger 自己的口径记录，不重新讨论。

**已被人类裁定、不再复议的偏差**
- 主题从 **item id** 推导，不用 `astrum:book_theme` 数据组件（理由：4 个事实里 3 个由 id 定死，组件会成为第 4 份可矛盾的副本）。
- **UB 伤害由 sink 自己结算**，不进效果管线、不乘 `MultiplierChain`（UB 没有 hook index，规格 §5.4 说 UB 不吃递减）。
- 结算点在**动画第 14 tick（STRIKE_FRACTION = 0.5）**而不是按下那一 tick；改成 0.0 是一行常数改动。
- `ShowcaseLifetime.shouldDespawn` 取 **3 个参数**（age / animationTicks / maxLifetime），而非计划里的 2 个。
- 参战**均分**经验（每个在轮盘上的角色都分一份，不论谁打得多）。
- 三套 UI 主题；`rice_bowl` 取代原来的糖/可可豆/蜂蜜/蛋糕四种原版食材。
- 两项 area_charge 护栏的**文件可填 0、OP 覆盖不可填 0** 这条不对称。
- 「一个 trigger 多个 effect 全部执行」——曾因 ledger 作者自己误读 §5.3 而丢过 `area_charge`（最严重的一次自伤），现已修正并加了 mutation 证据。

**已知缺口（ledger 已记，不在本次审查范围）**
- `copyOnDeath()` **零自动化覆盖**：它是 `AttachmentType` 的包私有字段，单测断言不到；删掉它 631 个测试仍全绿。唯一证据是 T6 的一次真机探针（respawn 前后 `hasData roster=true`、队伍第 3 槽原样回来）。**这是本分支最贵的一条"只靠一次探针"的保证**，建议在 M2/M3 之前补 GameTest（`build.gradle:62-69` 里 `neoforge.enabledGameTestNamespaces` 已经配好，但仓库里 0 个 `@GameTest`）。
- 八个触发器的**活体验证不完整**：`combo_n`/`crit`/`kill`/`low_health`/`block` 只有真值表级测试；只有 `stack_full` 与引爆隔离被真机测过。姬塔的三条 hook 里两条挂在 `combo_n:3`，而 `combo_n` 的端到端**从未在真实世界观测过**。
- 伤害面板与 `seiga_itten` 的四段挥砍：4 次 hurt 落在同一 tick（见上文只经人眼 #2）。
- 单人/局域网主机以外的 `IntegratedServer`「开放到局域网」路径未验证；跨机器未验证。
- `SINGLE_TARGET_RANGE = 6.0` 是**凭空定的常数**（ledger 问题 c）。
- `CAST_ANIMATION_SECONDS = 1.4` 与客户端动画长度是两份数据，靠一个测试断言相等；数据包替换动画 JSON 会让脱战时间错位（诚实修法是每档一个长度字段，属模式变更，已出界）。
- 若同一 tick 内被击退/重击，`LandedHit` 在无敌窗口内最多丢 `(extra − main)` 的伤害（已裁定接受，属 JSON 平衡问题而非逻辑问题）。
- 同 tick 的 `extra_damage` 合并依赖 `preHitHealth` 单槽 map，嵌套伤害序列理论上可覆盖（ledger 建议改成按 `DamageContainer` 队列）。
- `.gitattributes` 未拷、`gradlew` 是 `100644`（`./gradlew` 在 Linux/macOS/CI 上会 Permission denied；本机没用上，发布 jar 不受影响，但仓库作为开源件会被人第一时间碰到）。
- `build.gradle:177` 的 `publishing { maven { url "file://..." } }` 在 Gradle 10 会硬失败（模板继承）。
- `neoforge.mods.toml` 里 NeoForge 的 `versionRange` 是字面量 `[21.1.265,)` 而非 `${neo_version}`，改版本号会留下过期下限。
- `impl/attach` 现在持有 `Stack*` 类型、`impl/combat` 消费它们（方向正确，但计划文件清单写的是 `impl/combat`）。
- `sdd.ps1` 的三个静默失败 bug（`$Args` 影子、`-like '*'`、pipe 挂起）已修但 `.superpowers` 被 gitignore，所以修复**不在任何提交里**——下一个会话会重新踩。
