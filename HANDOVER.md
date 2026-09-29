# 交接说明（HANDOVER.md）

**项目**：https://github.com/Zxin-Pro/aibot-fabric
**当前版本**：v0.6.0（开发中，未打 tag / 未发 Release）
**交接时间**：2026-09-29
**状态**：核心功能已完成，8 条版本线全部通过 CI 构建

---

## 一、这个项目要做什么（需求原文）

> 参考 minecraft-numen 这个模组，给我仓库上的 AI 机器人模组更新一下。
>
> 1. **不需要面板**，要干净的样子
> 2. 只需要这个 AI 能**一直玩下去**
> 3. 配置文件配置好之后，玩家用指令创建好 AI 之后，它就**可以自己去玩了**
> 4. **不需要额外的对话**，他自己靠着对游戏的理解让他自己去玩
> 5. **Token 消耗必须大部分 99% 的要命中缓存**
> 6. 版本做**全覆盖**（1.20.1 到现在的最新正式版）
> 7. 做完上传 GitHub，**更新一个中版本**

**需求澄清（在对话中确认过的）**：

- 第 4 点不是「砍掉对话能力」，而是「**真人玩家不干预的情况下也能自主玩**」。
  对话能力保留（玩家搭话能回），但没人管时它也得自己玩下去。
- API **要自定义**，因为用户用的是**中转站**（不是直连官方 API）。
- 编译推 GitHub Actions（本地开发沙箱跑不了 JDK 21）。

---

## 二、已完成的工作

### 2.1 功能改造（对应需求 2/3/4）

| 需求 | 实现 | 关键文件 |
|---|---|---|
| 一直玩下去 | 无条件步数上限 + 死亡自动重生 + 上下文压缩 | `AutoLoop.java`、`ContextCompressor.java` |
| 无人干预自主玩 | `autonomousMode` 提示词 + 空闲自愈 + 目标自举 | `AutoLoop.requestDecision()`、`PromptBuilder` |
| 一条命令开玩 | `autoStartOnSpawn`（spawn 后自动开循环） | `MultiBotManager.spawn()`、`AIBotCommand.doSpawn()` |
| 对话保留但可选 | `allowChat` / `chatCooldownSteps`；默认不刷屏 | `AIConfig`、`AutoLoop` |
| 干净（无面板） | 本来就没有 GUI；新增 `chatAnnounceLevel` 默认 0（静默） | `AIConfig` |

**空闲自愈是这个需求的核心**：没有它，模型在无目标时会反复输出 `idle` 发呆。
实现在 `AutoLoop.requestDecision()` 里，检测连续 idle / 长期无进展后注入强制指令。

### 2.2 中转站适配（对应需求「API 自定义」）

| 组件 | 作用 |
|---|---|
| `CacheUsageParser.java`（新增） | 8 种缓存字段方言自适应；未上报时如实标记而非谎报 0 |
| `UpstreamTracker.java`（新增） | 检测中转站多上游轮询（命中率低的隐蔽主因） |
| `LLMClient.java` | 自定义请求头、上游识别、usage 原文留存、超时/重试可调 |
| `AIConfig.java` | 单价可配、`cacheHitField` 手动覆盖、`extraHeaders` |

### 2.3 缓存分层（对应需求 5）

**原理**：服务端前缀缓存「从第一个不同字节开始全部失效」，所以要把变化点尽量后移。

提示词从 3 条消息拆成 4 条：

```
[0] system   静态系统提示词            ← 永不变，全 bot 共享
[1] user     静态动作协议              ← 永不变，全 bot 共享
[2] user     慢变层（身份 + 长期经验）  ← 每会话/若干步才变
[3] user     快变层（状态/计划/反馈）   ← 每轮都变
```

- `StaticPrefix.java`：静态前缀从 ~1200 token 扩到 **2288 token**
- `PromptBuilder.java`：分层构建，身份移入慢变层
- 测试证明：身份变化不影响前两层（多 bot 可共享缓存）

### 2.4 版本覆盖（对应需求 6）

**8 条版本线，覆盖 1.20 ~ 26.3 全部正式版**：

| 目录 | 编译 MC | 覆盖范围 | Java | CI |
|---|---|---|---|---|
| `fab-1.20.1` | 1.20.1 | 1.20 ~ 1.20.1 | 17 | ✅ |
| `fab-1.20.6` | 1.20.6 | 1.20.2 ~ 1.20.6 | 21 | ✅ |
| `fab-1.21.1` | 1.21.1 | 1.21 ~ 1.21.1 | 21 | ✅ |
| `fab-1.21.4` | 1.21.4 | 1.21.2 ~ 1.21.4 | 21 | ✅ |
| `fab-1.21.8` | 1.21.8 | 1.21.5 ~ 1.21.8 | 21 | ✅ |
| `fab-1.21.11` | 1.21.11 | 1.21.9 ~ 1.21.11 | 21 | ✅ |
| `fab-26.1.2` | 26.1.2 | 26.1 ~ 26.1.2 | 25 | ✅ |
| `fab-26.3` | 26.3 | 26.2 ~ 26.3 | 25 | ✅ |

**1.20 之前的版本（1.19.4 / 1.19.2 / 1.18.1 / 1.16.5 / 1.14.4）未实现**，
`version-matrix.yml` 里已写好适配要点，状态为 `pending`。
如需支持，按那里的 note 逐条做（工作量较大，见第四节）。

### 2.5 工程改进

- **`.github/workflows/build.yml`**（新增）：矩阵构建，从 `version-matrix.yml` 自动读取
- **`version-matrix.yml`**（新增）：版本矩阵与适配要点，改这里就能增减构建目标
- **`tools/generate-module.py`**（新增）：从模板生成新版本线骨架
- **`tools/sync-shared.sh`**（新增）：把纯逻辑文件同步到各版本线
- **`tests/`**（新增）：3 组独立单测，57 项断言，全部通过
  - `CacheParserTest.java`（19 项）：缓存字段方言解析
  - `CacheStatsTest.java`（14 项）：命中率计算不被未上报污染
  - `PromptBuilderTest.java`（24 项）：静态前缀字节稳定性

---

## 三、关键设计决策（接手必读）

### 3.1 为什么必须 8 个独立模块，而不是一份代码

Minecraft 的 API 在这些版本里反复大改，且**差异不可调和**：

| 断代 | 关键差异 |
|---|---|
| 1.20.1 ~ 1.21.1 | 旧体系：`BuiltInRegistries.get()` 返回实体、`isSolidRender(level,pos)`、`Inventory.selected` 公开、`hasPermission(int)` |
| **1.21.4** | **过渡态**：已改新架构（`getValue()`、`isSolidRender()` 无参）但**仍是旧命名**（`ResourceLocation`、`getSelected()`、`hasPermission(int)`）；`ServerPlayer.level()` 未协变 |
| 1.21.8 | 同上，但 `ServerPlayer.level()` 已协变 |
| 1.21.11 | `ResourceLocation` → **`Identifier`**；`permissions().hasPermission(...)`；`getRespawnData()` |
| 26.x | 不再混淆，**不写 mappings**（写了会配置失败）；`swing` 参数、`AbstractBedBlock`、`BedRule` 等继续变化 |

**特别注意 1.21.4**：它是最容易搞错的一代，不能简单当成 1.21.1 或 1.21.11 处理。

### 3.2 为什么编译必须走 CI

本地开发沙箱（PRoot）**无法运行 JDK 21+**（`Failed to mark memory page as executable`，
JIT 需要 mmap 可执行页，被 PRoot 拦截）。JDK 17 能跑但 Loom 1.17.x 要求 21+。

所以：**任何编译验证都必须推 GitHub Actions**。
workflow 会自动装 JDK 17/21/25 并用 foojay 解析 toolchain。

### 3.3 缓存设计的硬性约束

**修改 `StaticPrefix.java` 里的任何字符串都会导致线上缓存全部冷启动一次**。
启动日志会打印指纹（`len=3970 hash=3fe6039` 之类），改动前后对比即可确认。

**纪律**（违反即缓存失效）：
- 静态前缀里**禁止**出现时间戳、随机数、坐标、玩家名、UUID
- **禁止**拼接运行时变量
- 动作 Schema 的字段顺序必须固定
- 身份（名字/性格）**必须**放慢变层，不能进静态前缀（否则每个 bot 都从第 3 条分叉）

### 3.4 那些踩过的坑（已修，别改回去）

1. **`maxStepsPerSession <= 0` 不能被改成 200**
   它表示「无上限」。`ConfigStore.normalize()` 里有注释警告，
   历史上这个 bug 让「一直玩下去」的承诺静默失效。

2. **`ContextCompressor` 的去重必须用单调高水位线**
   用 `pending` 末尾元素判断会「倒退」（pending 批量清空后末尾变小），
   导致老记录被重复吸收、摘要出现重复内容。

3. **`CacheStats` 必须区分「未上报」和「0 命中」**
   未上报的请求不能进命中率分母，否则中转站用户看到的是假的很低命中率。

4. **26.x 不能写 `loom.officialMojangMappings()`**
   Mojang 从 26.x 起不再发布 `client_mappings`，写了会配置阶段就失败：
   `Failed to find official mojang mappings for 26.1.2`

5. **26.1.2 必须用 Java 25**
   它的 Minecraft 类文件已是 v69（Java 25），用 Java 21 会报
   `class file has wrong version 69.0, should be 65.0`

---

## 四、剩余工作

### 4.1 必须做的（发版前）

- [ ] **打 tag 并发布 Release**
      （`dist/` 已被 `.gitignore` 忽略，jar 通过 Release 分发）
      当前 `gradle.properties` 已是 `version=0.6.0`
- [ ] **决定是否删除 `dist/` 里的 0.4.0 老 jar**
      （该目录已被忽略但文件仍在仓库里）
- [ ] **实机测试**：目前只验证了编译通过，**未在真实服务器跑过**
      建议至少测：`/aibot spawn` → 观察它是否自己动起来 → 死亡后是否自动重生
- [ ] 更新 `README.md` 里的版本号引用（部分章节还写着 v0.5.0）

### 4.2 可选：扩展到 1.20 之前的版本

`version-matrix.yml` 里已写好 5 个待实现断代及其适配要点。
**注意**：这些不能靠改几行适配完成，需要逐文件重写。

| 断代 | 难度 | 说明 |
|---|---|---|
| 1.19.4 | 中 | `ServerPlayerEntity` → `ServerPlayer` 大剥离 |
| 1.19.2 | 中高 | 仍是 `ServerPlayerEntity` 时代，聊天事件签名不同 |
| 1.18.1 | 中高 | 同上，命令 API 是 v2 callback 早期版本 |
| 1.16.5 | 高 | Java 8、`new Identifier(ns,path)`、Registry 访问完全不同 |
| 1.14.4 | 很高 | 建议作为独立项目，不与现有代码共享 |

**建议**：如果目标用户是主流服务器（1.20+），当前 8 条线已经够用，
不必做这些老版本。

### 4.3 可选：缓存命中率的进一步优化

理论上前缀已经做到最长、变化点已尽量后移。若要继续提升：

1. **让慢变层真正「慢变」**：目前长期记忆每次动作都写，
   可以改成累计 N 次才更新一次摘要（减少慢变层被击穿的概率）
2. **给状态 JSON 做差分预告**：把变化频率更低的字段（如维度、玩家名）
   从快变层挪到慢变层
3. **验证服务器切块边界**：DeepSeek 按 64 token 对齐切块，
   可以微调静态前缀长度使其正好落在块边界上（收益很小，不值得）

**更重要的是先诊断中转站**（见 `README.md` 0.55.5 节）。

---

## 五、常用操作

### 5.1 构建

**本地（需要 JDK 21+）**：

```bash
cd fab-1.21.1
./gradlew build          # 产物在 build/libs/
```

**CI（推荐）**：

```bash
# 全量构建
gh workflow run build.yml

# 只构建指定模块
gh workflow run build.yml -f only=fab-1.21.1

# 查看结果
gh run list --limit 3
```

或直接在 GitHub 网页上点 Actions → Build All Versions → Run workflow。

### 5.2 修改现有版本线

**改纯逻辑代码**（缓存/提示词/配置/记忆等）——改 `fab-1.21.1` 然后同步：

```bash
./tools/sync-shared.sh          # 自动同步 17 个纯逻辑文件到其他版本线
```

**改版本相关代码**（`ActionExecutor` / `TickActionDriver` / `StateCollector` 等）——
必须**逐个版本手工适配**，因为 API 不同：

```bash
./tools/sync-shared.sh --list   # 查看哪些文件是纯逻辑、哪些需要手工适配
```

### 5.3 新增版本线

```bash
python3 tools/generate-module.py fab-1.21.6 1.21.6 "0.128.2+1.21.6" 21 1.17.19
```

然后：
1. 按该版本的 API 适配源码（可用 Mojang mappings 核对，见下）
2. 把 `version-matrix.yml` 里对应条目的 `status` 改为 `ready`
3. 推送，看 CI 报什么错，逐个修

### 5.4 查某个版本的 API（核对类名/方法名）

```bash
# 拿 client_mappings（注意：26.x 没有这个文件）
curl -s https://piston-meta.mojang.com/mc/game/version_manifest_v2.json -o vm.json
python3 -c "
import json,urllib.request
d=json.load(open('vm.json'))
u=[v['url'] for v in d['versions'] if v['id']=='1.21.8'][0]
j=json.load(urllib.request.urlopen(u))
print(j['downloads']['client_mappings']['url'])
"
# 下载后用 grep 查（proguard 格式：官方名 -> 混淆名）
```

**注意**：26.x 的 `downloads` 里只有 `client` / `server`，没有 `client_mappings`。

### 5.5 调试缓存

游戏内命令（需要 OP）：

```
/aibot cache probe       # 看服务端返回的 usage 原文（最直接）
/aibot cache upstream    # 诊断是不是中转站轮询
/aibot cache stats       # 命中率与费用
/aibot prompt            # 提示词分层与静态前缀指纹
```

### 5.6 跑单元测试（不需要 Minecraft）

```bash
# 需要 gson
curl -sL -o /tmp/gson.jar https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar

# 3 个测试类，放在 tests/ 目录，需要放到 com/example/aibot/llm/ 路径下编译
javac -cp /tmp/gson.jar -d /tmp/t \
  fab-1.21.1/src/main/java/com/example/aibot/llm/*.java \
  fab-1.21.1/src/main/java/com/example/aibot/memory/*.java \
  tests/*.java
java -cp /tmp/gson.jar:/tmp/t com.example.aibot.llm.CacheParserTest
```

---

## 六、配置项速查（v0.6.0 新增项）

| 配置项 | 默认 | 说明 |
|---|---|---|
| `autonomousMode` | `true` | 无人干预时自主游玩 |
| `autoStartOnSpawn` | `true` | spawn 后自动开启循环 |
| `defaultGoal` | 空 | 默认目标；留空则让 AI 自己决定 |
| `allowChat` | `true` | 是否允许主动说话 |
| `chatCooldownSteps` | `20` | 说话最小间隔（步） |
| `chatAnnounceLevel` | `0` | 0=静默 / 1=仅重要事件 / 2=全部 |
| `extraHeaders` | 空 | 自定义请求头，`\n` 分隔多行 |
| `cacheHitField` | 空 | 手动指定缓存字段名（留空=自动探测） |
| `pricePerMillionInputMiss` | 2.0 | 未命中输入单价 |
| `pricePerMillionInputHit` | 0.2 | 命中输入单价 |
| `pricePerMillionOutput` | 8.0 | 输出单价 |
| `requestTimeoutMs` | 45000 | 超时（中转站慢，默认放宽） |
| `connectTimeoutSeconds` | 15 | 连接超时 |

完整列表：`/aibot config show`

---

## 七、当前 Git 状态

**分支**：`main`
**最新提交**：见 `git log --oneline -10`

**重要提交**：
- `2a98cc8` feat!: 自主模式 + 中转站适配 + 缓存分层（核心改造）
- `4265cda` feat: 同步到 4 条版本线
- `e943c7c` feat: 新增 4 条版本线骨架
- 后续若干 `fix:` 提交流是各版本的 API 适配（由 CI 发现并逐个修正）

**未打 tag**：需要发布时自行打 `v0.6.0`

---

## 八、如果卡住了

1. **CI 编译失败** → 看 job 日志里的 `error:`，用 5.4 的方法查该版本的正确 API
2. **缓存命中率低** → 先 `/aibot cache upstream` 确认中转站是否轮询
3. **AI 不动** → 检查 `/aibot status`：是否 `[自主中]`、API 配置是否完整
4. **IDE 里一片红** → 正常，版本相关文件在别的版本线下 API 不同，以 CI 为准
