# 验收矩阵 — Astrum (姬塔切片)

本页是发布闸门（release gate）。每一行不是「应该能用」，而是**实际跑过**的一次观测：PASS 必须带证据（日志原文、测试名、命令输出），FAIL 就是缺陷，做不到就写 HUMAN 并给出人照着做能复现的步骤。

- 版本：`0.1.0`，MC 1.21.1 + NeoForge 21.1.265，GeckoLib 4.9.3（**外部依赖，不内嵌**）
- 测量时间：2026-11-13
- 单元/集成测试：**663 通过，0 失败**（`gradlew test`，74 个测试类）

## 状态总览

| # | 项目 | 状态 |
|---|---|---|
| 1 | 专用服务端启动 + 客户端加入 | **PASS** |
| 2 | 旧存档 / 版本漂移（隔离而非清空） | **PASS** |
| 3 | 删除测试（角色定义消失后不崩） | **PASS** |
| 4 | JEI / REI 共存 | **HUMAN（未安装 JEI）** |
| 5 | 局域网双人：一人放 UB，另一人看见 | **部分 PASS**：施放者侧全 PASS；旁观者侧 **未实现** |
| 6 | 完整可玩循环（5 分钟人肉清单） | **HUMAN** |
| 7 | UB 特效长什么样 | **HUMAN** |

三行 PASS、零行 FAIL、三行 HUMAN、一行部分 PASS（§5 说明口径变化）。

**自动化能证明什么，不能证明什么。** 名字带 PASS 的行，证据都是日志原文或测试名。凡是「要有人
看着屏幕、按住一个键、点一个格子」才能确认的事，一律写在 HUMAN 行里并给出照着做就能复现的步骤——
**没有一条 PASS 是靠「代码里写了这个分支」撑起来的**。

---

## 1. 专用服务端启动 + 客户端加入 — PASS

**证据 A（服务端）** — `gradlew runServer`，真实 `DedicatedServer`，探针用 `PlayerList.placeNewPlayer` 放进两个真实 `ServerPlayer`（只有 socket 是内存通道，其余全是生产路径）：

```
[12:59:14] [Server thread/INFO] [minecraft/DedicatedServer]: Done (3.741s)! For help, type "help"
[12:59:14] [Server thread/INFO] [astrum/]: [T16] characters loaded: [astrum:djeeta]
[12:59:14] [Server thread/INFO] [astrum/]: [T16] PASS two real players are on the dedicated server - server.players=2 level.players=2
[12:59:14] [Server thread/INFO] [astrum/]: [T16] PASS the shipped datapack definition reached the server's registry - astrum:djeeta defined=true
```

> 该日志里还有一条 `[T16] PASS the showcase sink is installed (the entity, ...)` —— 那是**变更前**的
> 断言，今天是相反的：装的是 `EffectsOnlySink`，`astrum:showcase` 仍然注册但没有任何代码生成它。
> 今天这一条由下面这行取代（同一台真实的专用服务端，2026-11-13 重跑）：
>
> ```
> astrum: UB cast sink installed = effects only (no entity is spawned; damage settles on the cast; the caster's client is told to draw the effect)
> astrum: payloads registered (..., ub_cast_effect, ...), protocol 2
> ```
>
> 这一轮的 `runServer` 只是启动 + 加载，**没有**再跑双人场景（见 §5 的口径说明）。

**证据 B（真实客户端加入真实远程服务端）** — `gradlew runClient` 连 `127.0.0.1:25597`，**不是**单人世界：

```
[13:55:42] [Render thread/INFO] [astrum/]: [T16C] joined a world: multiplayer=true singleplayer=false players=2
[13:55:42] [Render thread/INFO] [astrum/]: [T16C] PASS this client joined a REAL remote server (not single-player) - connection=true singleplayer=false remotePlayers=2
```

服务端在同一次运行里记录了这个真实客户端登录：

```
[13:55:41] [Server thread/INFO] [minecraft/MinecraftServer]: Dev joined the game
```

即：**独立进程的专用服务端 + 独立进程的真实客户端**，双方都是生产路径，没有单人世界替代。服务端与客户端这一次运行的错误行数均为 0（过滤 JNA/`SystemReport` 之类的沙箱噪音后）。

---

## 2. 旧存档 / 版本漂移 — PASS

场景是**降级**：磁盘上的 `dataVersion` 比本构建能读的更高。做法完全走生产路径——真实 `PlayerList.saveAll()` 写出真实 `playerdata/<uuid>.dat`，把 `astrum:roster` 的 `dataVersion` 改成 `1+7`，再塞进一条真实进度（`level 12 / exp 345 / affection 6`），然后用**登录时那条加载路径**读回来。

```
[T16] PASS the server wrote a real playerdata file - .../world/playerdata/<uuid>.dat
[T16] PASS that file carries the astrum:roster attachment as the mod writes it - keys=[entries, dataVersion] dataVersion=1
[T16] PASS a roster written by a newer dataVersion is quarantined, not misread - quarantined=true buildReads=1 fileWrote=8
[T16] PASS a quarantined book refuses to be read as an empty one - entries() threw=true
[T16] PASS the quarantined tag is written back unchanged — the book is not wiped - equal=true keys=[dataVersion, entries] level=12
[T16] PASS the roster push to the client is the quarantine marker, not an empty book - payload=RosterSyncPayload[entries=[], quarantined=true]
[T16] PASS a cast from a quarantined book still resolves (base tier, nothing thrown) - refusal=Optional.empty chargeLeft=0.0
```

服务端自己打的那行 WARN 也一并留证：

```
[Server thread/ERROR] [astrum/]: astrum: unreadable roster attachment kept as-is and left untouched on disk
  (Unsupported astrum roster dataVersion 8 (this build reads 1). Refusing to reinterpret it.)
```

**端到端到屏幕**：同一个隔离状态被推给一个**真实客户端**，界面确实走了隔离分支而不是画一个空书：

```
[13:55:49] [T16C] PASS an unreadable save reaches this client as a QUARANTINE, not an empty book - quarantined=true entries=0
[13:55:49] [T16C] PASS the 角色册 screen draws the quarantine state without throwing - rendered=true screen=CharacterBookScreen
```

结论：书没被清空（`equal=true`、`level=12` 原样回写），玩家能看到「存档读取失败」而不是一个看似空的书，且游戏仍可玩（UB 回落到基础档）。

---

## 3. 删除测试 — PASS

角色 JSON 从整合包里消失时，存档里仍拥有它、编队里仍带着它。这是最容易崩的状态，逐条走过：

```
[T16] PASS a party edit for a character the pack does not define is refused (CHARACTER_UNKNOWN) - reason=CHARACTER_UNKNOWN accepted=false
[T16] PASS a cast from a slot naming an undefined character is refused with CHARACTER_UNKNOWN - reason=Optional[CHARACTER_UNKNOWN] chargeLeft=100.0
[T16] PASS feeding a party of undefined characters is NOBODY_TO_FEED, not a crash - outcome=NOBODY_TO_FEED
[T16] PASS a landed hit with an undefined character on the wheel does not throw - targetHealth=18.08/20.0
[T16] PASS a kill with an undefined character on the wheel does not throw - victimAlive=false health=0.0
[T16] PASS the roster push still describes the owned-but-undefined character honestly - payload=RosterSyncPayload[entries=[Entry[id=astrum:deleted_character, level=5, exp=40, expToNext=0, affection=4, tier=1]], quarantined=false]
[T16] PASS the still-defined character keeps casting after the deletion (the mod degrades) - refusal=Optional.empty showcases=3
```

> 最后一条里的 `showcases=3` 是**变更前**的实体计数；今天同一个场景下这个数字恒为 0，而「照常能放」
> 这一半（`refusal=Optional.empty`）仍然成立。**这一行的 PASS 只依赖 `refusal=Optional.empty` 与
> `targetHealth` / `victimAlive` 这些数值**，不依赖任何实体计数——2026-11-13 的 effects-only 构建
> 把实体整个从施放路径上拿掉了，所以「删档后不崩」这件事的形态是：**伤害照常结算、拒绝仍是带类型的**，
> 而不是「演出实体仍然生成」。

要点：拒绝是**带类型的**（`CHARACTER_UNKNOWN`），不是异常；气槽没被扣（`chargeLeft=100.0`）；名册推送把 `expToNext` 报成 0 而不假装有曲线；同一个会话里仍定义的角色照常能放 UB —— 是降级，不是瘫掉。

> **注意（诚实的边界）**：数据包注册表只在进入世界时读取，本 mod 没有「运行中热重载角色 JSON」的钩子。所以这里是把「定义已不存在」这个**状态**直接构造出来，再把所有会解析它的路径跑一遍——与真实删档后玩家所处的状态相同。删文件后**不重开世界**就期待它立刻消失，不属于本构建承诺的行为。

---

## 4. JEI / REI 共存 — HUMAN（未安装 JEI）

**这一行没有测，因为 JEI 不在这个环境里。** 本仓库的依赖只有 NeoForge 与 GeckoLib；`build.gradle` 里 JEI 的三行是 ModDevGradle 模板自带的注释，全部被注释掉，没有任何 `mezz.jei` 制品在类路径上。

能说的只有一件事，而且已经测过：**JEI 缺席时一切正常**——上面第 1–3、5、6 行的全部运行都是在没有 JEI 的环境里跑的，663 个测试也在这个环境里全绿。

**不声称 JEI 兼容性。** 要验证需要由人来做：

1. 装 JEI（NeoForge 1.21.1 版本），与本 mod 同进一个 `mods/`。
2. 启动游戏，确认没有 `Missing or unsupported mandatory dependencies`、没有 mixin 冲突崩溃。
3. 打开 JEI 的物品列表，搜 `character_card`、`character_book`、`rice_bowl`，确认三者的配方都能被 JEI 显示（本 mod 的配方是原版 `crafting_shapeless` JSON，JEI 通过原版配方表读取，理论上应当直接可见——但**未测就是未测**）。
4. 打开 JEI 时按 `B`/`V`，确认轮盘与 HUD 仍正常绘制。

REI 同理，完全未测。

---

## 5. 局域网双人：一人放 UB，另一人看见 — PASS（2026-11-13 起改为「特效」口径）

> **口径变化（必须读）**：本行的原始证据来自**演出实体**时代——服务端生成 `astrum:showcase`，另一个
> 客户端通过网络收到这个实体并绘制。2026-11-13 的变更（不需要实体，但是实体的相关代码保留）之后
> **不再生成任何实体**，UB 的表现形式是**施放者自己屏幕上的特效**。所以下面两类结论要分开看：
>
> | 断言 | 现在 | 证据 |
> |---|---|---|
> | 一次施放生成 0 个 `astrum:showcase` | **PASS** | §5.1（同一台真实客户端 + 集成服务端的 21 项探针） |
> | 伤害照常结算（20 / 4×20） | **PASS** | §5.1 |
> | 施放者的客户端特效层被触发并绘制 | **PASS** | §5.1（层自己打的 `began at client tick N` + 每帧 DEBUG） |
> | **旁观者**看得见别人的 UB 特效 | **未实现，不是 PASS** | 见 §5.2 |

### 5.1 现在的实测（真实客户端 + 集成服务端，21 项，0 失败）

`gradlew runClient`（先 `sdd.ps1 mute`），探针自建一次性世界 `astrum-t17-probe`，日志
`build/t17-client7.log`。**没有截图、没有点击、没有输入、没有聚焦窗口，鼠标未移动。**

```
[T17] PASS the installed cast sink is EffectsOnlySink
[T17] PASS no astrum:showcase exists before the cast (0)
[T17] base cast: refusal=Optional.empty hp 400.0 -> 380.0 (lost 20.0), showcases 0
[T17] PASS the base tier dealt exactly 20 once (lost 20.0)
[T17] PASS the base cast spawned no astrum:showcase
[T17] enhanced cast: refusal=Optional.empty hp 380.0 -> 300.0 (lost 80.0), showcases 0
[T17] PASS the enhanced tier dealt 4 x 20 = 80 once (lost 80.0)
[T17] PASS the enhanced cast spawned no astrum:showcase
[T17] PASS astrum:showcase is still registered although nothing spawns it
[T17] PASS the effect installed no Screen (no pause, no input capture)
[T17] PASS the client received both effect payloads (2)
[T17] PASS the registered GUI layer drew effect frames (86)
[T17] PASS the last drawn frame is the effect's final tick, not an earlier one (age 19 of 20)
[T17] === 21 checks, 0 failures ===
```

生产代码自己打的三行（不是探针），证明「包到达 → 层开始画」这条链路：

```
astrum: ub_cast_effect received — character astrum:djeeta, base (effect #1), start client tick 18
astrum: ub_cast_effect received — character astrum:djeeta, enhanced (effect #2), start client tick 18
astrum: UB effect layer astrum:ub_effect began at client tick 18 — character djeeta, enhanced,
        gui 427x240, burst Box[x=105, y=12, width=216, height=216], cut-in Box[x=241, y=98, width=176, height=132]
```

伤害结算的服务端 DEBUG（同一份日志）：

```
astrum: UB 姬塔 hit 1/1 on 僵尸 -> 20.0 damage, lost 20.0 hp, landed=true (hp 380.0/400.0)
astrum: UB 姬塔 of Dev settled 1 of 1 declared hits on 1 target(s), 20.0 damage asked in total
astrum: UB 姬塔 hit 1/4 on 僵尸 -> 20.0 damage ... (2/4, 3/4, 4/4 each 20.0)
astrum: UB 姬塔 of Dev settled 4 of 4 declared hits on 1 target(s), 80.0 damage asked in total
```

### 5.2 旧的「旁观者看见」证据——已不再适用

以下是**变更前**的观测，留档是因为它记录了「网络同步一个实体」这件事在当时确实工作过；它**不能**
用来声称今天的旁观者能看见什么：

```
[13:55:45] [Server thread/INFO] [astrum/]: [T16S] peer cast #8: showcases=1 ids=[676]
[13:55:45] [T16C] PASS this client received another player's showcase entity over the network - count=1 id=676 type=entity.astrum.showcase pos=-40.5/73.0/-6.3 playersInWorld=2
```

现在的 UB 特效**只发给施放者本人**（`PacketDistributor.sendToPlayer`）：全屏特效是不可回避的，广播给
身边每个人是一个这一轮没有做的设计决定（见 task-17 报告的 concerns）。所以「队友能不能看见我的必杀」
**今天没有实现**，本行不再声称 PASS。

### 5.3 与 UB 无关但同一场景的旁观方证据（仍然成立）

这两条说的是**气槽同步**和**轮盘**，与演出实体无关，所以变更不影响它们：

**HUD 在旁观方保持正确**（它自己的气槽、自己的编队，没有被别人的状态污染）：
```
[13:55:49] [T16C] PASS the second client's party sync carries the other player's wheel state - ownParty=[empty×5] ownCharge=3.06
[13:55:49] [T16C] PASS the UB HUD renders on the second client without throwing - renders=1 charge=3.06 guiHeight=240
[13:55:49] [T16C] PASS the wheel can resolve every slot digit against the party it was pushed - digits=[1->OptionalInt[0], ..., 5->OptionalInt[4]]
```

`ownCharge=3.06` 这个值本身是跨玩家证据：旁观方没打人，却被同伴 `area_charge`（半径 8、每次 3 点）充了 3.06 点——正是它站在 2.01 格内的结果。服务端侧的对应观测：

```
[T16] PASS a second player's gauge is charged by another player's area_charge hook - bobCharge=3.06 (djeeta area_charge radius 8.0, amount 3.0, on combo 3) distance=2.01
```

> 同一份旧日志里还有两条 `[T16] PASS ... showcase's 160-block tracking range` /
> `the spawn announcement names astrum:showcase` —— 它们断言的是**变更前**的实体同步，今天不再成立，
> 所以不再列在这里（与 §5.2 同一个理由）。

**未覆盖的部分**：integrated server 的「对局域网开放」按钮（需要点 GUI，本会话禁止操作游戏窗口），以及**两台不同机器**之间的真实网络（这里两端都在本机，走 loopback）。这两点属于 HUMAN 范围，但没有证据表明会不同——本 mod 没有任何 `isDedicatedServer()` 分支（`guard` 会把这类分支判为 FAIL），单人/专用服务端跑的是同一套代码。

---

## 6. 完整可玩循环 — HUMAN

给人在 5 分钟内照着走的清单。数据与机制在自动化里都验过了（下方逐项标注），但「手感」只能人来判断。

| 步 | 做什么 | 应当看到 | 自动化已验 |
|---|---|---|---|
| 1 | 合成角色卡：纸 + 金粒 + 青金石（无序） | 得到「姬塔」角色卡 | ✅ `character_id=astrum:djeeta` |
| 2 | 合成角色册：书 + 纸×2 + 线（无序） | 得到角色册（另有两种主题：加粉色染料 / 加绿色染料） | ✅ 三种主题都验过 |
| 3 | 手持角色卡右键 | 聊天栏「姬塔 加入了你的角色册（等级 1，好感 1）」，卡被消耗 | ✅ 原文照录 |
| 4 | 手持角色册右键 | 打开角色册界面，能看到姬塔 | ⚠️ 屏幕未人肉目视（见下方第 4 条说明） |
| 5 | 在角色册里点「姬塔」格，再点 `② 编队` 的 1 号槽 | 格子上出现绿色「在队」角标，1 号槽画出姬塔头像 | ⚠️ **HUMAN**：只验过服务端那一侧（见 §6.1） |
| 6 | 打怪（约 10 只僵尸） | 经验上涨，升级时聊天栏「姬塔 升到 2 级」 | ✅ `kills=10 now=level 2` |
| 7 | 合成料理：碗 + 小麦×2；手持右键方块 | 「吃下…，N 名角色好感提升」；同一料理同一天再喂会被拒 | ✅ 好感 +1，同日再喂被拒且不扣 |
| 8 | 连续几天各喂一份 | 好感达到 8 → 3 阶 | ✅ `affection=8 threshold=8 daysFed=6` |
| 9 | 充满气槽，按 `V` | 屏幕上一道金白剑气 + 爆环 + 姬塔切入图，约 1 秒淡出；伤害**立刻**落到目标 | ✅ 21 项探针（§5.1）：0 实体、20 点伤害、特效层绘制 |
| 10 | 好感 3 阶后按 `V` | 强化 UB：4 段，共 80 点；特效层同样触发（`enhanced` 位为真） | ✅ 探针实测 4×20 = 80；⚠️ 特效**好不好看**未人肉目视 |
| 11 | 按 `K` 打开设置界面 | 三个小节（轮盘/HUD/UB 特效）可改，改完立刻生效 | ⚠️ 未人肉目视（键集可由日志证明，见 §6.2） |

**关于第 4、5 步——组队走的是角色册，不是 `B`。** `B` 是**预设轮盘**（按住 → 数字键切换预设），
它**不改队伍**；改队伍的唯一入口是角色册的 `② 编队` 区域（先点一个角色格，再点槽位放入；右键槽位清空）。
旧版这一行写的是「按 `B`，把姬塔放进 1 号位」并标 PASS，那是错的，两处都错：`B` 不做这件事，
而当时的「证据」其实是一次**直接发 `party_edit` 包**的探针——它证明了服务端接受这个包（那是 §6.1 的
`PartyService` 路径，真实且仍然成立），但**没有**证明玩家能在界面上点出来。所以这一行现在是 HUMAN。

**关于第 9、10 步**：「伤害落到目标」现在是**施放瞬间**结算（没有实体就没有 tick 源，见 task-17 报告），
所以第 9 步的「1.4 秒后」已不成立。「特效长什么样」只有人能判断——见 §7。

**关于第 10、11 步**：强化 UB 的**数据**在 `astrum:djeeta` 的 `ub.enhanced` 里、**判定**由
`AffectionKernel.usesEnhancedUb` 单一实现，单测覆盖了阶梯，探针也实测了 4×20 = 80 点伤害；但
「按 `V` 肉眼看到的那一秒值不值得看」需要人看屏幕。

判定标准很简单：**任何一步与「应当看到」不符，就是缺陷，请连同日志一起报。**

### 6.1 组队这条路径：自动化验到哪里，人还要验什么

**已验（服务端，`PartyRulesTest` 18 个用例 + `PartyEditPayloadTest` 10 个，都在 `gradlew test` 里）**：
`PartyService.edit` 是 `/astrum party set` 和 `party_edit` 包**共用的那一个**实现，测试钉住了
「不拥有 → `NOT_OWNED`、槽位越界 → `SLOT_OUT_OF_RANGE`、角色不存在 → `CHARACTER_UNKNOWN`、
书读不懂 → `BOOK_UNREADABLE`、同一个角色不能占两个槽 → 后一次是**移动**」这些判定。
也就是说：**只要那个包发得对，队伍一定对。**

> **为什么这里没有日志原文。** 旧版这一行标 PASS，依据是一次直接发 `party_edit` 包的探针。
> 那次探针的日志在本仓库里**没有留存**（`run/logs/` 被后续运行覆盖，`.superpowers/sdd/.../logs/`
> 只有 t7/t8 的）。既然拿不出原文，就不能在这里引用它——引用一段自己无法指认的日志，正是这一行
> 原本失真的一半原因。可复核的替代品是上面两个测试类的名字，它们随时可以重跑。

**未验（也就是 HUMAN 的那一半）**：玩家的鼠标能不能走到这条路径上。具体是

1. 右键角色册 → `CharacterBookScreen` 打开，`① 角色册` 格里出现「姬塔」；
2. 左键点姬塔格 → 该格高亮，`③ 养成` 开始显示她的等级 / 经验 / 好感；
3. 左键点 `② 编队` 的 1 号槽 → 槽里出现头像，角色格右上出绿色「在队」角标；
4. 右键那个槽 → 槽清空，角标消失；
5. 把姬塔从格子里**拖**到 2 号槽 → 与第 3 步同样的结果（拖拽与点击发的是同一个包）。

几何本身有单测（`BookLayoutTest` 断言格与槽矩形不重叠、点击坐标映射到哪一个格），但「画出来的东西
和算出来的矩形一致」只有眼睛能确认。

### 6.2 配置界面这次由日志证明的部分

配置界面是**从 spec 生成的**（`ConfigurationScreen` 遍历 `ModConfigSpec`），所以「界面上有没有某个键」
等价于「spec 里有没有某个键」，后者可以打日志。这一轮删掉三个没人读的键之后，实际写进
`config/astrum-client.toml` 的键集是：

```
client keys = [hud.enableUbHud, hud.hudAnchor, hud.hudOffsetX, hud.hudOffsetY,
               wheel.wheelMode, wheel.wheelOpacity, wheel.wheelScale,
               effect.enableUbCastEffect]
```

`[camera]` 整节、`enableScrollSwitching` 都不在其中——`ConfigDefaultsTest.theClientConfigHasExactlyTheSpecKeys`
把这份键集**逐字**钉住，多一个键就红。**但「界面上看起来对不对」（分组标题、控件是按钮还是文本框、
改完是否立刻生效）仍然只有人能判断**，这就是第 11 步留 HUMAN 的原因。

### 6.3 每秒自然回能（`ubChargePerSecond`）这次实测了什么

这个键以前也在「改了什么也不发生」的清单上。现在它由 `impl.ub.PassiveCharge` 在
`PlayerTickEvent.Post` 上支付，2026-11-13 在一台**真实专用服务端**上跑过（`gradlew runServer`，
先用 `sdd.ps1 mute`），日志原文存在 `build/t18-passive-charge.log`。

探针放了一个真实 `ServerPlayer` 到真实世界里，然后每 tick 调 `player.doTick()`——也就是
`ServerGamePacketListenerImpl.tick()` 对已连接玩家做的那一次调用，**不是**绕开被测代码的捷径。
全程没有截图、没有点击、没有输入、没有聚焦窗口，鼠标未移动；服务端由探针自己 `halt(false)`。

```
[T18] player placed on the dedicated server (FakePlayer, dummy connection) — level=minecraft:overworld charge=0.0
[T18] server config keys = [balance.comboWindowTicks, balance.diminishingBase, balance.enableDiminishingReturns, balance.stackTtlTicks, multiplayer.allyMode, multiplayer.areaChargePairCooldownTicks, multiplayer.areaChargeReceiveCapPerSecond, multiplayer.maxActiveShowcaseEntities, ub.ubChargePerDamage, ub.ubChargePerDamageTaken, ub.ubChargePerKill, ub.ubChargePerSecond, ub.ubGlobalCooldownTicks]
[T18] client config keys = [effect.enableUbCastEffect, hud.enableUbHud, hud.hudAnchor, hud.hudOffsetX, hud.hudOffsetY, wheel.wheelMode, wheel.wheelOpacity, wheel.wheelScale]
```

**A. 配成 20/秒（1 点/tick），从 0 起跑** — 气槽稳步上升，第 100 tick 到顶，之后既不超也不动：

```
[T18] phase 1: ubChargePerSecond = 20.0 (read back 20.0), gauge reset to 0.0
[T18] phase 1 tick 20: charge = 19.0
[T18] phase 1 tick 60: charge = 59.0
[T18] phase 1 tick 100: charge = 99.0
[T18] PASS charge reached the cap at the configured 20/s — samples [20t=19.0, 40t=39.0, 60t=59.0, 80t=79.0, 100t=99.0]
[T18] tick 101: charge = 100.0 (20/s = 1/tick, so the cap lands at tick 100)
[T18] PASS a full gauge holds the cap and does not overshoot — samples […, 120t=100.0, 160t=100.0, 200t=100.0]
```

（采样点比 tick 计数早一拍，是因为 tick 20 的那行读的是第 19 次增量的结果；第 101 tick 的
`charge = 100.0` 才是「第 100 次增量把它填满」的直接证据。）

**B. 配成 0，跑 200 tick** — 一点都不涨：

```
[T18] phase 2: ubChargePerSecond = 0.0 (read back 0.0), gauge reset to 0.0
[T18] phase 2 tick 200: charge = 0.0
[T18] PASS with ubChargePerSecond = 0 the gauge never moves — samples […]
```

**C. 回到出厂默认 0.2/秒（0.01/tick）** — 这一条是「小数余数不丢」的现场证明：前 99 tick 都是 0.0，
第 100 tick 突然变成 1.0，第 200 tick 是 2.0。**任何每 tick 截断的实现都会永远停在 0.0**：

```
[T18] phase 3: ubChargePerSecond back to the default 0.2 (read back 0.2), gauge reset
[T18] phase 3 tick 75: charge = 0.0 (0.01/tick, whole points only)
[T18] phase 3 tick 100: charge = 1.0 (0.01/tick, whole points only)
[T18] PASS the default 0.2/s pays its first whole point on tick 100 — samples […]
[T18] phase 3 tick 200: charge = 2.0 (0.01/tick, whole points only)
[T18] PASS and keeps accruing: two points by tick 200 — samples […]
[T18] === 5 checks, 0 failures ===
```

**这只是服务端的一半。** 「HUD 上的条是否肉眼可见在涨」「`0` 之后条真的停住不动」需要人看屏幕——
见 §6 第 11 步与 §7。另外探针跑完已由 `sdd.ps1 clean` 删除，`guard` 复查 `no probe junk` 为 OK。

---

## 7. UB 特效长什么样 — HUMAN（只能由人判断）

这一节的每一条都**没有被任何人看过**。本会话不允许截图、不允许操作游戏窗口，所以我能给的只有「它被
触发了、它画了 86 帧、最后一帧是第 19 tick」这类**间接**证据（§5.1），以及下面这一小段由生产代码自己
打的每帧日志：

```
astrum: UB effect frame layer=astrum:ub_effect age=0/20t quads=2 frames=1
        (flare 0.47 slash 0.00 burst 0.00 cut-in 0.85, character djeeta, enhanced)
```

要人回答的问题：

1. **像不像金色剑气**：`ub_strike` / `ub_burst` / `ub_flare` 三张 AI 生成的图（已做亮度→alpha 转换）
   叠在一起，是「金光一闪」还是「一团糊」？
2. **姬塔切入图**（`ub_cutin_djeeta.png`，从官方 6★ 卡面裁的 4:3）出现在**右下角**，会不会太大 /
   太小 / 挡住视线？这个位置是本项目其它 HUD 之外唯一空着的角落，但没有被目视确认过。
3. **0.85 峰值透明度**够不够亮、会不会太白；`[effect] enableUbCastEffect` 关掉之后是否真的没有残留。
4. **1 秒**是不是太长/太短——全屏闪光在连战时每 5 秒（100 tick 冷却）来一次。
5. **F1 隐藏 HUD 时特效一起消失**是否符合预期（现在会，因为它是 GUI 层）。
6. 视觉上是否**只**有一秒，没有「画完了但还在偷偷画空白」的残影（这一条有算术和探针各一半证据）。

---

## 复现方式

```powershell
# 单元/集成（663 个测试）—— 先让 TEMP 指向仓库内可写目录
New-Item -ItemType Directory -Force build\gametmp | Out-Null
$env:TEMP='<repo>\build\gametmp'; $env:TMP=$env:TEMP
gradlew test --rerun-tasks
```

### 本机环境的三条硬要求（都实测踩过）

| 要求 | 为什么 | 漏了会怎样 |
|---|---|---|
| `-Djava.io.tmpdir=<repo>\build\gametmp` | LWJGL 启动时要把 `lwjgl.dll` 解到 `java.io.tmpdir`；本机默认 TEMP 不可写 | `runClient` 报 `UnsatisfiedLinkError: Failed to locate library: lwjgl.dll`，而且**只停在 `Loading ImmediateWindowProvider fmlearlywindow`**，MC 日志只有 4 行——真错误在 Gradle 输出里 |
| `TEMP` / `TMP` 指向同一个可写目录 | `BookThemeTest` 用 ImageIO 读 PNG，ImageIO 要建临时缓存文件 | 5 个用例报 `IIOException: Can't create cache file!`（`AccessDeniedException`），看起来像图片坏了，其实是 TEMP 不可写 |
| 每次 `runClient` 前跑 `sdd.ps1 fixnatives` | Gradle 缓存会把 `lwjgl-*-natives-windows-arm64.jar` 重新下回来，LWJGL 按类路径上**有什么**选 natives 而不是按 `os.arch` | 在 amd64 机器上选到 arm64 natives，同样死在 `lwjgl.dll` |

```powershell
# 专用服务端（本机只需要上面第一条里的 tmpdir，如果 gradle.properties 没设的话）
pwsh .superpowers/sdd/sdd.ps1 mute          # 启动前静音（用户要求）
gradlew runServer

# 客户端连本地服务端：先 fixnatives，再 mute，再启动
pwsh .superpowers/sdd/sdd.ps1 fixnatives
pwsh .superpowers/sdd/sdd.ps1 mute
gradlew runClient "-Djava.io.tmpdir=<repo>\build\gametmp"   # 或用 gradle.properties / JAVA_TOOL_OPTIONS
```

> 这三条都是**本机沙箱**的性质，不是 mod 的行为：干净机器上不需要。写在这里是因为它们造成的失败
> 长得像代码缺陷（「dll 找不到」「图片读不了」），而每次重新发现它们都要花掉半小时。
