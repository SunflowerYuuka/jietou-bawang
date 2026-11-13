# 验收矩阵 — Astrum (姬塔切片)

本页是发布闸门（release gate）。每一行不是「应该能用」，而是**实际跑过**的一次观测：PASS 必须带证据（日志原文、测试名、命令输出），FAIL 就是缺陷，做不到就写 HUMAN 并给出人照着做能复现的步骤。

- 版本：`0.1.0`，MC 1.21.1 + NeoForge 21.1.265，GeckoLib 4.9.3
- 测量时间：2026-11-13
- 单元/集成测试：**631 通过，0 失败**（`gradlew test --rerun-tasks`，71 个测试类）

## 状态总览

| # | 项目 | 状态 |
|---|---|---|
| 1 | 专用服务端启动 + 客户端加入 | **PASS** |
| 2 | 旧存档 / 版本漂移（隔离而非清空） | **PASS** |
| 3 | 删除测试（角色定义消失后不崩） | **PASS** |
| 4 | JEI / REI 共存 | **HUMAN（未安装 JEI）** |
| 5 | 局域网双人：一人放 UB，另一人看见 | **PASS** |
| 6 | 完整可玩循环（5 分钟人肉清单） | **HUMAN** |

四行 PASS、零行 FAIL、两行 HUMAN。

---

## 1. 专用服务端启动 + 客户端加入 — PASS

**证据 A（服务端）** — `gradlew runServer`，真实 `DedicatedServer`，探针用 `PlayerList.placeNewPlayer` 放进两个真实 `ServerPlayer`（只有 socket 是内存通道，其余全是生产路径）：

```
[12:59:14] [Server thread/INFO] [minecraft/DedicatedServer]: Done (3.741s)! For help, type "help"
[12:59:14] [Server thread/INFO] [astrum/]: [T16] characters loaded: [astrum:djeeta]
[12:59:14] [Server thread/INFO] [astrum/]: [T16] PASS two real players are on the dedicated server - server.players=2 level.players=2
[12:59:14] [Server thread/INFO] [astrum/]: [T16] PASS the shipped datapack definition reached the server's registry - astrum:djeeta defined=true
[12:59:14] [Server thread/INFO] [astrum/]: [T16] PASS the showcase sink is installed (the entity, not just the interface) - manager=true entityType=entity.astrum.showcase
```

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

要点：拒绝是**带类型的**（`CHARACTER_UNKNOWN`），不是异常；气槽没被扣（`chargeLeft=100.0`）；名册推送把 `expToNext` 报成 0 而不假装有曲线；同一个会话里仍定义的角色照常能放 UB —— 是降级，不是瘫掉。

> **注意（诚实的边界）**：数据包注册表只在进入世界时读取，本 mod 没有「运行中热重载角色 JSON」的钩子。所以这里是把「定义已不存在」这个**状态**直接构造出来，再把所有会解析它的路径跑一遍——与真实删档后玩家所处的状态相同。删文件后**不重开世界**就期待它立刻消失，不属于本构建承诺的行为。

---

## 4. JEI / REI 共存 — HUMAN（未安装 JEI）

**这一行没有测，因为 JEI 不在这个环境里。** 本仓库的依赖只有 NeoForge 与 GeckoLib；`build.gradle` 里 JEI 的三行是 ModDevGradle 模板自带的注释，全部被注释掉，没有任何 `mezz.jei` 制品在类路径上。

能说的只有一件事，而且已经测过：**JEI 缺席时一切正常**——上面第 1–3、5、6 行的全部运行都是在没有 JEI 的环境里跑的，631 个测试也在这个环境里全绿。

**不声称 JEI 兼容性。** 要验证需要由人来做：

1. 装 JEI（NeoForge 1.21.1 版本），与本 mod 同进一个 `mods/`。
2. 启动游戏，确认没有 `Missing or unsupported mandatory dependencies`、没有 mixin 冲突崩溃。
3. 打开 JEI 的物品列表，搜 `character_card`、`character_book`、`rice_bowl`，确认三者的配方都能被 JEI 显示（本 mod 的配方是原版 `crafting_shapeless` JSON，JEI 通过原版配方表读取，理论上应当直接可见——但**未测就是未测**）。
4. 打开 JEI 时按 `B`/`V`，确认轮盘与 HUD 仍正常绘制。

REI 同理，完全未测。

---

## 5. 局域网双人：一人放 UB，另一人看见 — PASS

同一台专用服务端上两个玩家：一个用真实 `ub_cast` 网络包放 UB，另一个是**独立进程的真实客户端**。关键证据是**实体 id 对得上**——服务端生成 `#676`，客户端收到的也是 `676`：

服务端：
```
[13:55:45] [Server thread/INFO] [astrum/]: [T16S] peer cast #8: showcases=1 ids=[676]
[13:55:51] [Server thread/INFO] [astrum/]: [T16S] peer cast #9: showcases=1 ids=[703]
```

客户端（同一个进程里的另一个玩家）：
```
[13:55:45] [T16C] PASS this client received another player's showcase entity over the network - count=1 id=676 type=entity.astrum.showcase pos=-40.5/73.0/-6.3 playersInWorld=2
[13:55:50] [T16C] PASS another player's showcase was visible to this client for the whole window - sawShowcase=true entitiesSeen=1 hudDrew=true
```

**HUD 在旁观方保持正确**（它自己的气槽、自己的编队，没有被别人的状态污染）：
```
[13:55:49] [T16C] PASS the second client's party sync carries the other player's wheel state - ownParty=[empty×5] ownCharge=3.06
[13:55:49] [T16C] PASS the UB HUD renders on the second client without throwing - renders=1 charge=3.06 guiHeight=240
[13:55:49] [T16C] PASS the wheel can resolve every slot digit against the party it was pushed - digits=[1->OptionalInt[0], ..., 5->OptionalInt[4]]
```

`ownCharge=3.06` 这个值本身是跨玩家证据：旁观方没打人，却被同伴 `area_charge`（半径 8、每次 3 点）充了 3.06 点——正是它站在 2.01 格内的结果。服务端侧的对应观测：

```
[T16] PASS a second player's gauge is charged by another player's area_charge hook - bobCharge=3.06 (djeeta area_charge radius 8.0, amount 3.0, on combo 3) distance=2.01
[T16] PASS the second player is inside the showcase's 160-block tracking range - distance=4.59 blocks
[T16] PASS the spawn announcement names astrum:showcase (what the other client draws) - type=entity.astrum.showcase entityId=100
```

**未覆盖的部分**：integrated server 的「对局域网开放」按钮（需要点 GUI，本会话禁止操作游戏窗口），以及**两台不同机器**之间的真实网络（这里两端都在本机，走 loopback）。这两点属于 HUMAN 范围，但没有证据表明会不同——本 mod 没有任何 `isDedicatedServer()` 分支（`guard` 会把这类分支判为 FAIL），单人/专用服务端跑的是同一套代码。

---

## 6. 完整可玩循环 — HUMAN

给人在 5 分钟内照着走的清单。数据与机制在自动化里都验过了（下方逐项标注），但「手感」只能人来判断。

| 步 | 做什么 | 应当看到 | 自动化已验 |
|---|---|---|---|
| 1 | 合成角色卡：纸 + 金粒 + 青金石（无序） | 得到「姬塔」角色卡 | ✅ `character_id=astrum:djeeta` |
| 2 | 合成角色册：书 + 纸×2 + 线（无序） | 得到角色册（另有两种主题：加粉色染料 / 加绿色染料） | ✅ 三种主题都验过 |
| 3 | 手持角色卡右键 | 聊天栏「姬塔 加入了你的角色册（等级 1，好感 1）」，卡被消耗 | ✅ 原文照录 |
| 4 | 手持角色册右键 | 打开角色册界面，能看到姬塔 | ✅ 服务端 use 不消耗 |
| 5 | 按 `B`，把姬塔放进 1 号位 | 轮盘 1 号位显示姬塔 | ✅ 走真实 `party_edit` 包 |
| 6 | 打怪（约 10 只僵尸） | 经验上涨，升级时聊天栏「姬塔 升到 2 级」 | ✅ `kills=10 now=level 2` |
| 7 | 合成料理：碗 + 小麦×2；手持右键方块 | 「吃下…，N 名角色好感提升」；同一料理同一天再喂会被拒 | ✅ 好感 +1，同日再喂被拒且不扣 |
| 8 | 连续几天各喂一份 | 好感达到 8 → 3 阶 | ✅ `affection=8 threshold=8 daysFed=6` |
| 9 | 充满气槽，按 `V` | 姬塔挥剑（`tempest_blade`），1.4 秒后伤害落到目标 | ✅ `strike@14`，目标 200→178.32 |
| 10 | 好感 3 阶后按 `V` | 换成强化 UB（`seiga_itten`，4 段） | ⚠️ 仅代码路径与数据，**未人肉目视** |
| 11 | 按 `K` 打开设置界面 | 三个分页（轮盘/镜头/HUD）可改，改完立刻生效 | ⚠️ 未人肉目视 |

**关于第 10、11 步**：强化 UB 的**数据**在 `astrum:djeeta` 的 `ub.enhanced` 里、**判定**由 `AffectionKernel.usesEnhancedUb` 单一实现，单测覆盖了阶梯；但「按 `V` 肉眼看到换成 4 段动画」需要人看屏幕。第 11 步的界面同理——`K` 键绑定与界面注册在代码里存在，但没有一次人肉点击验证。

判定标准很简单：**任何一步与「应当看到」不符，就是缺陷，请连同日志一起报。**

---

## 复现方式

```powershell
# 单元/集成（631 个测试）
$env:TEMP='<repo>\build\tmp-java'; $env:TMP=$env:TEMP
gradlew test --rerun-tasks

# 专用服务端
gradlew runServer

# 客户端连本地服务端（先改 run/server.properties 的 server-port 与 level-name）
gradlew runClient
```

> 沙箱环境注意：测试会在 `TEMP` 下写 ImageIO 临时文件，若 `TEMP` 不可写会出现 `Can't create cache file!` 的假失败。把 `TEMP`/`TMP` 指向仓库内可写目录即可。
