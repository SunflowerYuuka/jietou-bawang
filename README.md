# Astrum

> 把《公主连结 Re:Dive》的角色带进 Minecraft 1.21.1：抽卡、养成、编队、放必杀。
>
> **English abstract.** Astrum is a NeoForge mod for Minecraft 1.21.1 that brings Princess Connect!
> Re:Dive characters into the game as *data*, not as hard-coded content. A character is a JSON file in
> a datapack registry: it declares how it charges its ultimate, what it does on hit, what its ultimate
> looks like and what each affection tier unlocks. The mod ships exactly one — 姬塔 (Djeeta) — as a
> worked example. The vertical slice covers the whole loop: craft a character card, enrol the
> character, build a party on a radial wheel, fight for experience, feed her dishes to raise affection,
> and fire a GeckoLib-animated ultimate. Zero Mixins; every hook is a NeoForge event. Verified with
> 631 automated tests and a recorded acceptance matrix ([docs/acceptance.md](docs/acceptance.md)).

---

## 依赖

| 项目 | 版本 | 说明 |
|---|---|---|
| Minecraft | **1.21.1** | 仅在 1.21.1 上构建/测试过 |
| NeoForge | **21.1.265** 或更高 | `neoforge.mods.toml` 声明 `[21.1.265,)` |
| GeckoLib | **4.9.3**（`[4.7,)`） | **必需**，包名 `software.bernie.geckolib` |
| Java | **21** 或更高 | 编译目标 21 |

GeckoLib 已 **jarJar** 内嵌进本 mod 的 jar，所以只装本 mod 也能正常播放动画。若你已单独装了
GeckoLib，版本冲突时以较高者为准；**GeckoLib 5 不可用**（它换了包名 `com.geckolib`，面向更新的
Minecraft）。

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

1. **抽卡入手**：合成「角色卡」（纸 + 金粒 + 青金石，无序），手持右键，角色进入你的**角色册**。
2. **翻书**：合成「角色册」（书 + 纸×2 + 线）并右键打开。册子有三种主题：默认、**樱**（+粉色染料）、
   **森**（+绿色染料）；卡片也随主题变色。册子里能看等级、经验条、好感阶数与全部技能说明。
3. **编队**：按 `B` 呼出**轮盘**，把角色放进 1–5 号位。
4. **打架攒气**：命中、被击、连击都会充能。充满后按 `V` 放**必杀**（UB）——一段 GeckoLib 动画，
   伤害在动画的**命中帧**结算，动画被打断则那一发不结算伤害。
5. **升级**：击杀按受害者最大生命值给经验。等级只改数值，不改招式。
6. **送料理涨好感**：把料理（如「米饭」，碗 + 小麦×2）拿在手上**右键方块**喂给队伍，好感 +1。
   **同一份料理每天只能喂一次**，好感每升一阶的效果写在角色 JSON 里；达到 3 阶时 UB **换成强化版**。

### 按键

| 键 | 作用 |
|---|---|
| `V` | 点按 = 放必杀；按住 = 蓄力盘；按住时按 `1`–`5` = 直接放该位 |
| `B` | 按住呼出编队轮盘 |
| `K` | 打开本 mod 的设置界面 |

按键都可在「选项 → 控制」里重绑。

## 配置

两条路都通向同一份配置：

- **游戏内**：模组列表里本 mod 的「Config」按钮，或直接按 `K`。
- **文件**：`config/astrum-client.toml`（客户端）与 `config/astrum-server.toml`（服务端）。

`astrum-server.toml` 分为 `[balance]`（充能/伤害系数）、`[ub]`（必杀参数）、`[wheel]`（轮盘行为）、
`[multiplayer]`（`allyMode`、`maxActiveShowcaseEntities` 等）。`astrum-client.toml` 分为 `[wheel]`
（轮盘手感）、`[camera]`、`[hud]`。

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
- `ub` —— 必杀：`base`（动画名、段数、伤害、自身增益）与 `enhanced`（3 阶好感时替换，可带额外团队增益）
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
| GeckoLib（角色动画库） | <https://github.com/bernie-g/geckolib> |
| Minecraft Forge（NeoForge 的前身） | <https://github.com/MinecraftForge> |

## 免责声明

- 本 mod 是**非官方同人作品**，与 Cygames, Inc. 无任何关联，未获其授权或认可。
- 《公主连结 Re:Dive》及其角色、名称、美术、音乐等一切原作内容的**版权归 Cygames, Inc. 所有**。
- 本 mod **不用于任何商业用途**，不售卖、不内购、不附带任何形式的收费。
- mod 内引用的原作素材**仅作本地引用**，本仓库不再分发原始素材文件。
- 如果你是版权方并认为本 mod 侵犯了你的权益，请通过仓库 issue 联系，我们会**在收到请求后立即下架**相关内容。
- Minecraft 是 Mojang Studios 的商标，本 mod 同样与之无关。
