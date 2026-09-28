# AIBot —— 由大语言模型自主控制的 Minecraft AI 假玩家

一个 Fabric 模组：在 Minecraft 里加入由 LLM 驱动的**假玩家**。它能自主观察世界、规划任务、执行动作、
与玩家对话，并在无人干预下**长期持续自主游玩**。可以同时生成**多个**智能体，
每个都有**自定义名字与性格**。**支持 Minecraft 1.20.1 / 1.21.1 / 1.21.11 / 26.3 四个版本。**

> **v0.5.0 新增**：多智能体并发、自定义名字/性格（参考 Carpet）、**上下文压缩**（可以真正一直玩下去）、
> 智能体能**听到并回应**玩家聊天。详见第 0.6 节。

> 本文件为 **UTF-8（带 BOM）** 编码。如果你在任何地方看到中文显示成 `???`，
> 请用 UTF-8 打开本文件（见第 13 节）。

---

## 0. 一句话架构

**四套独立 Gradle 模块共享同一份「版本无关」源码（配置 / LLM 客户端 / 提示词 / 记忆 / 任务计划 / 动作解析），
版本相关的部分（假玩家构造、注册表 API、命令权限、时钟 API、配方系统）在各自模块内改写**——
因为 26.3 用非重映射的 `fabric-loom` 而 1.x 用 `fabric-loom-remap`，
且 26.3 与 1.20.1 的配方 API 完全不同，单一 `build.gradle` 无法干净地覆盖四个目标，多模块是唯一稳妥的结构。

---

## 0.5 完全自主运行 + 与真人玩家行为一致

### 核心原则：除了「谁在操控」，其余和真人完全一样

假玩家不是在「作弊改世界」，而是通过**原版玩家输入通道**驱动，
服务端看到的行为与真人客户端完全一致：

| 行为 | 真人玩家 | 本模组 | 之前的作弊实现（已废弃） |
|---|---|---|---|
| 走路 | 按 W，逐 tick 移动 | 设置 `zza`/`xxa`，由原版 `travel()` 推进 | ❌ `teleportTo` 瞬移 |
| 重力/碰撞 | 有 | **有**（原版物理） | ❌ 无，可穿墙 |
| **绕障碍** | **绕开 / 跳上去 / 挖开 / 搭桥** | **A\* 寻路**（见下） | ❌ 直线顶墙到超时 |
| 挖方块 | 按住左键，按硬度耗时 | `gameMode.handleBlockBreakAction(...)` | ❌ `destroyBlock` 瞬间破坏 |
| 工具选择 | 换最合适的工具 | `selectBestToolFor()` 按 `getDestroySpeed` 选 | ❌ 从不换工具 |
| 工具耐久 | 消耗 | **消耗**（`isToolAboutToBreak()` 可感知） | ❌ 不消耗 |
| 放方块 | 右键，走放置校验 | `gameMode.useItemOn(...)` | ❌ `setBlockAndUpdate` 凭空放 |
| 吃东西 | 1.6 秒，会被打断 | `startUsingItem(...)`，**原版 tick 结算** | ❌ `food.eat()` 瞬间结算 |
| 攻击 | 等冷却条 | 检查 `getAttackStrengthScale` | ❌ 无视冷却 |
| **捡掉落物** | **走过去捡** | `tryPickupNearby()` 走向掉落物 | ❌ 直接进背包 |
| **躲避危险** | **本能躲开岩浆/火** | `avoidDanger()` 反射 | ❌ 站着被烧 |
| **被打就反应** | **掉血立刻跑** | 每 tick 比较血量差，掉血即撤离 | ❌ 挨打不管 |

### A\* 寻路（`PathFinder`）

真人走路会做六件事，A\* 全都支持：

| 动作 | 实现 | 代价 |
|---|---|---|
| 平地走 | 相邻格可站立 | 1.0 |
| 跳上 1 格台阶 | 检测上方可通行 | 1.5 |
| 掉下去 | 落差 ≤ 4 格（避免摔伤） | 1.2 + 0.2×落差 |
| 游泳 | 水中可通过 | 1.5+ |
| **挖穿挡路方块** | 硬度 ≤ 3.0 才挖（可用开关关闭） | 4.0 |
| **搭桥跨缺口** | 跨度 ≤ 3，需背包有方块 | 6.0 + 跨度 |

技术要点：

- 用原版 `level.noCollision(AABB)` 做真实碰撞判定，所以栅栏、半砖、台阶等形状都正确
- 搜索上限 4000 节点，纯计算（不改世界），不会卡 tick
- **卡住自恢复**：走路时连续多 tick 位移 < 0.02 格即判定被挡，自动重新规划（最多 5 次），
  5 次仍失败才报错——这就是真人「走不通就换条路」的行为
- `hasLineOfSight()` 提供视线检查，避免「透视」感知

### 长期自主运行的三项保障

| 问题 | 解决方案 | 实现位置 |
|---|---|---|
| 死了就彻底停机 | **死亡自动重生**，循环不中断，保留物品栏 | `AutoLoop.handleDeath()` |
| 跑 200 步就停 | **移除步数上限**（默认 0 = 无限） | `AIConfig.maxStepsPerSession` |
| 反复横跳做不成事 | **结构化任务计划**（任务栈） | `TaskPlan` |
| 走远就找不到家 | **地标记忆**，提示词带相对距离 | `LandmarkMemory` |
| LLM 失误导致猝死 | **生存反射**（受伤/低血/饥饿/危险方块） | `AutoLoop.runSurvivalReflex()`、`TickActionDriver.avoidDanger()` |
| 卡在死路上空转 | 任务连续失败自动跳过 + 重新寻路 | `TaskPlan.failCurrent()`、`MAX_REPLANS` |

### 关键实现细节（踩坑记录）

1. **`PlayerList.respawn()` 会丢弃我们的子类。**
   原版内部是 `new ServerPlayer(...)`，重生后拿到的是**普通 ServerPlayer**。
   - `MultiBotManager.Agent` 同时保存 `entity` 与 `rebound`，用 `player()` 统一取；
   - `ActionExecutor` / `StateCollector` / `TickActionDriver` 参数类型都是 `ServerPlayer`。
2. **动作是跨 tick 的，不是瞬间的。**
   `execute()` 返回 `async=true` 表示「已启动」，
   走远路可能几百 tick，挖黑曜石要 188 tick（9.4 秒，和真人一样）。
3. **tick 事件必须用 `START_SERVER_TICK`。**
   移动输入（`zza`/`xxa`）是被**玩家自己的 tick** 消费的，
   放在 `END_SERVER_TICK` 会慢一拍且可能被原版重置。
4. **重生保留物品栏**（`respawn(player, true)`）。
5. **计划跨重启持久化**到 `config/aibot/memory-<名字>-plan.json`。
6. **一个智能体出错不能拖垮别人。**
   `MultiBotManager.tick()` 对每个 agent 单独 try/catch，
   出错的只停它自己，不影响其他智能体，更不会打断服务器主循环。

---

## 0.6 多智能体 + 自定义名字 + 上下文压缩（v0.5.0 新增）

### 0.6.1 可以同时跑很多个智能体

每个智能体都是一个**独立完整的原版玩家**：自己的实体、自己的 UUID、自己的目标、
自己的记忆文件。服务器、TAB 列表、聊天栏都当它是另一个真人。

```
/aibot spawn 小明
/aibot spawn 阿强
/aibot personality 小明 谨慎的农夫，喜欢种地，遇到怪物就躲
/aibot personality 阿强 莽撞的矿工，爱冒险，看见矿洞就想钻
/aibot goal 小明 建一个农场并储备 64 个面包
/aibot goal 阿强 挖到钻石并造一套铁装备
/aibot auto all on
```

| 特性 | 说明 |
|---|---|
| **自定义名字** | `/aibot spawn <名字>`，遵循原版规则（3~16 字符，字母数字下划线）。**名字即身份**，与 Carpet 一致 |
| **同名 = 同一个玩家** | UUID 由名字散列得到，因此移除后重新 spawn，**背包与统计数据会延续** |
| **性格** | `/aibot personality <名字> <描述>`，会进入提示词，影响动作选择与聊天语气 |
| **独立记忆** | 每个 bot 一份 `memory-<名字>.json` / `landmarks-<名字>.json`，互不污染 |
| **数量上限** | `maxBots`（默认 5），因为每个都要独立发 LLM 请求，费用线性增长 |
| **共享 LLM 客户端** | 所有 bot 复用同一份静态前缀，命中同一份服务端 KV 缓存，边际成本远低于各建一个 |

> ⚠️ 名字合法性会**提前校验**。非法名字（带中文、空格、过长）如果直接塞给
> `placeNewPlayer`，会抛出难以理解的异常，甚至产生无法移除的幽灵玩家。

### 0.6.2 上下文压缩（这是「能一直玩下去」的真正前提）

**问题**：一个真正长期自主运行的智能体，玩几小时就是几千步。
如果把每一步都塞进提示词，上下文会无限膨胀 —— 最后必然触发 API 上限，
不但贵，模型还会被陈旧的流水账淹没、判断力下降。

**方案**（`ContextCompressor`，三层，从便宜到贵）：

| 层 | 做法 | 效果 |
|---|---|---|
| 1. 滑窗 | 只保留最近 N 条完整动作记录（默认 12） | 细节有界 |
| 2. 摘要 | 被挤出去的记录按每 5 条聚合成一句阶段摘要 | 历史不丢失，压缩成结论 |
| 3. 预算 | 按估算 token 数硬性裁剪（默认 1500），优先丢最早的摘要 | 逼近上限时兜底 |

实测（跑 `tools/CompressorTest`，3000 步模拟）：

```
提示词长度: 最小=132  最大=1449  平均=1412     ← 恒定，不随步数增长
压缩统计: 已压缩 2988 条历史 → 16 条摘要
```

**两个关键设计决定：**

1. **不调用 LLM 做摘要。** 额外调用既费钱又变慢，更严重的是
   LLM 摘要每次措辞都不同，会破坏提示词缓存的**字节一致性**，导致缓存全部失效。
   这里用确定性规则聚合（统计主导动作、成功/失败次数、代表性失败原因）。
2. **格式完全确定**，无哈希、无时间戳、无随机顺序 —— 因此同一状态下
   反复构建的提示词字节完全一致，前缀缓存不会被打穿。

用 `/aibot context stats [名字]` 查看压缩情况。

### 0.6.3 智能体能听到你说话

之前的版本里智能体**只能说、不能听** —— 你跟它讲话它毫无反应，这是最不像真人的地方。

现在通过 Fabric 的 `ServerMessageEvents.CHAT_MESSAGE` 监听聊天，
喂给每个智能体（会跳过它自己说的话，避免自问自答）。
有人在最近 3 步内跟它说话时，提示词会加一条强提醒：

> 【有人在跟你说话】XXX 说：「…」。请先用 chat 动作简短回应他，再继续手上的事。

### 0.6.4 这一版修掉的不合理之处

| 问题 | 之前的做法 | 现在 |
|---|---|---|
| **步数上限悄悄生效** | `ConfigStore.normalize()` 把 `maxStepsPerSession<=0` 强制改成 **200**，跑满 200 步就自己停了，与「一直玩下去」的承诺矛盾 | 允许 0/负数 = 真·无上限（**这是真实存在过的 bug**） |
| **说话像系统公告** | `broadcastSystemMessage`，黄色斜体、无玩家名牌、不进聊天记录、插件监听不到 | 走原版 `broadcastChatMessage`，和真人发言完全一致 |
| **转头瞬移** | `look()` 把 yaw/pitch 直接归零，一 tick 猛转 180° | 在当前朝向前后 ±60° 小幅扫视，并同步给客户端 |
| **记忆文件混在一起** | 所有 bot 共用一个 `memory.json`，互相覆盖 | 按名字隔离 |
| **机器人名字写死在提示词里** | `StaticPrefix` 里硬编码「名字叫 AIBot」 | 移到动态区，因此多 bot 才能共享前缀缓存 |
| **智能体听不见** | 无 | 见 0.6.3 |

### 0.6.5 仍然不做的事（有意为之）

这些**故意不做**，因为它们属于作弊或伪造身份，不是「像真人」：

- ❌ 不伪造 Mojang 皮肤签名（`textures` 属性）—— 那属于伪造正版身份。
  想换外观请在 `skinOwner` 填一个正版玩家名，让服务端按名字查皮肤（原版机制）。
- ❌ 不瞬移、不凭空放方块、不瞬间结算进食、不无视攻击冷却。
- ❌ 不在多人服务器上给假玩家超级权限。

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

- 国内镜像直接下载（推荐，避开 GitHub）：
  - `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/`
  - `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/`
  - `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/x64/windows/`
- 或 `winget install EclipseAdoptium.Temurin.21.JDK`（此方式走 GitHub，网络可能不通）

### Gradle 发行版下载慢/失败

`gradle/wrapper/gradle-wrapper.properties` 已把 `distributionUrl` 指向腾讯云镜像：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.5.1-bin.zip
networkTimeout=120000
```

如果你在海外或想用官方源，改回 `https://services.gradle.org/distributions/gradle-9.5.1-bin.zip` 即可。

---

## 2. 快速开始（直接用现成 jar）

不想自己编译的话，直接下载发布版：

**https://github.com/Zxin-Pro/aibot-fabric/releases**

| Minecraft | 下载文件 |
|---|---|
| 1.20.1 | `aibot-0.5.0-1.20.1.jar` |
| 1.21.1 | `aibot-0.5.0-1.21.1.jar` |
| 1.21.11 | `aibot-0.5.0-1.21.11.jar` |
| 26.3 | `aibot-0.5.0-26.3.jar` |

装法见第 3 节，配置见第 4 节。

---

## 3. 打包（自己构建四个版本的 jar）

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

每个模块构建后产出（版本号以实际为准，当前为 `0.5.0`）：

```
fab-<版本>/build/libs/aibot-<版本号>.jar           <- 装进 mods 的就是这个
fab-<版本>/build/libs/aibot-<版本号>-sources.jar   <- 源码包，不用装
```

**选对 jar**：给 1.20.1 用 `fab-1.20.1` 的产物，给 26.3 用 `fab-26.3` 的产物，不能混用。

---

## 4. 安装

1. 安装对应版本的 **Fabric Loader**（26.3 需 ≥ 0.19.5）。
2. 把 **Fabric API** 放进 `mods/`：
   - 1.20.1 → 0.92.11+1.20.1
   - 1.21.1 → 0.116.17+1.21.1
   - 1.21.11 → 0.141.6+1.21.11
   - 26.3 → 0.161.0+26.3
3. 把 **对应版本的 `aibot-<版本号>.jar`** 放进 `mods/`。
4. 启动游戏 / 服务器。

mods 目录位置：

- 客户端：`%APPDATA%\.minecraft\mods`（Windows）
- 服务端：服务器根目录下的 `mods/`

---

## 5. 配置 LLM

启动一次后，配置文件会自动生成在：

- 单人：`.minecraft/saves/<存档名>/config/aibot/config.json`
- 服务端：服务器根目录 `config/aibot/config.json`

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
| `maxStepsPerSession` | `0` | 单次会话步数上限，**0 = 无限（长期自主模式）** |
| `autoRestart` | `true` | 到达步数上限后自动续跑（仅在上限 > 0 时生效） |
| `autoRespawn` | `true` | **死亡自动重生**（长期运行的关键） |
| `respawnDelayTicks` | `100` | 重生前等待（5 秒），给掉落物留时间 |
| `survivalReflex` | `true` | **生存反射**：血量/饥饿过低时绕过 LLM 自保 |
| `stuckThreshold` | `3` | 连续失败多少次判定为卡住 |
| `persistPlan` | `true` | 计划持久化，重启后接着做 |
| `botName` | `AIBot` | 默认智能体名字（`/aibot spawn` 不带名字时用它） |
| **`maxBots`** | `5` | **同时允许的智能体数量上限**（每个都独立发请求，费用线性增长） |
| **`autoSpawnOnStart`** | `false` | 服务器启动时自动拉起所有 `autoLoop=true` 的档案（重启即续玩） |
| **`contextWindow`** | `12` | **上下文压缩**：保留最近多少条完整动作细节 |
| **`contextTokenBudget`** | `1500` | **上下文压缩**：动态部分的目标 token 预算 |

---

## 6. 使用 / 命令

所有命令需要 **OP 权限（等级 2）**。涉及具体智能体的命令都要带**名字**。

```
--- 生成与管理 ---
/aibot spawn [名字]                    生成智能体（不写名字则用配置里的 botName）
/aibot remove <名字|all>               移除智能体（档案保留，可再次 spawn 恢复）
/aibot list                            列出所有智能体及其状态/性格/目标

--- 身份与目标 ---
/aibot personality <名字> <描述>        设置性格（影响动作选择与聊天语气）
/aibot goal <名字> <自然语言>           设置长期目标（会清空旧计划并触发重新规划）

--- 自主运行 ---
/aibot auto <名字|all> on|off          开关自主循环（无步数上限，持续运行）
/aibot status [名字]                   查看状态（含缓存命中率、上下文压缩、计划进度）

--- 调试与统计 ---
/aibot cache stats                     查看缓存统计与节省费用
/aibot cache reset                     重置缓存统计
/aibot context stats [名字]            查看上下文压缩情况（压缩了多少条历史）
/aibot context clear [名字]            清空某智能体的上下文与短期记忆
/aibot do <名字> {"action":...}         手动执行一个动作（调试用）
/aibot config show                     显示当前配置
/aibot config set <key> <value>        修改配置
```

### 典型流程（长期挂机）

**单智能体：**

```
/aibot config set apiKey sk-xxxx
/aibot config set baseUrl https://api.deepseek.com
/aibot spawn 小明
/aibot personality 小明 谨慎的农夫，优先种地和储存食物
/aibot goal 小明 建造一个有工作台和箱子的石屋，然后持续挖矿积累资源
/aibot auto 小明 on
```

**多智能体同时玩：**

```
/aibot spawn 阿强
/aibot personality 阿强 莽撞的矿工，爱冒险，看见矿洞就想钻
/aibot goal 阿强 挖到钻石并造一套铁装备
/aibot auto all on
```

开了 `auto on` 之后就可以不管它了：它会自己拆解目标、执行、死亡重生、跳过做不到的任务、
压缩历史、被人搭话时回话。**这就是「一直自己玩下去」**。

想看进展用 `/aibot list`、`/aibot plan show`、`/aibot status <名字>`，
想停用 `/aibot auto <名字|all> off`。

> 想让服务器**重启后自动拉起**之前记录的智能体：
> `/aibot config set autoSpawnOnStart true`（会拉起所有 `autoLoop=true` 的档案）。

### 新增动作（LLM 可用）

| 动作 | 说明 |
|---|---|
| `plan` | 把长期目标拆解成 3~6 个步骤写入任务栈 |
| `remember` | 记录地标（home/chest/mine/farm/base），避免迷路 |

---

## 7. 缓存命中优化（核心性能指标）

LLM 调用按「**静态前缀 + 动态后缀**」严格分层，目标是缓存命中率 **> 80%**。

### 请求结构（三条消息，顺序固定）

| 位置 | 角色 | 内容 | 是否变化 |
|---|---|---|---|
| `[0]` | system | 系统提示词、动作 Schema、规则约束（`StaticPrefix.SYSTEM_PROMPT`） | **永不变化** |
| `[1]` | user | 动作协议重申 + 输出格式（`PromptBuilder.STATIC_USER`） | **永不变化** |
| `[2]` | user | 状态 JSON + 短期记忆 + 长期记忆 + 目标 + 反馈 | 每轮变化 |

前两条逐字节固定，因此服务端可以缓存其 KV，只有第三条需要重算。

### 保证前缀稳定的纪律

- 静态前缀里**禁止**出现时间戳、随机数、tick 数、坐标、玩家名、UUID。
- 状态 JSON 的字段顺序手动固定拼接（**不用 Gson 序列化 HashMap**，遍历顺序不稳定）。
- 坐标统一格式化为 1 位小数，避免浮点尾数抖动。
- 方块/实体列表按「距离升序 + ID 字典序」排序，消除遍历顺序差异。
- 动作 Schema 用字符串常量写死，不用 Set/Map 生成。

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

- **DeepSeek**：读取 `usage.prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`
- **OpenAI**：读取 `usage.prompt_tokens_details.cached_tokens`

每 100 次调用还会自动打一条命中率日志，便于长期观察。

> 注意：换 `model` 或换 `baseUrl` 必然导致一次冷启动，命中率会短期下降，属正常现象。

---

## 8. 测试步骤

### 8.1 开发环境直接跑（不用装到正式游戏）

```bat
set "JAVA_HOME=C:\path\to\jdk-21"
cd fab-1.21.11
gradlew.bat runClient
```

首次会生成 `run/` 目录。测试服务端用 `gradlew.bat runServer`
（首次需在 `run/eula.txt` 里把 `eula=false` 改成 `true`）。

### 8.2 单人存档测试

1. 按上面步骤装好 mod。
2. 进存档，`/aibot config set apiKey ...` 配好密钥。
3. `/aibot spawn 小明` —— 假玩家应出现在你附近，且 TAB 列表里能看到它。
4. `/aibot do 小明 {"action":"chat","message":"你好"}` —— 聊天栏应出现假玩家说的话。
5. `/aibot do 小明 {"action":"mine","block":"minecraft:oak_log"}` —— 附近橡木被挖掉、掉落物进背包。
6. `/aibot auto 小明 on` —— 观察它每 2 秒决策一次，日志里有 `第 N 步 xxx -> 成功/失败`。
7. `/aibot cache stats` —— 确认缓存命中率（跑 10+ 步后再看更有意义）。

### 8.2.1 重点验证 v0.5.0 的新功能

**① 多智能体 + 自定义名字**

```
/aibot spawn 阿强
/aibot personality 阿强 莽撞的矿工，爱冒险
/aibot auto all on
/aibot list                      # 应看到 小明 和 阿强 两条，各自独立步数
```
两个智能体应能同时活动、互不干扰。

**② 同名恢复背包（Carpet 行为）**

```
（先让 小明 挖点东西）
/aibot remove 小明
/aibot spawn 小明
```
重新 spawn 后，**背包里的东西应该还在** —— 因为 UUID 由名字散列而来，
服务器认为它是同一个玩家。如果背包清空了，说明 UUID 生成有问题。

**③ 上下文压缩（这是「一直玩下去」的关键）**

```
/aibot context stats 小明
```
放着挂机几十分钟，反复看这条命令：`已压缩 N 条历史` 的 N 应持续增长，
而 `/aibot status` 里的提示词长度应该**基本恒定**（不随步数线性变长）。
如果提示词一直变长，说明压缩没生效，长时间运行最终会超上下文上限。

**④ 聊天回应**

在游戏里直接对着智能体打字说话（不要用 `/aibot do`），例如：
```
小明你在干嘛
```
它应该在最近几次决策内用 chat 动作回你一句。如果完全没反应，
检查日志里有没有「注册聊天监听失败」。

**⑤ 死亡自动重生**

```
/aibot do 小明 {"action":"chat","message":"测试重生"}
/kill 小明
```
它应在 5 秒后自动重生并继续自主循环，`/aibot list` 里仍显示 `[存活]`。

### 8.3 重点验证 A\* 寻路

```
/aibot do 小明 {"action":"move","x":50,"y":64,"z":50}
```

**在它和目标之间故意放一堵墙**，看它表现为哪一种：

- 绕过去（墙不够高、旁边能走）
- 跳上去（墙高 1 格）
- 挖穿（墙硬度 ≤ 3.0，如石头、泥土）
- 搭桥（中途有沟壑且背包有方块）

如果它顶着墙不动，把日志发出来——这就是寻路没生效。

另外测挖矿是否**按硬度耗时**、工具是否**掉耐久**：

```
/aibot do 小明 {"action":"mine","block":"minecraft:stone"}
```

### 8.3.1 离线自测上下文压缩

压缩逻辑不依赖 Minecraft，可以脱离游戏直接跑（需要 JDK）：

```bat
cd aibot-fabric
javac -encoding UTF-8 -d tests\out tests\CompressorTest.java ^
  fab-1.20.1\src\main\java\com\example\aibot\memory\ContextCompressor.java ^
  fab-1.20.1\src\main\java\com\example\aibot\memory\ShortTermMemory.java
java -cp tests\out CompressorTest
```

模拟跑 3000 步，断言提示词长度有界、历史以摘要保留、预算截断生效、clear() 正常。
期望输出 `>>> 全部测试通过`。

### 8.4 服务端测试

1. 服务端装 Fabric + Fabric API + 本 mod，启动。
2. 用 OP 账号进服，执行上面同样的命令。
3. 假玩家会正常入服，其他玩家也能看到它、能和它说话。

### 8.5 验证缓存是否真的生效

```bat
gradlew.bat runClient --info
```

在日志里搜 `cached=`，能看到每次调用的
`prompt=... completion=... cached=... miss=...`。
第一次调用 `cached=0` 是正常的（冷启动）；从第二次起 `cached` 应明显大于 0。

---

## 9. 常见错误与解决

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
| 假玩家生成了但 TAB 列表看不到 | 没有调用 `placeNewPlayer` 入服 | 本项目已在 `MultiBotManager.spawn()` 中调用 |
| `spawn` 报名字非法 | 原版玩家名规则：3~16 字符，仅字母数字下划线 | 换一个合法名字；本项目会提前校验并给出具体原因 |
| 一直停在 200 步就自己停了 | 旧版本 `ConfigStore.normalize()` 把 `maxStepsPerSession<=0` 强制改成 200 | **v0.5.0 已修**。若你从旧版升级，检查配置文件里的实际值 |
| 智能体不回应我说的话 | 聊天监听没注册成功（版本间签名差异） | 看日志有没有「注册聊天监听失败」；有则说明该版本的聊天事件 API 不同 |
| 提示词越来越长 / 请求被拒 | 上下文压缩没生效 | `/aibot context stats` 看压缩计数；正常应随步数增长而提示词长度恒定 |
| `/aibot` 命令没反应 | 权限不足 | 需要 OP 等级 2 |
| `/aibot status` 说找不到智能体 | 命令需要名字 | 用 `/aibot list` 看名字，再 `/aibot status <名字>` |
| LLM 调用一直失败 | 密钥/地址错误，或网络不通 | `/aibot config show` 核对；用 `/aibot do <名字> {...}` 先验证本地动作 |
| 缓存命中率一直 0 | 服务端不返回缓存字段，或模型/地址换了 | 确认服务端支持；保持 `model`/`baseUrl` 不变 |
| 多智能体后费用涨得快 | 每个 bot 都独立发请求，费用线性增长 | 调低 `maxBots`，或给不重要的 bot `/aibot auto <名字> off` |

---

## 10. 安全与合规（务必阅读）

- **备份存档**：假玩家会自动挖方块、移动、改世界。长期挂机前**务必备份存档**。
  （模组启动时也会在日志里提醒你。）
- **API Key 安全**：密钥只存在 `config/aibot/config.json`，不硬编码在代码里。
  `/aibot config show` 与 `/aibot status` 都会对密钥脱敏显示。
- **多人服务器**：让模组控制假玩家在他人服务器上活动**可能违反服务器规则**，
  也可能被视为作弊。**请只在单人存档或自己的服务器上使用。**
- **遵守 Minecraft EULA**：本项目仅供学习与个人使用。
- **成本控制**：`maxStepsPerSession` 默认 `0`（无限），token 会持续消耗，
  多智能体时是**线性叠加**。先用 `/aibot cache stats` 观察花费，
  再用 `maxBots` 与逐个 `auto off` 控制规模。

---

## 11. 项目结构

```
aibot-fabric/
├── build-all.bat                    一键构建四版本
├── README.md                        本文件（UTF-8 with BOM）
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
│   ├── ShortTermMemory.java         短期记忆（环形缓冲，供压缩器滑窗）
│   ├── ContextCompressor.java       **上下文压缩**（滑窗 + 摘要 + token 预算）
│   ├── LongTermMemory.java          长期记忆（memory-<名字>.json 持久化）
│   ├── LandmarkMemory.java          地标记忆（家 / 箱子 / 矿点）
│   └── ChatMemory.java              **聊天记忆**（听到的玩家发言）
├── state/
│   └── StateCollector.java          状态采集 → 固定字段顺序 JSON
├── action/
│   ├── ActionParser.java            LLM 输出容错解析
│   ├── ActionExecutor.java          动作分发与参数校验
│   ├── PathFinder.java              A* 寻路（绕障/跳台阶/挖穿/搭桥/视线检查）
│   └── TickActionDriver.java        逐 tick 动作驱动（走路/挖掘/放置/进食/攻击/拾取）
├── entity/
│   ├── AIBotPlayer.java             假玩家实体（版本差异集中处）
│   ├── BotProfile.java              **智能体档案**（名字/性格/目标/生成点）
│   ├── BotProfileStore.java         **档案持久化**（bots.json）
│   └── MultiBotManager.java         **多智能体管理**（生成/移除/重生/tick/听聊天）
├── core/
│   ├── AutoLoop.java                自主循环状态机（每个智能体一个实例）
│   └── TaskPlan.java                结构化任务计划（任务栈 + 持久化）
└── command/
    └── AIBotCommand.java            /aibot 命令树（按名字操作）
```

---

## 12. 四个版本的关键差异（移植备忘）

| 差异点 | 1.20.1 | 1.21.1 | 1.21.11 | 26.3 |
|---|---|---|---|---|
| 资源 ID 类 | `ResourceLocation` | `ResourceLocation` | **`Identifier`** | `Identifier` |
| `ServerPlayer` 构造 | **3 参数**（无 `ClientInformation`） | 4 参数 | 4 参数 | 4 参数 |
| `ServerGamePacketListenerImpl` | **3 参数**（无 cookie） | 4 参数 | 4 参数 | 4 参数 |
| `placeNewPlayer` | **2 参数** | 3 参数 | 3 参数 | 3 参数 |
| `PlayerList.respawn` | **2 参数** | 3 参数 | 3 参数 | 3 参数 |
| `bot.level()` | 返回 `Level` | 返回 `Level` | 返回 `ServerLevel` | 返回 `ServerLevel` |
| 取得 `ServerLevel` | `serverLevel()` | `serverLevel()` | `level()` | `level()` |
| 命令权限 | `hasPermission(2)` | `hasPermission(2)` | `permissions().hasPermission(...)` | 同左 |
| 出生点 | `getSharedSpawnPos()` | `getSharedSpawnPos()` | `getRespawnData().pos()` | 同左 |
| 白天时间 | `getDayTime()` | `getDayTime()` | `getDayTime()` | **`getOverworldClockTime()`** |
| 食物判断 | `Item.isEdible()` | `Item.isEdible()` | `DataComponents.FOOD` | `DataComponents.FOOD` |
| 造成伤害 | `hurt(DamageSource, float)` | `hurt(...)` | `hurtServer(ServerLevel, ...)` | `hurtServer(...)` |
| 注册表读取 | `Registry.get(id)` | `Registry.get(id)` | `Registry.getValue(id)` | `Registry.getValue(id)` |
| `isSolidRender` | 需 `(BlockGetter, BlockPos)` | 需参数 | 无参数 | 无参数 |
| 物品堆比较 | `isSameItemSameTags` | `isSameItemSameComponents` | 同左 | 同左 |
| **配方产物** | `getResultItem(RegistryAccess)` | `getResultItem(HolderLookup.Provider)` | **`display().result().resolveForFirstStack(...)`** | 同左 |
| **配方材料** | `getIngredients()` | `getIngredients()` | **`placementInfo().ingredients()`** | 同左 |
| **配方容器** | 直接 `Recipe<?>` | `RecipeHolder<?>` | `RecipeHolder<?>` | `RecipeHolder<?>` |
| **Drop 物品** | `drop(stack, false)` | 同左 | 同左 | **`drop(stack, false, Prediction)`** |
| **挥手动画** | `swing(hand)` | `swing(hand)` | `swing(hand, boolean)` | **`swing(hand, SwingAnimation, boolean)`** |
| **睡觉** | `startSleepInBed(pos)` | 同左 | 同左 | **`startSleepInBed(bedBlock, state, BedRule, pos)`** |
| **手持槽位** | `Inventory.selected` 字段 | `Inventory.selected` 字段 | `setSelectedSlot()` / `getSelectedItem()` | 同左 |
| **建世界高度** | `getMinBuildHeight()` | `getMinBuildHeight()` | `getMinY()` / `getMaxY()` | `getMinY()` / `getMaxY()` |
| 服务器目录 | `getServerDirectory()` 返回 `File` | 返回 `Path` | 返回 `Path` | 返回 `Path` |
| 映射 | mojmap | mojmap | mojmap | **无映射（26.1+ 不再混淆）** |
| Loom 插件 | `fabric-loom-remap` | `fabric-loom-remap` | `fabric-loom-remap` | **`fabric-loom`** |
| 依赖配置 | `modImplementation` | `modImplementation` | `modImplementation` | **`implementation`** |

> 注：`handleBlockBreakAction` / `useItemOn` 的签名在四个版本里**一致**，
> 这正是「走原版输入通道」方案的好处 —— 核心执行逻辑几乎不用改。

---

## 13. 中文显示成问号（???）怎么解决

本文件是 **UTF-8**。如果显示成 `???`，是**打开它的程序**用了错误的编码（通常是简体中文
Windows 的默认 ANSI 代码页 **936 / GBK**），不是文件坏了。

先确认文件本身完好：

```bat
git show HEAD:README.md | more
```

如果这里中文正常，那文件没问题，按下面处理：

| 场景 | 解决 |
|---|---|
| **记事本（Notepad）** | 另存为时编码选 **UTF-8**；或直接用 VS Code 打开 |
| **VS Code** | 右下角点编码 → **Reopen with Encoding** → **UTF-8** |
| **Windows 终端 / CMD** | 先执行 `chcp 65001` 切到 UTF-8，再看输出 |
| **浏览器看 GitHub 页面** | 强制刷新 `Ctrl+F5`；仍乱码则是代理/CDN 改写，用 raw 直链：`https://raw.githubusercontent.com/Zxin-Pro/aibot-fabric/main/README.md` |
| **PowerShell 读文件** | 用 `Get-Content README.md -Encoding UTF8`，**不要**用默认读取 |

> 本次已把文件重写为 **UTF-8 带 BOM**，目的是让那些「靠猜编码」的编辑器
> 也能正确识别为 UTF-8。BOM 对 Markdown 渲染没有副作用。

---

## 14. 已知限制 / 后续可做

- **寻路是 A\*，但不是 Baritone**：能绕障、跳台阶、下落、游泳、挖穿、搭桥，
  也有卡住重新规划。相比 Baritone 仍缺：跨维度寻路、矿洞复杂立体的长距离规划、
  更省的代价函数（当前是曼哈顿距离 + 垂直惩罚）。
  搜索上限 4000 节点，超过 100 格以上的复杂地形可能规划失败（会明确报错，不会静默卡住）。
- **合成不等同于真人开界面**：假玩家没有客户端 GUI，合成是在服务端按真实配方规则结算
  （材料必须齐全、产物按配方给）。结果与真人一致，但它不会「打开合成台」。
- **不会主动加载区块**：没有客户端，所在区块若无其他玩家加载，
  状态加载会跳过（已做 `isLoaded` 防护）。
- **不再受「同时只有一个」限制**（v0.5.0 起）：可以同时跑多个智能体，
  各自独立实体、独立记忆、独立循环。上限由 `maxBots` 控制。
- **重生后实体不再是 `AIBotPlayer` 子类**：原版 `respawn` 会 new 普通 `ServerPlayer`，
  功能不受影响（所有执行器都按 `ServerPlayer` 编写）。
- **上下文压缩是有损的**：久远历史会被聚合成摘要，具体到「第 37 步挖了几个石头」
  这种细节会丢失。这是刻意的取舍 —— 用细节换「可以无限期运行」。
  调大 `contextWindow` 可保留更多细节，代价是 token 消耗上升。
- **摘要不调用 LLM**：用确定性规则聚合。好处是零额外成本且不破坏提示词缓存字节一致性，
  代价是摘要不如 LLM 写得自然。若你更看重摘要质量，可以自己接一个摘要调用，
  但**务必注意它会破坏缓存命中率**。
- **皮肤**：离线服务器上所有假玩家都是默认皮肤（原版机制限制）。
  正版服务器可在档案里填 `skinOwner` 借用某个正版玩家的外观。
  **不伪造 Mojang 皮肤签名**（那属于伪造正版身份）。
- **LLM 输出解析**：已做三重容错；若模型频繁输出非 JSON，
  建议强化 `StaticPrefix` 里的格式约束（注意保持前缀稳定以免掉缓存命中率）。
- **仅编译期验证过**：四个版本都能构建出可加载的 jar，但寻路、多智能体并发、
  上下文压缩、聊天回应的**运行时行为尚未实机测试**，请按第 8 节自行验证。
