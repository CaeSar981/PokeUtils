# PokeUtils-remake

[![build](https://github.com/CaeSar981/PokeUtils/actions/workflows/build.yml/badge.svg)](https://github.com/CaeSar981/PokeUtils/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/CaeSar981/PokeUtils?display_name=tag)](https://github.com/CaeSar981/PokeUtils/releases)

面向 **Pixelmon 1.12.2** 服务端的精灵合成插件：两只同星级精灵 + 一个粘剂，合成出一只更高星级的精灵。

围绕这条主循环，内建保底、属性继承、属性洗练、分解回收、粘剂合成、每日委托、合成图鉴与合成券，
每一项都由 `config.yml` 里的一个开关控制 —— 关掉之后不只是行为停用，连界面入口图标都不再绘制。

## 特性

**合成主流程** —— 界面化操作（`/pu compound`），产物星级 +1（上限 7 星）；基础成功率按星级递减
`(11 - 星级 - 3) × 10%`（1 星 70% → 6 星 20%），粘剂与权限组可再加成；带高星二次确认、个人冷却、
失败返还、七星失败保留一只（VIP）、全服播报与连败保底（可按星级分档）。

**养成与回收** —— 属性继承（性格 / 特性 / 个体值，闪光父本提高产物闪光率）、属性洗练
（支持「只升不降」）、分解回收（拆成金币 + 粘剂）、粘剂合成（低阶换高阶）、合成券
（优先扣券、失败退还）。

**日常与收集** —— 每日委托（按 UUID + 日期确定性生成，进度随合成 / 分解 / 提交推进）、
合成图鉴（按里程碑领奖）、流水与榜单（`/pu history` · `stats` · `top`）。

**工程侧** —— 三种存储后端（SQLite / MySQL / YAML），`/pu migrate` 可整体搬家；
写入统一为「绝对值 upsert」，异步写回失败可安全重试；界面渲染与查询路径不碰数据库
（面板读走后台线程池，委托进度走与写回同一个 FIFO 队列）。

## 环境要求

| 项 | 要求 |
| --- | --- |
| 服务端 | CatServer 1.12.2（需要能加载 Pixelmon 模组） |
| 模组 | Pixelmon 8.4.3 |
| Java | **8** |
| 前置插件 | `PokeStar`、`Vault`、`PokemonAPI`、`MSGToggle` |
| 经济 | 任意 Vault 兼容的经济插件 |

## 安装

1. 从 [Releases](https://github.com/CaeSar981/PokeUtils/releases) 下载 `PokeUtils-remake-<版本>.jar`
   （或按下方「构建」自行出包）
2. 放进服务端 `plugins/` 目录，启动一次
3. 需要时修改 `plugins/PokeUtils-remake/config.yml`，用 `/pu reload` 重载

默认 SQLite，装好即可运行。换存储后端只需改一处：

```yaml
Storage:
  type: sqlite      # sqlite（默认）/ mysql / yaml
```

- `mysql`：多服共享数据、或用外部工具做报表；连接参数在 `Storage.mysql.*`
- `yaml`：每玩家一个 `plugins/PokeUtils-remake/yaml/players/<uuid>.yml`，记事本能直接看和改；
  **只支持单实例**（没有文件锁，两台服写同一份目录必然互相覆盖）

> ⚠️ 换 `Storage.type` 必须**重启服务端**（`/pu reload` 不会重连数据库）。三种后端之间是互相独立的数据，
> 搬家请用 `/pu migrate <sqlite|mysql|yaml> [dry-run]`。

## 功能开关

六个玩法与五个基础能力都有独立开关，全部默认开启。

**关掉之后入口图标也不再绘制** —— 那一格彻底留空（不填灰玻璃之类的占位物），点上去没有任何反应；
`/pu <子命令>` 同样会被挡下并回一句「XX功能已关闭」，不会静默无响应。

| 开关 | 关掉后的效果 |
| --- | --- |
| `Compound.inherit.enabled` | 槽 20 留空；合成不再继承任何属性 |
| `Compound.salvage.enabled` | 槽 21 留空；`/pu salvage` 只提示关闭 |
| `Compound.reroll.enabled` | 槽 22 留空；`/pu reroll` 只提示关闭，已打开的面板不再响应 |
| `Compound.sticker-craft.enabled` | 槽 23 留空；`/pu craft` 只提示关闭 |
| `Compound.quest.enabled` | 槽 24 留空；`/pu quest` 只提示关闭，合成 / 分解 / 粘剂合成不再累计进度 |
| `Compound.dex.enabled` | 槽 25 留空；`/pu dex` 只提示关闭，合成成功不再记录图鉴 |
| `Compound.pc.enabled` | 选料界面不画「从电脑盒子选精灵」按钮 |
| `Compound.preview.enabled` | 主界面不画「产出预览」按钮 |
| `Compound.pity.enabled` | 关闭连败保底（提示也不发） |
| `Compound.broadcast.enabled` | 关闭高星合成成功的全服播报 |
| `Compound.log.enabled` | 关闭合成流水日志（`history` / `stats` / `top` 随之无数据） |

关掉开关**不会删数据**：图鉴记录、委托进度、已领里程碑原样躺在库里，重新打开就接着用。

## 命令与权限

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/pu compound` | 打开合成界面 | `Compound.use-permission`（留空 = 人人可用） |
| `/pu salvage`（`fenjie`） | 分解回收界面 | 同上 |
| `/pu reroll`（`xilian`） | 属性洗练界面 | 同上 |
| `/pu quest`（`daily` / `weituo`） | 每日委托界面 | 同上 |
| `/pu dex`（`tujian`） | 合成图鉴界面 | 同上 |
| `/pu craft`（`stickercraft`） | 粘剂合成界面 | 同上 |
| `/pu bal [玩家]` | 粘剂余额与保底进度 | 查自己免权限；查他人需 `pokeutils.admin` |
| `/pu history [玩家] [条数]` | 合成流水（默认 `Compound.history.size` 条） | 同上 |
| `/pu stats [玩家]` | 聚合统计 | 查全服需 `pokeutils.admin` |
| `/pu top [count\|star]` | 合成排行榜（固定前 10） | — |
| `/pu g <玩家> <类型> <数量>` | 给予粘剂（支持**离线**玩家） | `pokeutils.admin` |
| `/pu take <玩家> <类型> <数量>` | 扣减粘剂（发错了可以撤回） | `pokeutils.admin` |
| `/pu reload` | 重载配置（含粘剂定义） | `pokeutils.admin` |
| `/pu questreset <玩家\|all>` | 作废当天委托（清零 + 标记已领，不补发） | `pokeutils.admin` |
| `/pu storage` | 后端健康度：缓存 / 待落盘 / 熔断 / 后端类型 | `pokeutils.admin` |
| `/pu migrate <sqlite\|mysql\|yaml> [dry-run]` | 整体搬家（含粘剂、保底、委托、图鉴） | `pokeutils.admin` |
| `/pu debug` | 导出配置快照、产出池、存储状态 | `pokeutils.admin` |

玩法入口那六个命令只负责「校验 + 打开界面」，真正的操作在界面里完成 —— 它们都要针对
「队伍里的某一只精灵」做事，聊天栏没法直观指认「第几只」，用界面选不会选错。

`/pu` 全链路支持 Tab 补全，候选按权限与开关过滤；玩家名只补在线的（离线名单要查库 / 请求 Mojang，
不能放在高频路径上）。

权限节点已在 `plugin.yml` 中声明：

| 节点 | 默认 | 用途 |
| --- | --- | --- |
| `pokeutils.use` | `true` | 见 `Compound.use-permission` |
| `pokeutils.admin` | `op` | 管理指令 |
| `vip.yx` / `vip.cs` | `false` | 七星失败保留一只 |

## 构建

需要 **JDK 8**。本仓库不依赖 Maven（`pom.xml` 只是留给 IDE 与习惯 Maven 的人），
`build.sh` 用 `javac` + `jar` 直出，产物语义等价于 `mvn package`：

```bash
./build.sh           # 只构建，产物在 dist/
./build.sh deploy    # 构建并同步到 ../TestServer/plugins/
```

首次构建需自备两个第三方二进制（按约定都不入库）：

| 文件 | 来源 |
| --- | --- |
| `libs/sqlite-jdbc-3.42.0.0.jar` | Maven Central `org.xerial:sqlite-jdbc`，裁剪 + 重定位后 shade 进产物 |
| `spigot-api-1.12.2-R0.1-SNAPSHOT.jar` | Spigot 快照仓库，仅编译期使用 |

路径可用环境变量改：`SQLITE_JAR`、`SPIGOT_API_JAR`、`JDK_HOME`、`PYTHON`。
脚本自动识别 Git Bash / Linux / macOS，不用改脚本本身。

几个要点：`stub-api` 只参与编译期 classpath、**不进产物**；SQLite 驱动手工 shade 进产物
（先裁到 4 个平台的 native，再用 `tools/relocate.py` 做**纯字节等长重定位**到 `pokeshaded` ——
这一步不是可选项，CatServer 自带 `sqlite-jdbc-3.21`，不重定位会被父加载器抢走，
运行期直接 `NoSuchMethodError`）；结束前自检三项：桩混进产物数 **0**、驱动 class/native **> 0**、
重定位残留 **0**。

## 自动构建与发版

`.github/workflows/build.yml` 在 GitHub 上自动出包，**不需要本地装 JDK**：

| 触发 | 行为 |
| --- | --- |
| push 到 `main` / 提交 PR | 编译一次，jar 作为 artifact 保留 400 天（平台上限） |
| push 到 `main`（任意提交） | 额外把产物挂到滚动预发布 [`latest`](https://github.com/CaeSar981/PokeUtils/releases/tag/latest)，附件**不过期** |
| push 一个 `v*` 标签 | 编译，并把 jar + `.sha256` 挂到同名 Release（已存在则覆盖附件） |
| push 到 `main` 且 `plugin.yml` 的 `version` 变了 | 自动打 `v<版本>` 标签并发版 |
| 手动 Run workflow | 只构建 |

**发版的唯一动作**：改 `plugin/src/main/resources/plugin.yml` 里的 `version`，然后 push 到 `main`。
工作流会现场下载 spigot-api 快照与 sqlite-jdbc（这两个二进制不入库），再调用 `build.sh`，
因此 CI 与本地构建走的是同一套脚本、同一套自检。

> 想拿最新构建又不想翻 Actions 的话，用固定链接：
> `https://github.com/CaeSar981/PokeUtils/releases/download/latest/PokeUtils-remake-latest.jar`
> —— 每次 push 到 `main` 自动覆盖，指向当次构建。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [`docs/配置参考.md`](docs/配置参考.md) | 全部配置键按轮次汇总的默认值与说明、数据文件布局 |
| [`docs/修复记录.md`](docs/修复记录.md) | 逐条修复记录：每个坑的现象、根因与改法。排查线上问题先从这份开始 |
| [`docs/开发说明.md`](docs/开发说明.md) | 目录结构与类的职责、编译桩原理、与依赖插件的接口核对 |
| [`config.yml`](plugin/src/main/resources/config.yml) | 全部配置键的默认值与原位注释（最权威） |
