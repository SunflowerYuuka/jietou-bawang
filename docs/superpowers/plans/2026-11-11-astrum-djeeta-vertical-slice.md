# Astrum 一期实施计划（姬塔垂直切片）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 MC 1.21.1 + NeoForge 上做出「角色卡驱动」的 PCR 战斗 mod 的首个可玩切片——只做姬塔，从合成角色卡到放出 4 段 UB 全流程走通。

**Architecture:** 角色效果不新建攻击管线，而是挂在 NeoForge 伤害事件上，用「trigger × effect」的 datapack 数据组合表达；五个角色槽的有序列表同时决定递减倍率与 UB 轮盘位次；UB 演出时临时生成一个 GeckoLib 实体播放动画后自毁，伤害在服务端一次性结算。

**Tech Stack:** Java 21 / Minecraft 1.21.1 / NeoForge / ModDevGradle / GeckoLib 4 / JUnit 5

**Spec:** `docs/superpowers/specs/2026-11-11-pcr-character-mod-design.md`（下称"规格"。本计划的每个数值与规则都引自它，执行时两份一起读。）

## Global Constraints

- **JDK 21**（已装，默认 `java`）。目标 jar 用 Java 21 编译。**不要装 JDK 25**，那是 26.x 的要求。
- **Minecraft 1.21.1**，`minecraft_version_range=[1.21.1,1.21.2)`，`javaVersion="[21,)"`。
- **modid = `astrum`**（永久不可改，它出现在所有 `ResourceLocation` 里），**包名 `com.astrum.pcrd`**，主类 `Astrum`。
- NeoForge 依赖**必须走代理** `127.0.0.1:7890`。下载卡住先确认代理开着，再怀疑别的。
- **禁止 Mixin**（规格 §7）。全部走事件与扩展点。
- **禁止 `level.getDataStorage()`** 存任何世界级状态——它按维度各存一份，会静默分叉。世界级开关一律走 ModConfig。
- **禁止用 `isDedicatedServer()` 做功能分支**——局域网主机的 `IntegratedServer` 也是服务端。只用 `level.isClientSide()` / `Dist` 判端。
- 所有 `net.minecraft.client.*` 只能出现在 `impl/client/` 下，用 `Dist.CLIENT` 限定。
- `api.v1` 包的签名里只允许 `ResourceLocation` / `ResourceKey` / `Holder` / 基本类型 / 自己的 record。**`Level`、`Entity`、`ServerPlayer`、`ItemStack` 一律不进签名**。
- 配置值**在使用处懒读取**，不得静态缓存；`diminishingBase` 必须 clamp 到 `[0.1, 1.0]`。
- 模块化走 **L1 功能分包**。验收：删掉 `impl/` 下任意一个子包，项目仍能编译。
- GeckoLib 声明 `type="required"`、`versionRange="[4.7,)"`、`side="BOTH"`，**到 Task 10 才引入**——前面任务不装它也能跑。
- 本计划中的 NeoForge API 名称以 1.21.1 为准；落笔时若与 IDE 补全不符，**以 IDE 为准**，并在提交信息里记下差异。

## 测试策略（重要，先读）

本项目的可自动化测试只覆盖**不依赖游戏运行时的纯逻辑**，其余靠手工验收。这不是偷懒，是这一阶段唯一诚实的分工：

| 类型 | 手段 | 覆盖 |
|---|---|---|
| **单元测试（JUnit 5，`./gradlew test`）** | `src/test/java`，可 import MC 的纯数据类（`ResourceLocation`、`Codec`）但不能碰注册表和世界 | 倍率计算、充能累积、tap/hold 状态机、Codec 往返与容错 |
| **编译验证** | `./gradlew compileJava` | 一切代码 |
| **手工验收** | `./gradlew runClient` / `runServer` + 规格 §11 的清单 | 一切与游戏运行时耦合的部分 |

一期**不做 GameTest**。写测试时若发现某个类必须 import `Level` 或注册表，说明它该拆成「纯逻辑 + 薄壳」，按那个方式拆。

---

## Review Focus

以下五类输入/条件，规格隐含要求正确处理但没有哪条测试天然覆盖，是最容易咬到玩家的地方。每条都在下面标注了由哪个任务测试。

1. **坏掉或字段不全的 datapack JSON** —— 一个 `data/astrum/character/*.json` 写错不该让世界进不去，只该被跳过并在日志里报一行。（Task 2 的 Codec 容错测试 + Task 4 的手工验收）
2. **局域网主机（`IntegratedServer`）+ 第二名玩家** —— 主机走了"单人分支"会让加入方数值不同步，且单人测试永远发现不了。（Task 7 的手工验收加跑 LAN 一栏；Task 12 再跑一次）
3. **多个玩家都带姬塔** —— `area_charge` 互相充能会形成闭环，充能速率随人数线性上涨。（Task 6 的成对 CD 测试 + Task 8 手工验收）
4. **演出中区块卸载 / 玩家传送 / 服务器卡顿** —— 动画被打断时没有兜底就会永久留下一个角色站在那。（Task 10 的 `maxLifetime` 手工验收）
5. **空队伍（0 个角色）时按 UB 键、打开轮盘、攻击** —— 最容易 NPE 的地方，玩家新开局就是这个状态。（Task 7 的空队伍测试 + Task 9 手工验收）

---

### Task 1: 工程脚手架，空 mod 能启动（M0）

**Files:**
- Create: `settings.gradle`, `gradle.properties`, `build.gradle`, `gradle/wrapper/*`, `.gitignore`
- Create: `src/main/resources/META-INF/neoforge.mods.toml`
- Create: `src/main/java/com/astrum/pcrd/Astrum.java`

**Interfaces:**
- Consumes: 无
- Produces: 可编译可启动的工程骨架；`Astrum` 主类（后续所有注册都从这里挂）

- [ ] **Step 1: 初始化 git 仓库**

```bash
cd /k/AI/Minecraft/PrincessConnet && git init && git add -A && git commit -m "chore: 初始化仓库（含设计规格）"
```

- [ ] **Step 2: 取官方 MDK 模板，不要手写脚手架**

从 `https://github.com/NeoForgeMDKs/MDK-1.21-ModDevGradle` 取 1.21.1 分支的工程文件（`gradlew`、`gradle/wrapper/`、`build.gradle`、`settings.gradle`、`gradle.properties`）复制进工程根。**代理必须开着**，见指南 §2.4。

- [ ] **Step 3: 改版本三件套与 modid**

`gradle.properties` 里把 `mod_id` 改成 `astrum`、`mod_name` 改成 `Astrum`、`mod_group_id` 改成 `com.astrum.pcrd`、`mod_version` 改 `0.1.0`；确认 `minecraft_version=1.21.1`、`neo_version` 为 1.21.1 对应版本。

- [ ] **Step 4: 写 `Astrum.java` 与 `neoforge.mods.toml`**

`Astrum.java`：`@Mod("astrum")` 主类，构造函数收 `(IEventBus modBus, ModContainer container)`，先留空。`neoforge.mods.toml` 里填 `modId = "astrum"`、`displayName`、`minecraft_version_range="[1.21.1,1.21.2)"`、`[[features.astrum]] javaVersion="[21,)"`。

- [ ] **Step 5: 启动验证**

Run: `./gradlew runClient`
Expected: 游戏进主菜单，Mods 列表里有 Astrum。第一次构建要 10–60 分钟（含反编译），属正常。

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat: 空 mod 骨架，runClient 可启动"
```

---

### Task 2: `api.v1` 数据记录与 Codec

**Files:**
- Create: `src/main/java/com/astrum/pcrd/api/v1/RegistryKeys.java`
- Create: `src/main/java/com/astrum/pcrd/api/v1/EffectHook.java`
- Create: `src/main/java/com/astrum/pcrd/api/v1/UbDef.java`
- Create: `src/main/java/com/astrum/pcrd/api/v1/GrowthDef.java`
- Create: `src/main/java/com/astrum/pcrd/api/v1/AffectionDef.java`
- Create: `src/main/java/com/astrum/pcrd/api/v1/CharacterDef.java`
- Create: `src/main/java/com/astrum/pcrd/api/v1/CharacterProgress.java`
- Test: `src/test/java/com/astrum/pcrd/api/v1/CharacterDefCodecTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `RegistryKeys.CHARACTERS : ResourceKey<Registry<CharacterDef>>`（值 `astrum:character`）
  - `record CharacterDef(ResourceLocation id, String displayName, Dimension dimension, List<EffectHook> effects, UbDef ub, GrowthDef growth, AffectionDef affection)`，`static final Codec<CharacterDef> CODEC`
  - `enum Dimension { OUTPUT, SURVIVAL, CHARGE, CONTROL, RESOURCE }`
  - `record EffectHook(String trigger, String effect, Map<String,Float> triggerParams, Map<String,Float> effectParams, float chance)`
  - `record UbDef(String animation, int hits, float damage, float selfAtkBuff, int buffTicks, float teamChargeBonus)` —— 后三个用 `optionalFieldOf` 带默认值（`1.0f` / `0` / `0f`），并提供便利工厂 `UbDef.simple(String animation, int hits, float damage)`
  - `record CharacterProgress(int level, int exp, int affection)`，带 `CODEC`

- [ ] **Step 1: 写失败的 Codec 往返测试**

```java
@Test void 往返不丢字段() {
    CharacterDef in = new CharacterDef(ResourceLocation.fromNamespaceAndPath("astrum","djeeta"),
        "姬塔", Dimension.CHARGE, List.of(), UbDef.simple("seiga_itten", 4, 20f),
        new GrowthDef(100, 1.15f, 30), new AffectionDef(3, 8, List.of()));
    JsonElement json = CharacterDef.CODEC.encodeStart(JsonOps.INSTANCE, in).getOrThrow();
    CharacterDef out = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    assertEquals(in, out);
}

@Test void 缺字段时解析失败但不抛异常() {
    JsonObject json = new JsonObject();  // 空对象
    var result = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json);
    assertTrue(result.error().isPresent());   // 返回 DataResult 错误，而非 throw
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew test --tests '*CharacterDefCodecTest*'`
Expected: 编译失败，`CharacterDef` 不存在。

- [ ] **Step 3: 实现七个 record 与 Codec**

全部用 `RecordCodecBuilder.create`。字段名严格照规格 §4.2 的 JSON 键：`schemaVersion`、`displayName`、`dimension`、`effects`、`ub`、`growth`、`affection`；`EffectHook` 用 `triggerParams`/`effectParams`（**不要**用 `params` 或 `params2`，规格里已修掉那个瑕疵）。`Dimension` 用 `StringRepresentable.fromEnum` 解析成小写。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew test --tests '*CharacterDefCodecTest*'`
Expected: PASS（2 个测试）

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(api): CharacterDef 等数据记录与 Codec"
```

---

### Task 3: 纯逻辑核心与单元测试

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/combat/Diminishing.java`
- Create: `src/main/java/com/astrum/pcrd/impl/ub/ChargeMath.java`
- Create: `src/main/java/com/astrum/pcrd/impl/client/WheelInputState.java`
- Test: `src/test/java/com/astrum/pcrd/impl/combat/DiminishingTest.java`
- Test: `src/test/java/com/astrum/pcrd/impl/ub/ChargeMathTest.java`
- Test: `src/test/java/com/astrum/pcrd/impl/client/WheelInputStateTest.java`

**Interfaces:**
- Consumes: `Dimension`（Task 2）
- Produces:
  - `static float[] Diminishing.multipliers(List<Dimension> party, boolean enabled, float base)` —— 按槽位顺序返回 5 个倍率，同维度第 n 名（1 起）为 `clamp(base^(n-1), 0.1, 1.0)`
  - `static float ChargeMath.accumulate(float charge, float delta, float max)` —— clamp 到 `[0, max]`
  - `static boolean ChargeMath.canCast(float charge, float max, long cdEndTick, long nowTick)`
  - `enum WheelPhase { IDLE, PENDING, WHEEL }` + `class WheelInputState`，方法 `onPress(long nowMs)`、`onRelease(long nowMs)`、`onTick(long nowMs)`、`WheelPhase phase()`

- [ ] **Step 1: 写三个失败测试**

```java
@Test void 同维度按顺序递减_不同维度不递减() {
    var m = Diminishing.multipliers(Arrays.asList(
        Dimension.OUTPUT, Dimension.SURVIVAL, Dimension.OUTPUT,
        Dimension.CHARGE, Dimension.OUTPUT), true, 0.7f);
    assertEquals(1.00f, m[0], 1e-4);
    assertEquals(1.00f, m[1], 1e-4);
    assertEquals(0.70f, m[2], 1e-4);
    assertEquals(1.00f, m[3], 1e-4);
    assertEquals(0.49f, m[4], 1e-4);
}

@Test void 关闭递减时全为1() { /* enabled=false 时五个都是 1.0 */ }

@Test void 系数被clamp在合理区间() { /* base=0 不除零、base=5 不超 1.0 */ }

@Test void 充能不越界() {
    assertEquals(100f, ChargeMath.accumulate(99f, 50f, 100f), 1e-4);
    assertEquals(0f,   ChargeMath.accumulate(1f, -50f, 100f), 1e-4);
}

@Test void 点按直接放_长按进入轮盘() {
    var s = new WheelInputState();
    s.onPress(0); s.onRelease(150);
    assertEquals(WheelPhase.IDLE, s.phase());   // tap 已处理
    s.onPress(1000); s.onTick(1300);
    assertEquals(WheelPhase.WHEEL, s.phase());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew test --tests '*DiminishingTest*' --tests '*ChargeMathTest*' --tests '*WheelInputStateTest*'`
Expected: 编译失败。

- [ ] **Step 3: 实现三个类**

`Diminishing.multipliers` 用一个 `EnumMap<Dimension,Integer>` 计数；`WheelInputState` 的阈值常量 `HOLD_MS = 200`，`onRelease` 在 `nowMs - pressMs < HOLD_MS` 时置 `IDLE` 并**置一个 `consumeTap` 标志**供调用方取走（这样测试与 UI 共用同一份判定）。这三个类**不得 import 任何 `net.minecraft.*`**。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew test`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: 递减/充能/轮盘输入三处纯逻辑与单测"
```

---

### Task 4: datapack registry 与姬塔定义（M1）

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/registry/CharacterRegistry.java`
- Create: `src/main/java/com/astrum/pcrd/api/v1/AstrumApi.java`
- Create: `src/main/resources/data/astrum/character/djeeta.json`
- Modify: `src/main/java/com/astrum/pcrd/Astrum.java`

**Interfaces:**
- Consumes: `CharacterDef.CODEC`、`RegistryKeys.CHARACTERS`（Task 2）
- Produces:
  - `CharacterRegistry.init(IEventBus)`，监听 `DataPackRegistryEvent.NewRegistry` 注册 `CHARACTERS`
  - `static Optional<CharacterDef> CharacterRegistry.get(ResourceLocation id)`
  - `AstrumApi.characters() : Registry<CharacterDef>`（唯一门面，见规格 §9.1）

- [ ] **Step 1: 注册 datapack registry**

`CharacterRegistry.init` 里 `event.dataPackRegistry(RegistryKeys.CHARACTERS, CharacterDef.CODEC, CharacterDef.CODEC, b -> b.maxId(256))`。主类构造函数调它。

- [ ] **Step 2: 写 `djeeta.json`（自举验证）**

按规格 §6.4 的 JSON 原样落盘，含三个 effect、`ub.base`/`ub.enhanced`、`growth`、`affection`（tier2=3、tier3=8）。**不要硬编码在 Java 里**——自己走公开 API 路径，这是 A8 的自举验证。

- [ ] **Step 3: 加一条 reload 后打印的日志**

在 `CharacterRegistry` 里监听 `AddReloadListenerEvent` 或 `OnDatapackSyncEvent`，遍历注册表把 `id + displayName + 三阶门槛` 打到日志。

- [ ] **Step 4: 手工验收**

Run: `./gradlew runClient` → 进世界 → `/reload`
Expected: 日志出现 `astrum:djeeta 姬塔 tier2=3 tier3=8`。

- [ ] **Step 5: 验收"坏 JSON 只跳过不崩"**

把 `djeeta.json` 里 `"dimension"` 的值改成乱码，重启世界。
Expected: 世界正常进入，日志报该文件解析失败，注册表为空（或只少了这个角色），**不崩**。改回来。

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat: datapack registry 与姬塔定义（M1）"
```

---

### Task 5: 四个 Attachment

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/attach/RosterData.java`
- Create: `src/main/java/com/astrum/pcrd/impl/attach/PartyData.java`
- Create: `src/main/java/com/astrum/pcrd/impl/attach/UbState.java`
- Create: `src/main/java/com/astrum/pcrd/impl/attach/StackState.java`
- Create: `src/main/java/com/astrum/pcrd/impl/attach/Attachments.java`
- Modify: `src/main/java/com/astrum/pcrd/Astrum.java`
- Test: `src/test/java/com/astrum/pcrd/impl/attach/StackStateTest.java`

**Interfaces:**
- Consumes: `CharacterProgress.CODEC`（Task 2）
- Produces:
  - `Attachments.ROSTER : Supplier<AttachmentType<RosterData>>`
  - `Attachments.PARTY : Supplier<AttachmentType<PartyData>>`（5 槽有序，`List<Optional<ResourceLocation>>`）
  - `Attachments.UB_STATE : Supplier<AttachmentType<UbState>>`
  - `Attachments.STACK : Supplier<AttachmentType<StackState>>`（`serialize=false`）
  - `StackState.get(ResourceLocation stackId, UUID attacker, long nowTick) : int` 与 `add(...)`，带 `lastHitTick` 惰性过期

- [ ] **Step 1: 写失败的叠层过期测试**

```java
@Test void 叠层超时后归零() {
    var s = new StackState();
    s.add(new ResourceLocation("astrum","poison"), ATTACKER, 100L);
    s.add(new ResourceLocation("astrum","poison"), ATTACKER, 110L);
    assertEquals(2, s.get(new ResourceLocation("astrum","poison"), ATTACKER, 120L));
    assertEquals(0, s.get(new ResourceLocation("astrum","poison"), ATTACKER, 500L)); // 已过期
}
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew test --tests '*StackStateTest*'`，Expected: 编译失败。

- [ ] **Step 3: 实现四个数据类与注册**

`ROSTER`/`PARTY`/`UB_STATE` 用 `AttachmentType.builder(...).serialize(codec, ...).copyOnDeath().build()`；`STACK` 用 `serialize(codec)` **但不加**持久化（`serialize=false` 在 1.21.1 NeoForge 里即只建不序列化，按 IDE 补全确认写法）。`PartyData` 构造时校验"同一角色不得占两个槽"，重复时丢后者并打日志。

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test`，Expected: PASS。

- [ ] **Step 5: 手工验收跨维度不丢**

Run: `./gradlew runClient` → `/astrum debug roster` 打印空 → 手工塞一个角色 → 走地狱门 → 再打印。
Expected: 数据还在（玩家 Attachment 存 `playerdata/`，跨维度共享）。

- [ ] **Step 6: 提交** — `git commit -m "feat: 四个 Attachment 与叠层惰性过期"`

---

### Task 6: 附着管线（M2 前半）

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/combat/HookResolver.java`
- Create: `src/main/java/com/astrum/pcrd/impl/combat/StackTracker.java`
- Create: `src/main/java/com/astrum/pcrd/impl/combat/DamagePipeline.java`
- Create: `src/main/java/com/astrum/pcrd/impl/combat/AstrumDamageTypes.java`
- Test: `src/test/java/com/astrum/pcrd/impl/combat/HookResolverTest.java`

**Interfaces:**
- Consumes: `CharacterRegistry.get`、`Attachments.PARTY`、`Diminishing.multipliers`、`StackState`
- Produces:
  - `record ResolvedHook(ResourceLocation characterId, EffectHook hook, float multiplier)`
  - `static List<ResolvedHook> HookResolver.resolve(List<ResourceLocation> party, String trigger)` —— 只返回该 trigger 命中的钩子，倍率已乘好
  - `DamagePipeline.onIncomingDamage(LivingIncomingDamageEvent)` 与 `DamagePipeline.onDamagePost(LivingDamageEvent.Post)`

- [ ] **Step 1: 写失败的解析测试**

```java
@Test void 只有一个角色时倍率为1() { /* party=[djeeta], trigger="melee_hit" → 1 条，mult=1.0 */ }
@Test void 空队伍返回空列表不抛() { /* party=[null,null,null,null,null] → List.of() */ }
@Test void 两个同维度角色第二个被递减() { /* 见 Task 3 的倍率表 */ }
```

- [ ] **Step 2: 跑测试确认失败** — Expected: 编译失败。

- [ ] **Step 3: 实现 `HookResolver`**

读 `PARTY` 的 5 个槽 → 取 `CharacterDef` → 按 `Diminishing.multipliers` 算倍率 → 过滤 `trigger` 匹配的 hook。**不得 import `Level`**（用传入的 party 列表，不查玩家）。

- [ ] **Step 4: 跑测试确认通过** — `./gradlew test`，Expected: PASS。

- [ ] **Step 5: 实现 `DamagePipeline` 与递归短路**

监听伤害事件；入口第一行判 `if (source.is(AstrumDamageTypes.TAG)) return;`（**短路，防递归**）。收集本 tick 所有 `extra_damage` 累加成一个数，在 tick 结束时**只调一次** `target.hurt(customSource, total)`。

- [ ] **Step 6: 手工验收**

Run: `./gradlew runClient` → 给玩家装姬塔 → 拿剑砍僵尸。
Expected: 每 3 次命中出现一次范围伤害；控制台无栈溢出（递归检查通过）。

- [ ] **Step 7: 提交** — `git commit -m "feat(combat): 附着管线与递归短路（M2）"`

---

### Task 7: 网络包、UB 结算与 HUD（M2 完成）

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/network/NetworkHandler.java`
- Create: `src/main/java/com/astrum/pcrd/impl/network/payload/ChargeSyncPayload.java`
- Create: `src/main/java/com/astrum/pcrd/impl/network/payload/UbCastPayload.java`
- Create: `src/main/java/com/astrum/pcrd/impl/ub/UbService.java`
- Create: `src/main/java/com/astrum/pcrd/impl/client/ClientSetup.java`
- Create: `src/main/java/com/astrum/pcrd/impl/client/HudChargeBar.java`
- Modify: `src/main/java/com/astrum/pcrd/Astrum.java`

**Interfaces:**
- Consumes: `Attachments.UB_STATE`、`ChargeMath`、`CharacterRegistry`
- Produces:
  - `UbService.addCharge(ServerPlayer p, float amount)`、`UbService.tryCast(ServerPlayer p, int slot) : boolean`
  - `ChargeSyncPayload(float charge, float max, long cdEndTick)`，`TYPE` + `STREAM_CODEC`
  - `UbCastPayload(int slot)`，服务端 `tryCast` 前必校验槽合法、槽满、CD 到

- [ ] **Step 1: 注册 payload**

`RegisterPayloadHandlersEvent` 里 `registrar.playToClient(ChargeSyncPayload.TYPE, ...)` 与 `registrar.playToServer(UbCastPayload.TYPE, ...)`。**两端都要注册**，否则 LAN 加入方收不到。

- [ ] **Step 2: 实现 `UbService`**

`addCharge` 走 `ChargeMath.accumulate` 并**只改服务端**；改完立即推 `ChargeSyncPayload`（每 tick 最多一次）。`tryCast` 依序校验：槽位 ∈ [0,5)、槽内有角色、`ChargeMath.canCast` 为真；通过则清空充能、置 CD、返回 true。

- [ ] **Step 3: 写失败的空队伍测试**

```java
@Test void 空队伍按UB不崩() {
    // party 全空时 tryCast 返回 false，且不抛异常、不清空充能
}
```

- [ ] **Step 4: 跑测试确认失败再通过** — `./gradlew test --tests '*UbServiceTest*'`

- [ ] **Step 5: 实现 HUD**

`RegisterGuiLayersEvent` 注册在 `VanillaGuiLayers.HOTBAR` 之上，画一条充能条 + 冷却环。位置与开关读客户端配置（Task 12 补），此处先硬编码在 hotbar 上方，**避开右上角**（Jade/AppleSkin 的地盘）。

- [ ] **Step 6: 手工验收（含 LAN）**

Run: `./gradlew runClient` → 砍怪 → 充能条上涨 → 换维度后不归零。
再跑一次：Open to LAN + 第二个 `runClient` 加入。
Expected: **两个客户端的充能条数值一致**，加入方也能看到条在动。这一条专抓"主机走了单人分支"。

- [ ] **Step 7: 提交** — `git commit -m "feat(ub): 充能同步与 HUD（M2 完成）"`

---

### Task 8: UB 轮盘

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/client/WheelInput.java`
- Create: `src/main/java/com/astrum/pcrd/impl/client/WheelRenderer.java`
- Modify: `src/main/java/com/astrum/pcrd/impl/client/ClientSetup.java`

**Interfaces:**
- Consumes: `WheelInputState`（Task 3）、`UbCastPayload`（Task 7）、`Attachments.PARTY`
- Produces:
  - `WheelInput` 每客户端 tick 驱动 `WheelInputState`，产出 `OptionalInt tapSlot` 与 `OptionalInt holdSlot`
  - `WheelRenderer` 在 `WHEEL` 相位绘制 5 等分径向菜单

- [ ] **Step 1: 注册键位**

`RegisterKeyMappingsEvent` 注册释放键，默认 `V`（**不能用 `R`**，那是 JEI 的配方键；也不用中键）。必须可在控制里改。

- [ ] **Step 2: 接上状态机**

`ClientTickEvent.Post` 里把按下/松开喂给 `WheelInputState`：拿到的 tap 发 `UbCastPayload(selectedSlot)`；进入 `WHEEL` 后鼠标方向 72° 一档选中并**只更新本地预设**（松手才提交）。

- [ ] **Step 3: 屏蔽快捷栏数字键**

`WHEEL` 相位下拦截 `1`–`5`：改放对应槽的 UB 并关闭轮盘，**同时取消原版快捷栏切换**。漏了这条，玩家按 3 会同时切到第 3 格物品。

- [ ] **Step 4: 渲染**

环半径 = 屏高 0.40，环带宽度 ≈ 12%，**环内完全留空**；背景 alpha 0.30、图标与文字不透明。3 阶角色画强化版图标（规格 §6.2 的要求）。

- [ ] **Step 5: 手工验收**

Run: `./gradlew runClient` → 点按 `V` 直接放 → 长按 `V` 出轮盘 → 鼠标选格切换预设 → 长按 + 按 `3` 立刻放第 3 槽。
Expected: 按数字键时**快捷栏不切换**；空队伍时开轮盘不崩。

- [ ] **Step 6: 提交** — `git commit -m "feat(ui): UB 轮盘与 tap/hold 输入"`

---

### Task 9: 演出实体与 GeckoLib（M3）

**Files:**
- Modify: `build.gradle`（加 GeckoLib 依赖）
- Modify: `src/main/resources/META-INF/neoforge.mods.toml`（声明 required 依赖）
- Create: `src/main/java/com/astrum/pcrd/impl/entity/ShowcaseEntity.java`
- Create: `src/main/java/com/astrum/pcrd/impl/client/ShowcaseRenderer.java`
- Create: `src/main/resources/assets/astrum/geo/djeeta.geo.json`
- Create: `src/main/resources/assets/astrum/animations/djeeta.animation.json`
- Modify: `src/main/java/com/astrum/pcrd/impl/ub/UbService.java`

**Interfaces:**
- Consumes: `UbService.tryCast` 通过后的时机
- Produces:
  - `ShowcaseEntity` 带 `characterId`、`animationName`、`maxLifetime`（默认 60 tick）
  - `UbService` 在服务端一次性结算伤害后生成实体（受 `maxActiveShowcaseEntities` 约束）

- [ ] **Step 1: 引入 GeckoLib**

`build.gradle` 加 GeckoLib 依赖：**先查 `https://github.com/bernie-g/geckolib` 的 1.21.1 NeoForge 分支实际版本号再落笔**，不要凭记忆填。`neoforge.mods.toml` 加 `[[dependencies.astrum]] modId="geckolib" type="required" versionRange="[4.7,)" ordering="AFTER" side="BOTH"`。**区间留大**，锁太死会让装了新版的玩家直接进不去。

- [ ] **Step 2: 写演出实体**

`GeoEntity` 子类；`noGravity`、`invulnerable`、无碰撞、不参与 AI；`tick()` 里 `if (tickCount > maxLifetime) discard()` —— **这是兜底，必须有**。

- [ ] **Step 3: 服务端一次性结算伤害**

在 `tryCast` 成功后**立刻**结算伤害（不等动画播完），再判 `maxActiveShowcaseEntities`：超限就跳过生成实体，只留伤害。

- [ ] **Step 4: 验收伤害不延迟**

Run: `./gradlew runClient` → 放 UB。
Expected: **伤害立刻出现**，动画随后播；动画期间按 Esc / 传送离开，实体消失不留残影。

- [ ] **Step 5: 验收兜底自毁**

改 `maxLifetime` 为 20 tick 试验；再在演出中把玩家 teleport 到 1000 格外。
Expected: 实体在 1 秒内消失，不永久残留。

- [ ] **Step 6: 提交** — `git commit -m "feat(ub): GeckoLib 演出实体与兜底自毁（M3）"`

---

### Task 10: 角色卡与队伍（M3）

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/item/CharacterCardItem.java`
- Create: `src/main/resources/data/astrum/recipe/djeeta_card.json`
- Create: `src/main/resources/assets/astrum/lang/zh_cn.json`、`en_us.json`
- Modify: `src/main/java/com/astrum/pcrd/Astrum.java`

**Interfaces:**
- Consumes: `Attachments.ROSTER`、`Attachments.PARTY`、`CharacterRegistry.get`
- Produces:
  - `CharacterCardItem` 带 `characterId` DataComponent；右键写 `ROSTER` 并消耗卡
  - 队伍界面（`impl/client/`）读 `ROSTER` 出可用角色、拖拽排序写 `PARTY`

- [ ] **Step 1: 写物品与合成**

`DeferredRegister.Items` 注册；`recipe/djeeta_card.json` 用原版材料做有序合成。`use()` 里若 `ROSTER` 已有该 id，则**不消耗卡**并提示"已拥有"。

- [ ] **Step 2: 队伍界面**

5 个槽 + 一个可用角色列表；支持拖拽排序（顺序 = 递减优先级 = 轮盘位次）。改动后发 `PartySyncPayload` 通知服务端。

- [ ] **Step 3: 手工验收**

Run: `./gradlew runClient` → 收集材料合成卡 → 右键 → 队伍界面里出现姬塔 → 放进槽 1 → 砍怪触发效果。
Expected: 全流程通；重复右键提示"已拥有"且卡不被吃掉。

- [ ] **Step 4: 提交** — `git commit -m "feat: 角色卡合成与队伍界面"`

---

### Task 11: 等级、好感度与配置收尾

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/progress/GrowthService.java`
- Create: `src/main/java/com/astrum/pcrd/impl/config/AstrumServerConfig.java`
- Create: `src/main/java/com/astrum/pcrd/impl/config/AstrumClientConfig.java`
- Modify: `src/main/java/com/astrum/pcrd/Astrum.java`
- Test: `src/test/java/com/astrum/pcrd/impl/progress/GrowthTest.java`

**Interfaces:**
- Consumes: `Attachments.ROSTER`、`GrowthDef`、`AffectionDef`
- Produces:
  - `static int GrowthService.expForLevel(GrowthDef g, int level)` —— `expBase * expPerLevel^(level-1)`
  - `static int GrowthService.affectionTier(AffectionDef a, int affection)` → 1/2/3
  - `AstrumServerConfig`：`enableDiminishingReturns`、`diminishingBase`、`ubGlobalCooldownTicks`、`allyMode`、`areaChargePairCooldownTicks`、`areaChargeReceiveCapPerSecond`、`maxActiveShowcaseEntities`

- [ ] **Step 1: 写失败的经验曲线与阶梯测试**

```java
@Test void 经验随等级递增() { /* lvl1=100, lvl2=115, lvl3=132（取整） */ }
@Test void 好感度三阶边界() { /* 0→1阶, 3→2阶, 8→3阶 */ }
```

- [ ] **Step 2: 跑测试确认失败再通过** — `./gradlew test --tests '*GrowthTest*'`

- [ ] **Step 3: 接等级经验**

玩家击杀时按**造成的伤害占比**给队伍里的角色分摊经验（规格 §6.1）。**不是**给所有角色平均分。

- [ ] **Step 4: 接好感度**

送料理（原版食物）给队伍角色，`affection` 累加，过阈值时：2 阶套用 `tier2HookModifiers`；3 阶切到 `ub.enhanced`。

- [ ] **Step 5: 写两个配置文件**

按规格 §8.2/§8.3 逐项落。**每个读取点直接 `CONFIG.x.get()`**，不得缓存到 static 字段。`diminishingBase` 读取后 clamp 到 `[0.1, 1.0]`。

- [ ] **Step 6: 手工验收配置改动**

Run: `./gradlew runClient` → 改 `<存档>/serverconfig/astrum-server.toml` 里 `enableDiminishingReturns=false` → 重进世界。
Expected: 递减失效（同维度角色都吃满倍率）。注意：改代码里的默认值不会影响已生成的老 toml，要手动删文件。

- [ ] **Step 7: 提交** — `git commit -m "feat: 等级、好感度三阶与服务端配置"`

---

### Task 12: 收尾验收（M3 完成）

**Files:**
- Modify: `README.md`（新建）

**Interfaces:**
- Consumes: 全部
- Produces: 可交付的一期切片

- [ ] **Step 1: 跑完整验收矩阵**

逐条执行规格 §11 的四项 + §11 的 LAN 附加项：

```bash
./gradlew runClient     # 进游戏、Mods 列表有 Astrum
./gradlew runServer     # 专服能起、无红字
./gradlew test          # 全绿
```

Expected: 三条全过；旧存档能进；装 JEI 不崩。

- [ ] **Step 2: 跑 L1 分包验收**

临时把 `impl/combat/` 整个目录挪出工程，`./gradlew compileJava`。
Expected: 只有引用它的地方报错，其余能编译；说明分包边界成立。挪回来。

- [ ] **Step 3: 校准 UB 与附着的数值比例**

按规格 §5.3 的护栏核对：打一只僵尸，记录"姬塔平时附着总输出 20 秒的总量"，再放一次 UB 记录伤害。
Expected: UB 伤害落在该总量的 **15–25 倍**区间内（即一次 UB ≈ 15–25 秒的平时输出）。超出就调 `djeeta.json` 的 `ub.base.damage` 或 `gain_charge`/`splash` 的数值。

- [ ] **Step 4: 写 README**

含：玩法一句话、姬塔的技能映射表、`data/astrum/character/*.json` 的 10 行字段示例（规格 §9.2 的 A8 要求）、配置项说明、已知限制（1 阶是白板）。

- [ ] **Step 5: 提交** — `git commit -m "docs: README 与一期验收完成"`
