package com.mc9y.pokeutils.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家持久化的抽象后端（目前有 SQLite 与 MySQL 两个实现）。
 *
 * <p><b>为什么要有这层接口</b>：原来 SQLite 是「唯一实现」，插件各处直接引用
 * {@code SqliteStorage} 这个具体类。要支持 MySQL 只有两条路 —— 要么在每一处
 * {@code if (mysql) ... else ...}，要么先把契约抽出来。后者显然更便宜：插件本体
 * （监听器 / 命令 / 门面）从此只认这个接口，换后端不动一行业务代码。</p>
 *
 * <p><b>写入语义是「绝对值」而不是「增量」</b>：{@link #setSticker} 接收的是变更后的
 * 数量。这一点是为异步写回量身定的 —— 增量在重试时会被重复应用（扣两次钱），
 * 绝对值天然幂等，重放多少次结果都一样。增量的便利由
 * {@link #addSticker} 这个 default 方法提供，它在内存里做完读改写再落到绝对值接口上。</p>
 *
 * <p><b>实现要求</b>：{@link #getSticker} / {@link #setSticker} /
 * {@link #getFailStreak} / {@link #setFailStreak} / {@link #getFailStar} / {@link #setFailStar}
 * 这几个方法必须能被多线程并发调用（缓存层的写回线程与主线程可能同时进来）。</p>
 */
public interface Storage {

    /** 打开连接（必要时建表）。重复调用应当幂等。 */
    void open() throws Exception;

    /** 关闭连接。已关闭时调用不应抛异常。 */
    void close();

    /** 连接是否可用。为 {@code false} 时上层会退化成「读到 0 / 写入丢弃」。 */
    boolean isOpen();

    /** 后端类型标识，取值 {@code "sqlite"} / {@code "mysql"} / {@code "yaml"}，用于日志与测试断言。 */
    String kind();

    /** 供启动日志打印的可读描述（SQLite 给文件路径，MySQL 给连接串与表名）。 */
    String describe();

    /**
     * 拿到未经缓存包装的裸后端。
     *
     * <p>插件业务永远用不到它 —— 它的用途只有一个：测试要证明「数据真的落到了库里」，
     * 就必须绕开内存缓存去问后端本身，否则断言等于在断言内存变量。</p>
     */
    Storage backend();

    /** 该 UUID 在库里是否已有记录。 */
    boolean hasPlayer(UUID uuid);

    /** 按玩家名反查 UUID（不区分大小写）；查不到返回 {@code null}。 */
    UUID findUuidByName(String name);

    /** 确保玩家行存在并把名字刷新为最新。 */
    void touchPlayer(UUID uuid, String name);

    /** 某种粘剂的持有数量（没有记录时返回 0）。 */
    int getSticker(UUID uuid, String type);

    /** 该玩家全部粘剂的快照。 */
    Map<String, Integer> getStickers(UUID uuid);

    /** 把某种粘剂的数量写成绝对值（负数会被夹到 0）。 */
    void setSticker(UUID uuid, String name, String type, int amount);

    /** 连续合成失败次数。 */
    int getFailStreak(UUID uuid);

    /** 写入连续失败次数（负数会被夹到 0）。 */
    void setFailStreak(UUID uuid, String name, int value);

    /**
     * 这些连败是<b>在哪个原料星级档</b>上累积的（0 = 未知 / 不限定档位）。
     *
     * <p>新增（A2 保底分档）：原实现只有一个全局计数，于是可以在一星档「垫刀」
     * 把计数推到阈值、再在六星档消费保底 —— 保底被用在了它从未积累过的档位上。
     * 记下档位之后，换档即重新计数。</p>
     */
    int getFailStar(UUID uuid);

    /** 写入连败所属的原料星级档（负数夹到 0）。 */
    void setFailStar(UUID uuid, String name, int star);

    /**
     * 进服时要读的全部玩家数据：粘剂、连败次数、连败档位。
     *
     * <p><b>它存在的理由只有性能</b>：这三份数据原来要三次独立查询，而它们全都发生在
     * 主线程的 {@code PlayerJoinEvent} 里。开服/重启瞬间几十个人一起进来，就是几十组
     * 数据库往返叠在主线程上 —— 那是玩家真能感觉到的「进服卡一下」。
     * 合成一次读之后，进服路径只剩一次往返。</p>
     */
    final class PlayerSnapshot {
        public final Map<String, Integer> stickers;
        public final int failStreak;
        public final int failStar;

        public PlayerSnapshot(Map<String, Integer> stickers, int failStreak, int failStar) {
            this.stickers = stickers == null
                    ? java.util.Collections.<String, Integer>emptyMap() : stickers;
            this.failStreak = failStreak < 0 ? 0 : failStreak;
            this.failStar = failStar < 0 ? 0 : failStar;
        }
    }

    /**
     * 一次读回 {@link PlayerSnapshot}。
     *
     * <p>接口的默认实现退化成三次独立查询 —— 对任何第三方/测试实现都仍然可用；
     * 内置的 SQLite 与 MySQL 后端都覆盖成了<b>单条 {@code LEFT JOIN}</b>，
     * 把三次往返并成一次。之所以能用 {@code player} 当左表：{@code setSticker}
     * 在写粘剂之前一定先 {@code touchPlayer}，所以「有粘剂行」必然「有玩家行」。</p>
     */
    default PlayerSnapshot loadSnapshot(UUID uuid) {
        return new PlayerSnapshot(getStickers(uuid), getFailStreak(uuid), getFailStar(uuid));
    }

    // ------------------------------------------------------------------ 每日委托

    /**
     * 该玩家在库里存在委托记录的日期列表（升序）。
     *
     * <p>只为 {@code /pu migrate} 存在：{@link #getQuests} 是按 {@code day} 查的，
     * 迁移时必须先知道要搬哪些天，否则只能拿到「今天」那一份。
     * 旧日期的委托本身没有业务价值，但一次迁移后数据不完整会让人怀疑整个迁移。</p>
     */
    Set<String> listQuestDays(UUID uuid);

    /**
     * 读取某玩家某一天已落库的委托（没有则返回空列表）。
     *
     * <p>按 {@code (uuid, day, slot)} 存。旧的行刻意不清理 —— 留着可以当历史查。</p>
     */
    List<QuestData> getQuests(UUID uuid, String day);

    /**
     * 落库一组委托。必须幂等：同一天重复调用不能冲掉已有进度。
     *
     * <p>实现上靠主键 + {@code INSERT OR IGNORE}（MySQL 是 {@code INSERT IGNORE}），
     * 而不是「先查再插」—— 后者在并发下会插出两套。</p>
     */
    void insertQuests(UUID uuid, String name, String day, List<QuestData> quests);

    /** 写入某条委托的进度（绝对值）。 */
    void setQuestProgress(UUID uuid, String day, int slot, int progress);

    /**
     * 一次写入多条委托进度：{@code slot -> progress}。
     *
     * <p>存在的理由只有一个 —— <b>少一次往返</b>。一次成功合成会同时推进
     * {@code SUCCESS} 与 {@code ATTEMPT} 两条委托，原来的写法是调两次
     * {@link #setQuestProgress}，在 MySQL 上就是两次独立的网络往返，都发生在玩家点击的
     * 那一帧。默认实现退化成逐条调用（对任何第三方/测试实现仍然可用），内置的两个
     * 后端都覆盖成了一次 {@code executeBatch}。</p>
     *
     * <p>原子性说明：批量里的任何一条失败都会抛 {@link StorageException}，已经写过的那几条
     * 不回滚 —— 委托进度是「单调推进的计数」，多推进一条不会给玩家多发的奖励
     * （领奖另有 {@code claimed} 判重），所以这里不做事务。</p>
     */
    default void setQuestProgressBatch(UUID uuid, String day, Map<Integer, Integer> progressBySlot) {
        if (progressBySlot == null || progressBySlot.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, Integer> entry : progressBySlot.entrySet()) {
            this.setQuestProgress(uuid, day, entry.getKey().intValue(), entry.getValue().intValue());
        }
    }

    /** 把某条委托标记为已领取。 */
    void setQuestClaimed(UUID uuid, String day, int slot);

    /**
     * 删除早于 {@code day} 的委托记录，返回删除的行数。
     *
     * <p>{@code day} 是 {@code yyyy-MM-dd}，字典序即时间序，所以实现里可以直接写
     * {@code day < ?}，不必解析日期。传当天日期即「只保留今天」。</p>
     *
     * <p><b>为什么需要它</b>：委托表是「每个玩家每天 3 行、只增不减」的，而查询永远只看
     * 当天 —— 历史行除了占磁盘没有任何用途。1000 人服跑一年就是 100 万行，备份体积和
     * 恢复时间都跟着涨。保留天数由 {@code Compound.quest.keep-days} 控制，0 = 不清理
     * （保留历史，供运维自己查）。</p>
     *
     * <p>失败语义与查询一致：<b>留痕 + 返回 0</b>，不抛 {@code StorageException} ——
     * 清理失败不该让插件启用失败，也不该阻止玩家用委托功能。</p>
     */
    int purgeQuestsBefore(String day);

    // ------------------------------------------------------------------ 合成图鉴

    /** 图鉴里已收录的种族数（不同种族计数，不含重复产出）。 */
    int dexSpeciesCount(UUID uuid);

    /** 图鉴产出总次数（同一只合出几次算几次）。 */
    int dexTotalCount(UUID uuid);

    /** 图鉴内容快照：种族名 -> 产出次数（按次数倒序，界面直接顺序渲染）。 */
    Map<String, Integer> dexAll(UUID uuid);

    /**
     * 记录一次产出（该种族的累计次数 +1）。
     *
     * <p>刻意<b>不返回</b>新值：取回它要在写完之后再跑一次 SELECT，而仅有的两个调用方
     * （{@code PokeUtils#recordDex} 与 {@code StorageMigrator}）都把返回值丢掉。
     * 需要读数时用 {@link #dexAmount} 或 {@link #dexAll} —— 不要为了一个没人要的
     * 返回值在合成成功的那一帧多付一次网络往返。</p>
     */
    void dexAdd(UUID uuid, String name, String species);

    /** 某个种族的产出次数。 */
    int dexAmount(UUID uuid, String species);

    /** 已领取过的图鉴里程碑。 */
    Set<Integer> dexClaimed(UUID uuid);

    /**
     * 领取一个里程碑奖励。
     *
     * @return {@code true} = 本次是首次领取（该发奖）；{@code false} = 已经领过
     *
     * <p><b>判重必须由数据库主键保证</b>（{@code INSERT OR IGNORE} 后看影响行数），
     * 不能用「先查 {@link #dexClaimed} 再写」—— 玩家连点两下时两次调用在同一 tick
     * 内依次执行，中间没有任何 await，先查后写会被直接绕过、奖励发两遍。</p>
     */
    boolean dexClaim(UUID uuid, String name, int milestone);

    /**
     * 原子增减某种粘剂，结果夹在 {@code [0, +∞)}，返回变更后的数量。
     *
     * <p>读改写必须在同一把锁里完成，否则两个线程各自读到 3 再各加 1，会写回两个 4。
     * 这里的锁粒度就是实现对象本身 —— 对 SQLite 而言这正好是它原有的
     * {@code synchronized} 方法所用的同一把锁，不会引入新的争用。</p>
     */
    default int addSticker(UUID uuid, String name, String type, int delta) {
        synchronized (this) {
            int next = Math.max(0, this.getSticker(uuid, type) + delta);
            this.setSticker(uuid, name, type, next);
            return next;
        }
    }

    /**
     * 关服收尾：把异步写回队列排空，然后关闭后端。
     *
     * <p>默认实现就是 {@link #close()}；带缓存的实现会覆写成「先落盘再关」。</p>
     */
    default void shutdown(long timeoutMs) {
        this.close();
    }

    /**
     * 周期性维护（当前只对 SQLite 有意义：把 WAL 合并回主库并截断）。
     *
     * <p><b>为什么要它</b>：SQLite 用 WAL 模式时，已提交的数据先写进 {@code -wal} 附属文件，
     * 只有 checkpoint 时才合并回 {@code .db}。默认的自动 checkpoint 只在 WAL 涨到约 1000 页
     * 时触发，而且<b>不会截断文件</b> —— 一个从不重启的服，{@code -wal} 会一路涨上去
     * （本项目测试服实测 1.8MB，那是被 {@code taskkill /F} 反复硬杀攒出来的）。
     * 更麻烦的是：WAL 越大，「打开数据库时回放」的耗时越长。</p>
     *
     * <p>默认空实现：网络后端没有这个概念。</p>
     *
     * @return true = 本次确实做了维护
     */
    default boolean checkpoint() {
        return false;
    }

    /**
     * 全部玩家的主记录。给 {@code /pu migrate}（跨后端搬迁）与运维盘点用。
     *
     * <p><b>为什么是批量接口而不是「先列出 UUID 再逐个查」</b>：后者是标准的 N+1 ——
     * 一万个玩家就是一万次查询，换成 MySQL 就是一万次网络往返。搬迁这种一次性动作
     * 也经不起这个量级。</p>
     *
     * <p>默认返回空表（而不是抛异常）：它属于<b>运维能力</b>，不是业务契约。
     * 某个后端忘了覆写时，表现应当是「迁移搬了 0 条、并如实告诉你 0 条」，
     * 而不是把一条未实现异常抛到命令执行者脸上。</p>
     */
    default List<PlayerRecord> listPlayers() {
        return new ArrayList<PlayerRecord>();
    }

    /** 全部粘剂行。理由同 {@link #listPlayers()}。 */
    default List<StickerRecord> listStickers() {
        return new ArrayList<StickerRecord>();
    }

    /** {@code pu_player} 的一行。 */
    final class PlayerRecord {
        public final UUID uuid;
        public final String name;
        public final int failStreak;
        /** 连败所属的原料星级档（0 = 未知 / 旧数据）。 */
        public final int failStar;

        public PlayerRecord(UUID uuid, String name, int failStreak, int failStar) {
            this.uuid = uuid;
            this.name = name;
            this.failStreak = failStreak;
            this.failStar = failStar;
        }
    }

    /** {@code pu_sticker} 的一行。 */
    final class StickerRecord {
        public final UUID uuid;
        public final String type;
        public final int amount;

        public StickerRecord(UUID uuid, String type, int amount) {
            this.uuid = uuid;
            this.type = type;
            this.amount = amount;
        }
    }
}
