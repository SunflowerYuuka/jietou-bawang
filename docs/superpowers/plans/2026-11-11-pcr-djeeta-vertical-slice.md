# PrincessConnect 姬塔垂直切片 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 MC 1.21.1 + NeoForge 上把「角色卡驱动战斗」这条链用 1 个角色（姬塔）完整跑通：角色定义走 datapack → 攻击附着 → 叠层引爆 → UB 充能与轮盘 → 演出 → 合成卡/等级/好感度。

**Architecture:** 纯逻辑内核（无 MC 类型、JUnit 直接测）+ 薄 MC 胶水（事件/附件/网络/渲染）。静态定义走 datapack registry，玩家状态走 3 个分开的 `AttachmentType`，叠层挂目标实体且 `serialize=false`。对外 `api.v1` 只描述数据、不描述执行。

**Tech Stack:** Java 21、Gradle 9.6.1（wrapper）、ModDevGradle 2.0.142、NeoForge 21.1.265、JUnit 5、GeckoLib 4.9.3（仅 M3 起）。

**Spec:** `docs/superpowers/specs/2026-11-11-pcr-character-mod-design.md`（下称「规格」）；硬约束 `K:\AI\Minecraft\MC模组开发指南.md` §8/§9/§10。

## Global Constraints

- `minecraft_version=1.21.1`、`minecraft_version_range=[1.21.1,1.21.2)`、`neo_version=21.1.265`、`loader_version_range=[1,)`。
- `mod_id=astrum`、`mod_group_id=com.astrum.pcrd`、Java toolchain 21。modid 永久不可改。
- 参考版本：ModDevGradle `2.0.142`；GeckoLib `software.bernie.geckolib:geckolib-neoforge-1.21.1:4.9.3`（mods.toml 里声明 `versionRange="[4.7,)"`、`type="required"`、`side="BOTH"`）。
- 命名空间只注册 `astrum:`；**不覆盖**原版任何 `data/` json；通用物品用 `c:` 标签。
- **全项目禁用 Mixin**（指南 §9.3 / 规格 §7）。
- `api.v1` 不得 import `impl`；`impl` 可以 import `api`。api 签名只允许 `ResourceLocation` / `ResourceKey` / 基本类型 / 自己的 record，**禁止** `Level`/`Entity`/`ServerPlayer`/`ItemStack` 出现在 api 签名里。
- `net.minecraft.client.*` 只出现在 `impl/client/`。
- 禁止 `isDedicatedServer()` 分支；禁止 `level.getDataStorage()`；禁止 tick 轮询判过期。
- 配置值**使用处懒读取**，不得静态缓存；系数必须 clamp（`base ∈ [0.1, 1.0]`）。
- NeoForge 依赖必须走代理 `127.0.0.1:7890`，写进 `gradle.properties` 的 `systemProp.*`。
- 任何「我猜的 NeoForge API 细节」都必须先读反编译源码再写代码（Task 1 Step 8 给出定位方法）。

## Review Focus

规格是愿景文档，下面 5 类输入它没逐一列举，但都是真实玩家会撞上的，各自由指定任务钉住：

1. **datapack 里坏 JSON / 未知枚举 / 缺字段**：期望只跳过该角色 + 一条 WARN，游戏照常加载（Task 3、4）。
2. **配置被填成 0 / 负数 / 2.0**：期望 clamp 后仍可玩，不出现"效果全废"或数值爆炸（Task 5、7）。
3. **一次攻击里 5 个角色的附加伤害同时触发**：期望合并成单次 `hurt()`、不因无敌帧丢伤害、不递归（Task 9、10）。
4. **LAN 加入方（非 OP、无计分板队伍、另一个客户端）**：期望 UB 槽与队伍数值一致、area_charge 对合作玩家真的生效（Task 8、11、16）。
5. **演出期间区块卸载 / 传送 / 卡顿 / 多人同时放**：期望实体必定自毁、不残留、超限只结算伤害（Task 13）。

---

### Task 1: M0 工程骨架，`runClient` 能进游戏

**Files:**
- Create: `settings.gradle`、`build.gradle`、`gradle.properties`、`gradle/wrapper/gradle-wrapper.properties`、`gradlew`、`gradlew.bat`、`.gitignore`
- Create: `src/main/templates/META-INF/neoforge.mods.toml`
- Create: `src/main/java/com/astrum/pcrd/Astrum.java`
- Create: `src/main/resources/pack.mcmeta`

**Interfaces:**
- Produces: `Astrum.MOD_ID = "astrum"`、`Astrum.LOGGER`（`org.slf4j.Logger`）、`Astrum` 构造函数签名 `Astrum(IEventBus modBus, ModContainer container)`。

- [ ] **Step 1: 生成工程骨架**

从官方模板复制结构与脚本（**不要手写脚手架**），只改身份。等价内容：

`settings.gradle`：
```groovy
pluginManagement { repositories { gradlePluginPortal() } }
plugins { id 'org.gradle.toolchains.foojay-resolver-convention' version '0.8.0' }
rootProject.name = 'PrincessConnet'
```

`gradle.properties`（除版本与身份外，其余行照抄下面）：
```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
systemProp.http.nonProxyHosts=localhost|127.0.0.1

minecraft_version=1.21.1
minecraft_version_range=[1.21.1,1.21.2)
neo_version=21.1.265
loader_version_range=[1,)

mod_id=astrum
mod_name=Princess Connect: Re:Dive
mod_license=MIT
mod_version=0.1.0
mod_group_id=com.astrum.pcrd
mod_authors=astrum
```

`build.gradle`：照抄 `NeoForgeMDKs/MDK-1.21-ModDevGradle` 的 main 分支内容，但删掉 `parchment { ... }` 块与 `parchment_*` 属性（减少一条外部依赖），并保留：`id 'net.neoforged.moddev' version '2.0.142'`、`java.toolchain.languageVersion = JavaLanguageVersion.of(21)`、`mods { "${mod_id}" { sourceSet(sourceSets.main) } }`、`generateModMetadata` 任务与 `sourceSets.main.resources.srcDir generateModMetadata`、`neoForge.runs` 的 client/server/data 三个配置、`tasks.withType(JavaCompile) { options.encoding = 'UTF-8' }`。

`gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 用本机已缓存的 `https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip`（`~/.gradle/wrapper/dists/gradle-9.6.1-bin` 已存在，命中缓存零下载）。

`src/main/templates/META-INF/neoforge.mods.toml`：
```toml
modLoader = "javafml"
loaderVersion = "${loader_version_range}"
license = "${mod_license}"

[[mods]]
modId = "${mod_id}"
version = "${mod_version}"
displayName = "${mod_name}"
description = '''公主连结主题的角色卡驱动战斗模组。'''

[[dependencies.${mod_id}]]
modId = "neoforge"
type = "required"
versionRange = "[21.1.265,)"
ordering = "NONE"
side = "BOTH"

[[dependencies.${mod_id}]]
modId = "minecraft"
type = "required"
versionRange = "${minecraft_version_range}"
ordering = "NONE"
side = "BOTH"

[features.${mod_id}]
javaVersion = "[21,)"
```

- [ ] **Step 2: 写最小主类与 `pack.mcmeta`**

`src/main/java/com/astrum/pcrd/Astrum.java`：`@Mod(Astrum.MOD_ID)` 的类，两个常量（`MOD_ID`、`LOGGER`），构造器只打一行 `LOGGER.info("astrum loaded")`。

`src/main/resources/pack.mcmeta`：`{"pack": {"description": "astrum resources", "pack_format": 34}}`（1.21.1 的 `pack_format` 是 34；若模板自带该文件，以模板为准）。

- [ ] **Step 3: 验证能编译**

Run: `.\gradlew.bat build --stacktrace`
Expected: `BUILD SUCCESSFUL`，产出 `build/libs/astrum-0.1.0.jar`。首次会下载 NeoForge/NeoForm 并反编译，10–60 分钟属正常。

若失败于 `Connection timed out` / `Downloading Minecraft`：先确认 `127.0.0.1:7890` 端口开着（规格 §2.2：**没开要先问用户**），然后在同一条命令前加环境变量 `$env:JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890'`（NFRT 是 Gradle 另起的子 JVM，不继承 daemon 的 `systemProp`，这个变量进程树全体继承）。

- [ ] **Step 4: 验证能进游戏**

Run: `.\gradlew.bat runClient`
Expected: 游戏窗口出现，标题界面正常；关闭窗口后检查 `runs/client/logs/latest.log` 里有 `astrum loaded`，且 `Mod List` 里出现 "Princess Connect: Re:Dive"。

- [ ] **Step 5: 初始化 git 并提交**

`.gitignore` 至少含：`build/`、`runs/`、`.gradle/`、`run-data/`、`*.iml`、`.idea/`、`bin/`、`.vscode/`、`.probe/`。
```bash
cd /k/AI/Minecraft/PrincessConnet
git init -b main
git add -A && git commit -m "chore(M0): scaffold NeoForge 1.21.1 project as astrum"
```

- [ ] **Step 6: 记下 API 真相来源（后续所有任务都靠它）**

反编译产物在 `build/moddev/artifacts/`（`*-sources.jar` 或 `*-sources/`）；MDG 缓存也可在 `~/.gradle/caches/` 下找 `neoforge-21.1.265-*sources*.jar`。后续任务凡涉及具体 API 形态（事件类名、`AttachmentType` 构建器、`PayloadRegistrar` 注册方式），**先在这个源码里 grep 确认签名再落笔**，不要凭记忆写。

---

### Task 2: 单元测试底座

**Files:**
- Modify: `build.gradle`（加 JUnit 依赖、`test { useJUnitPlatform() }`、`neoForge { unitTest { ... } }`）
- Create: `src/test/java/com/astrum/pcrd/AstrumTest.java`
- Create: `src/main/java/com/astrum/pcrd/impl/InternalIds.java`

**Interfaces:**
- Produces: `InternalIds.id(String path) -> ResourceLocation`（`astrum:` 命名空间）；后续所有任务用它造 id。

- [ ] **Step 1: 写失败测试**

```java
package com.astrum.pcrd;

import com.astrum.pcrd.impl.InternalIds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AstrumTest {
    @Test
    void modIdIsAstrum() {
        assertEquals("astrum", Astrum.MOD_ID);
    }

    @Test
    void internalIdsUseAstrumNamespace() {
        assertEquals("astrum:djeeta", InternalIds.id("djeeta").toString());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat test --tests "com.astrum.pcrd.*"`
Expected: 编译失败（`InternalIds` 不存在）。

- [ ] **Step 3: 加测试配置并实现 `InternalIds`**

`build.gradle` 追加：`testImplementation 'org.junit.jupiter:junit-jupiter:5.11.3'`、`testRuntimeOnly 'org.junit.platform:junit-platform-launcher'`；`test { useJUnitPlatform() }`；`neoForge { unitTest { enable(); testedMod = mods.getByName("astrum") } }`。若 `mods.getByName` 在该 Gradle/MDG 版本上不可用，改用 MDG 源码里 `ModModelContainer` 暴露的等价写法（读源码确认，别猜）。

`InternalIds`：`public final class InternalIds { public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(Astrum.MOD_ID, path); } }`。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat test --tests "com.astrum.pcrd.*"`
Expected: `BUILD SUCCESSFUL`，2 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "test: enable JUnit unit-test harness for astrum"
```

---

### Task 3: `api.v1` 词表与 Codec（M1 的数据层）

**Files:**
- Create: `src/main/java/com/astrum/pcrd/api/v1/Dimension.java`、`Trigger.java`、`EffectType.java`、`TriggerParams.java`、`EffectHook.java`、`UbDef.java`、`GrowthDef.java`、`AffectionDef.java`、`CharacterDef.java`、`CharacterProgress.java`
- Test: `src/test/java/com/astrum/pcrd/api/v1/CharacterDefCodecTest.java`

**Interfaces:**
- Produces（后续任务直接用这些名字，不得改名）：
  - `enum Dimension { OUTPUT, SURVIVAL, CHARGE, CONTROL, RESOURCE }`，`StringRepresentable`，JSON 值为小写原词（`output`…）。
  - `enum Trigger { MELEE_HIT, CRIT, PROJECTILE_HIT, KILL, LOW_HEALTH, COMBO_N, BLOCK, STACK_FULL }`
  - `enum EffectType { APPLY_EFFECT, EXTRA_DAMAGE, LIFESTEAL, GAIN_CHARGE, AREA_CHARGE, SPLASH, STACK, DETONATE }`
  - `record TriggerParams(int hits, Optional<ResourceLocation> stackId)`，常量 `TriggerParams.NONE = new TriggerParams(0, Optional.empty())`，codec 用 `optionalFieldOf("hits", 0)` 与 `optionalFieldOf("stackId")`。
  - `sealed interface EffectHook permits EffectHook.ApplyEffect, EffectHook.ExtraDamage, EffectHook.Lifesteal, EffectHook.GainCharge, EffectHook.AreaCharge, EffectHook.Splash, EffectHook.Stack, EffectHook.Detonate`；每个 record 的前三个组件都是 `(Trigger trigger, TriggerParams triggerParams, double chance)`，之后是本效果自己的参数；接口方法 `Trigger trigger()`、`TriggerParams triggerParams()`、`double chance()`、`EffectType type()`；`Codec<EffectHook> CODEC` 用 `EffectType.CODEC.dispatch("effect", EffectHook::type, t -> 对应的 hookCodec)`。
    - `ApplyEffect(..., ResourceLocation effect, int duration, int amplifier)`
    - `ExtraDamage(..., double amount)`
    - `Lifesteal(..., double fraction)`
    - `GainCharge(..., double amount)`
    - `AreaCharge(..., double radius, double amount, boolean includeSelf)`
    - `Splash(..., double radius, double damage)`
    - `Stack(..., ResourceLocation stackId, int max)`
    - `Detonate(..., double damagePerStack, double radius)`
  - `record UbTier(String animation, int hits, double damage, OptionalDouble radius, OptionalDouble selfAtkBuff, int buffTicks, OptionalDouble teamChargeBonus, Optional<String> extra)`
  - `record UbDef(UbTier base, UbTier enhanced)`
  - `record GrowthDef(int expBase, double expPerLevel, int maxLevel)`
  - `record HookModifier(int hookIndex, double mult)`
  - `record AffectionDef(int tier2Threshold, int tier3Threshold, List<HookModifier> tier2HookModifiers)`
  - `record CharacterDef(int schemaVersion, String displayName, Dimension dimension, Optional<ResourceLocation> portrait, Optional<ResourceLocation> model, List<EffectHook> effects, UbDef ub, GrowthDef growth, AffectionDef affection)`
  - `record CharacterProgress(int level, int exp, int affection)` + `Codec<CharacterProgress> CODEC`

- [ ] **Step 1: 写失败测试（用规格 §6.4 的真实 JSON）**

```java
package com.astrum.pcrd.api.v1;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class CharacterDefCodecTest {
    static final String DJEETA = """
        {"schemaVersion":1,"displayName":"姬塔","dimension":"charge",
         "effects":[
           {"trigger":"melee_hit","effect":"gain_charge","effectParams":{"amount":2.0}},
           {"trigger":"combo_n","triggerParams":{"hits":3},"effect":"splash","effectParams":{"radius":3.0,"damage":4.0}},
           {"trigger":"combo_n","triggerParams":{"hits":3},"effect":"area_charge","effectParams":{"radius":8.0,"amount":3.0,"includeSelf":true}}],
         "ub":{"base":{"animation":"tempest_blade","hits":1,"damage":20.0,"selfAtkBuff":1.3,"buffTicks":360},
               "enhanced":{"animation":"seiga_itten","hits":4,"damage":20.0,"teamChargeBonus":0.2}},
         "growth":{"expBase":100,"expPerLevel":1.15,"maxLevel":30},
         "affection":{"tier2Threshold":3,"tier3Threshold":8,"tier2HookModifiers":[{"hookIndex":1,"mult":1.5}]}}""";

    @Test
    void decodesDjeetaFromSpec() {
        var json = JsonParser.parseString(DJEETA);
        var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(1, def.schemaVersion());
        assertEquals(Dimension.CHARGE, def.dimension());
        assertEquals(3, def.effects().size());
        assertInstanceOf(EffectHook.GainCharge.class, def.effects().get(0));
        assertEquals(2.0, ((EffectHook.GainCharge) def.effects().get(0)).amount());
        assertEquals(3, def.effects().get(1).triggerParams().hits());
        assertEquals("tempest_blade", def.ub().base().animation());
        assertEquals(4, def.ub().enhanced().hits());
        assertEquals(1, def.affection().tier2HookModifiers().size());
    }

    @Test
    void roundTripsAllEightEffectTypes() {
        List<String> hooks = List.of(
            "{\"trigger\":\"melee_hit\",\"effect\":\"apply_effect\",\"effectParams\":{\"effect\":\"minecraft:poison\",\"duration\":100,\"amplifier\":0}}",
            "{\"trigger\":\"crit\",\"effect\":\"extra_damage\",\"effectParams\":{\"amount\":3.0}}",
            "{\"trigger\":\"kill\",\"effect\":\"lifesteal\",\"effectParams\":{\"fraction\":0.2}}",
            "{\"trigger\":\"melee_hit\",\"effect\":\"gain_charge\",\"effectParams\":{\"amount\":2.0}}",
            "{\"trigger\":\"combo_n\",\"triggerParams\":{\"hits\":3},\"effect\":\"area_charge\",\"effectParams\":{\"radius\":8.0,\"amount\":3.0,\"includeSelf\":true}}",
            "{\"trigger\":\"combo_n\",\"triggerParams\":{\"hits\":3},\"effect\":\"splash\",\"effectParams\":{\"radius\":3.0,\"damage\":4.0}}",
            "{\"trigger\":\"melee_hit\",\"effect\":\"stack\",\"effectParams\":{\"stackId\":\"astrum:poison\",\"max\":5}}",
            "{\"trigger\":\"stack_full\",\"triggerParams\":{\"stackId\":\"astrum:poison\"},\"effect\":\"detonate\",\"effectParams\":{\"damagePerStack\":2.0,\"radius\":3.0}}");
        for (String h : hooks) {
            var json = JsonParser.parseString(h);
            var hook = EffectHook.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            var reEncoded = EffectHook.CODEC.encodeStart(JsonOps.INSTANCE, hook).getOrThrow();
            assertEquals(Optional.of(hook), EffectHook.CODEC.parse(JsonOps.INSTANCE, reEncoded).result(), h);
        }
    }

    @Test
    void unknownEnumValueIsAnErrorNotACrash() {
        var bad = JsonParser.parseString(DJEETA.replace("\"charge\"", "\"nonexistent\""));
        assertTrue(CharacterDef.CODEC.parse(JsonOps.INSTANCE, bad).error().isPresent());
    }

    @Test
    void missingRequiredFieldIsAnError() {
        var bad = JsonParser.parseString(DJEETA.replace("\"expBase\":100,", ""));
        assertTrue(CharacterDef.CODEC.parse(JsonOps.INSTANCE, bad).error().isPresent());
    }

    @Test
    void characterProgressRoundTrips() {
        var p = new CharacterProgress(5, 42, 2);
        var enc = CharacterProgress.CODEC.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        assertEquals(Optional.of(p), CharacterProgress.CODEC.parse(JsonOps.INSTANCE, enc).result());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat test --tests "com.astrum.pcrd.api.v1.*"`
Expected: 编译失败（类型未定义）。

- [ ] **Step 3: 实现这 10 个类型**

要点（不是逐行照抄）：枚举用 `Codec.STRING.xmap` 或 `StringRepresentable.fromEnum`；每个 hook record 一个 `RecordCodecBuilder`，编号从 `"trigger"`、`"triggerParams"`、`"chance"` 开始，第四项起按 `effectParams` 里的键名用 `fieldOf`（`apply_effect` 用 `effect`/`duration`/`amplifier`，`stack` 的键名是 `stackId`/`max` 等，见规格 §4.2 注释与 §6.4）；`CharacterDef` 的图省字段（`portrait`/`model`）用 `optionalFieldOf`。`EffectHook.CODEC` 的外层 dispatch 键是同级 `"effect"`。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat test --tests "com.astrum.pcrd.api.v1.*"`
Expected: 5 个测试全绿。**Review Focus #1**（坏 JSON/未知枚举只报错不崩）由此钉住：`Codec.parse` 返回 error，由 Task 4 记录日志后跳过。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(api): typed character definition codec vocabulary"
```

---

### Task 4: datapack registry + 门面 + 姬塔 JSON（M1 验收）

**Files:**
- Create: `src/main/java/com/astrum/pcrd/api/v1/RegistryKeys.java`、`AstrumApi.java`、`src/main/java/com/astrum/pcrd/api/v1/event/UbChargeEvent.java`（其余两个事件在 Task 8/14 补）
- Create: `src/main/java/com/astrum/pcrd/impl/registry/CharacterRegistry.java`
- Create: `src/main/resources/data/astrum/character/djeeta.json`
- Modify: `Astrum.java`（`register(modBus)`）
- Test: `src/test/java/com/astrum/pcrd/impl/registry/DjeetaResourceTest.java`

**Interfaces:**
- Produces: `RegistryKeys.CHARACTERS`（`ResourceKey<Registry<CharacterDef>>`，id `astrum:character`）；`AstrumApi.characters() -> Optional<Registry<CharacterDef>>`、`@ApiStatus.Internal static void bind(Registry<CharacterDef>)`；`CharacterRegistry` 负责 mod 总线上的 `DataPackRegistryEvent.NewRegistry` 注册，以及在服务端启动/重载后把注册表交给 `AstrumApi.bind`。
- 本任务只建 `UbChargeEvent` 一个事件；`UbActivatedEvent` 在 Task 12、`CharacterEquippedEvent` 在 Task 14 建（各自的自举锚点）。

- [ ] **Step 1: 写失败测试（验证随 jar 发布的 JSON 与 codec 一致）**

```java
class DjeetaResourceTest {
    @Test
    void packagedDjeetaJsonMatchesCodec() throws Exception {
        try (var in = DjeetaResourceTest.class.getResourceAsStream("/data/astrum/character/djeeta.json")) {
            assertNotNull(in, "djeeta.json 必须在 resources 里（自举验证：自家角色走公开路径加载）");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(Dimension.CHARGE, def.dimension());
            assertEquals(3, def.effects().size());
            assertEquals(3, def.affection().tier2Threshold());
            assertEquals(8, def.affection().tier3Threshold());
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat test --tests "*DjeetaResourceTest*"`
Expected: 失败（资源不存在 / `assertNotNull`）。

- [ ] **Step 3: 写 `djeeta.json`（照规格 §6.4 原文，补 `id` 之外的键全部保留）+ registry 引导 + 门面**

`DataPackRegistryEvent.NewRegistry` 里 `event.dataPackRegistry(RegistryKeys.CHARACTERS, CharacterDef.CODEC, CharacterDef.CODEC)`；`ServerStartedEvent`/`ReloadListener` 里 `AstrumApi.bind(registry)`。引导代码里对每个 id 单独 `try/catch`：解析失败 **WARN + 跳过该条**，绝不外抛。

- [ ] **Step 4: 跑测试 + 进游戏验证 `/reload`**

Run: `.\gradlew.bat test --tests "*DjeetaResourceTest*"` → 通过。
Run: `.\gradlew.bat runClient` → 进世界后 `/reload`，日志出现 `astrum: character astrum:djeeta loaded`（WARN 行数为 0）；再把 `djeeta.json` 临时改坏一个枚举值重进，日志出现 1 条 WARN 且**游戏不崩**，改回。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(M1): datapack character registry, api facade, djeeta bootstrap"
```

---

### Task 5: 配置（服务端/客户端）+ 懒读取 + clamp

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/config/ServerConfig.java`、`ClientConfig.java`、`Balance.java`
- Modify: `Astrum.java`（`container.registerConfig(...)`）
- Test: `src/test/java/com/astrum/pcrd/impl/config/BalanceTest.java`

**Interfaces:**
- Produces: `Balance`（纯函数、不碰配置对象）：`static double diminishingMultiplier(int sameDimensionRank, double base, boolean enabled)` → `enabled ? clamp(pow(clampBase(base), rank), 0.1, 1.0) : 1.0`；`static double clampBase(double raw)` → `Math.clamp(raw, 0.1, 1.0)`。`ServerConfig.BALANCE.diminishingBase` 等字段由使用处每次读取。

- [ ] **Step 1: 写失败测试**

```java
class BalanceTest {
    @Test void firstSameDimensionGetsFullMultiplier() { assertEquals(1.0, Balance.diminishingMultiplier(0, 0.7, true)); }
    @Test void successiveRanksFollowBasePowers() {
        assertEquals(0.7, Balance.diminishingMultiplier(1, 0.7, true), 1e-9);
        assertEquals(0.49, Balance.diminishingMultiplier(2, 0.7, true), 1e-9);
    }
    @Test void disabledMeansOne() { assertEquals(1.0, Balance.diminishingMultiplier(3, 0.7, false)); }
    @Test void floorIsOneTenth() { assertEquals(0.1, Balance.diminishingMultiplier(40, 0.7, true), 1e-9); }
    @Test void hostileConfigValuesAreClamped() {
        assertEquals(0.1, Balance.clampBase(0.0));     // 填 0 不至于效果全废
        assertEquals(1.0, Balance.clampBase(2.0));     // 填 2.0 不至于爆炸
        assertEquals(0.1, Balance.clampBase(-3.0));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\gradlew.bat test --tests "*BalanceTest*"` → 编译失败。

- [ ] **Step 3: 实现 `Balance` + 两个 `ModConfigSpec`**

服务端 `[balance] enableDiminishingReturns / diminishingBase`、`[ub] ubGlobalCooldownTicks=100 / ubChargePerDamage=1.0 / ubChargePerDamageTaken=0.33 / ubChargePerKill=20.0 / ubChargePerSecond=0.2`、`[wheel] scrollSwitchPolicy="ALLOW"`、`[multiplayer] allyMode="ALL_PLAYERS" / areaChargePairCooldownTicks=20 / areaChargeReceiveCapPerSecond=6.0 / maxActiveShowcaseEntities=8`。客户端 `[wheel] enableScrollSwitching=false / wheelScale=0.40 / wheelOpacity=0.30`、`[camera] ubCameraPush=false`（键名与默认值逐字照规格 §8.2/§8.3）。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat test --tests "*BalanceTest*"` → 全绿。**Review Focus #2** 下半段由此钉住。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat(config): server and client config with clamped balance kernels"
```

---

### Task 6: 三个 Attachment + 存档 codec

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/attach/Attachments.java`、`UbState.java`、`Roster.java`、`Party.java`
- Modify: `Astrum.java`
- Test: `src/test/java/com/astrum/pcrd/impl/attach/RosterCodecTest.java`

**Interfaces:**
- Produces: `Attachments.ROSTER/PARTY/UB_STATE`（`DeferredRegister<AttachmentType<?>>`，`serialize` 带 codec；`STACK` 在 Task 10）；`record UbState(float charge, int selectedSlot, long cdEndTick)`；`record Roster(Map<ResourceLocation, CharacterProgress> entries, int dataVersion)`（`CURRENT_DATA_VERSION = 1`）；`record Party(List<Optional<ResourceLocation>> slots)`（长度恒为 5，`Party.empty()`）；`Roster.addOrIgnore(...)`。

- [ ] **Step 1: 写失败测试**

```java
class RosterCodecTest {
    @Test void rosterRoundTrips() {
        var r = new Roster(Map.of(InternalIds.id("djeeta"), new CharacterProgress(7, 12, 2)), 1);
        var enc = Roster.CODEC.encodeStart(JsonOps.INSTANCE, r).getOrThrow();
        assertEquals(Optional.of(r), Roster.CODEC.parse(JsonOps.INSTANCE, enc).result());
    }
    @Test void partyAlwaysHasFiveSlots() {
        var p = Party.of(List.of(InternalIds.id("djeeta")));
        assertEquals(5, p.slots().size());
        assertEquals(Optional.of(InternalIds.id("djeeta")), p.slots().get(0));
        assertTrue(p.slots().subList(1, 5).stream().allMatch(Optional::isEmpty));
    }
    @Test void sameCharacterCannotOccupyTwoSlots() {
        var d = InternalIds.id("djeeta");
        var p = Party.of(List.of(d, d));
        assertEquals(1, p.slots().stream().filter(s -> s.equals(Optional.of(d))).count());
    }
    @Test void unknownDataVersionIsRejectedNotCrashed() {
        var json = JsonParser.parseString("{\"dataVersion\":99,\"entries\":{}}");
        assertTrue(Roster.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
    }
}
```

- [ ] **Step 2: 跑测试确认失败** → `.\gradlew.bat test --tests "*RosterCodecTest*"`，编译失败。

- [ ] **Step 3: 实现三个 codec + 三个 `AttachmentType`**

`Roster` 的 codec 先用 `Codec.INT.fieldOf("dataVersion")` 校验，再 `Codec.unboundedMap(ResourceLocation.CODEC, CharacterProgress.CODEC)`；`Party` 用 `Codec.either(...)` 列表并 `Party.of` 补齐 5 槽/去重。注册用 `AttachmentType.builder(...)`（**先读 Task 1 Step 6 的源码确认 1.21.1 的 builder/serialize 方法名**）。

- [ ] **Step 4: 跑测试确认通过** → 全绿；再 `runClient` 进世界、`/save-all`、退出重进，日志确认附件被序列化（`playerdata` 里出现 `astrum` 键）。

- [ ] **Step 5: 提交** → `git commit -m "feat(attach): roster/party/ubstate attachments with versioned codecs"`

---

### Task 7: 递减倍率接线（内核 → 战斗）

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/combat/PartyMultipliers.java`
- Test: `src/test/java/com/astrum/pcrd/impl/combat/PartyMultipliersTest.java`

**Interfaces:**
- Produces: `PartyMultipliers.forSlot(List<Optional<ResourceLocation>> party, Map<ResourceLocation, Dimension> dimensions, int slot, double base, boolean enabled) -> double`；`PartyMultipliers.all(List<...> same args) -> double[]`（长度 5）。

- [ ] **Step 1: 写失败测试（规格 §5.2 的槽位示例）**

```java
class PartyMultipliersTest {
    static final ResourceLocation A = InternalIds.id("a"), B = InternalIds.id("b"),
            C = InternalIds.id("c"), D = InternalIds.id("d"), E = InternalIds.id("e");
    static final Map<ResourceLocation, Dimension> DIMS = Map.of(
        A, Dimension.OUTPUT, B, Dimension.SURVIVAL, C, Dimension.OUTPUT,
        D, Dimension.CHARGE,  E, Dimension.OUTPUT);
    static final List<Optional<ResourceLocation>> PARTY =
        List.of(Optional.of(A), Optional.of(B), Optional.of(C), Optional.of(D), Optional.of(E));

    @Test void matchesSpecExample() {
        var m = PartyMultipliers.all(PARTY, DIMS, 0.7, true);
        assertEquals(1.00, m[0], 1e-9);   // output 第 1 名
        assertEquals(1.00, m[1], 1e-9);   // survival 不同维度
        assertEquals(0.70, m[2], 1e-9);   // output 第 2 名
        assertEquals(1.00, m[3], 1e-9);   // charge 不同维度
        assertEquals(0.49, m[4], 1e-9);   // output 第 3 名
    }
    @Test void emptySlotsAreSkippedNotCounted() {
        var party = List.of(Optional.<ResourceLocation>empty(), Optional.of(A), Optional.<ResourceLocation>empty(),
                            Optional.<ResourceLocation>empty(), Optional.of(E));
        var m = PartyMultipliers.all(party, DIMS, 0.7, true);
        assertEquals(1.0, m[1], 1e-9);
        assertEquals(0.7, m[4], 1e-9);
        assertEquals(1.0, m[0], 1e-9);
    }
    @Test void unknownDimensionInDatapackFallsBackToFullMultiplier() {
        var m = PartyMultipliers.all(PARTY, Map.of(), 0.7, true);
        for (double v : m) assertEquals(1.0, v, 1e-9);
    }
}
```

- [ ] **Step 2: 跑测试确认失败** → 编译失败。

- [ ] **Step 3: 实现**：按槽位顺序扫描，维护 `Map<Dimension,Integer> rankSeen`，每个非空槽取 `Balance.diminishingMultiplier(rank, base, enabled)` 后 `rank++`。**只产倍数，不碰效果机制**。

- [ ] **Step 4: 跑测试确认通过**。

- [ ] **Step 5: 提交** → `git commit -m "feat(combat): per-dimension diminishing multipliers over party order"`

---

### Task 8: UB 充能内核 + 同步 payload（M2 一半）

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/ub/ChargeKernel.java`、`src/main/java/com/astrum/pcrd/impl/network/AstrumNetwork.java`、`ChargeSyncPayload.java`、`src/main/java/com/astrum/pcrd/api/v1/event/UbChargeEvent.java`
- Modify: `Astrum.java`（`RegisterPayloadHandlersEvent`）
- Test: `src/test/java/com/astrum/pcrd/impl/ub/ChargeKernelTest.java`

**Interfaces:**
- Produces: `ChargeKernel.addDealt(double charge, double dealt, double perDamage) -> double`、`addTaken(...)`、`addKill(double charge, double perKill)`、`naturalRegen(double perSecond) -> double`（每 tick 1/20）、`clamp(double) -> double`（0..100）、`isFull(double)`；`UbChargeEvent extends Event implements ICancellableEvent`，组件 `ResourceLocation characterId`、`double amount`（可被监听者改写/取消）；`ChargeSyncPayload(float charge, long cdEndTick)` 在两端注册（`payloadType(...).codec(...).handler(CLIENT, ...)` + `playToClient`）。

- [ ] **Step 1: 写失败测试**

```java
class ChargeKernelTest {
    @Test void damageGrantsCharge() { assertEquals(2.5, ChargeKernel.addDealt(0.5, 2.0, 1.0), 1e-9); }
    @Test void damageTakenUsesLesserCoefficient() { assertEquals(0.99, ChargeKernel.addTaken(0.33, 2.0, 0.33), 1e-9); }
    @Test void killGrantsFlat() { assertEquals(20.0, ChargeKernel.addKill(0.0, 20.0), 1e-9); }
    @Test void naturalRegenIsPerTick() { assertEquals(0.01, ChargeKernel.naturalRegen(0.2), 1e-9); }
    @Test void chargeNeverExceedsHundred() { assertEquals(100.0, ChargeKernel.clamp(140.0), 1e-9); assertEquals(0.0, ChargeKernel.clamp(-5.0), 1e-9); }
    @Test void fullOnlyAtHundred() { assertTrue(ChargeKernel.isFull(100.0)); assertFalse(ChargeKernel.isFull(99.99)); }
}
```

- [ ] **Step 2: 跑测试确认失败** → 编译失败。

- [ ] **Step 3: 实现 `ChargeKernel` + payload + 事件**

服务端每个 `ServerPlayer` 每 5 tick（`player.tickCount % 5 == 0`）比对 `UB_STATE` 变化再发 `ChargeSyncPayload`（**不要每 tick 发包**）；收到伤害/造成伤害只在 `ServerLevel` 侧改状态。所有充能入口先 `NeoForge.EVENT_BUS.post(new UbChargeEvent(...))`，被取消则丢弃。

- [ ] **Step 4: 跑测试确认通过**

Run: `.\gradlew.bat test --tests "*ChargeKernelTest*"` → 全绿。此阶段 HUD 还没做（Task 12），用 `LOGGER.debug` 打印服务端充能值确认随攻击上涨即可。

- [ ] **Step 5: 提交** → `git commit -m "feat(ub): charge accumulation kernel and charge sync payload"`

---

### Task 9: 伤害事件入口 —— 先实测可写性，再接线

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/combat/CombatEvents.java`、`DamageBatch.java`、`OwnDamage.java`
- Create: `src/main/resources/data/astrum/damage_type/hook_damage.json`、`src/main/resources/data/astrum/tags/damage_type/is_projectile_immune.json`（仅当入口需要区分攻击类型时）
- Test: `src/test/java/com/astrum/pcrd/impl/combat/DamageBatchTest.java`、`OwnDamageTest.java`

**Interfaces:**
- Produces: `DamageBatch`（`add(ResourceLocation type, float amount)`、`entries() -> List<Entry>`，`record Entry(ResourceLocation type, float amount)`，同 type 合并、保持插入序）；`OwnDamage.isOurs(ResourceLocation damageTypeId) -> boolean`（命名空间等于 `astrum` 即为自家伤害源）。

- [ ] **Step 1: 实测事件可写性（规格 §5.1 ⚠️ + §13 待定 #4）**

在 `CombatEvents` 里**先用日志版**订阅 `LivingIncomingDamageEvent`、`LivingDamageEvent.Pre`、`LivingDamageEvent.Post`，各自打印 `getAmount()`、`entity`、`source`，跑 `runClient` 砍一只僵尸，把三行日志记到本任务注释里，确认：哪个能 `setAmount`、哪个能 `setCanceled`、顺序如何。

- [ ] **Step 2: 写失败测试**

```java
class OwnDamageTest {
    @Test void ownNamespaceIsOurs() { assertTrue(OwnDamage.isOurs(InternalIds.id("hook_damage"))); }
    @Test void vanillaDamageIsNotOurs() { assertFalse(OwnDamage.isOurs(ResourceLocation.parse("minecraft:player_attack"))); }
}
class DamageBatchTest {
    @Test void mergesSameDamageTypeIntoSingleEntry() {
        var b = new DamageBatch();
        b.add(InternalIds.id("hook_damage"), 2.0f);
        b.add(InternalIds.id("hook_damage"), 3.5f);
        assertEquals(1, b.entries().size());
        assertEquals(5.5f, b.entries().get(0).amount(), 1e-6);
    }
    @Test void keepsDistinctTypesSeparateAndOrdered() {
        var b = new DamageBatch();
        b.add(InternalIds.id("hook_damage"), 1f);
        b.add(ResourceLocation.parse("minecraft:magic"), 2f);
        assertEquals(2, b.entries().size());
        assertEquals(InternalIds.id("hook_damage"), b.entries().get(0).type());
    }
    @Test void emptyBatchHasNoEntries() { assertTrue(new DamageBatch().entries().isEmpty()); }
}
```

- [ ] **Step 3: 跑测试确认失败** → 编译失败。

- [ ] **Step 4: 实现 + 接线**

入口第一行短路：把 `DamageSource` 的伤害类型 id 取出来（`typeHolder().unwrapKey().map(ResourceKey::location)`，**方法名按 Task 1 Step 6 在源码里确认**），`OwnDamage.isOurs(id)` 为真就 `return`。命中时创建一个 `DamageBatch`，遍历 `ROSTER`×`PARTY` 的 hook，每条 `extra_damage` 往 batch 里塞（乘上 Task 7 的倍率与 Task 15 的等级/好感度系数），最后 `flush` 成**单次** `hurt()`。`hurt()` 用独立 `DamageType`（`data/astrum/damage_type/hook_damage.json`）。

- [ ] **Step 5: 跑测试确认通过** + `runClient`：临时在 `data/astrum/character/` 里加 4 个测试角色（复制 djeeta.json 改 id 与 dimension，正好顺带验证自举路径），组 5 人队打一只怪，日志确认同一 tick 只有一次 batch flush、单次 `hurt`、无递归；怪物掉血不因无敌帧丢伤害。测完删掉这 4 个临时 json。

- [ ] **Step 6: 提交** → `git commit -m "feat(combat): damage entry with self-source short-circuit and same-hit merge"`

**Review Focus #3** 归本任务：一次攻击 5 个角色同时附加伤害 → 合并为单次 `hurt`、不递归（`DamageBatchTest` + 手动日志）。

---

### Task 10: 八个效果执行器 + 叠层惰性过期

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/combat/EffectExecutor.java`、`StackRuntime.java`、`StackState.java`、`StackKernel.java`
- Modify: `Attachments.java`（`STACK`，`serialize` 无 / 内存）
- Test: `src/test/java/com/astrum/pcrd/impl/combat/StackKernelTest.java`

**Interfaces:**
- Produces: `StackKernel`（纯）：`static int add(StackState state, long nowTick, int delta, int max)`、`static boolean isExpired(StackState state, long nowTick, int ttlTicks)`、`static float detonateDamage(int stacks, double damagePerStack, double multiplier)`；`record StackState(int stacks, long lastHitTick)`；`StackRuntime` 负责 `AttachmentType` 读写与 key 里带攻击者 UUID。

- [ ] **Step 1: 写失败测试**

```java
class StackKernelTest {
    @Test void stacksAccumulateUpToMax() {
        var s = new StackState(0, 100);
        assertEquals(3, StackKernel.add(s, 100, 3, 5));
        assertEquals(5, StackKernel.add(new StackState(4, 100), 101, 3, 5));
    }
    @Test void expiresLazilyAfterTtl() {
        var s = new StackState(2, 100);
        assertFalse(StackKernel.isExpired(s, 100 + 79, 80));
        assertTrue(StackKernel.isExpired(s, 100 + 80, 80));
    }
    @Test void detonateScalesWithStacksAndMultiplier() {
        assertEquals(6.0f, StackKernel.detonateDamage(3, 2.0, 1.0), 1e-6);
        assertEquals(4.2f, StackKernel.detonateDamage(3, 2.0, 0.7), 1e-6);
    }
    @Test void zeroStacksDetonatesForNothing() { assertEquals(0.0f, StackKernel.detonateDamage(0, 2.0, 1.0), 1e-6); }
}
```

- [ ] **Step 2: 跑测试确认失败**。

- [ ] **Step 3: 实现 8 个执行器 + `StackKernel`**

`apply_effect` 造 `MobEffectInstance`（`Holder<MobEffect>` 由 `ResourceLocation` 查注册表，查不到就 WARN 跳过）；`extra_damage` 走 Task 9 的 batch；`lifesteal` 给攻击者回血；`gain_charge`/`area_charge` 走 Task 8/11；`splash` 对半径内非友方实体再开一次 batch；`stack`/`detonate` 走 `StackKernel`。**递减倍率只乘到数值参数上**（伤害、回血、充能量、`duration`），不改变是否触发、`max` 层数、是否引爆。

- [ ] **Step 4: 跑测试确认通过** + `runClient`：堆满 5 层触发引爆，日志里层数、伤害、过期行为符合预期；挂机 10 分钟无 tick 轮询痕迹（`/debug` 或 `Spark` 都行，最低标准：代码里没有 `EntityTickEvent` 遍历实体）。

- [ ] **Step 5: 提交** → `git commit -m "feat(combat): eight effect executors and lazy stack expiry"`

---

### Task 11: area_charge 多人护栏

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/combat/AllySelector.java`、`AreaChargeBudget.java`、`src/main/java/com/astrum/pcrd/impl/combat/AllyMode.java`
- Test: `src/test/java/com/astrum/pcrd/impl/combat/AllySelectorTest.java`、`AreaChargeBudgetTest.java`

**Interfaces:**
- Produces: `enum AllyMode { ALL_PLAYERS, SAME_TEAM_ONLY }`（配置字符串解析，未知值回落 `ALL_PLAYERS`）；`AllySelector.select(List<Candidate>, UUID caster, AllyMode mode, double radius, boolean includeSelf) -> List<Candidate>`，`record Candidate(UUID id, boolean self, Optional<String> teamName, double distance)`；`AreaChargeBudget.grant(UUID caster, UUID receiver, double requested, long nowTick, long nowSecond) -> double`。

- [ ] **Step 1: 写失败测试**

```java
class AllySelectorTest {
    static final UUID ME = UUID.randomUUID(), OTHER = UUID.randomUUID(), FAR = UUID.randomUUID();
    static List<Candidate> pool() {
        return List.of(new Candidate(ME, true, Optional.empty(), 0.0),
                       new Candidate(OTHER, false, Optional.empty(), 4.0),
                       new Candidate(FAR, false, Optional.empty(), 20.0));
    }
    @Test void allPlayersModeReachesTeammatelessNeighbours() {
        var got = AllySelector.select(pool(), ME, AllyMode.ALL_PLAYERS, 8.0, true);
        assertEquals(2, got.size());   // 自己 + 邻居，不含 20 格外
        assertTrue(got.stream().anyMatch(c -> c.id().equals(OTHER)));  // isAlliedTo 在无队伍时会给 false，这里必须仍选中
    }
    @Test void includeSelfFalseExcludesCaster() {
        var got = AllySelector.select(pool(), ME, AllyMode.ALL_PLAYERS, 8.0, false);
        assertEquals(1, got.size());
        assertTrue(got.stream().noneMatch(Candidate::self));
    }
    @Test void sameTeamOnlyRequiresMatchingTeam() {
        var withTeams = List.of(new Candidate(ME, true, Optional.of("red"), 0.0),
                                new Candidate(OTHER, false, Optional.of("blue"), 4.0),
                                new Candidate(FAR, false, Optional.of("red"), 4.0));
        var got = AllySelector.select(withTeams, ME, AllyMode.SAME_TEAM_ONLY, 8.0, false);
        assertEquals(1, got.size());
        assertTrue(got.get(0).id().equals(FAR));
    }
    @Test void unknownModeStringFallsBackToAllPlayers() {
        assertEquals(AllyMode.ALL_PLAYERS, AllyMode.parse("NONSENSE"));
    }
}

class AreaChargeBudgetTest {
    @Test void pairCooldownBlocksRapidRefill() {
        var b = new AreaChargeBudget();
        UUID caster = UUID.randomUUID(), receiver = UUID.randomUUID();
        assertEquals(3.0, b.grant(caster, receiver, 3.0, 100, 5), 1e-9);
        assertEquals(0.0, b.grant(caster, receiver, 3.0, 105, 5), 1e-9);   // 同一对、CD 20 tick 内
        assertEquals(3.0, b.grant(caster, receiver, 3.0, 121, 5), 1e-9);   // 过了 CD，同一秒内仍受每秒上限约束
    }
    @Test void perSecondCapLimitsBurst() {
        var b = new AreaChargeBudget();
        UUID receiver = UUID.randomUUID();
        double total = 0;
        for (int i = 0; i < 10; i++) {
            UUID caster = UUID.randomUUID();                              // 每次不同施放者，绕开成对 CD
            total += b.grant(caster, receiver, 3.0, 100 + i, 5);
        }
        assertTrue(total <= 6.0 + 1e-9, "每秒接收上限必须封顶，实际 " + total);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**。

- [ ] **Step 3: 实现**：`AllySelector` 里 **不得** 使用 `isAlliedTo()` 作为判据（规格 §7.4）；`AreaChargeBudget` 内部两张表（`Map<PairKey, Long>` 与 `Map<UUID, double[]>` 的 `[second, granted]`），判过期靠"被调用时比较当前 tick/second"。

- [ ] **Step 4: 跑测试确认通过**。

- [ ] **Step 5: 提交** → `git commit -m "feat(combat): ally selection and area-charge budget guards"`

**Review Focus #4** 的判定逻辑部分归本任务（LAN 端到端在 Task 16）。

---

### Task 12: UB 释放流程 + 轮盘状态机 + HUD（M2 剩余）

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/ub/UbService.java`、`WheelInputMachine.java`、`src/main/java/com/astrum/pcrd/impl/network/UbCastPayload.java`、`PartySyncPayload.java`、`src/main/java/com/astrum/pcrd/impl/client/UbHud.java`、`WheelScreen.java`、`WheelInput.java`、`ClientEvents.java`
- Test: `src/test/java/com/astrum/pcrd/impl/ub/WheelInputMachineTest.java`

**Interfaces:**
- Produces: `WheelInputMachine`（纯）：`enum State { IDLE, PENDING, WHEEL }`、`enum Decision { NONE, OPEN_WHEEL, TAP_CAST, CLOSE, CAST_SLOT }`；`onPress(long nowMillis)`、`poll(long nowMillis)`、`onRelease(long nowMillis)`、`onDigit(int slot1to5)`、`state()`、`lastDecision()`、`castSlot() -> Optional<Integer>`、`capturesHotbarDigits() -> boolean`；常量 `HOLD_THRESHOLD_MILLIS = 200`。
- `UbService.tryCast(ServerPlayer player, int slot)`：校验槽合法/充能满/全局 CD（全部读 `ServerConfig` 懒读），失败时**给反馈**（音效 + 槽抖动 payload），成功时扣充能置 CD、`NeoForge.EVENT_BUS.post(new UbActivatedEvent(...))`、广播演出、触发 Task 13。
- 本任务新建 `api/v1/event/UbActivatedEvent`（`ResourceLocation characterId`、`int slot`、`boolean enhanced`）。

- [ ] **Step 1: 写失败测试**

```java
class WheelInputMachineTest {
    @Test void quickReleaseIsTapCast() {
        var m = new WheelInputMachine();
        m.onPress(1000);
        assertEquals(Decision.TAP_CAST, m.onRelease(1150));
        assertEquals(State.IDLE, m.state());
    }
    @Test void holdingPastThresholdOpensWheel() {
        var m = new WheelInputMachine();
        m.onPress(1000);
        assertEquals(Decision.NONE, m.poll(1199));
        assertEquals(Decision.OPEN_WHEEL, m.poll(1200));
        assertEquals(State.WHEEL, m.state());
    }
    @Test void releaseAfterWheelOpenedOnlyCloses() {
        var m = new WheelInputMachine();
        m.onPress(1000);
        m.poll(1300);
        assertEquals(Decision.CLOSE, m.onRelease(1400));
        assertEquals(State.IDLE, m.state());
    }
    @Test void digitInsideWheelCastsThatSlotAndCloses() {
        var m = new WheelInputMachine();
        m.onPress(1000);
        m.poll(1300);
        m.onDigit(3);
        assertEquals(Decision.CAST_SLOT, m.lastDecision());
        assertEquals(3, m.castSlot().orElseThrow());
        assertEquals(State.IDLE, m.state());
    }
    @Test void hotbarDigitsAreCapturedOnlyWhileWheelIsOpen() {
        var m = new WheelInputMachine();
        assertFalse(m.capturesHotbarDigits());
        m.onPress(1000); m.poll(1300);
        assertTrue(m.capturesHotbarDigits());
        m.onRelease(1400);
        assertFalse(m.capturesHotbarDigits());
    }
    @Test void strayReleaseWhileIdleDoesNothing() {
        assertEquals(Decision.NONE, new WheelInputMachine().onRelease(500));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**。

- [ ] **Step 3: 实现状态机 + 客户端表现 + 服务端校验**

客户端：默认键位 `V`（可改，**不用 `R`/中键**）；`WHEEL` 期间捕获鼠标隐藏光标、屏蔽 `1`–`5` 快捷栏；环半径 = 屏高 40%，环带 12%，环内完全留空，环带 alpha 0.30；3 阶角色用强化图标。服务端：`UbCastPayload` 收到后 `UbService.tryCast` 再校验，失败也回一个明确的反馈 payload（**禁止静默失败**）。

- [ ] **Step 4: 跑测试确认通过** + `runClient`：攒满 → 点按 `V` 放大招；长按 `V` 出轮盘、按 `3` 直放第 3 格、快捷栏没被切；CD 中点按 V 有音效与抖动。**再走进下界传送门换维度，确认 UB 充能没有归零**（M2 验收条款「换维度不归零」）。

- [ ] **Step 5: 提交** → `git commit -m "feat(ub): cast service, wheel state machine, hud and wheel ui"`

---

### Task 13: 演出实体 + GeckoLib（M3 第一段）

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/showcase/ShowcaseEntity.java`、`ShowcaseManager.java`、`src/main/java/com/astrum/pcrd/impl/client/ShowcaseRenderer.java`
- Create: `src/main/resources/assets/astrum/geo/djeeta.geo.json`、`animations/djeeta.animation.json`
- Modify: `build.gradle`（GeckoLib 依赖 + 仓库）、`src/main/templates/META-INF/neoforge.mods.toml`（GeckoLib 依赖块）
- Test: `src/test/java/com/astrum/pcrd/impl/showcase/ShowcaseLifetimeTest.java`

**Interfaces:**
- Produces: `ShowcaseManager.spawn(ServerLevel level, ServerPlayer caster, UbTier tier, int activeCount, int maxActive) -> Optional<ShowcaseEntity>`（超限返回 `Optional.empty()` 但调用方照常结算伤害）；`ShowcaseLifetime.shouldDespawn(int ageTicks, int maxLifetimeTicks) -> boolean`（纯函数）。

- [ ] **Step 1: 写失败测试**

```java
class ShowcaseLifetimeTest {
    @Test void despawnsAtMaxLifetime() {
        assertFalse(ShowcaseLifetime.shouldDespawn(119, 120));
        assertTrue(ShowcaseLifetime.shouldDespawn(120, 120));
    }
    @Test void despawnsWhenAnimationInterruptedLongEnough() {
        assertTrue(ShowcaseLifetime.shouldDespawn(600, 120));  // 卡顿/区块卸载后 age 会一直涨，必须自毁
    }
}
```

- [ ] **Step 2: 跑测试确认失败**。

- [ ] **Step 3: 加 GeckoLib 依赖并实现实体**

`build.gradle`：`repositories { maven { url = 'https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/' } }`；`implementation "software.bernie.geckolib:geckolib-neoforge-1.21.1:4.9.3"`。mods.toml 加 GeckoLib 依赖块（`versionRange="[4.7,)"`, `type="required"`, `side="BOTH"`）。实体：`noGravity`、`invulnerable`、无碰撞、不参与世界 tick 逻辑，`tick()` 里只判 `ShowcaseLifetime`；伤害在服务端**一次性结算，不等动画播完**。

- [ ] **Step 4: 跑测试确认通过** + `runClient`：放大招看到模型播动画、`120 tick` 后消失；召唤 10 个（循环放）时第 9 个起不生成实体但伤害照扣；`/kill @e[type=astrum:showcase]` 也能清干净。

- [ ] **Step 5: 提交** → `git commit -m "feat(M3): geckolib showcase entity with forced lifetime"`

**Review Focus #5** 归本任务。

---

### Task 14: 角色卡 + 合成 + 入册 + `/astrum` 调试命令

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/item/CharacterCardItem.java`、`AstrumDataComponents.java`、`CardEnroll.java`、`src/main/java/com/astrum/pcrd/impl/command/AstrumCommand.java`、`src/main/java/com/astrum/pcrd/api/v1/event/CharacterEquippedEvent.java`
- Create: `src/main/resources/assets/astrum/models/item/character_card.json`、`textures/item/character_card.png`、`data/astrum/recipe/djeeta_card.json`、`data/astrum/lang/{en_us,zh_cn}.json`
- Test: `src/test/java/com/astrum/pcrd/impl/item/CardEnrollTest.java`

**Interfaces:**
- Produces: `AstrumDataComponents.CHARACTER_ID`（`DataComponentType<ResourceLocation>`）；`CardEnroll.enroll(Roster roster, ResourceLocation characterId) -> Roster`（纯函数：已在册则原样返回，由调用方决定是否消耗卡）。

- [ ] **Step 1: 写失败测试**

```java
class CardEnrollTest {
    static final ResourceLocation DJEETA = InternalIds.id("djeeta");
    @Test void enrollingNewCharacterAddsLevelOneEntry() {
        var r = CardEnroll.enroll(new Roster(Map.of(), 1), DJEETA);
        assertEquals(Optional.of(new CharacterProgress(1, 0, 1)), Optional.of(r.entries().get(DJEETA)));
    }
    @Test void enrollingExistingCharacterDoesNotResetProgress() {
        var existing = new Roster(Map.of(DJEETA, new CharacterProgress(9, 50, 3)), 1);
        assertEquals(existing, CardEnroll.enroll(existing, DJEETA));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**。

- [ ] **Step 3: 实现物品、合成、语言、命令**

右键使用：`enroll` 后写回 `ROSTER`（若已存在则不消耗卡，给提示）；`/astrum` 子命令至少 `roster` / `party set <slot> <id>` / `charge set <值>` / `cast <slot>` / `affection add <n>`，权限 `LV2`，且**所有调试能力都可绕过命令**（改配置文件/存档）——因为 LAN 主机可能没开作弊（规格 §7.4）。

- [ ] **Step 4: 跑测试确认通过** + `runClient`：合成卡 → 右键 → `/astrum roster` 里出现姬塔 1 级好感 1；重复右键不重置等级。

- [ ] **Step 5: 提交** → `git commit -m "feat(item): character card item, recipe, enroll flow and debug command"`

---

### Task 15: 等级与好感度

**Files:**
- Create: `src/main/java/com/astrum/pcrd/impl/growth/ExperienceKernel.java`、`AffectionKernel.java`、`GrowthEvents.java`、`src/main/java/com/astrum/pcrd/impl/item/DishItem.java`（或复用原版食物判定）
- Test: `src/test/java/com/astrum/pcrd/impl/growth/ExperienceKernelTest.java`、`AffectionKernelTest.java`

**Interfaces:**
- Produces: `ExperienceKernel.expToNext(int level, GrowthDef g) -> int`（`round(g.expBase() * pow(g.expPerLevel(), level - 1))`）、`ExperienceKernel.add(CharacterProgress p, int amount, GrowthDef g) -> CharacterProgress`（到 `maxLevel` 封顶）、`ExperienceKernel.distribute(int totalExp, Map<ResourceLocation, Double> contributions) -> Map<ResourceLocation, Integer>`（有贡献者至少 1 点，全无贡献返回空表）；`AffectionKernel.tier(int affection, AffectionDef a) -> int`、`AffectionKernel.hookMultiplier(AffectionDef a, int hookIndex, int tier) -> double`、`AffectionKernel.ubTier(int tier) -> UbTier 选择`（`tier >= 3` 用 `enhanced`）。

- [ ] **Step 1: 写失败测试**

```java
class ExperienceKernelTest {
    static final GrowthDef G = new GrowthDef(100, 1.15, 30);
    @Test void firstLevelNeedsBase() { assertEquals(100, ExperienceKernel.expToNext(1, G)); }
    @Test void curveGrowsGeometrically() { assertEquals(115, ExperienceKernel.expToNext(2, G)); }
    @Test void levelUpCarriesRemainder() {
        var p = ExperienceKernel.add(new CharacterProgress(1, 0, 1), 250, G);
        // 100 → 升到 2 级，剩 150；150 ≥ 115 → 升到 3 级，剩 35；35 < round(100*1.15^2)=132 停手
        assertEquals(3, p.level());
        assertEquals(35, p.exp());
    }
    @Test void cappedAtMaxLevel() {
        var p = ExperienceKernel.add(new CharacterProgress(30, 0, 1), 99999, G);
        assertEquals(30, p.level());
    }
    @Test void killExpSplitsByContributionWithFloorOfOne() {
        var d = InternalIds.id("djeeta"); var x = InternalIds.id("x");
        var got = ExperienceKernel.distribute(100, Map.of(d, 90.0, x, 10.0));
        assertEquals(90, got.get(d)); assertEquals(10, got.get(x));
        assertEquals(Map.of(d, 1), ExperienceKernel.distribute(10, Map.of(d, 0.0)));  // 有贡献但比例极低 → 1
        assertTrue(ExperienceKernel.distribute(10, Map.of()).isEmpty());
    }
}

class AffectionKernelTest {
    static final AffectionDef A = new AffectionDef(3, 8, List.of(new HookModifier(1, 1.5)));
    @Test void tiersFollowThresholds() {
        assertEquals(1, AffectionKernel.tier(0, A));
        assertEquals(1, AffectionKernel.tier(2, A));
        assertEquals(2, AffectionKernel.tier(3, A));
        assertEquals(3, AffectionKernel.tier(8, A));
    }
    @Test void tierTwoOnlyScalesExistingHooks() {
        assertEquals(1.5, AffectionKernel.hookMultiplier(A, 1, 2), 1e-9);
        assertEquals(1.0, AffectionKernel.hookMultiplier(A, 1, 1), 1e-9);
        assertEquals(1.0, AffectionKernel.hookMultiplier(A, 0, 2), 1e-9);  // 没列出的 hook 不放大
    }
    @Test void tierThreeSelectsEnhancedUb() {
        assertEquals(true, AffectionKernel.usesEnhancedUb(3));
        assertEquals(false, AffectionKernel.usesEnhancedUb(2));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**。

- [ ] **Step 3: 实现 + 接线**

击杀时按"该角色贡献的附着伤害"分摊经验（贡献来自 Task 9 的 batch 结算记账，记在 `impl/combat/ContributionTracker`，内存表 + 击杀后清空，**不写存档**）；送料理：手持糖/可可豆/蜂蜜瓶/蛋糕右键 → `affection += 1`（每物品每天上限 1 次，用玩家 `tickCount` 内存表，不新增掉落物）。

- [ ] **Step 4: 跑测试确认通过** + `runClient`：带姬塔打怪升级，HUD/命令能看到等级上涨；喂 3 次糖到 2 阶（`广域斩击` 倍率变 1.5），喂到 8 次到 3 阶（UB 换成 4 段 + 轮盘图标换强化版）。

- [ ] **Step 5: 提交** → `git commit -m "feat(growth): level curve from combat participation and affection tiers"`

---

### Task 16: 兼容性与验收矩阵（发布门槛）

**Files:**
- Create: `docs/superpowers/acceptance/2026-11-11-m3-acceptance.md`（逐项打勾的实测记录）
- Modify: 需要时补 `data/astrum/tags/**`

- [ ] **Step 1: `runClient` + `runServer` 双端**

Run: `.\gradlew.bat runServer`（先写 `runs/server/eula.txt` = `eula=true`、`server.properties` 里 `online-mode=false`），等日志出现 `Done (`，再 `stop`。
Expected: 无红字，无 `NoClassDefFoundError`；专服不因客户端类崩溃（规格 §9.5 / 指南 §8 R4）。

- [ ] **Step 2: 旧存档能进**

把 Step 1 之前建的世界的 `playerdata` 备份，改代码后重进。
Expected: 角色册、队伍、充能不丢；`dataVersion` 升级路径无异常。

- [ ] **Step 3: 装 JEI 不崩**

`build.gradle` 加 `localRuntime "mezz.jei:jei-1.21.1-neoforge:<版本>"`（版本从 `https://maven.blamejared.com` 或 CurseForge 确认），`runClient` 进游戏。
Expected: 无 Mixin 冲突（本项目零 Mixin）、无注册表冲突、轮盘/HUD 与 JEI 界面能共存。

- [ ] **Step 4: LAN 联机验收（规格 §11 附加条）**

开单人世界 → 「开放到局域网」→ 同机再开一个 `runClient` 实例加入。
Expected: 两端 UB 槽与队伍界面数值一致；加入方能看到主机放 UB 的演出；主机走的是 `IntegratedServer` 路径但功能正常（**没有** `isDedicatedServer()` 分支）；无队伍时 `area_charge` 对加入方生效。

- [ ] **Step 5: 模块化验收（指南 §8.6 删除测试）**

临时 `git mv` 走 `impl/combat` 整个包、注释掉 `Astrum.java` 里对应一行注册，跑 `.\gradlew.bat build`。
Expected: 仍 `BUILD SUCCESSFUL`；随后 `git restore` 还原。

- [ ] **Step 6: 提交验收记录**

```bash
git add -A && git commit -m "test(M3): acceptance matrix run, deletion test, LAN verification"
```

---

## 计划自审记录

- **规格覆盖**：§1/§2（Task 1–16 全链）、§3 决策 1–20（modid/包名 T1、JDK21 T1、api.v1 T3/T4、ModConfig T5、递减 0.7 T5/T7、UB 不吃递减 T12、轮盘 A+数字键 T12、GeckoLib T13、不锁玩家 T13、合成卡 T14、等级+好感 T15、2 套动画 T13、姬塔 T4、area_charge T11）、§4 数据模型（T3/T6/T10）、§5 战斗（T7/T9/T10/T11/T12/T13）、§6 养成（T14/T15）、§7 兼容（T16）、§8 配置（T5）、§9 api.v1（T3/T4）、§11 里程碑（M0 T1、M1 T4、M2 T8/T12、M3 T13–T15）、§12 风险 1–15 各自有对应任务、§13 待定 #4 由 T9 Step 1 实测、#5 资产由 T13 产出占位模型。
- **命名一致性**：`AstrumApi.characters()`（规格 §9.2 A4 里写成 `PrincessApi`，与同节 §9.1 文件清单矛盾；本计划统一取 `AstrumApi`，实现时不要两个都建）。
- **类型一致性**：`CharacterProgress(level, exp, affection)` 在 T3/T6/T14/T15 组件顺序一致；`UbState(charge, selectedSlot, cdEndTick)` 在 T6/T8/T12 一致；`Dimension` 在 T3/T7/T15 一致。
- **未决**：姬塔的 `.geo.json` 与两套动画是美术资产，T13 只产出可跑通的最小占位模型（1 个立方体 + 2 段动画），美术替换不影响代码。

## 已知缺口（写计划时明确不做）

- 不写 `example-addon`（规格 §2.2 已删，改自举验证，T4 Step 1 钉住）。
- 不做抽卡/星级/hook 数量解锁/多角色/结构维度世界生成（规格 §2.2）。
- 不做 `L2` Gradle 多模块 / `L3` 拆多个 mod（指南 §8.4 判据未命中）。
