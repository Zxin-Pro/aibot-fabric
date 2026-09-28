# AIBot —— 由大语言模型自主控制的 Minecraft AI 假玩家

一个 Fabric 模组：在 Minecraft 里加入一个由 LLM 驱动的假玩家。它能自主观察世界、规划任务、执行动作、
与玩家对话，并在无人干预下持续游玩。**支持 Minecraft 1.20.1 / 1.21.1 / 1.21.11 / 26.3 四个版本。**

---

## 0. 一句话架构

**四套独立 Gradle 模块共享同一份「版本无关」源码（配置 / LLM 客户端 / 提示词 / 记忆 / 动作解析），
版本相关的部分（假玩家构造、注册表 API、命令权限、时钟 API）在各自模块内改写**——
因为 26.3 用非重映射的 `fabric-loom` 而 1.x 用 `fabric-loom-remap`，
单一 `build.gradle` 无法干净地覆盖四个目标，多模块是唯一稳妥的结构。

---

## 1. 环境准备（重要，先读这一节）

本项目在 Windows + 无全局 JDK 的环境下开发验证。**四个版本需要的 JDK 不同**：

| 版本线 | 编译目标 Java | 启动 Gradle 用的 JDK | Loom | Loader | Fabric API |
|---|---|---|---|---|---|
| 1.20.1 | **17** | **21** | 1.17.19 | 0.19.3 | `0.92.11+1.20.1` |
| 1.21.1 | 21 | 21 | 1.17.19 | 0.19.3 | `0.116.17+1.21.1` |
| 1.21.11 | 21 | 21 | 1.17.19 | 0.19.3 | `0.141.6+1.21.11` |
| 26.3 | **25** | **25** | 1.17.21 | 0.19.5 | `0.161.0+26.3` |

**两个必须理解的坑：**

1. **1.20.1 的编译目标是 17，但启动 Gradle 必须用 21。**
   因为 Loom 1.17.x 自身要求运行它的 JVM ≥ 21。
   「运行 Gradle 的 JDK」和「编译目标的 JDK」是两件不同的事情，
   前者由 `JAVA_HOME` 决定，后者由 `build.gradle` 里的 `java { toolchain { ... } }` 决定。

2. **Gradle 只自动探测「当前 JVM 所在的那个 JDK」。**
   所以 1.20.1 模块必须在 `gradle.properties` 里登记 JDK 路径：

   ```properties
   org.gradle.java.installations.paths=C:\\path\\to\\jdk-17,C:\\path\\to\\jdk-21,C:\\path\\to\\jdk-25
   ```

   否则 Gradle 会去 `github.com` 下载 toolchain（国内网络通常不可达而失败，
   报错形如 `Could not HEAD 'https://github.com/adoptium/...'`）。

### 安装 JDK

任选一种方式，装 **JDK 17 / 21 / 25** 三个：

* 国内镜像直接下载（推荐，避开 GitHub）：
  * `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/`
  * `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/`
  * `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/windows/`
* 或 `winget install EclipseAdoptium.Temurin.21.JDK`（此方式走 GitHub，网络可能不通）

### Gradle 发行版下载慢/失败

`gradle/wrapper/gradle-wrapper.properties` 已把 `distributionUrl` 指向腾讯云镜像：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.5.1-bin.zip
networkTimeout=120000
```

如果你在海外或想用官方源，改回 `https://services.gradle.org/distributions/gradle-9.5.1-bin.zip` 即可。

---

## 2. 打包（构建四个版本的 jar）

### 方式一：一键脚本（推荐）

`build-all.bat` 会依次构建四个版本。**先改脚本顶部的三个 JDK 路径**为你自己的实际路径：

```bat
set "AIBOT_JDK17=C:\Users\zjh19\DSH\tools\jdk-17.0.20.1+1"
set "AIBOT_JDK21=C:\Users\zjh19\DSH\tools\jdk-21.0.12.1+1"
set "AIBOT_JDK25=C:\Users\zjh19\DSH\tools\jdk-25.0.4.1+1"
```

然后运行：

```bat
cd aibot-fabric
build-all.bat
```

### 方式二：手动逐版本构建

**1.20.1（注意用 JDK 21 启动 Gradle）：**

```bat
set "JAVA_HOME=C:\path\to\jdk-21"
cd fab-1.20.1
gradlew.bat build --no-daemon
```

**1.21.1 / 1.21.11：**

```bat
set "JAVA_HOME=C:\path\to\jdk-21"
cd fab-1.21.11
gradlew.bat build --no-daemon
```

**26.3（必须用 JDK 25）：**

```bat
set "JAVA_HOME=C:\path\to\jdk-25"
cd fab-26.3
gradlew.bat build --no-daemon
```

> Linux / macOS 把 `gradlew.bat` 换成 `./gradlew`，`set` 换成 `export`。

### 产物位置

每个模块构建后产出：

```
fab-<版本>/build/libs/aibot-0.1.0.jar           <- 装进 mods 的就是这个
fab-<版本>/build/libs/aibot-0.1.0-sources.jar   <- 源码包，不用装
```

**选对 jar**：给 1.20.1 用 `fab-1.20.1` 的产物，给 26.3 用 `fab-26.3` 的产物，不能混用。

---

## 3. 安装

1. 安装对应版本的 **Fabric Loader**（26.3 需 ≥ 0.19.5）。
2. 把 **Fabric API** 放进 `mods/`：
   * 1.20.1 → 0.92.11+1.20.1
   * 1.21.1 → 0.116.17+1.21.1
   * 1.21.11 → 0.141.6+1.21.11
   * 26.3 → 0.161.0+26.3
3. 把 **对应版本构建出的 `aibot-0.1.0.jar`** 放进 `mods/`。
4. 启动游戏 / 服务器。

mods 目录位置：

* 客户端：`%APPDATA%\.minecraft\mods`（Windows）
* 服务端：服务器根目录下的 `mods/`

---

## 4. 配置 LLM

启动一次后，配置文件会自动生成在：

* 单人：`.minecraft/saves/<存档名>/config/aibot/config.json`
* 服务端：服务器根目录 `config/aibot/config.json`

**API Key 不硬编码**，只存在这个文件里。可以直接编辑文件，也可以用命令改：

```
/aibot config set apiKey sk-你的密钥
/aibot config set baseUrl https://api.deepseek.com
/aibot config set model deepseek-chat
```

`baseUrl` 可指向**任意 OpenAI 兼容服务**（DeepSeek / OpenAI / 智谱 / 通义 / 本地 vLLM / Ollama 等），
代码会自动补上 `/chat/completions`。

### 完整配置项

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `apiKey` | `""` | API 密钥，**必须自己填** |
| `baseUrl` | `https://api.deepseek.com` | 可改任意 OpenAI 兼容地址 |
| `model` | `deepseek-chat` | 模型名 |
| `temperature` | `0.3` | 采样温度（决策任务建议低值） |
| `maxTokens` | `1024` | 单次回复上限 |
| `cacheEnabled` | `true` | 缓存优化开关 |
| `requestTimeoutMs` | `30000` | 请求超时 |
| `maxRetries` | `2` | 失败重试次数 |
| `decisionIntervalTicks` | `40` | 决策间隔（40 tick = 2 秒） |
| `maxStepsPerSession` | `200` | 单次会话步数上限 |
| `botName` | `AIBot` | 假玩家名字 |

---

## 5. 使用 / 命令

所有命令需要 **OP 权限（等级 2）**。

```
/aibot spawn                          生成假玩家
/aibot remove                         移除假玩家（同时停止自主循环）
/aibot goal <自然语言>                 设置长期目标，例如 /aibot goal 建造一个石屋
/aibot auto on                        开启自主循环
/aibot auto off                       停止自主循环
/aibot status                         查看状态（含缓存命中率）
/aibot cache stats                    查看缓存统计与节省费用
/aibot cache reset                    重置缓存统计
/aibot memory show                    查看短期/长期记忆
/aibot memory clear                   清空记忆
/aibot do {"action":"mine","block":"minecraft:oak_log"}    手动执行一个动作（调试用）
/aibot config show                    显示当前配置
/aibot config set <key> <value>       修改配置
```

### 典型流程

```
/aibot config set apiKey sk-xxxx
/aibot config set baseUrl https://api.deepseek.com
/aibot spawn
/aibot goal 砍 20 个木头并合成工作台
/aibot auto on
...
/aibot auto off
/aibot cache stats
```

---

## 6. 缓存命中优化（核心性能指标）

LLM 调用按「**静态前缀 + 动态后缀**」严格分层，目标是缓存命中率 **> 80%**。

### 请求结构（三条消息，顺序固定）

| 位置 | 角色 | 内容 | 是否变化 |
|---|---|---|---|
| `[0]` | system | 系统提示词、动作 Schema、规则约束（`StaticPrefix.SYSTEM_PROMPT`） | **永不变化** |
| `[1]` | user | 动作协议重申 + 输出格式（`PromptBuilder.STATIC_USER`） | **永不变化** |
| `[2]` | user | 状态 JSON + 短期记忆 + 长期记忆 + 目标 + 反馈 | 每轮变化 |

前两条逐字节固定，因此服务端可以缓存其 KV，只有第三条需要重算。

### 保证前缀稳定的纪律

* 静态前缀里**禁止**出现时间戳、随机数、tick 数、坐标、玩家名、UUID。
* 状态 JSON 的字段顺序手动固定拼接（**不用 Gson 序列化 HashMap**，遍历顺序不稳定）。
* 坐标统一格式化为 1 位小数，避免浮点尾数抖动。
* 方块/实体列表按「距离升序 + ID 字典序」排序，消除遍历顺序差异。
* 动作 Schema 用字符串常量写死，不用 Set/Map 生成。

启动日志会打印**静态前缀指纹**：

```
[AIBot] 提示词静态前缀指纹: len=2380 hash=5f3a91c2
```

指纹变了就说明有人改了静态前缀，缓存命中率会掉。

### 缓存统计

```
/aibot cache stats
```

输出累计调用次数、缓存命中/未命中 token、命中率、估算节省与总花费。
若命中率低于 50%，命令会直接列出排查清单。

* **DeepSeek**：读取 `usage.prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`
* **OpenAI**：读取 `usage.prompt_tokens_details.cached_tokens`

每 100 次调用还会自动打一条命中率日志，便于长期观察。

> 注意：换 `model` 或换 `baseUrl` 必然导致一次冷启动，命中率会短期下降，属正常现象。

---

## 7. 测试步骤

### 7.1 开发环境直接跑（不用装到正式游戏）

```bat
set "JAVA_HOME=C:\path\to\jdk-21"
cd fab-1.21.11
gradlew.bat runClient
```

首次会生成 `run/` 目录。测试服务端用 `gradlew.bat runServer`
（首次需在 `run/eula.txt` 里把 `eula=false` 改成 `true`）。

### 7.2 单人存档测试

1. 按上面步骤装好 mod。
2. 进存档，`/aibot config set apiKey ...` 配好密钥。
3. `/aibot spawn` —— 假玩家应出现在你附近，且 TAB 列表里能看到它。
4. `/aibot do {"action":"chat","message":"你好"}` —— 聊天栏应出现假玩家说的话。
5. `/aibot do {"action":"mine","block":"minecraft:oak_log"}` —— 附近橡木被挖掉、掉落物进背包。
6. `/aibot auto on` —— 观察它每 2 秒决策一次，日志里有 `第 N 步 xxx -> 成功/失败`。
7. `/aibot cache stats` —— 确认缓存命中率（跑 10+ 步后再看更有意义）。

### 7.3 服务端测试

1. 服务端装 Fabric + Fabric API + 本 mod，启动。
2. 用 OP 账号进服，执行上面同样的命令。
3. 假玩家会正常入服，其他玩家也能看到它、能和它说话。

### 7.4 验证缓存是否真的生效

```bat
gradlew.bat runClient --info
```

在日志里搜 `cached=`，能看到每次调用的
`prompt=... completion=... cached=... miss=...`。
第一次调用 `cached=0` 是正常的（冷启动）；从第二次起 `cached` 应明显大于 0。

---

## 8. 常见错误与解决

| 报错 | 原因 | 解决 |
|---|---|---|
| `错误: 不支持发行版本 25` / `Cannot find a Java installation ... languageVersion=17` | Gradle 找不到目标 toolchain，去 GitHub 下载失败 | 在对应模块 `gradle.properties` 里加 `org.gradle.java.installations.paths=...` 登记本机 JDK 路径 |
| `Dependency requires at least JVM runtime version 21. This build uses a Java 17 JVM` | 用 JDK 17 启动了 Gradle | 1.20.1 也用 **JDK 21** 启动 Gradle（编译目标仍是 17，由 toolchain 控制） |
| `Could not HEAD 'https://github.com/adoptium/...'` | toolchain 下载走 GitHub，网络不通 | 同第 1 条；或用国内镜像手动装 JDK |
| Gradle 下载超时 | `services.gradle.org` 重定向到被墙的 CDN | 已默认改为腾讯云镜像，见 `gradle-wrapper.properties` |
| `Connection是抽象的; 无法实例化` | 简单名 `Connection` 被 `WaypointTransmitter.Connection` 嵌套类遮蔽（1.21.11） | 用全限定名 `net.minecraft.network.Connection`（本项目已处理） |
| `找不到符号: 方法 getDayTime()` | 26.3 移除了 `Level.getDayTime()`，改用 `WorldClock` 体系 | 用 `level.getOverworldClockTime()`（本项目已处理） |
| `程序包 net.minecraft.core.component 不存在` | 1.20.1 没有 `DataComponents`（1.20.5+ 才有） | 1.20.1 用 `stack.getItem().isEdible()`（本项目已处理） |
| `不兼容的类型: Level无法转换为ServerLevel` | 1.21.1 及以前 `bot.level()` 返回 `Level` | 用 `bot.serverLevel()`（1.21.11+ 才是 `level()`） |
| `Item id not set` | 1.21.2+ 注册物品必须 `setId` | 本项目不注册新物品，不受影响 |
| 假玩家生成了但 TAB 列表看不到 | 没有调用 `placeNewPlayer` 入服 | 本项目已在 `FakePlayerManager` 中调用 |
| `/aibot` 命令没反应 | 权限不足 | 需要 OP 等级 2 |
| LLM 调用一直失败 | 密钥/地址错误，或网络不通 | `/aibot config show` 核对；用 `/aibot do` 先验证本地动作 |
| 缓存命中率一直 0 | 服务端不返回缓存字段，或模型/地址换了 | 确认服务端支持；保持 `model`/`baseUrl` 不变 |

---

## 9. 安全与合规（务必阅读）

* **备份存档**：假玩家会自动挖方块、移动、改世界。长期挂机前**务必备份存档**。
  （模组启动时也会在日志里提醒你。）
* **API Key 安全**：密钥只存在 `config/aibot/config.json`，不硬编码在代码里。
  `/aibot config show` 与 `/aibot status` 都会对密钥脱敏显示。
* **多人服务器**：让模组控制假玩家在他人服务器上活动**可能违反服务器规则**，
  也可能被视为作弊。**请只在单人存档或自己的服务器上使用。**
* **遵守 Minecraft EULA**：本项目仅供学习与个人使用。
* **成本控制**：`maxStepsPerSession` 默认 200 步会限制单次会话的 token 消耗；
  先用 `/aibot cache stats` 观察花费再放开。

---

## 10. 项目结构

```
aibot-fabric/
├── build-all.bat                    一键构建四版本
├── README.md                        本文件
├── fab-1.20.1/                      1.20.1 模块（Java 17，Loom remap）
├── fab-1.21.1/                      1.21.1 模块（Java 21）
├── fab-1.21.11/                     1.21.11 模块（Java 21）
└── fab-26.3/                        26.3 模块（Java 25，Loom 非 remap）
```

每个模块内：

```
src/main/java/com/example/aibot/
├── Aibot.java                       主入口：装配组件、注册事件与命令
├── config/
│   ├── AIConfig.java                配置对象（纯 POJO，无 MC 依赖）
│   └── ConfigStore.java             配置 JSON 读写（原子落盘）
├── llm/
│   ├── StaticPrefix.java            静态前缀（缓存核心，禁止动态内容）
│   ├── PromptBuilder.java           静态前缀 + 动态后缀拼装
│   ├── LLMClient.java               异步 HTTP 客户端（超时/重试/限流）
│   └── CacheStats.java              缓存命中统计与费用估算
├── memory/
│   ├── ShortTermMemory.java         短期记忆（最近 20 条，固定格式）
│   └── LongTermMemory.java          长期记忆（memory.json 持久化）
├── state/
│   └── StateCollector.java          状态采集 → 固定字段顺序 JSON
├── action/
│   ├── ActionParser.java            LLM 输出容错解析
│   └── ActionExecutor.java          动作执行（14 种动作）
├── entity/
│   ├── AIBotPlayer.java             假玩家实体（版本差异集中处）
│   └── FakePlayerManager.java       生成/移除/聊天
├── core/
│   └── AutoLoop.java                自主循环状态机
└── command/
    └── AIBotCommand.java            /aibot 命令树
```

---

## 11. 四个版本的关键差异（移植备忘）

| 差异点 | 1.20.1 | 1.21.1 | 1.21.11 | 26.3 |
|---|---|---|---|---|
| 资源 ID 类 | `ResourceLocation` | `ResourceLocation` | **`Identifier`** | `Identifier` |
| `ServerPlayer` 构造 | **3 参数**（无 `ClientInformation`） | 4 参数 | 4 参数 | 4 参数 |
| `ServerGamePacketListenerImpl` | **3 参数**（无 cookie） | 4 参数 | 4 参数 | 4 参数 |
| `placeNewPlayer` | **2 参数** | 3 参数 | 3 参数 | 3 参数 |
| `bot.level()` | 返回 `Level` | 返回 `Level` | 返回 `ServerLevel` | 返回 `ServerLevel` |
| 取得 `ServerLevel` | `serverLevel()` | `serverLevel()` | `level()` | `level()` |
| 命令权限 | `hasPermission(2)` | `hasPermission(2)` | `permissions().hasPermission(...)` | 同左 |
| 出生点 | `getSharedSpawnPos()` | `getSharedSpawnPos()` | `getRespawnData().pos()` | 同左 |
| 白天时间 | `getDayTime()` | `getDayTime()` | `getDayTime()` | **`getOverworldClockTime()`** |
| 食物判断 | `Item.isEdible()` | `Item.isEdible()` | `DataComponents.FOOD` | `DataComponents.FOOD` |
| 造成伤害 | `hurt(DamageSource, float)` | `hurt(...)` | `hurtServer(ServerLevel, ...)` | `hurtServer(...)` |
| 注册表读取 | `Registry.get(id)` | `Registry.get(id)` | `Registry.getValue(id)` | `Registry.getValue(id)` |
| `isSolidRender` | 需 `(BlockGetter, BlockPos)` | 需参数 | 无参数 | 无参数 |
| 服务器目录 | `getServerDirectory()` 返回 `File` | 同左 | 返回 `Path` | 返回 `Path` |
| 映射 | mojmap | mojmap | mojmap | **无映射（26.1+ 不再混淆）** |
| Loom 插件 | `fabric-loom-remap` | `fabric-loom-remap` | `fabric-loom-remap` | **`fabric-loom`** |
| 依赖配置 | `modImplementation` | `modImplementation` | `modImplementation` | **`implementation`** |

---

## 12. 已知限制 / 后续可做

* **`place` / `craft` / `sleep` / `store` 四个动作是预留接口**，当前返回「尚未实现」并写入失败记忆。
  接口签名已定好，补实现即可。
* **寻路是 MVP 版**：`move` / `pathfind` 采用「直线靠近 + 落地校验」，
  不做真实地形寻路。需要真正寻路可接入 Baritone。
* **假玩家同时只允许一个**（`FakePlayerManager` 单实例），简化设计。
* **LLM 输出解析**：已做「纯 JSON / 剥代码块 / 截取花括号」三重容错，
  若模型仍频繁输出非 JSON，建议在 `StaticPrefix` 里强化格式约束（注意保持前缀稳定）。
