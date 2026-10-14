# PokeUtils

Pixelmon（1.12.2）服务器的**精灵合成**插件。由原始 jar `PokeUtils-1.0.0-SNAPSHOT.jar` 反编译复原而来，
源码逻辑与行为与原插件一致。

> **插件名（`plugin.yml` 的 `name`）为 `PokeUtils-remake`**，用于与原版 jar 区分。
> 主类、命令（`/pu`）、包名、权限节点与数据表结构**均未改动**；
> 数据目录相应变为 `plugins/PokeUtils-remake/`（从旧目录迁移即可无缝续用旧数据）。

> **本版本已在原版基础上移除 PokeQuest 依赖**，同时去掉了合成时向 PokeQuest 上报任务进度的调用。
> 另外移除了 `PokeStatsAPI` 硬依赖 —— 它是个**挂名依赖**：原版 `plugin.yml` 声明了它，
> 但整份字节码对 `com.mc9y.pokestatsapi` 零引用（连反射都没有）。

## 功能

- `/pu compound`（别名 `pu` / `putils` / `pokeu`）打开「精灵合成」GUI
- 放入 **2 只同星级精灵** + **1 个粘剂**，消耗金币合成出 **1 只高一星级** 的精灵
- 粘剂：普通粘剂（`PT`，+10% 成功率）、精良粘剂（`JL`，+30% 成功率）；
  类型、显示名、材料、加成、槽位全部可在 `config.yml` 的 `Compound.stickers` 里增删改
- 基础成功率按星级递减：`(11 - min(星级, 7) - 3) × 10 %`，最高 100%
- 合成失败补偿所耗金币的 20%；**七星合成失败时**，拥有 `vip.yx` / `vip.cs`
  （可在 `config.yml` 的 `VIP.keep-one` 配置）的玩家可保留一只精灵
- 合成达到 `Compound.broadcast.min-star`（默认 6）星时通过 MSGToggle 发送全服流水播报，
  文案可配
- 连续失败保底：连败达到 `Compound.pity.threshold` 次后下一次必定成功
- 高星合成二次确认、合成冷却、每次合成都写一条结构化日志（见修复记录第 12～16 条）

第三轮增强（见修复记录第 18 条，A/B/C/D 四组共 14 条）：

- **界面反馈**：开界面 / 选择 / 成功 / 失败四处音效，以及成功时的屏幕 Title，音效名与
  Title 文案、时长都在 `config.yml` 的 `Compound.feedback` 里配；留空表示该处不发声
- **产出预览**：合成主界面槽 26 的「产出预览」按钮，按目标档位列出真实 PokeStar 产出表
- **`/pu history`**：查某个玩家的合成流水（成功 / 失败 / 星级 / 消耗 / 返还）
- **`/pu stats`**：全服聚合统计（总次数、成功率、金币消耗与返还、分星级明细）
- **`/pu top`**：合成排行榜，可按成功次数或最高星级排序
- **`/pu debug`**：一次性导出配置快照 / 产出池 / 存储状态，排查用
- **权限组折扣与加成**：`Compound.groups` 里按权限节点配金币折扣与成功率加成
- **合成券**：`Compound.vouchers`，背包里有匹配的券时优先扣券、不扣金币（活动 / 补偿 / 礼包）
- **失败补偿可改为送粘剂**：`Compound.refund.mode` = `money` / `sticker` / `both`
- **配方可配**：`Compound.material-count`（1 或 2 只材料）、`Compound.sticker-required`
- **保底进度提示方式可配**：`Compound.pity.notify` = `chat` / `actionbar` / `both` / `none`
- **启动自检产出池**：开机就把空档位点名报出来，不再等玩家撞上去
- **日志保留策略**：`Compound.log.keep-days`，开机清理过期日志
- **同 tick 防重复点击**：连点器在同一下里塞多次点击也只会结算一次

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
| `/pu top [success\|star] [条数]` | 合成排行榜（按成功次数 / 最高星级） | — |
| `/pu debug` | 导出配置快照、产出池、存储状态 | `pokeutils.admin` |
| `/pu reload` | 重载配置文件（含粘剂定义） | `pokeutils.admin` |

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
> 其中 `PokeQuest` 与 `PokeStatsAPI` 已在本版本中移除（见下方「还原度验证」）。
> `PokeStatsAPI` 是**挂名依赖**（声明了但字节码零引用），移除后不影响任何行为，
> 也不再需要测试服里那个占位替身。

## 目录结构

```
PokeUtils/
├── pom.xml                     父 POM
├── build.sh                    无 Maven 时的等价构建脚本（javac + jar + SQLite 驱动 shade）
├── libs/                       放置真实第三方插件 jar（可选）
│   └── sqlite-jdbc-3.42.0.0.jar  SQLite 驱动（会被裁 native + 重定位后 shade 进产物）
├── tools/
│   └── relocate.py             纯字节重定位：把 SQLite 驱动包名改到 pokeshaded（含 JNI 符号）
├── dist/                       已构建好的插件 jar
├── reference/                  原始 jar 与原始反编译结果（留档对照）
│   ├── PokeUtils-1.0.0-SNAPSHOT.original.jar
│   ├── decompiled-raw/         CFR 直接输出（含反编译标记，未整理）
│   └── decompiled-vineflower/  Vineflower 输出（结构与原源码最接近，推荐对照用）
├── stub-api/                   仅用于编译的第三方 API 桩（不会打进插件）
│   └── src/main/java/...
└── plugin/                     插件本体
    ├── pom.xml
    └── src/main/
        ├── java/com/mc9y/pokeutils/
        │   ├── PokeUtils.java                主类（命令 / 经济 / 数据加载 / 粘剂定义缓存）
        │   ├── data/PlayerData.java          合成会话与三个 GUI
        │   ├── data/YSData.java              玩家粘剂数据的薄门面（转发到 Storage）
        │   ├── data/Storage.java             存储契约（写入语义 = 绝对值，便于异步重试）
        │   ├── data/SqliteStorage.java       SQLite 后端（裸后端，autocommit + WAL）
        │   ├── data/MysqlStorage.java        MySQL 后端（轻量连接池 + 反射驱动直连）
        │   ├── data/CachedStorage.java       门面：内存缓存 + 同步写穿 / 异步写回 + 退避熔断 + 兜底扫描
        │   ├── data/StorageFactory.java      按 Storage.* 配置建后端；连不上就降级 SQLite
        │   ├── data/StorageException.java    写失败时抛出（读失败降级留痕，写失败不静默）
        │   ├── data/Async.java               两个后台线程池（单线程写队列 + 通用计算）
        │   ├── data/LogIndex.java            合成流水的内存索引（/pu history·stats·top 的数据源）
        │   ├── data/LogWriter.java           流水日志落盘器（句柄常开、写后即刷，跑在写队列上）
        │   ├── data/LegacyDataMigration.java 旧版 Data/<玩家名>.yml -> 数据库 迁移 + 归档
        │   ├── data/StickerType.java         粘剂定义（读 config.yml 的 Compound.stickers）
        │   ├── data/VoucherType.java         合成券定义（读 config.yml 的 Compound.vouchers）
        │   ├── data/CompoundLog.java         合成流水日志（写 logs/、解析与聚合）
        │   └── listener/PokeCompoundPlayerListener.java  点击 / 拖拽 / 进出服 / 关界面
        └── resources/
            ├── plugin.yml
            └── config.yml
```

## 还原度验证

重建结果已与原 jar 做了**字节码级**逐项比对，结果如下：

| 校验项 | 结果 |
| --- | --- |
| 类文件清单（6 个 class） | 完全一致 |
| 每个类的字段 / 方法 / 构造器签名 | 完全一致 |
| 每个类的常量池字符串（含全部中文与颜色代码） | 除 PokeQuest 相关 6 条外完全一致 |
| 每个方法的**字节码指令序列**（指令多重集，忽略分支目标数值） | 除 PokeQuest 调用的 13 条指令外完全一致 |
| 每个方法的**局部变量表**（变量名 / 类型 / 槽位编号） | 完全一致 |
| 外部类型调用指令种类（`invokevirtual` ↔ `invokeinterface`） | 完全一致 |
| `config.yml` | 字节级完全一致 |
| `plugin.yml` | 少 `- PokeQuest` 与 `- PokeStatsAPI` 两行 |
| 反编译源码比对（Vineflower） | `PlayerData` / `YSData` / `PokeUtils` **逐字节一致**；`PokeCompoundPlayerListener` **仅差 PokeQuest 两行** |
| 依赖桩是否误打进产物 | 无（`provided` 作用域保证） |
| 类加载 / 超类型解析 | 通过 |

当前版本与原 jar 的实质差异，除**特意移除 PokeQuest 与挂名的 PokeStatsAPI** 外，还有**十一处刻意的缺陷修复**，
外加**两项新功能**（详见下一节「修复记录」）：

1. `plugin.yml`：`depend` 列表去掉 `- PokeQuest` 与 `- PokeStatsAPI`

   - 去掉 `- PokeQuest` 是配套第 2 条（不再上报任务进度）；
   - 去掉 `- PokeStatsAPI` 是清理**挂名依赖**：`strings` 扫过全部 class，常量池里连
     `com.mc9y.pokestatsapi` 这个字符串都不存在，属零引用清理，行为完全不变。
2. `PokeCompoundPlayerListener`：去掉 `Main.getQuestManager().submitQuest(p.getName(), "Compound", star1 + "")`

   该调用无返回值，原用于向 PokeQuest 上报 `Compound` 任务进度；移除后对合成逻辑、扣费、
   成功率判定、精灵发放与播报均无影响。
3. `star1 < 6` → `star1 < 7`（`PlayerData#checkJL` 与监听器各一处）：让「六星 → 七星」档位与其
   VIP 保留分支真正可达
4. `onInventoryClose`：会话移除延后一 tick 判定，界面被「切换」时不再误删会话
5. `config.yml`：新增 `VIP.keep-one` 权限节点配置（缺省值与原行为一致）
6. 产出池为空预检：在**扣金币 / 扣粘剂 / 删精灵之前**判定 `getStarPokemon(star1 + 1)`，
   为空则中止本次合成并提示玩家
7. 新增 `onInventoryDrag`：补上**拖拽事件**的拦截。原插件只注册了 `InventoryClickEvent`，
   而拖拽在 Bukkit 里是独立事件，点击的 `setCancelled(true)` 拦不住它 —— 玩家能把物品
   拖进那 45 格里，关掉界面后物品随之消失
8. 队伍槽位失效防护：界面开着时所选槽位上的精灵可能已被移走，原实现
   `pps.get(slot).getSpecies()` 直接解引用会抛空指针（监听器 + `PlayerData#openGui` / `#checkJL`）
9. 0 星防护：PokeStar 对未被 `stars.yml` 收录的种族返回 0 星，而 `0 == 0 && 0 < 7` 成立会让
   两只这样的精灵**不花金币**（`price[0] = 0`）、以 80% 成功率成交（监听器 + `checkJL`）
10. `/pu` 命令健壮性：`/pu g` 的判空判在了 `args[1]` 这个 String 上（玩家离线必 NPE）、
    `Integer.parseInt` 未包 try、粘剂数据缺失时空指针；`/pu compound` 由控制台执行时
    强转 `(Player) sender` 会 `ClassCastException`
11. **粘剂数据变更即落盘**：原来只有 `onDisable` 与 `PlayerQuitEvent` 会写盘，服务端被
    `taskkill /F` 结束（不走 `onDisable`）时，整段会话的粘剂变动会静默回滚
12. `PlayerData#openYSGui` **从异步改回主线程**：原来整段包在 `runTaskAsynchronously` 里，
    最后一句 `p.openInventory(inv)` 是在调度线程上开界面，并且顺带并发读普通 `HashMap`
13. **数值配置化**：各档金币消耗、基础成功率、粘剂加成、失败补偿比例全部从代码里抽到
    `config.yml` 的 `Compound` 段（读不到时回退默认值，与原实现逐位一致）

另新增两项功能（非修复）：

- **连续失败保底** —— 连败达到 `Compound.pity.threshold` 次后，下一次必定成功；
  计数按玩家持久化，成功后清零。可用 `Compound.pity.enabled` 关闭。
- **`/pu reload` 现在真的能调数值** —— 上面 13 条抽出来的那些值改完 reload 即可生效。

> 也就是说，上表里的「字节码 / 源码逐项一致」描述的是**移除 PokeQuest 之后、修这些缺陷之前**
> 的状态；修复点均在源码中以「修复」注释单独标出，可随时回退出对照。

> 留存于 `reference/` 的原始 jar 与反编译输出**保持原样未改动**，以便随时对照。

## 修复记录

以下几处是**原插件就存在**的缺陷，反编译复原时先如实复刻，随后修掉，
并在 `../TestServer/` 的端到端自动测试里准备了回归用例
（F / G / I 组，以及后加的 J 组 —— J 组四条对应下面第 5~8 节，
也就是顶部清单里的第 7~10 条；K 组四条对应下面第 9~11 节，即清单里的第 11~13 条）。

### 1. `star1 == 6` 是死代码，整档「六星 → 七星」不可达

`config.yml` 明码标价「`§3六星` -> `§c七星` 消耗 1000000 金币」，`PlayerData` 里的价格表
`{0, 1000, 10000, 30000, 100000, 400000, 1000000}` 第 6 项也正是它，但两处判断都写的是
`star1 < 6`，把 `star1 == 6` 挡在门外，于是：

- `checkJL` 对六星对子算出 `jl = 0` → 六转七永远没有基础成功率；
- 失败补偿里的 `if (star1 == 6) { 保留一只精灵 }` 永远执行不到（能进来时 `star1 ≤ 5`），
  `config.yml` 那句「如果合成七星失败, 优秀训练师/传说训练师将会保留一只精灵」**从不生效**。

**修法**：两处 `star1 < 6` → `star1 < 7`；「星级超限」提示由「不能超过五星」改为「不能超过六星」。
同一分支里写重的 `p.hasPermission("vip.yx") || p.hasPermission("vip.yx")` 一并修掉 ——
它对应配置文案里的「优秀训练师 / 传说训练师」两个身份，第二个节点改名后已无从得知，
现改为可配置项 `config.yml → VIP.keep-one`（缺省 `vip.yx`、`vip.cs`）。

### 2. 界面开着时重复 `/pu compound` 会清空合成会话

`onInventoryClose` 里有「关掉合成界面且不在选择态 → 移除会话」，而 `openGui` 的顺序是
「先 put 会话、再 `openInventory`」；`openInventory` 会先关掉玩家已打开的旧界面并**同步**
派发 close 事件，于是刚建好的新会话被当成「玩家放弃合成」删掉 ——
界面还开着，但之后每一次点击都被静默忽略。

**修法**：把这段移除**延后一 tick 再判定**：若玩家此刻仍开着本插件的某个界面，
说明是「切换界面」而非「放弃合成」，此时保留会话。

### 3. 权限节点写重（见上文第 1 条末尾）

### 4. 产出池为空时，玩家会在「已被扣光」之后才吃到异常

拿到产出池后的那句是**直接按下标取**的：

```java
List<String> starPMs = psa.getStarPokemon(star1 + 1);
String rpmn = starPMs.get((int) (Math.random() * starPMs.size())).replace("Ho-Oh", "HoOh");
```

`getStarPokemon(int)` 在档位缺失时返回 `Collections.emptyList()`（**不是 null**），
所以空池不会在 `size()` 处暴露，而是一路走到 `get(0)` 才抛
`IndexOutOfBoundsException` —— 此时执行顺序已经过了
「扣金币 → 扣粘剂 → 关界面 → 删掉两只精灵」，玩家净亏，控制台只留一行报错。

现实触发条件就是上面 PokeStar 的 `star: 4m`：只要 `stars.yml` 里某一档被写丢或写成非数字，
该档整体消失，玩家做那一档合成就会踩中。

**修法**：取值后立刻判空，为空则在**动任何资产之前**中止：

```java
List<String> starPMs = psa.getStarPokemon(star1 + 1);
if (starPMs == null || starPMs.isEmpty()) {
    p.sendMessage("§3精灵合成 §f|| §c目标 " + (star1 + 1) + " 星档位的产出池为空, 已中止合成, 未扣除任何物品!");
    return;
}
```

后面成功分支**复用这一份** `starPMs`，不再二次调用 `getStarPokemon`，
避免两次调用之间数据变动导致「校验的池」和「使用的池」不一致。
提示文案只用档位数字，不引用 `getPokeShowName(star1 + 1)` —— 档位缺失时它返回空串，
正是本分支要处理的情况（引用它反而会得到一句残缺的提示）。

> 这是**治标**的兜底：真正的根因是依赖数据，修 `stars.yml` 才是治本。
> 两道一起上，才既能修好当前环境，又保证以后数据再次损坏时玩家不吃亏。

### 5. 拖拽事件完全没拦 —— 物品可以拖进界面后被吞掉

原插件只注册了 `InventoryClickEvent` 与 `InventoryCloseEvent`。
**在 Bukkit 里「拖拽」是独立于「点击」的事件**，`InventoryClickEvent#setCancelled(true)`
对它没有任何约束力；而合成界面是用 `Bukkit.createInventory(null, 45, ...)` 造出来的
**真实容器**，不是假界面。于是：把一个物品拖到那 45 格里的任意一格 → 关掉界面 → 物品没了
（既不退回背包，也不落地）。这不是理论风险，是任何玩家十秒钟就能试出来的。

**修法**：补一个 `onInventoryDrag`，标题命中本插件三个界面之一、且拖拽落点
（`rawSlot < 顶部界面尺寸`）在本插件界面内时 `setCancelled(true)`。
**只拦落在界面内的那部分** —— 玩家在自己背包范围内整理物品的拖拽不受影响，
一刀切全 cancel 只会平白惹人烦。

### 6. 队伍槽位失效会抛空指针

```java
int star1 = psa.getPokemonStar(pps.get(pd.getSlot1()).getSpecies().name());   // ← 直接解引用
```

`pd.getSlot1()` 存的是**队伍下标**，而那只精灵随时可能不在原位：

- 界面开着的时候，玩家/其他插件把队伍里的精灵移走、放生、换位；
- 或者玩家连点两次「确认合成」，而第一次已经把那两只删掉了。

这时 `pps.get(slot)` 返回 `null`，`.getSpecies()` 直接抛 `NullPointerException`。
更糟的是同一条路径上还有第二处 `null` 入口：`openGui` 渲染槽位时
`getSpriteItem(pokemon)`、以及 `checkJL` 里的 `pm1.getSpecies()` ——
也就是说**重开界面本身就会炸**，而不是只有点合成才炸。

**修法**：三处都改成「取出来先判空」。

- 监听器：判空后重置两个槽位、提示「所选槽位上的精灵已不存在, 请重新选择!」并关掉界面
  （让玩家回到一个干净状态，而不是停在显示着幽灵精灵的界面上）；
- `openGui`：为 `null` 时显示「请选择精灵」占位，并把失效槽位重置为 `-1`；
- `checkJL`：`pm1`/`pm2` 任一为 `null` 就把基础成功率留成 0。

### 7. 未被 `stars.yml` 收录的种族可以**不花金币**合成

`PokeStarAPI#getPokemonStar` 的字节码是
`STAR_DATA.stream().filter(d -> d.hasPokemon(name)).map(...).findAny().orElse(0)` ——
**没被任何一档收录的种族一律返回 0 星**。而合成判定是：

```java
if (star1 == star2) {            // 0 == 0 → 成立
    if (star1 < 7) {             // 0 < 7 → 成立
        int money = price[star1];  // price[0] = 0  ← 一分钱不花
```

同时 `checkJL` 会算出 `jl = (11 - 0 - 3) * 10 = 80%`。也就是说：**两只「未收录」精灵
= 免费换一只一星精灵**，成功率还高达 80%。这不是臆测 —— 端到端测试里的 J0 步实测过
Pixelmon 8.4.3：

```
J0 EnumSpecies 共 906 种；PokeStar 判为 0 星的 1 种：MissingNo
```

`MissingNo` 是一个真实存在的 `EnumSpecies`（游戏里可以正常抓到/刷出），
但 Pixelmon 的 `BaseStats.allBaseStats` 里没有它，于是 PokeStar 建分档时
`es.getBaseStats()` 取空被 `catch` 跳过（见 PokeStar 的 `ModuleImpl#initialize`），
它就此掉出所有星级名单。

**修法**：`star1 < 1 || star2 < 1` 直接拒绝，提示「所选精灵未被星级系统收录, 无法参与合成!」；
`checkJL` 同步加上 `star1 >= 1`，避免界面先显示一个假的 80% 成功率。
1 星是星级系统的最低合法档位，`0` 一律按「未收录」处理。

### 8. `/pu` 命令的几处空指针（顺带）

```java
String player = args[1];                    // String，永远不为 null
Player p = Bukkit.getPlayer(player);
if (player == null || !p.isOnline()) {      // ← 判的是 String，真正解引用的 p 反而没判
```

玩家离线时 `p == null`，这一行**必 NPE** —— 原判断形同虚设。同一段还有两处：

- `Integer.parseInt(args[3])` 没有兜底，`/pu g <玩家> PT abc` 就是一条 `NumberFormatException`；
- `this.ysdatas.get(player).deposit(...)`：`ysdatas` 只在 `onEnable` 与 `PlayerJoinEvent`
  里灌数据，缺失时同样空指针。

另外 `/pu compound` 直接 `(Player) sender`，控制台敲一次就 `ClassCastException`。

**修法**：判空对象改成 `p`；`parseInt` 包 try 并提示「数量必须是整数！」；
粘剂数据缺失时提示而不是崩；`compound` 前先判 `sender instanceof Player`。

### 9. 粘剂数据只在玩家「正常退服」时落盘

`YSData#save()` 全项目只有两个调用点：`PokeUtils#onDisable` 与
`PokeCompoundPlayerListener#onPlayerQuit`。

单看没问题，但**强制结束服务端时 `onDisable` 不会执行** —— `taskkill /F`、崩溃、
断电都算（本机的关服方式恰好就是 `taskkill /F`）。于是只要玩家没正常退服，
这一整个会话里 `/pu g` 发的粘剂、合成消耗的粘剂，全部静默回滚到上一次退服时的数量。

**修法**：`deposit` / `withdraw`（以及保底计数）每次变更都同步 `save()`。单个数据文件
只有两三个整数，同步写盘的开销可以忽略，换来的是数据不再存在「未落盘窗口」。
K 组直接读磁盘上那份 yml 来核对（而不是读内存里的对象），确保测的是「真的写下去了」。

### 10. `openYSGui` 在异步线程里开界面

```java
new BukkitRunnable() {
    public void run() {
        ...
        inv.setItem(19, dj1);            // 读 PokeUtils.ysdatas —— 普通 HashMap
        p.openInventory(inv);            // ← 打开界面是要走主线程的
    }
}.runTaskAsynchronously(PokeUtils.getInstance());
```

两处隐患：`openInventory` 会操作玩家的窗口状态并派发事件，属于主线程职责；而这段代码
还从异步线程读 `ysdatas` 这个普通 `HashMap`，与主线程的 `put` / `remove` 并发。
多数时候侥幸不出错，属于「迟早会遇到一次的偶发」。

**修法**：改成 `runTask`。这段逻辑本身没有任何耗时操作（不像主界面那样要为每只精灵取贴图），
延后一 tick 的时序不变，但全部回到主线程；顺带给粘剂数据补了判空。
K 组用一个代理 `Player` 记录 `openInventory` 被调用时的线程，实测为
`Server thread / isPrimaryThread = true`。

### 11. 价格与成功率全写死在代码里

| 数值 | 原来的位置 |
| --- | --- |
| 各档金币消耗 `{0,1000,10000,30000,100000,400000,1000000}` | 监听器内联数组 |
| 基础成功率公式 `(11 - 星级 - 3) * 10` | `PlayerData#checkJL` |
| 粘剂加成 `10 / 30` | `PlayerData` 三处 + 界面文案 |
| 失败补偿 `20%` | 监听器内联 |

`/pu reload` 只重载了 `Info.lore` 与 `VIP.keep-one`，**改一个数值就得重新编译**。

**修法**：全部抽到 `config.yml` 的 `Compound` 段；读取时带默认值，默认值与上面逐位一致，
所以**没有 `Compound` 段的旧配置文件行为完全不变**。改完 `/pu reload` 即可生效。
K 组是端到端验证的：把 `Compound.base-rate.1` 改成 5 → 界面上的成功率真的变成
`5% + 10% = 15%`；把 `Compound.price.1` 改成 1234 → 一次成功合成真的只扣 1234 金币。

### 新增功能：连续失败保底

连败容易把人劝退，所以加了一个可配置的保底：同一玩家连续失败达到
`Compound.pity.threshold`（默认 5）次后，**下一次必定成功**。

- 计数存在数据库的 `pu_player.fail_streak` 字段里（见修复记录第 17 条），
  换服 / 重启都不丢；
- 成功（无论是否由保底触发）后清零，失败则 +1；
- 触发时会给一条明确提示，不会让玩家误以为是运气；
- 界面上的「连续失败」进度条会实时显示 `当前 / 阈值`；
- `Compound.pity.enabled: false` 或 `threshold: 0` 即可关闭。

K 组覆盖了三条路径：未达阈值时 0% 照常失败且计数 +1、达到阈值时 0% 也成功且清零、
关掉开关后即使计数达标也照常失败。

### 12. 粘剂类型写死在代码里（只有 `PT` / `JL`）

从「哪些粘剂存在」到「界面第 20 格放什么」，全部是内联 `switch (type)` 硬编码：
`YSData` 里两个字段、`PlayerData` 三处 `switch`、监听器里的槽位判断、命令里的类型校验、
权限文案里的加成数字。想加第三种粘剂（比如「传说粘剂 +50%」）要改六个文件。

**修法**：新增 `data/StickerType.java`，定义从 `config.yml` 的 `Compound.stickers` 读：

```yaml
Compound:
  stickers:
    - key: PT
      display: "普通粘剂"
      material: SLIME_BALL
      rate: 10
      slot: 20
    - key: JL
      display: "精良粘剂"
      material: MAGMA_CREAM
      rate: 30
      slot: 21
```

界面槽位、显示名、材料、成功率、材料不足时的提示、数据快照全部走这张表；
`/pu g` 的类型校验也从「写死的 PT/JL」改成「配置里有没有这个 key」，
类型写错时会直接列出可用 key。**没有 `stickers` 段的旧配置会回落到内置的 PT/JL 默认值**，
行为不变。`/pu reload` 会同时让粘剂定义失效重建。

### 13. 高星材料误操作：二次确认

七星材料是玩家最贵的一件东西（10 万～100 万金币 + 两只六星精灵），
而原实现的「确认合成」就是一次普通左键 —— 手滑即不可逆。

**修法**：材料星级 ≥ `Compound.confirm-min-star`（默认 5）时，「确认合成」第一次点击
只弹提示，需要再点一次才真正执行；界面上的确认按钮文案会随状态切换，
并显示「本次消耗 / 当前余额（不足标红）/ 连续失败进度」。
**换材料 / 换粘剂会把确认状态重置**，避免「先放低星骗过确认、再换成高星」的绕过路径。

### 14. 连点器 / 脚本刷合成：冷却

原实现没有任何频率限制，一个宏每秒点一次「确认合成」就能把保底计数和金币流水刷成一条直线。

**修法**：`Compound.cooldown-ms`（默认 `0` = 关闭）。判定放在自定义事件之后、
二次确认之前，且**只有判定通过才记时间** —— 反过来写的话，连点器只要保持
「比冷却快一点点」的频率就能一直绕过。L10/L11 用真实的 `tryUseCompoundCooldown` 验证了
「窗口内第二次被拒 / 关掉后立刻放行」两条路径。

### 15. 没有审计：合成日志

一次 `getLogger()` 调用都没有。遇到「我合成了但什么都没拿到」的纠纷，
管理员手上没有任何可查的记录。

**修法**：`Compound.log.enabled`（默认 `true`）时，每次合成落一行
制表符分列的日志到 `plugins/PokeUtils-remake/logs/compound-<日期>.log`：
玩家、材料星级、结果、消耗、粘剂、剩余、保底计数、耗时毫秒。
L12 断言了字段数与星级取值。

### 16. 管理指令的缺口（`/pu g` 的几处硬伤）

| 问题 | 后果 |
| --- | --- |
| `if (player == null \|\| !p.isOnline())` 里 `player` 是 `args[1]` 这个 **String**（永不为 null），真正被解引用的 `p` 却来自 `Bukkit.getPlayer()` | 玩家不在线时这一行**必 NPE**，日志里只有一条栈 |
| 玩家不在线就拒绝 | 运维想给下线的玩家补发粘剂根本做不到 —— 而「发东西」恰恰经常发生在对方不在线的时候 |
| 只有 `deposit`，没有扣减 | 发错了没法撤回，只能「再发一个反向数量」，账目越搞越乱 |
| `plugin.yml` 里 `permissions:` 段一个节点都没声明 | 「谁能开合成界面」完全不受控 |

**修法**：

- **修掉 NPE**：判空对象改成 `p`；目标解析顺序改为
  「在线玩家 → 库里的 `name → uuid` 记录 → `Bukkit.getOfflinePlayer`」。
  中间那一步是关键：正版验证服上 `getOfflinePlayer(陌生名字)` 会去请求 Mojang 接口
  并把主线程卡住，而只要这人进过服，库里就一定有记录（零网络、零阻塞）。
- **补 `/pu take`**：与 `/pu g` 共用同一套实现（`handleStickerCommand(sender, args, give)`）。
- **补 `/pu bal`**：列出各粘剂余额与保底进度；查他人需要 `pokeutils.admin`。
- **补权限声明**：`pokeutils.use` / `pokeutils.admin` / `vip.yx` / `vip.cs`。
- **`Compound.use-permission`**：留空 = 人人可用（保持原行为），填了就按节点卡。

### 17. 数据层：每个玩家一个 YAML → SQLite（主键改用 UUID）

原实现是 `plugins/PokeUtils-remake/Data/<玩家名>.yml`，两个问题：

- **名字当键**：玩家改一次 ID 名，粘剂和保底计数就全部归零；
- **只在 `onDisable` / `PlayerQuitEvent` 写盘**：而本测试服的关服方式是 `taskkill /F`
  （拿不到 `onDisable`），一整个会话的变动会静默回滚。

**修法**：新增 `data/SqliteStorage.java` 作为持久化的唯一出口，两张表：

```sql
CREATE TABLE pu_player  (uuid TEXT PRIMARY KEY, name TEXT, fail_streak INTEGER, updated_at INTEGER);
CREATE TABLE pu_sticker (uuid TEXT, type TEXT, amount INTEGER, PRIMARY KEY (uuid, type));
```

`YSData` 从「YAML 持有者」退化成薄门面，每次读写直接打库。SQLite 是 autocommit，
**不存在「等退服 / 关服才落盘」的窗口**。

三个实现要点值得记下来：

1. **本地文件驱动 + WAL**：`setJournalMode(WAL)` / `setSynchronous(NORMAL)` /
   `setBusyTimeout(5000)`，读不被写阻塞，运维工具占着写锁时等 5 秒而不是立刻报错。
2. **不用 `DriverManager`**：Bukkit 给每个插件套了独立的 `PluginClassLoader`，
   而 `DriverManager` 按「调用方的 ClassLoader」匹配驱动，在插件里经常抛
   `No suitable driver found`。改走 `SQLiteDataSource#getConnection()` ——
   它内部直接构造 `SQLiteConnection`，不过 `DriverManager`。
3. **驱动必须重定位**：CatServer 1.12.2 的 `libraries/` 里自带 `sqlite-jdbc-3.21.0.1.jar`，
   而插件类加载器**先委托父加载器**，于是我们自己 shade 的 3.42 版永远不会被用到 ——
   实测症状是启动时抛 `NoSuchMethodError: org.sqlite.SQLiteConfig.setBusyTimeout(I)V`
   （该方法 3.24+ 才有）。构建脚本因此把驱动整体重定位到 `pokeshaded` 包，
   连 **JNI 符号**一起改（native 库导出的是 `Java_org_sqlite_core_NativeDB__1open_1utf8`，
   只改 Java 侧会抛 `UnsatisfiedLinkError`）。

**旧数据不丢**：新增 `data/LegacyDataMigration.java`，在 `PlayerJoinEvent` 里把
`Data/<玩家名>.yml` 导入 SQLite，然后把原文件**归档**（不删除）到与 `Data/` **平级**的
`Data-migrated/`。选择「进服时迁移」而不是「启动时批量迁移」，是因为启动时手上只有名字，
反查 UUID 要走 `getOfflinePlayer`，在正版验证服上会阻塞主线程；而玩家进服时手里就是
货真价实的 UUID。代价是「永远不回来的玩家」不会被迁移 —— 那种数据本来也不会被任何人读到。

L18 用真实的 `MigrationProbe` 走了一遍全流程：写 YAML → 迁移 → 断言
`PT` / `JL` / `fail-streak` 三项都进库 → 断言原文件已从 `Data/` 移走且出现在 `Data-migrated/`。

### 18. 第三轮增强：A 玩家体验 / B 运营数据 / C 经济权限 / D 架构健壮

14 条一次落地。新增两个类（`data/CompoundLog.java`、`data/VoucherType.java`），
`config.yml` 新增 25 个键（见下方「配置一览」），端到端回归从 227 项扩到 **370 项**。

- **A1 产出预览**：主界面槽 26 放 `NETHER_STAR` 按钮，分页列出该档位的真实产出表
  （数据就是 PokeStar 的 `getStarPokemon`，预览里有的就能合出来）。槽 49 是「返回合成界面」，
  真的回主界面而不是只 `closeInventory`。⚠️ 槽 26 让给预览后，边框玻璃板从 **35 格变 34 格**。
- **A2 `/pu history`**：读流水日志查玩家的合成记录。
- **A3 音效 + Title**：开界面 / 选择 / 成功 / 失败四处音效 + 成功 Title，全部可配。
- **A4 保底提示**：`Compound.pity.notify` 支持 `actionbar`。1.12.2 的 spigot-api
  **没有** `Player#sendActionBar`，唯一正路是自己构 `PacketPlayOutChat`，而本机只有混淆原版
  靠运行时 remap，照猜签名会 `IncompatibleClassChangeError` —— 所以改成**反射尝试**，
  服务端上没有就回落聊天栏并打一次 WARN（`actionBarWarned` 只报一次，不刷屏）。
- **B5/B6/B7**：`/pu stats`（全服聚合）、`/pu top`（排行榜）、`/pu debug`（配置/产出池/存储快照）。
  聚合都在流水日志上做，扫描行数上限 `Compound.history.max-scan-lines`，避免主线程被大日志拖住。
- **C8 权限组折扣与加成**：`Compound.groups`，取**第一个命中**的组，**不做叠加** ——
  多重继承时叠加会算出一个谁也说不清的数。
- **C9 合成券**：`Compound.vouchers`，匹配顺序 `material` → `name`（精确显示名）→
  `lore-keyword`（lore 包含），外加 `star-limit` 限档。有券时**优先扣券、不扣金币**。
- **C10 失败补偿可改为送粘剂**：`Compound.refund.mode` = `money` / `sticker` / `both`，
  另有 `min-star` 闸门（只补高星失败，否则一星失败补粘剂会变成刷材料的路子）。
- **C11 配方可配**：`material-count`（1 或 2）、`sticker-required`。
- **D13 启动自检产出池**：开机就调 `getStarPokemon(1..7)`，空档位直接点名，
  并说明「这些档位的合成会被直接中止、不会扣任何东西」。
- **D14 日志保留**：`Compound.log.keep-days`，开机清理过期日志文件。
- **D15 同 tick 防重复点击**：Bukkit 里同 tick 的多次点击是**依次同步派发**的，
  冷却（`cooldown-ms`）管的是「隔一会儿的连点」，管不到同 tick ——
  所以另加 `isProcessing` 标志，进入结算就置位、`finally` 清除。

> `MySQL 存储后端可切换` 这条按用户要求**取消**：只有一台服，多一个后端只多一份维护面。

### 19. ⚠️【最容易再踩】双参读取看不到 jar 内置 config.yml

**症状**：插件换了新版、`config.yml` 还是旧版（没有新增段）时，某些新功能**静默失效**，
日志上一点线索都没有。第三轮实际就是「音效一声不响」。

**根因**：Bukkit 的 `MemorySection` 里这两类读取语义**不同**：

| 读法 | 实现 | 磁盘缺键、jar 内置有值时 |
| --- | --- | --- |
| 单参 `get(path)` / `getString(path)` / `getList(path)` | `get(path, getDefault(path))` | **能**读到 jar 内置默认 ✅ |
| 双参 `getString(path, def)` / `getInt(path, def)` / `getBoolean(path, def)` … | 只查本段 map，查不到**直接返回 def** | 返回代码里写的字面量 ❌ |

已在 CatServer 1.12.2 自带的 `org.bukkit.configuration` 上双重确认（`javap -c` 看字节码 +
独立小程序实测）。危害范围是**「代码字面量 ≠ 出厂 config.yml 值」的那些键**：
第三轮只有 4 个音效名中招（代码写 `""`，出厂是 4 个 Sound 枚举名），
而 Title（`getBoolean(..., true)`）、价格（`getInt(..., 1000)`）的字面量恰好等于出厂值，
于是一起看就像「大部分正常、只有音效坏了」，非常难查。

**约定（新增配置键必须遵守）**：**代码里的字面量默认值必须与出厂 `config.yml` 完全一致。**
音效已按此抽成 `PokeUtils.DEFAULT_SOUNDS`（与本类既有的 `DEFAULT_PRICE` /
`DEFAULT_STICKER_*` 同一套做法）。语义上：

- 键**不存在** → 用代码内置出厂值（老配置也能开箱即用）
- 键**存在但为空串** → 仍然表示「该处不发声」（双参读取只在缺键时才用 `def`）

回归项 **M11c** 是这条的哨兵：删掉 `Compound.feedback.sounds.select` 后仍必须发声，
不依赖测试服磁盘上那份 `config.yml`，将来把配置刷成全量配置也照样守着。

### 19b. ⚠️【与 19 同源，更凶险】`getConfigurationSection(缺键)` 会凭空造一个空段，永远不为 null

**症状**：想给某个「列表 / 嵌套段」型配置留代码内置默认值时，很自然会写成：

```java
ConfigurationSection sec = getConfig().getConfigurationSection("Compound.xxx.rewards");
if (sec == null) { /* 用代码内置默认 */ }   // ← 这个分支永远不会进
```

**`null` 兜底永远不会触发。** `MemorySection#getConfigurationSection` 在键不存在时会
`createSection(path)` **凭空造一个空段返回**（已在 CatServer 1.12.2 自带的
`org.bukkit.configuration` 上用 `javap -c` 反编译确认），于是控制流永远走 else，
读出来是**一个空列表**。

实测中招案例（第四轮图鉴）：测试服磁盘上的 `config.yml` 是老配置、没有 `Compound.dex` 段，
界面上显示「奖励档位: **0** 个」，玩家永远领不到里程碑奖励 —— 而**日志上一点线索都没有**，
只能靠端到端断言把它捞出来。

**正确写法**：不要用 `getConfigurationSection(path) == null` 判存在性，要用
`isConfigurationSection(path)` / `contains(path)` **正着判**：

```java
// ① 推荐：单参 isConfigurationSection 会回退到 jar 内置默认，
//    所以「磁盘缺键、jar 内置有段」时照样返回 true
if (!getConfig().isConfigurationSection("Compound.xxx.rewards")) {
    return defaults();
}
// ② 键确实存在，再逐条读（逐子键都走单参 / 双参，语义见 19 条）
```

**约定（新增配置读取必须遵守）**：凡是「缺键时想回退到代码内置默认」的地方，
一律用 `isConfigurationSection` / `contains` 判存在性，**禁止**用
`getConfigurationSection(...) == null` 当条件 —— 它是个恒假条件。

### 20. 数据层再进一步：SQLite / MySQL 可切换（`data/Storage.java`）

第三轮把存储从 YAML 换成了 SQLite，但插件各处直接引用 `SqliteStorage` 这个具体类。
要支持 MySQL 只有两条路：要么在每一处 `if (mysql) ... else ...`，要么先把契约抽出来。

**修法**：新增 `data/Storage.java` 接口（`open / close / isOpen / kind / describe / backend /
hasPlayer / findUuidByName / touchPlayer / getSticker / getStickers / setSticker /
getFailStreak / setFailStreak`），`SqliteStorage` 与新增的 `MysqlStorage` 各实现一份，
`PokeUtils#getStorage()` 的返回类型改成接口。业务代码（监听器 / 命令 / `YSData`）
**一行都没改**。

写入接口刻意收的是**绝对值**（`setSticker(uuid, name, type, amount)`）而不是增量。
这是为异步写回量身定的：增量在重试时会被重复应用（扣两次钱），绝对值天然幂等。
增量的便利由接口上的 `default addSticker(...)` 提供 —— 它在内存里做完读改写，
再落到绝对值接口上。

MySQL 侧有四个坑，都收敛在 `MysqlStorage` 里：

1. **`TEXT` 不能做主键**（报 1170 BLOB/TEXT column used in key spec），uuid 必须
   `VARCHAR(36)`；表用 `utf8mb4_general_ci`，让 `WHERE name = ?` 天然大小写不敏感 ——
   SQLite 那边是靠 `COLLATE NOCASE` 做到的，两边语义要对得上。
2. **`MAX(0, x)` 在 MySQL 上是语法错误**：SQLite 的 `MAX` 是两参标量函数，
   MySQL 的 `MAX` 是聚合函数，夹取必须用 `GREATEST(0, x)`。
   （已用独立探针实测确认。）现设计下这个坑踩不到 —— 夹取在 Java 侧用 `Math.max` 做掉了。
3. **驱动不 shade、直接从服务端拿**：CatServer 1.12.2 的 `libraries/` 里就有
   `mysql-connector-java-5.1.45.jar`，插件类加载器是父优先的，`Class.forName` 一定解析得到。
   插件体积一个字节不涨，也不会出现「插件带 8.x、服务端带 5.1」的驱动冲突。
4. **绕开 `DriverManager`**：它按「调用方的 ClassLoader」核对驱动是否可见，
   失败时的报错还是那句最没用的 `No suitable driver found`。这里反射实例化驱动对象后
   直接调 `Driver#connect(url, props)`，完全不经过 `DriverManager`；
   驱动类名按 `com.mysql.cj.jdbc.Driver` → `com.mysql.jdbc.Driver` →
   `org.mariadb.jdbc.Driver` 依次尝试，都失败才回退 `DriverManager`。
   连接池是自己写的极简 `ArrayBlockingQueue` 版（借出时校验 `isValid`，
   池满直接关），**不用**全局 synchronized —— 一次网络往返是毫秒级，全局锁会把
   本来并行的查询排成队。

**连不上就降级，绝不连坐**：MySQL 不可用时打一条 SEVERE 说明原因，然后**回退 SQLite**，
插件照常加载。存储后端故障不该让整台服务器的合成玩法一起下线。
（实测：停掉 MariaDB 重启服务端 → `MySQL 不可用（Connection refused: connect），
已自动回退到 SQLite`，`Done (12.8s)`，插件功能正常。）

### 21. 内存缓存 + 异步写回（`data/CachedStorage.java`）

本地 SQLite 的一次查询是微秒级，加不加缓存感觉不出来；换到 MySQL 就是一次网络往返
（局域网 1~3ms，跨机房 20~50ms）。而合成一次要点开界面、选材料、再点确认 ——
每次界面刷新都要读一遍粘剂数量，这些全落在服务端主线程上，玩家看到的就是「点了没反应」。

**修法**：`CachedStorage` 包在裸后端外面，做三件事：

| 关注点 | 做法 |
| --- | --- |
| 读 | 在线玩家的读全部命中内存；离线玩家（`/pu g <离线>`）直接打后端，不进缓存 |
| 写 | 改内存 + 标脏；SQLite 同步写穿，MySQL 丢给 `Async` 的**单线程**写队列 |
| 落盘 | 退服后落盘并驱逐；关服先排空队列、再同步补一刀、最后才关连接 |

三条不变量（写在这段代码的注释里，也是它唯一需要被理解的地方）：

1. **只有在线玩家有缓存**：进服读入、退服落盘并驱逐。否则给几千个只发过一次货的
   离线账号建缓存，内存会没人管地涨上去。
2. **只在写成功之后才驱逐**：玩家秒退秒进时缓存项还在，此时进服的 `loadPlayer`
   会复用这一项而不是去库里重读 —— 否则会用「写还没到达的旧值」把内存里更新的值覆盖回去。
3. **写队列是单线程的**：不是省资源，而是「同一玩家的写入必须有序」这条约束的实现方式。
   多线程之后两次写回可能并发抵达数据库，最后落盘的是哪一次取决于网络抖动。

**代价是明码标价的**：异步写回意味着服务端被强杀（`taskkill /F`）时会丢掉最后几毫秒的
写入 —— 这正是第三轮修掉的第 9 处缺陷的语义的一部分。所以默认值是
`Storage.async.write-behind: auto`：**本地后端同步写穿（写入返回即已落盘），
只有网络后端才异步**。要绝对安全就在配置里改成 `never`。

### 22. 合成流水改成内存索引（`data/LogIndex.java`）

`/pu history`、`/pu stats`、`/pu top` 原来是**每次执行**都把 `logs/` 目录读一遍、
全量读进内存、逐行按制表符切分。日志按天分文件且保留 30 天，一个中等规模的服跑一个月
就能有几十万行 —— 于是「玩家随手敲一条查询」变成一次几十到几百毫秒的主线程长阻塞。
`history.max-scan-lines`（默认 5 万行）只是把「很慢」压成「有点慢」。

**修法**：启动时读一次盘建索引，之后每合成一次就追加一行到内存尾部，
超出上限从头部裁掉。命令直接在内存里聚合，主线程零 IO。

**刻意不做增量统计**：增量维护要求「裁剪时把被裁掉的记录从统计里反向扣掉」，
一处漏扣统计就永久偏掉且很难发现。既然一次全量遍历 5 万条只要 1 毫秒，
那就每次都遍历 —— 用一点 CPU 换掉一整类隐蔽的状态不一致。
`/pu reload` 时的重建跑在后台线程上，且**读盘在锁外做**：重建期间新落进来的行先攒着，
结束后判重并入，不会让恰好在这一刻合成的玩家卡在主线程上。

### 23. ⚠️ 新增配置键时的第二条铁律（承接第 19 条）

第 19 条讲的是「双参 `get(path, def)` 不回退 jar 内置 `config.yml`，所以代码字面量默认值
必须与出厂 `config.yml` 逐字一致」。这一轮把这条从「约定」升级成**有哨兵的回归项**：

- `StorageFactory` 把出厂默认值定义成一组 `static final` 常量，紧接着一行注释说明
  「与 `config.yml` 的 `Storage` 段逐字对应」；
- 端到端用例 **N6** 直接用双参版本（`configGetRaw`）读回这几个键，
  逐条断言等于出厂值。两边一旦漂移，N6 立刻红。

### 24. 合成流水日志的落盘挪出主线程（`data/LogWriter.java`）

原来 `logCompound` 每写一行都是「主线程 `new FileOutputStream` + 写 + `close`」。
这里有个尴尬的不对称：**我们用 `write-behind` 花大力气把 MySQL 的网络往返挪出了主线程，
却把文件 IO 留在了主线程** —— 每次合成都是一次 open/write/close 的系统调用。

现在文件写走**同一个单线程写队列**（`Async.write`），句柄常开、写后即刷：

- 用同一个队列而不是另开线程池，是为了让「同一条流水的顺序」有保证，
  也避免关服时多出一处需要单独排空的地方；
- **写后即刷，不攒批**：流水是事后处理纠纷、复盘经济产出的审计凭证，
  攒批意味着强杀服务端会丢掉最近的一批记录 —— 这个项目整条链路上都在避免这个语义
  （原实现栽就栽在「只在 `onDisable` 落盘」上）。拿到的是「省掉 open/close 且不丢数据」，
  而不是「少几次写但可能丢数据」；
- **内存索引仍在主线程同步追加**：`/pu history` 立刻可见的语义不能因为落盘改成异步就变。
  两者共用 `CompoundLog.parse`，格式只有一处定义。

### 25. 存储写失败不再静默（`data/StorageException.java`）

`SqliteStorage` 里原有 **九处 `catch` 全是 `ex.printStackTrace()`**：异常只进 stderr
（不在 `latest.log` 里），而调用方拿到的是「默认值 0 / 空表」——**分不清「本来就是 0」
和「读库炸了」**。SQLite 是同步写穿路径，写失败被静默吞掉就等于**静默丢资产**，
而玩家看到的还是「操作成功」。

现在分成两类处理，这是刻意的区分而不是偷懒：

- **读失败**：留痕（`logger.log(SEVERE, …, ex)`）但**降级返回默认值** ——
  一次读失败不该让玩家连界面都打不开；
- **写失败**：留痕后**抛 `StorageException`**。抛出的这点在调用链上正好被
  `CachedStorage#flushEntry` 接住（保留脏标记 + 退避重试 + 如实记失败次数），
  而「离线玩家直接打后端」那条路径抛出后会被命令层感知，管理员不会再收到一句假的「操作成功」。

`MysqlStorage` 的七个 `catch` 同步升级（原来只是 `warning`，且连异常对象都没打进日志）。

### 26. 写回失败：退避 + 熔断 + 兜底扫描（`data/CachedStorage.java`）

原来失败后的处理是「在同一个单线程写队列上立刻重排，最多 3 次」。两个问题：

1. **重试会堵住全服**：单线程队列 + 每次重试都可能撞满一次连接超时（默认 5 秒），
   最坏 15 秒里**全服所有人的写入一起排队**；
2. **放弃之后没有任何兜底**：3 次用完后脏标记留在内存里，
   **除非该玩家下次再操作，否则永远不会重试** —— 挂着不动的玩家会一直卡到关服，
   而「强杀关服」是抓不到 `flushAll` 的。

现在改成四层：

| 层 | 做什么 |
| --- | --- |
| 退避 | 失败的那一条不再立刻重排，记下 `retryAt`（250ms 起翻倍，封顶 10 秒）交给兜底扫描 |
| 兜底扫描 | `sweep()` 每秒跑一次（插件的异步定时器驱动），补上「重试放弃」与「挂机玩家」两类情况 |
| 熔断 | 同一条反复失败且全局连续失败 ≥ 5 次时暂停排队 10 秒（避免全服堵在连接超时上），窗口过后自动放一次试探 |
| 关服补刀 | `shutdown` 先排空队列、再 `flushAll` 同步补一次，最后才关后端（顺序不能反） |

四条都建立在**绝对值写入**上，所以重放任意多次的结果都一样。
`/pu debug` 与 `P` 组用例可以观察 `consecutiveFailures()` / `isBroken()` / `sweep()`。

### 27. P0/P1 的四条小修

- **用合成券失败时退还券**（`Compound.refund.voucher-on-failure`，默认 `true`）。
  用券时 `money` 恒为 0，于是失败补偿里「返还 x% 金币」那一段**永远走不到** ——
  净效果是券 + 粘剂 + 两只精灵全损且一无所获，而用金币失败至少能拿回 20%。
  这个不对等没有道理。背包满时券掉在玩家脚下，不会凭空消失。
- **队伍槽位下标 0 是合法槽位**。选择材料时算「还剩几只」用的是
  `pd.getSlot1() > 0`，而槽位下标 0 就是第一格 —— 队伍**恰好两只**时，
  先选第 2 格再选第 1 格会少减一只，预览算出「还剩 2 只」，直到点确认才被外层拦下
  （提示还变成「背包最少需要装备三只精灵」）。改成 `>= 0`。
  同一件事在这个文件里原有 `> -1`（117 行）/ `==`（456 行）/ `> 0`（461 行）三种写法，
  现在统一到唯一正确的那个。**P 组 P5 专门用「队伍恰好两只」把这个边界钉住**。
- **SQLite 的 WAL 定期合并**（`Storage.sqlite.checkpoint-minutes`，默认 10，0 = 关）。
  WAL 模式下的已提交数据先落在 `-wal` 附属文件里，而默认的自动 checkpoint
  **不截断文件** —— 一个从不重启的服能把它涨到很大（本项目测试服被反复
  `taskkill /F` 之后实测 1.8MB），而 WAL 越大、下次打开数据库时回放越慢。
- **合成冷却表从「按玩家名」改成「按 UUID」**（`PokeUtils#lastCompoundAt`），
  并在退服时摘掉。按名字做键的话，玩家改一次 ID 名就留下一个永远不会被清的旧键 ——
  这张表只增不减。
- `handleGiveCommand` 的兜底解析在**正版验证模式**下不再调 `Bukkit.getOfflinePlayer`
  （那会同步请求 Mojang 的 profile 接口，网络不好时能把主线程卡住几秒）。
  离线模式行为不变。

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

## 编译桩的类型种类（重要）

Pixelmon 1.12.2 里 `Pokemon`、`PlayerPartyStorage`、`PokemonFactory` 都是 **类**（原插件字节码用
`invokevirtual` 调用）。编译桩必须同样声明为 `class`：

若误声明为 `interface`，javac 会生成 `invokeinterface`，在真机上首次执行到该调用时会抛
`IncompatibleClassChangeError`。反过来，`IStorageManager`、Vault 的 `Economy` 等本身是接口，
就必须声明为 `interface`。改动桩后请重新比对一次调用指令种类。

## 已知的、无行为影响的差异

- **常量池顺序**不同，导致部分字符串常量用 `ldc` 而非 `ldc_w`（2 字节 vs 3 字节），
  于是各方法的字节码总长度与原版相差几十字节。指令语义完全相同。
- 上述长度差也使局部变量表里的 `Start` / `Length` 偏移量与原版不同（变量名、类型、槽位编号仍一致）。

## 构建

需要 **JDK 8**。本机没有安装 Maven，因此提供一个等价脚本：

```bash
./build.sh           # 只构建
./build.sh deploy    # 构建并同步到 ../TestServer/plugins/
```

它用 `javac` + `jar` 直出，语义等价于 `mvn package`：

- `stub-api` 只参与编译期 classpath，**不进产物**（等价于 `provided` 作用域）；
- **SQLite 驱动**从 `libs/sqlite-jdbc-3.42.0.0.jar` 手工 shade 进产物，分三步：
  1. 展开 jar，只保留 `Windows/x86_64`、`Windows/x86`、`Linux/x86_64`、`Linux/x86`
     四个平台的 native（13 MB → 约 2.3 MB）；
  2. `tools/relocate.py` 做**纯字节等长重定位** —— `org/sqlite`、`org.sqlite`、
     `org_sqlite` 三种写法都改到 `pokeshaded`（恰好都是 10 字符，可以免改
     `CONSTANT_Utf8` 的 length 字段直接替换字节），JNI 符号一并处理；
  3. 重新打包，并重命名包目录、清理空父目录。
- 插件本体与 `resources/*.yml`、shade 后的驱动打成 `dist/PokeUtils-remake-1.0.0-SNAPSHOT.jar`；
- 结束前三项自检：第三方桩混进产物数应为 **0**、驱动 class/native 数应 **> 0**、
  重定位残留的 `org/sqlite` 条目数应为 **0**。

若你本机有 Maven，也可以照常：

```bash
mvn clean package
```

产物：`plugin/target/PokeUtils-remake-1.0.0-SNAPSHOT.jar`（另存一份到 `dist/`），
直接丢进服务端 `plugins/` 目录即可。

> 改完记得在 `../TestServer/` 里跑一次端到端回归（见该目录的 `README-测试指南.md` 第八节），
> 或者至少执行 `python ../TestServer/tools/verify-compat.py dist/PokeUtils-remake-1.0.0-SNAPSHOT.jar ...`
> 确认外部引用仍然成立。

### 与真实 PokeStar 的接口核对

本插件用到的 `PokeStarAPI` 三个方法 —— `getPokemonStar(String)` / `getPokeShowName(int)` /
`getStarPokemon(int)` —— 已对着真实的 **`PokeStar-2.0.0-BETA.jar`** 用 `javap` 逐个核对签名，
并在测试服上跑过端到端回归（当前 227/227）。真实 PokeStar 的 `stars.yml` 用的是
**种族值总和**分档（`ModuleImpl#initialize` 把 `EnumSpecies` 的六项种族值求和后写入表），
不是图鉴号。

两个运行期注意点：

- 真实 PokeStar **需要前置 AyCore**（`com.aystudio.core`）。缺它时
  `PokeStar.Main#initializeModule` 只捕获了三种 `Exception`，而缺类抛的是
  `NoClassDefFoundError`，会直接穿出 `onEnable`，日志里不会有「依赖缺失」的提示。
- PokeStar 自带的 `stars.yml` 里「四星」写作 `star: 4m`（字符串），
  `getInt()` 得 0 后被 `Math.max(1, x)` 夹成 1 星，导致**四星档整体丢失**、
  `getStarPokemon(4)` 返回空表。原插件拿到产出池后是直接 `get(0)` 的，
  于是「三星合成成功」会变成一次「扣完钱和精灵之后才抛 `IndexOutOfBoundsException`」。
  该问题**源自依赖数据**，详见 `../TestServer/README-测试指南.md` 第五节；
  本插件已另加「空池预检」作为兜底（见修复记录第 4 条），即使数据再坏也不会让玩家净亏。

### 与真实 PokemonAPI 的接口核对

除 `PokeStarAPI` 外，本插件还用到 **PokemonAPI** 的若干入口，已对着真实的
**`PokemonAPI-3.2.1.0-SNAPSHOT.jar`** 用 `javap` 逐个核对，签名 100% 一致：

```
PokemonAPI.getSpriteHelper()Lcom/mc9y/pokemonapi/api/sprite/SpriteHelper;
SpriteHelper.getSpriteItem(Lcom/pixelmonmod/pixelmon/api/pokemon/Pokemon;)Lorg/bukkit/inventory/ItemStack;
PokemonUtil.getPokemonName(Lcom/pixelmonmod/pixelmon/api/pokemon/Pokemon;)Ljava/lang/String;   [static]
PokemonUtil.getPokemonName(Lcom/pixelmonmod/pixelmon/enums/EnumSpecies;)Ljava/lang/String;      [static]
PlayerStorageUtil.givePlayerPokemon(Lorg/bukkit/entity/Player;Lcom/pixelmonmod/pixelmon/api/pokemon/Pokemon;)V  [static]
CustomEvent.<init>(Ljava/lang/String;Ljava/lang/Object;Lorg/bukkit/entity/Player;)V
CustomEvent.isCancelled()Z
Blank038API.getPokemonAPI()Lcom/mc9y/pokemonapi/PokemonAPI;   [static]
```

一个容易踩的点：**`Blank038API` 与 `PokemonAPI` 是同一个 jar**。真实 jar 的 `plugin.yml`
写着 `name: PokemonAPI` 但 `main: com.mc9y.blank038api.Blank038API`，即
`com.mc9y.pokemonapi.*` 与 `com.mc9y.blank038api.*` 两套包名同在这一个文件里，
所以代码里那句**未在 `plugin.yml` 声明依赖**的 `Blank038API.getPokemonAPI()` 能正常链接。

行为差异：真实 `PokemonUtil.getPokemonName` 走 i18n 返回**中文名**（如 `妙蛙种子`），
换掉测试替身后界面显示随之变成中文 —— 这是真实环境下的正确表现，**本插件无需改动**。

### 关于 `stub-api`

本插件依赖的 Pixelmon、PokeStar、PokemonAPI、Blank038API、MSGToggle、NMS、Vault
大多不是公开 Maven 构件，无法直接声明为远程依赖。因此本工程用 `stub-api` 模块提供了这些 API 的
**编译桩**，并以 `provided` 作用域引入：

- 只在编译期参与，**绝不会打进最终 jar**（`provided` 依赖不参与打包）
- 运行期这些类由服务端上的真实模组/插件提供，桩类不会与它们冲突

如果你手头已有真实插件 jar，可以把它们放进 `libs/`，然后：

1. 注释掉 `plugin/pom.xml` 中 `pokeutils-stub-api` 依赖
2. 取消 `plugin/pom.xml` 里「真实依赖（可选）」区块的注释
3. 重新 `mvn clean package`

两种方式产出的插件行为一致。

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
  - `Storage` —— 存储后端：`type`（`sqlite` / `mysql`）、
    `sqlite.file` 与 `sqlite.checkpoint-minutes`（WAL 定期合并，0 = 关）、
    `mysql.*`（host / port / database / user / password / table-prefix / use-ssl /
    server-timezone / pool-size / connect-timeout-ms / borrow-timeout-ms）、
    `async`（`write-behind` = `auto` / `always` / `never`、`flush-timeout-ms`、
    `worker-threads`）。**改 `type` 需要重启服务端**（`/pu reload` 不会重连数据库），
    且 SQLite 与 MySQL 里是两份互相独立的数据。详见修复记录第 20~27 条
- `plugins/PokeUtils-remake/pokeutils.db`：SQLite 数据库（`pu_player` / `pu_sticker` 两张表）。
  用 MySQL 后端时这个文件不参与读写（但会保留在原地，切回 SQLite 时数据还在）
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

## 说明

本项目为对既有 jar 的逆向复原，仅用于学习、维护与二次开发。
