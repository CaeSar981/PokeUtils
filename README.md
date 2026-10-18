# PokeUtils-remake

[![build](https://github.com/CaeSar981/PokeUtils/actions/workflows/build.yml/badge.svg)](https://github.com/CaeSar981/PokeUtils/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/CaeSar981/PokeUtils?display_name=tag)](https://github.com/CaeSar981/PokeUtils/releases)

面向 **Pixelmon 1.12.2** 服务端的精灵合成插件：两只同星级精灵 + 一个粘剂，合成出一只更高星级的精灵。

围绕这条主循环，插件内建了保底、属性继承、属性洗练、分解回收、粘剂合成、每日委托、合成图鉴
与合成券 —— 每一项都由 `config.yml` 里的一个开关控制，关掉之后不只是行为停用，连界面入口图标都不再绘制。

> **关于本仓库**：源码由原版 `PokeUtils-1.0.0-SNAPSHOT.jar` 反编译复原而来，并在此基础上做了大量增强。
> 插件名（`plugin.yml` 的 `name`）为 **`PokeUtils-remake`** 以便与原版共存区分；
> 主类、`/pu` 命令、权限节点与数据表结构都与原版一致，数据目录为 `plugins/PokeUtils-remake/`，
> 把旧目录搬过来即可无缝续用旧数据。

## 特性

**合成主流程**

- 界面化操作：`/pu compound` 打开，放入 2 只同星级精灵 + 1 个粘剂，产物星级 +1（最高 7 星）
- 基础成功率按星级递减：`(11 - 星级 - 3) × 10%`（1 星 70% → 6 星 20%，封顶 100%），粘剂与权限组可再加成
- 高星合成二次确认、个人冷却、失败返还部分金币；七星失败可用 VIP 权限保留一只精灵
- 达到阈值的合成会通过 MSGToggle 全服播报
- 连败保底：连败达到阈值后下一次必定成功，可按星级分档配置
- 产出预览：合成前就能看到目标档位的真实产出池

**养成与回收**

- **属性继承**：从父本继承性格 / 特性 / 个体值（个体值在父本区间内取点），闪光父本提高产物闪光概率
- **属性洗练**：重掷性格 / 特性 / 单项个体值，可开「只升不降」
- **分解回收**：把不要的精灵拆成金币 + 粘剂
- **粘剂合成**：低阶粘剂按配方换高阶粘剂
- **合成券**：持券时优先扣券、不扣金币；合成失败会退还（背包满则掉在脚下）

**日常与收集**

- **每日委托**：按 UUID + 日期确定性生成，进度按合成 / 分解 / 提交精灵推进，奖励金币、粘剂，可选发券
- **合成图鉴**：记录合成过的种族，按里程碑领取奖励
- **流水与榜单**：`/pu history`、`/pu stats`、`/pu top`

**工程侧**

- 三种存储后端：`sqlite`（默认，零配置）/ `mysql`（多服或外部报表）/ `yaml`（每玩家一个文本文件）
- 写入语义统一为「绝对值 upsert」，异步写回失败可安全重试；`/pu migrate` 可在三种后端之间整体搬家
- 界面渲染与查询路径不碰数据库：面板数据走后台线程池，委托进度走与写回同一个 FIFO 队列

## 环境要求

| 项 | 要求 |
| --- | --- |
| 服务端 | CatServer 1.12.2（需要能加载 Pixelmon 模组） |
| 模组 | Pixelmon 8.4.3 |
| Java | **8** |
| 前置插件 | `PokeStar`、`Vault`、`PokemonAPI`、`MSGToggle`（均需在 `plugin.yml` 里声明为 depend） |
| 经济 | 任意 Vault 兼容的经济插件 |

## 安装

1. 从 [Releases](https://github.com/CaeSar981/PokeUtils/releases) 下载 `PokeUtils-remake-<版本>.jar`
   （或按下方「构建」自行出包）
2. 放进服务端 `plugins/` 目录，启动一次
3. 需要时修改 `plugins/PokeUtils-remake/config.yml`，用 `/pu reload` 重载

默认配置是 SQLite，装好即可运行。想换存储后端：

```yaml
Storage:
  type: sqlite      # sqlite（默认）/ mysql / yaml
```

- `mysql`：多服共享数据、或用外部工具做报表时用；`Storage.mysql.*` 里配账号与表前缀
- `yaml`：每玩家一个 `plugins/PokeUtils-remake/yaml/players/<uuid>.yml`，记事本能直接看和改；
  **只支持单实例**（没有文件锁，两台服写同一份目录必然互相覆盖）

> ⚠️ 换 `Storage.type` 必须**重启服务端**（`/pu reload` 不会重连数据库）。三种后端之间是互相独立的数据，
> 搬家请用 `/pu migrate <sqlite|mysql|yaml> [dry-run]`。

## 功能开关

六个玩法与三个基础能力都有独立开关，全部默认开启。

**关掉之后入口图标也不再绘制** —— 那一格彻底留空（不填灰玻璃之类的占位物），点上去没有任何反应。
也就是说：界面上看不到的按钮，就是没开的功能，玩家不需要去点一下才知道。
`/pu <子命令>` 同样会被挡下，并回一句「XX功能已关闭」，不会静默无响应。

| 开关 | 关掉后的效果 |
| --- | --- |
| `Compound.inherit.enabled` | 主界面槽 20 的图标消失、该格留空；合成不再继承任何属性（产物属性完全随机） |
| `Compound.salvage.enabled` | 槽 21 的图标消失；`/pu salvage` 只提示关闭 |
| `Compound.reroll.enabled` | 槽 22 的图标消失；`/pu reroll` 只提示关闭，已开着的洗练面板不再响应 |
| `Compound.sticker-craft.enabled` | 槽 23 的图标消失；`/pu craft` 只提示关闭 |
| `Compound.quest.enabled` | 槽 24 的图标消失；`/pu quest` 只提示关闭，**合成 / 分解 / 粘剂合成都不再累计委托进度** |
| `Compound.dex.enabled` | 槽 25 的图标消失；`/pu dex` 只提示关闭，**合成成功不再记录图鉴** |
| `Compound.pc.enabled` | 选料界面不画「从电脑盒子选精灵」按钮（该格留白，点上去无反应） |
| `Compound.preview.enabled` | 主界面不画「产出预览」按钮 |
| `Compound.pity.enabled` | 关闭连败保底（提示也不会发） |
| `Compound.broadcast.enabled` | 关闭高星合成成功的全服播报 |
| `Compound.log.enabled` | 关闭合成流水日志（`/pu history`·`stats`·`top` 随之无数据） |

关掉开关**不会删数据**：图鉴记录、委托进度、已领里程碑都原样躺在库里，重新打开就接着用。

## 命令与权限

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/pu` | 显示帮助 | — |
| `/pu compound` | 打开合成界面 | `Compound.use-permission`（留空 = 人人可用） |
| `/pu g <玩家> <类型> <数量>` | 给予粘剂（支持**离线**玩家） | `pokeutils.admin` |
| `/pu take <玩家> <类型> <数量>` | 扣减粘剂（发错了可以撤回） | `pokeutils.admin` |
| `/pu bal [玩家]` | 查询粘剂余额与保底进度 | 查自己无需权限；查他人需 `pokeutils.admin` |
| `/pu history [玩家] [条数]` | 查合成流水（最近 N 条，默认 `Compound.history.size`） | 查自己无需权限；查他人需 `pokeutils.admin` |
| `/pu stats [玩家]` | 全服 / 指定玩家的聚合统计 | 查全服需 `pokeutils.admin` |
| `/pu top [count\|star]` | 合成排行榜：默认按成功次数，`star` 按最高星级（固定前 10） | — |
| `/pu debug` | 导出配置快照、产出池、存储状态 | `pokeutils.admin` |
| `/pu salvage`（别名 `fenjie`） | 打开「分解回收」界面，把精灵拆成金币 + 粘剂 | 同 `/pu compound` 的 `Compound.use-permission` |
| `/pu reroll`（别名 `xilian`） | 打开「属性洗练」界面，重掷性格 / 特性 / 单项个体值 | 同上 |
| `/pu quest`（别名 `daily` / `weituo`） | 打开「每日委托」界面，交付并领奖 | 同上 |
| `/pu dex`（别名 `tujian`） | 打开「合成图鉴」，领取里程碑奖励 | 同上 |
| `/pu craft`（别名 `stickercraft`） | 打开「粘剂合成」界面，用低阶粘剂换高阶粘剂 | 同上 |
| `/pu reload` | 重载配置文件（含粘剂定义） | `pokeutils.admin` |
| `/pu questreset <玩家\|all>` | 作废当天的每日委托（进度清零 + 标记已领，不补发） | `pokeutils.admin` |
| `/pu storage` | 存储后端健康度：缓存条目 / 待落盘 / 熔断状态 / 后端类型 | `pokeutils.admin` |
| `/pu migrate <sqlite\|mysql\|yaml> [dry-run]` | 把玩家数据整体搬到另一个后端（含粘剂、保底、**委托与图鉴**） | `pokeutils.admin` |

第四轮那五个命令都遵循同一条设计：**命令只负责「校验 + 打开界面」**，真正的操作在界面里完成。
它们都要针对「队伍里的某一只精灵」做事，而聊天栏里没法直观指认「第几只」；
用界面选就不会选错，也不用记参数顺序。

**Tab 补全**（第十轮新增）：`/pu` 全链路都有补全 —— 子命令、玩家名、粘剂键、数量、
`top` / `migrate` 的枚举参数。两条规则值得知道：

- **按权限与开关过滤**：没有 `pokeutils.admin` 的玩家看不到 `g` / `take` / `reload`；
  功能被 `Compound.*.enabled` 关掉时，对应子命令也不出现在候选里
  （与 `/pu` 无参帮助同一套规则 —— 否则等于在补全栏里推荐一个「敲下去只会说没权限」的命令）。
- **玩家名只补在线的**：离线名单要么查库、要么走 `Bukkit.getOfflinePlayer`（正版服会请求
  Mojang），都不能放在一个每秒可能触发多次的路径上。

权限节点已写进 `plugin.yml`（原插件一个都没声明）：

| 节点 | 默认 | 用途 |
| --- | --- | --- |
| `pokeutils.use` | `true` | 见 `Compound.use-permission` |
| `pokeutils.admin` | `op` | 管理指令 |
| `vip.yx` / `vip.cs` | `false` | 七星失败保留一只 |

## 依赖

| 依赖 | 类型 | 用途 |
| --- | --- | --- |
| Spigot API 1.12.2 | 服务端 | 基础 API |
| Pixelmon | 模组 | 精灵数据、精灵槽、精灵工厂 |
| Vault | 插件 | 金币读写 |
| PokeStar | 插件 | 星级查询、星级显示名、抽卡池 |
| PokemonAPI | 插件 | 精灵名称、精灵贴图、发放精灵、自定义事件 |
| Blank038API | 插件 | `Blank038API.getPokemonAPI()` 入口 —— **与 PokemonAPI 同属一个 jar**（真实 PokemonAPI 的主类就是它） |
| MSGToggle | 插件 | 全服流水播报 |

> 注意：原 `plugin.yml` 的依赖声明为 `PokeStar / Vault / PokeStatsAPI / PokemonAPI / MSGToggle / PokeQuest`，
> 其中 `PokeQuest` 与 `PokeStatsAPI` 已在本版本中移除（见 [`docs/开发说明.md`](docs/开发说明.md)）。
> `PokeStatsAPI` 是**挂名依赖**（声明了但字节码零引用），移除后不影响任何行为，
> 也不再需要测试服里那个占位替身。

## 配置参考

完整键位、默认值与逐行注释见
[`plugin/src/main/resources/config.yml`](plugin/src/main/resources/config.yml)。
下面按轮次汇总新增键，方便对照升级。

> ⚠️ **升级 jar 但沿用旧 `config.yml` 时**：新增的段若在旧文件里不存在，用的是**代码里的字面量默认值**，
> 而不是 jar 内置 `config.yml` 的值 —— 所以两者必须逐位一致，否则该键会静默失效（详见 [`docs/修复记录.md`](docs/修复记录.md) 第 19 条）。

## 配置一览（第三轮新增键）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `Compound.material-count` | `2` | 需要的同星级材料数量（1 或 2） |
| `Compound.sticker-required` | `true` | 是否必须放粘剂；`false` 时粘剂变可选加成 |
| `Compound.refund.mode` | `money` | `money` / `sticker` / `both` |
| `Compound.refund.sticker-type` | `PT` | 送哪种粘剂（键名取自 `Compound.stickers`） |
| `Compound.refund.sticker-amount` | `1` | 送几个 |
| `Compound.refund.min-star` | `0` | 材料星级 ≥ 本值才送（0 = 不限） |
| `Compound.refund.voucher-on-failure` | `true` | 用券失败时退还券（背包满则掉在脚下） |
| `Compound.groups` | `[]` | 权限组折扣与加成，示例见 `config.yml` 注释 |
| `Compound.vouchers` | `[]` | 合成券定义，示例见 `config.yml` 注释 |
| `Compound.preview.enabled` | `true` | 是否显示「产出预览」按钮 |
| `Compound.preview.page-size` | `45` | 预览每页条数 |
| `Compound.pity.notify` | `chat` | `chat` / `actionbar` / `both` / `none` |
| `Compound.pity.message` | 见 `config.yml` | 保底提示文案，占位符 `{streak}` / `{threshold}` / `{left}` |
| `Compound.log.keep-days` | `30` | 开机清理超过 N 天的日志（0 = 不清理） |
| `Compound.history.size` | `10` | `/pu history` 默认条数（上限 50） |
| `Compound.history.max-scan-lines` | `50000` | `/pu stats`、`/pu top` 最多往回扫多少行 |
| `Compound.feedback.sounds.open` | `BLOCK_CHEST_OPEN` | 开界面；**留空 = 不发声** |
| `Compound.feedback.sounds.select` | `UI_BUTTON_CLICK` | 选择精灵 / 粘剂 |
| `Compound.feedback.sounds.success` | `ENTITY_PLAYER_LEVELUP` | 合成成功 |
| `Compound.feedback.sounds.fail` | `BLOCK_ANVIL_LAND` | 合成失败 |
| `Compound.feedback.sounds.volume` | `1.0` | 音量 |
| `Compound.feedback.sounds.pitch` | `1.0` | 音调 |
| `Compound.feedback.title.enabled` | `true` | 成功 Title 开关（关掉不影响音效） |
| `Compound.feedback.title.title` | `§a合成成功` | 主标题 |
| `Compound.feedback.title.subtitle` | `§f{star} §f{pokemon}` | 副标题，占位符 `{star}` / `{pokemon}` |
| `Compound.feedback.title.fade-in` / `stay` / `fade-out` | `5` / `30` / `10` | Title 时长（tick） |

## 配置一览（第四轮新增键）

> 同样遵守修复记录第 19 条的约定：**代码里的字面量默认值必须与出厂 `config.yml` 完全一致**，
> 否则「换了 jar 但沿用旧 config.yml」的服上，这些键会静默失效。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `Compound.inherit.enabled` | `true` | 关掉后槽 20 点击只提示「功能已关闭」，合成退回「产物属性完全随机」 |
| `Compound.inherit.max-picks` | `1` | 一次最多同时继承几项（性格 / 特性 / 个体值）；`0` = 关闭 |
| `Compound.inherit.iv-percent.min` / `.max` | `50` / `100` | 个体值在「父本区间」里按比例取点的上下限 |
| `Compound.inherit.shiny.single-bonus` | `5` | 单只父本闪光带来的产物闪光加成（%） |
| `Compound.inherit.shiny.double-bonus` | `25` | 两只父本都闪光时的加成（%） |
| `Compound.reroll.enabled` | `true` | 洗练开关 |
| `Compound.reroll.price` | `20000` | 每次洗练金币 |
| `Compound.reroll.sticker-type` | `PT` | 每次洗练消耗的粘剂（键名取自 `Compound.stickers`） |
| `Compound.reroll.sticker-amount` | `1` | 消耗几个（`0` 则只扣金币） |
| `Compound.reroll.iv-min` / `.max` | `0` / `31` | 单项 IV 重掷区间（Pixelmon 合法区间 0~31） |
| `Compound.reroll.improve-only` | `false` | `true` = 只保留更好的结果（纯提升，不会洗坏） |
| `Compound.salvage.enabled` | `true` | 分解开关 |
| `Compound.salvage.sticker.<星级>` | `1:0 2:0 3:1 4:2 5:3 6:5 7:8` | 按星级返还的粘剂张数（没写的星级按 0） |
| `Compound.salvage.sticker-type` | `PT` | 返还哪种粘剂 |
| `Compound.salvage.money-percent` | `10` | 金币返还 = 该星级合成价格的百分之几 |
| `Compound.sticker-craft.enabled` | `true` | 粘剂合成开关 |
| `Compound.sticker-craft.recipes[]` | 1 条 `PT→JL` 示例 | 每条 = `from` / `to` / `amount` / `result` / `money` |
| `Compound.quest.enabled` | `true` | 每日委托开关 |
| `Compound.quest.daily-amount` | `3` | 每天生成几条 |
| `Compound.quest.keep-days` | `7` | 委托记录保留天数（含今天）；启用时异步清掉更早的行，`0` = 不清理 |
| `Compound.quest.types.<TYPE>.weight` | 见 `config.yml` | 生成权重（`0` = 不生成该类） |
| `Compound.quest.types.<TYPE>.targets` | 见 `config.yml` | 目标次数档次；`SUBMIT` 另有 `star` 门槛 |
| `Compound.quest.types.<TYPE>.display` | 见 `config.yml` | 文案，占位符 `{target}` / `{star}` |
| `Compound.quest.reward.money` | `20000` | 单条委托的金币奖励 |
| `Compound.quest.reward.sticker-type` / `.sticker-amount` | `PT` / `2` | 单条委托的粘剂奖励 |
| `Compound.dex.enabled` | `true` | 合成图鉴开关 |
| `Compound.dex.rewards.<种类数>` | `5` / `10` / `20` 三档 | 每档 = `money` / `sticker-type` / `sticker-amount`；每档只能领一次 |

## 配置一览（第十五轮新增键）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `Compound.reward-voucher.quest` | `""` | 委托领奖时额外发的券；填 `Compound.vouchers` 里某张券的 `display` / `name` / 材质名。**留空 = 不发** |
| `Compound.reward-voucher.dex` | `""` | 图鉴里程碑领奖时额外发的券；填法同上，留空 = 不发 |
| `Compound.reward-voucher.amount` | `1` | 上面两种奖励各发几张 |

## 配置一览（第七轮新增键）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `Compound.pc.enabled` | `true` | 是否允许从电脑盒子选材料。关掉后选料界面**不画**入口按钮（那一格留白，点上去无反应） |
| `Compound.pity.threshold-by-star` | `{}` | 按原料星级档单独定保底阈值，例如 `1: 3` / `6: 8`；**没列出的档位回退到 `threshold` 单值** |

## 配置一览（存储 / 后台）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `Storage.type` | `sqlite` | `sqlite` / `mysql` / `yaml` |
| `Storage.sqlite.file` | `pokeutils.db` | 数据库文件（相对路径基于插件数据目录，也可写绝对路径） |
| `Storage.sqlite.checkpoint-minutes` | `10` | 把 WAL 合并回主库并截断的间隔（`0` = 不开启） |
| `Storage.yaml.dir` | `yaml` | YAML 后端的数据根目录，实际文件在 `<dir>/players/<uuid>.yml`（见第 38 条） |
| `Storage.mysql.host` / `port` / `database` / `user` / `password` | `127.0.0.1` / `3306` / `pokeutils` / `root` / 空 | 连接参数 |
| `Storage.mysql.table-prefix` | `pu_` | 表名前缀，用来和同库其它插件隔开 |
| `Storage.mysql.use-ssl` / `server-timezone` | `false` / 空 | MySQL 8 的 cj 驱动通常要填时区（如 `Asia/Shanghai`） |
| `Storage.mysql.pool-size` | `4` | 连接池上限（写入走单线程，4 足够） |
| `Storage.mysql.connect-timeout-ms` / `borrow-timeout-ms` | `5000` / `3000` | 建连接 / 借连接的等待上限 |
| `Storage.mysql.fallback-to-sqlite` | `true` | 连不上时是否降到本地 SQLite。**`true` 意味着带着另一份数据上线**，两边会分叉；要「宁可不可用也不带错数据」就改 `false` |
| `Storage.async.write-behind` | `auto` | `auto`（本地同步写穿 / 网络异步写回）/ `always` / `never` |
| `Storage.async.flush-timeout-ms` | `5000` | 关服时最多等多久让写回队列排空 |
| `Storage.async.worker-threads` | `2` | 后台计算线程数（重建流水索引、重载配置） |

## 数据文件

- `plugins/PokeUtils-remake/config.yml`：
  - `Info` —— GUI 内「使用方法」书本的文案（名称 + lore；lore 里写 `{price}` 占位符的
    那一行会在打开界面时展开成各档价格表）
  - `VIP.keep-one` —— 七星合成失败时「保留一只精灵」的权限节点
  - `Compound` —— 合成数值：`price`（各档金币消耗）、`base-rate`（各档基础成功率）、
    `refund-percent`（失败补偿比例）、`pity`（保底开关 / 阈值 / 提示方式 / 文案）、
    `stickers`（粘剂定义）、`vouchers`（合成券定义）、`broadcast`（播报开关 / 阈值 / 文案）、
    `cooldown-ms`（合成冷却）、`confirm-min-star`（二次确认门槛）、
    `material-count` / `sticker-required`（配方）、`groups`（权限组折扣与加成）、
    `refund`（失败补偿方式）、`preview`（产出预览）、`log`（日志开关与保留天数）、
    `history`（`/pu history`、`/pu stats` 行为）、`feedback`（音效与成功 Title）、
    `use-permission`（开界面所需权限）。
    **整段缺失时全部回落到与原实现逐位一致的默认值**；
    ⚠️ 但「新增段缺失」时用的是**代码里写的字面量默认值**，不是 jar 内置 `config.yml` ——
    所以两者必须一致，详见修复记录第 19 条
  - `Storage` —— 存储后端：`type`（`sqlite` / `mysql` / `yaml`）、
    `sqlite.file` 与 `sqlite.checkpoint-minutes`（WAL 定期合并，0 = 关）、
    `yaml.dir`（每玩家一个文本文件，见 [`docs/修复记录.md`](docs/修复记录.md) 第 38 条）、
    `mysql.*`（host / port / database / user / password / table-prefix / use-ssl /
    server-timezone / pool-size / connect-timeout-ms / borrow-timeout-ms）、
    `async`（`write-behind` = `auto` / `always` / `never`、`flush-timeout-ms`、
    `worker-threads`）。**改 `type` 需要重启服务端**（`/pu reload` 不会重连数据库），
    且不同后端里是几份互相独立的数据。详见修复记录第 20~27 条
- `plugins/PokeUtils-remake/pokeutils.db`：SQLite 数据库（`pu_player` / `pu_sticker` 两张表）。
  用 MySQL / YAML 后端时这个文件不参与读写（但会保留在原地，切回 SQLite 时数据还在）
- `plugins/PokeUtils-remake/yaml/players/<uuid>.yml`：YAML 后端的数据文件，一个玩家一个文件。
  可以直接用记事本打开、直接改（改完让玩家重进一次服即生效）。
  切到别的后端时这些文件同样保留在原地，不会自动删除
- `plugins/PokeUtils-remake/Data/<玩家名>.yml`：**仅存在于升级前的旧版本**；
  玩家进服时被导入数据库并归档到 `plugins/PokeUtils-remake/Data-migrated/`（归档而非删除）
- `plugins/PokeUtils-remake/logs/compound-<日期>.log`：合成流水日志
  （句柄常开、写后即刷；写入排在插件自己的单线程写队列上，见第 24 条）

> `config.yml` 只在改配置 / `/pu reload` 时读取；而玩家数据**变更即入库**
> —— 同步写穿模式下写入返回时已经在盘上，异步写回模式下进入单线程队列并在
> 退服 / 关服时排空（见修复记录第 9、17、21 条）。
>
> 合成流水日志（`logs/compound-<日期>.log`）只在**启动时**读一次（建内存索引），
> 之后 `/pu history`·`/pu stats`·`/pu top` 全部读内存，不再碰磁盘（见第 22 条）。

## 构建

需要 **JDK 8**。本仓库不依赖 Maven（`pom.xml` 只是留给 IDE 与习惯 Maven 的人），
`build.sh` 用 `javac` + `jar` 直出，产物语义等价于 `mvn package`：

```bash
./build.sh           # 只构建，产物在 dist/
./build.sh deploy    # 构建并同步到 ../TestServer/plugins/
```

首次构建需要先准备两个第三方二进制（按约定都不入库）：

| 文件 | 来源 | 用途 |
| --- | --- | --- |
| `libs/sqlite-jdbc-3.42.0.0.jar` | Maven Central `org.xerial:sqlite-jdbc` | SQLite 后端驱动，裁剪 + 重定位后 shade 进产物 |
| `$HOME/.m2/repository/.../spigot-api-1.12.2-R0.1-SNAPSHOT.jar` | Spigot 快照仓库 | 编译期 API |

路径都可以用环境变量改：`SQLITE_JAR`、`SPIGOT_API_JAR`、`JDK_HOME`、`PYTHON`。
脚本会自动识别运行环境（Git Bash / Linux / macOS），不需要改脚本本身。

脚本做的事：

- `stub-api` 只参与编译期 classpath，**不进产物**（等价于 `provided`）；
- **SQLite 驱动**手工 shade 进产物，分三步：
  1. 展开 jar，只保留 `Windows/x86_64`、`Windows/x86`、`Linux/x86_64`、`Linux/x86`
     四个平台的 native（13 MB → 约 2.3 MB；`SQLITE_ALL_PLATFORMS=1` 可保留全部）；
  2. `tools/relocate.py` 做**纯字节等长重定位** —— `org/sqlite`、`org.sqlite`、`org_sqlite`
     三种写法都改到 `pokeshaded`（恰好都是 10 个字符，可以直接替换字节而不动常量池），
     JNI 符号一并处理。这一步不是可选项：CatServer 自带 `sqlite-jdbc-3.21`，
     不重定位就会被父加载器抢走，运行期直接 `NoSuchMethodError`；
  3. 重新打包、重命名包目录、清理空父目录；
- 结束前三项自检：第三方桩混进产物数应为 **0**、驱动 class/native 数应 **> 0**、
  重定位残留的 `org/sqlite` 条目数应为 **0**。

## 自动构建与发版

`.github/workflows/build.yml` 在 GitHub 上自动出包，**不需要自己装 JDK**：

| 触发 | 行为 |
| --- | --- |
| push 到 `main` / 提交 PR | 编译一次，jar 作为 Actions artifact 保留 30 天 |
| push 一个 `v*` 标签 | 编译，并把 jar + `.sha256` 挂到同名 Release（已存在则覆盖附件） |
| push 到 `main` 且 `plugin.yml` 的 `version` 变了 | 自动打 `v<版本>` 标签并发版 |
| 手动 Run workflow | 只构建 |

也就是说，**发版的唯一动作是改 `plugin/src/main/resources/plugin.yml` 里的 `version`**，
然后 push 到 `main`。工作流会现场下载 spigot-api 快照与 sqlite-jdbc（这两个二进制按约定不入库），
再调用 `build.sh`，所以 CI 与本地构建走的是同一套脚本、同一套自检。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [`docs/修复记录.md`](docs/修复记录.md) | 逐条修复记录：每个坑的现象、根因与改法。排查线上问题先从这份开始 |
| [`docs/开发说明.md`](docs/开发说明.md) | 目录结构与每个类的职责、与原版 jar 的字节码级还原度比对、编译桩原理、与真实依赖的接口核对 |
| [`plugin/src/main/resources/config.yml`](plugin/src/main/resources/config.yml) | 全部配置键的默认值与原位注释（最权威的参考） |

## 说明

本项目为对既有 jar 的逆向复原，并在其基础上继续维护与增强，仅用于学习、维护与二次开发。
