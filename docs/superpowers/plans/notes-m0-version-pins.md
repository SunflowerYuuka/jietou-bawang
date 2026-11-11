# M0 版本锁与构建环境事实

> 本文件记录 Task 1（M0 工程骨架）实际解析到的版本与环境事实。后续任务请以此为准。

## 版本（实际解析成功）

| 组件 | 版本 | 声明位置 |
| --- | --- | --- |
| Gradle | **9.6.1** | `gradle/wrapper/gradle-wrapper.properties`（`distributionUrl=.../gradle-9.6.1-bin.zip`） |
| ModDevGradle (MDG) | **2.0.142** | `build.gradle` plugins 块 |
| NeoForge | **21.1.265** | `gradle.properties` → `neo_version` |
| Minecraft | **1.21.1** | `gradle.properties` → `minecraft_version` |
| NeoForm | `20240808.144430`（由 NeoForge 21.1.265 决定，未手写） | 运行时由 MDG 解析 |
| FML | `4.0.45`（同上，运行时解析） | 运行时由 MDG 解析 |
| Java Toolchain | **21** | `build.gradle` → `java.toolchain.languageVersion` |
| Parchment | **未使用（已移除）** | — |

版本号只在 `gradle.properties` 写一次，`build.gradle` 全部通过 `project.*` 读取。

## Parchment

按 Task 1 要求，`neoForge { parchment { ... } }` 块与 `parchment_minecraft_version` / `parchment_mappings_version`
两个属性均已删除。构建与运行均不依赖 Parchment。若后续任务需要更好的参数名，再单独加回。

## 代理

`gradle.properties` 里用 `systemProp.http(s).proxyHost/Port=127.0.0.1:7890`，Gradle daemon 与
NFRT 之外的所有下载都吃这套配置。

**但 NFRT 是 Gradle fork 出去的独立 JVM，不继承 `systemProp`。** 首次 `createMinecraftArtifacts`
必须额外给进程级环境变量：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890'
```

否则会以 `Failed to download .../client.txt: HTTP Status Code 400` 失败（走不通直连时的表现）。

## 本沙箱环境的两个必须知道的点

1. **`GRADLE_USER_HOME` 必须指到可写目录。** `%USERPROFILE%\.gradle` 在本沙箱里是只读的，
   默认跑 `.\gradlew.bat` 会直接崩在
   `FileNotFoundException: ...\gradle-9.6.1-bin.zip.lck (拒绝访问)`。
   本机已把缓存播种到 `K:\AI\Minecraft\.gradle-home`（含 gradle 9.6.1 发行版），使用时：

   ```powershell
   $env:GRADLE_USER_HOME='K:\AI\Minecraft\.gradle-home'
   ```

   该目录在仓库之外，不进版本库。

2. **NFRT 的默认 JDK 编译器在本沙箱里写不出产物，需要一条机器本地配置。**
   沙箱让 `java.nio.file.Files.isWritable` 对所有路径返回 false，于是 NFRT 用 zipfs 打开输出 jar
   时拿到只读文件系统，javac 报
   `ERROR Line: 1, error while writing net.minecraft.world.ticks.package-info: null`。

   解决办法**不在仓库里**：在本机的 Gradle user home 追加一行

   ```properties
   # <GRADLE_USER_HOME>/gradle.properties —— 机器本地，不提交
   neoForge.neoFormRuntime.useEclipseCompiler=true
   ```

   本沙箱即 `K:\AI\Minecraft\.gradle-home\gradle.properties`（该目录在仓库外）。
   ECJ 走普通 `JarOutputStream` 输出，不受影响；这是 MDG 官方暴露的全局设置
   （"Global Settings for NFRT"），不是新依赖。**`build.gradle` 保持模板原样，没有这行。**
   在能正常报告可写路径的机器上不需要这条配置。

## ⚠️ 客户端日志路径是 `run/logs/latest.log`

**计划文档里写的 `runs/client/logs/latest.log` 是错的。** MDG 2.0.142 的默认 game directory 是
**`run/`**（不是 `runs/client/`），所以客户端日志在 **`run/logs/latest.log`**，存档在 `run/saves/`。
后续任务查日志时请用 `run/logs/latest.log`。`.gitignore` 里 `run/` 和 `runs/` 都已忽略。
（`runs/server` 之类同理：服务端是 `run/logs/latest.log`，除非另外配了 gameDirectory。）

## Minecraft 相关版本范围

- `minecraft_version_range=[1.21.1,1.21.2)`
- `loader_version_range=[1,)`（FML 只看主版本）
- `neoforge.mods.toml` 的 NeoForge 依赖写死 `[21.1.265,)`

## 1.21.1 的 pack_format

`34`。
