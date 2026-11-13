# Astrum

> 把《公主连结 Re:Dive》的角色带进 Minecraft 1.21.1：抽卡、养成、编队、放必杀。
>
> **English abstract.** Astrum is a NeoForge mod for Minecraft 1.21.1 that brings Princess Connect!
> Re:Dive characters into the game as *data*, not as hard-coded content. A character is a JSON file in
> a datapack registry: it declares how it charges its ultimate, what it does on hit, what its ultimate
> looks like and what each affection tier unlocks. The mod ships exactly one — 姬塔 (Djeeta) — as a
> worked example. The vertical slice covers the whole loop: craft a character card, enrol the
> character, build a party in the character book, fight for experience, feed her dishes to raise
> affection, and fire an ultimate that shows a screen effect and settles its damage. Zero Mixins; every
> hook is a NeoForge event. Verified with 663 automated tests and a recorded acceptance matrix
> ([docs/acceptance.md](docs/acceptance.md)).

---

## 依赖

| 项目 | 版本 | 说明 |
|---|---|---|
| Minecraft | **1.21.1** | 仅在 1.21.1 上构建/测试过 |
| NeoForge | **21.1.265** 或更高 | `neoforge.mods.toml` 声明 `[21.1.265,)` |
| GeckoLib | **4.9.3**（`[4.7,)`） | **必需，需要你另外下载安装**（见下） |
| Java | **21** 或更高 | 编译目标 21 |

### GeckoLib 要单独装

**本 mod 不再内嵌 GeckoLib**（不 jarJar）。`neoforge.mods.toml` 里把它声明为 `type = "required"`，
所以没装 GeckoLib 时游戏**在加载阶段就会明确报缺依赖**，而不是进游戏后某个功能静默失效。

装法：从 **Modrinth** <https://modrinth.com/mod/geckolib> 或 **CurseForge**
<https://www.curseforge.com/minecraft/mc-mods/geckolib> 下载 **1.21.1 / NeoForge** 版本的
**4.9.3**（`[4.7,)` 区间内的 4.x 都行），和本 mod 一起放进 `mods/`。

- **GeckoLib 5 不可用**：5.x 换了包名（`com.geckolib`）、面向更新的 Minecraft，加载器会拒绝而不是
  错误地加载。
- 整合包里请把 GeckoLib 作为**独立 mod** 列进依赖，不要指望它藏在别的 jar 里——两份 GeckoLib
  会互相冲突。

> 为什么要 GeckoLib：仓库里保留着一套 GeckoLib 动画实体代码（**当前不启用**，见下文「必杀」一节）。
> 它必须能编译、能注册，所以这个依赖不能省。

## 构建

```powershell
gradlew build
```

产物在 `build/libs/astrum-0.1.0.jar`。

**关于首次构建的网络**：Gradle 需要能访问 `dl.cloudsmith.io` 才能解析 GeckoLib ——
`software.bernie.geckolib` 没有发布到 Maven Central，只发在 Cloudsmith。如果卡在依赖解析，先确认这个
域名可达（本项目开发时走的是 `127.0.0.1:7890` 代理，配置在 `gradle.properties`）。NeoForge 与
ModDevGradle 的制品另有来源，通常不受影响。

跑测试：

```powershell
gradlew test
```

## 玩法

> **顺序很重要**：不先编队，按 `V` 永远只会得到「SLOT_EMPTY」的拒绝反馈。编队入口是**角色册界面**，
> 不是轮盘键——轮盘只负责切换一个已经在队伍里的角色的**预设**。

1. **抽卡入手**：合成「角色卡」（纸 + 金粒 + 青金石，无序），手持右键，角色进入你的**角色册**。
   已经在册里的角色不会再消耗卡片。
2. **打开角色册**：合成「角色册」（书 + 纸×2 + 线）并右键打开。册子有三种主题：默认、**樱**（+粉色染料）、
   **森**（+绿色染料）；卡片也随主题变色。界面分三块：**角色册**（等级、经验条、好感阶数）、
   **编队**（五个槽位）、**养成**（进度条与喂食入口）。
3. **编队（必做）**：在角色册的**编队**区，点选一个已拥有的角色，再点某个槽位把他放进去
   （也支持拖拽；右键点槽位清空）。**一个角色不会同时占两个槽**——放到别处会自动从原槽移走。
   命令行玩家也可以用 `/astrum party set <1-5> <角色id>`。
4. **打架攒气**：命中、被击、击杀都会充能；不打的时候也有 `ubChargePerSecond` 的每秒微量自然回能
   （默认 0.2/秒，约 8 分钟回满一条，填 0 可关）。充满后按 `V` 放**必杀**（UB）。
5. **升级**：击杀按受害者最大生命值给经验，经验在参战角色间**均分**。等级只改数值，不改招式。
6. **送料理涨好感**：把料理（如「米饭」，碗 + 小麦×2）拿在手上**右键方块**喂给队伍，好感 +1。
   **同一份料理每天只能喂一次**，好感每升一阶的效果写在角色 JSON 里；达到 3 阶时 UB **换成强化版**。

### 必杀（UB）是什么样

必杀**不会生成任何实体**，它是纯粹的**屏幕特效** + 一次伤害结算：

| 部分 | 说明 |
|---|---|
| 特效 | 金白剑气（`ub_strike`）+ 放射爆环（`ub_burst`）+ 斜向能量流（`ub_flare`）+ **姬塔切入图**，约 **1 秒**线性淡出 |
| 伤害 | 施放瞬间在**服务端**结算一次。基础 20，3 阶好感的强化版 4×20（多段） |
| 实体 | **无**。`astrum:showcase` 一个都不会生成 |
| 输入 | 不挡键、不暂停、不抢鼠标——它只是画在 HUD 下面的一层 |

伤害与特效**互不依赖**：特效关掉（`[effect] enableUbCastEffect = false`）伤害照常；特效被 `F1` 藏掉
伤害照常。伤害为什么在施放瞬间结算、而不是动画的命中帧：没有实体就没有 tick 源，而本项目禁止
tick 轮询，所以「立刻结算」是唯一不需要新子系统的选择——见
`.superpowers/sdd/.../task-17-report.md` 的取舍说明。

> **演出实体（GeckoLib 模型）的代码仍然完整保留**：`ShowcaseEntity`、它的渲染器、`.geo.json` /
> `.animation.json` 和实体类型注册都还在，能编译、能注册，只是**没有代码去生成它**。想重新启用，
> 把 `ShowcaseTypes.register` 里的 `EffectsOnlySink.install()` 换成
> `ShowcaseManager.installEntitySink()` 即可（一行），届时伤害会改回按动画命中帧结算。

### 按键

| 键 | 作用 |
|---|---|
| `V` | 点按 = 放**当前预设**的必杀；按住 = 提示条；按住时按 `1`–`5` = 直接放该位 |
| `B` | 按住呼出**轮盘**——**只切换预设**，不改队伍 |
| `K` | 打开本 mod 的设置界面 |

> `B` 的轮盘和 `V` 的提示条**都只作用在已经在队伍里的角色**上。队伍为空时它们没有可选项。

按键都可在「选项 → 控制」里重绑。

## 配置

两条路都通向同一份配置：

- **游戏内**：模组列表里本 mod 的「Config」按钮，或直接按 `K`。
- **文件**：`config/astrum-client.toml`（客户端）与 `config/astrum-server.toml`（服务端）。

`astrum-server.toml` 分为 `[balance]`（充能/伤害系数）、`[ub]`（必杀参数）、`[multiplayer]`
（`allyMode`、`maxActiveShowcaseEntities` 等）。`astrum-client.toml` 分为 `[wheel]`（轮盘手感）、
`[hud]`（充能条位置）、`[effect]`（`enableUbCastEffect`，关掉全屏必杀特效）。

> **每个键都真的有效。** 这一轮删掉了三个 nobody-reads 的键：`[camera] ubCameraPush`（没有任何
> 镜头代码）、`[wheel] enableScrollSwitching` 与 `[wheel] scrollSwitchPolicy`（滚轮换预设没实现，
> 已被 `B` 长按 + 数字键取代）。留着只会让玩家以为自己设错了。规则是本项目 §8.3 的那条：
> **配置声明的范围必须等于代码实际允许的范围**，否则界面就在骗人。
> `[ub] ubChargePerSecond`（每秒自然回能）原本也在这份清单上——现在它**真的生效**：
> 在线即按每秒这么多点回能，攒满整点才入账，小数余数不丢，气槽满时停止累积，填 `0` 就是关闭。

> `[effect]` 只在**你自己的客户端**生效：特效纯展示，不影响伤害、冷却和充能。整合包里想让所有
> 玩家都关掉，就分发一份带 `enableUbCastEffect = false` 的 `astrum-client.toml`（客户端配置不会被
> 服务端覆盖）。

> **单人游戏里的一处细节**：SERVER 侧的数值改动在单人世界里**立刻生效**，但配置文件本身是**进入世界时**
> 重读的——也就是说你手改 toml 之后，需要重进世界才会被读到，改内存值则不用。整合包作者请直接改文件后
> 重进世界。

## 给整合包作者

角色是**数据**，加一个角色不需要写代码。

**路径**：`data/<你的命名空间>/astrum/character/<角色id>.json`

**注册表是追加式的**：你的文件与 mod 自带的并存，不会互相覆盖。自带示例就在
`data/astrum/astrum/character/djeeta.json` —— 照抄它改是最快的上手方式。字段包括：

- `schemaVersion`、`displayName`
- `dimension` —— 这个角色靠什么攒气
- `effects` —— 一组钩子：`melee_hit`（命中给气）、`combo_n`（第 N 连击触发）、`area_charge`（范围给队友充能）等，
  每个带自己的数值
- `ub` —— 必杀：`base`（动画名、段数、伤害、自身增益）与 `enhanced`（3 阶好感时替换，可带额外团队增益）。
  `animation` 是给**保留的演出实体**用的字段，当前不生成实体所以不生效；`hits` / `damage` / `radius`
  才是实际数值
- `growth` —— `expBase`、`expPerLevel`、`maxLevel`
- `affection` —— `tier2Threshold` / `tier3Threshold`，以及 `tier2HookModifiers`（**按 hookIndex 指定**哪些
  钩子被放大——2 阶只改数值、不加招式）

数据是**容错**的：字段缺失走默认，未知字段被忽略而不是让整个注册表崩掉；某一项写坏时该角色退化为占位符，
其余角色照常工作。

## 鸣谢

本 mod 是非官方同人作品。没有下面这些项目，它不会存在：

| 对象 | 链接 |
|---|---|
| DeepSeek（本项目的开发与协作工具） | <https://www.deepseek.com/> |
| 公主连结 Re:Dive（Cygames，原作与角色版权方） | <https://priconne-redive.jp/> |
| 干炸里脊资源站（美术素材来源） | <https://redive.estertion.win/> |
| RediveData（台服 master data，数值参考） | <https://github.com/KiruyaMomochi/RediveData> |
| 萌娘百科·姬塔（原作数值参考） | <https://zh.moegirl.org.cn/> |
| NeoForge（模组加载器与事件 API） | <https://neoforged.net/> |
| ModDevGradle（Gradle 构建插件） | <https://github.com/neoforged/ModDevGradle> |
| GeckoLib（角色动画库，需另行安装） | <https://github.com/bernie-g/geckolib> |
| Minecraft Forge（NeoForge 的前身） | <https://github.com/MinecraftForge> |

**关于 mod 内的姬塔美术**：角色立绘一律来自**官方 6★ 卡面**，由上面「干炸里脊资源站」提供的素材
整理而来，版权归 Cygames, Inc.。其中的 **UB 切入图**
（`assets/astrum/textures/gui/ub_cutin_djeeta.png`，512×384，251 KB）是从官方卡面
（`105761.webp`，1408×792）**裁剪 + 缩放**得到的派生图，没有重绘、没有改色；生成可用
`tools/cutin_from_official_card.py` 复现。**原始 1408×792 素材不随本仓库分发**，与下面的免责声明一致。

## 免责声明

- 本 mod 是**非官方同人作品**，与 Cygames, Inc. 无任何关联，未获其授权或认可。
- 《公主连结 Re:Dive》及其角色、名称、美术、音乐等一切原作内容的**版权归 Cygames, Inc. 所有**。
- 本 mod **不用于任何商业用途**，不售卖、不内购、不附带任何形式的收费。
- mod 内引用的原作素材**仅作本地引用**，本仓库不再分发原始素材文件。
- 如果你是版权方并认为本 mod 侵犯了你的权益，请通过仓库 issue 联系，我们会**在收到请求后立即下架**相关内容。
- Minecraft 是 Mojang Studios 的商标，本 mod 同样与之无关。
