package com.mc9y.pokeutils.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 带内存缓存的存储门面 —— 插件业务真正读写的那一层。
 *
 * <p><b>为什么值得加一层缓存</b>：本地 SQLite 的一次查询是微秒级，加不加感觉不出来；
 * 但换到 MySQL 就是一次网络往返（局域网 1~3ms，跨机房 20~50ms）。而合成一次要点开界面、
 * 选材料、再点确认 —— 每个界面刷新都要读一遍粘剂数量，这些全落在服务端主线程上，
 * 一个 50ms 的往返乘以几次刷新，玩家看到的就是「点了没反应」。缓存把读路径变成
 * 一次 HashMap 查找，把写路径挪到后台线程。</p>
 *
 * <h3>三条不变量</h3>
 * <ol>
 *   <li><b>写绝对值，不写增量</b>：刷新出去的是「变更后的数量」。这样写回失败重试
 *       多少次结果都一样；增量写法重放一次就会多扣一次资产。</li>
 *   <li><b>只有在线玩家有缓存</b>：进服时 {@link #loadPlayer} 读入，退服时
 *       {@link #unloadPlayer} 落盘并驱逐。给离线玩家发货（{@code /pu g <离线>}）
 *       直接打后端，不同步进缓存 —— 否则缓存会被成千上万个只被访问过一次的 UUID 撑爆。</li>
 *   <li><b>只在写成功之后才驱逐</b>：{@link #maybeEvict} 要求脏标记全清。玩家秒退秒进的
 *       时候，缓存项还在（写还没落地），此时进服的 {@code loadPlayer} 会复用这一项而不是
 *       去库里重读 —— 否则会用「写还没到达的旧值」把内存里更新的值覆盖回去。</li>
 * </ol>
 *
 * <h3>写模式</h3>
 * <ul>
 *   <li><b>同步写穿</b>（SQLite）：本地文件写入微秒级，同步写的正确性强得多 ——
 *       写完返回时数据一定在盘上。K18 那条「从库里读到 = 已落盘」的断言因此逐位成立。</li>
 *   <li><b>异步写回</b>（MySQL）：改完内存立刻返回，落库丢给 {@link Async} 的单线程
 *       写队列。代价是服务端被强杀时可能丢掉最后几个毫秒的写入 —— 这是选 MySQL
 *       必然要接受的取舍，换来的是主线程不再被网络往返阻塞。</li>
 * </ul>
 *
 * <h3>写失败了会怎样</h3>
 * <p>四层保护，按「发生概率从高到低」排列：</p>
 * <ol>
 *   <li><b>退避重试</b>：失败的那一条不会被立刻重排（立刻重排会把几次重试串在同一个
 *       单线程队列上，每次都可能撞满一次连接超时，最坏十几秒里全服写入一起排队），
 *       而是记下 {@code retryAt} 交给 {@link #sweep()}。</li>
 *   <li><b>兜底扫描</b>：{@code sweep()} 每秒跑一次，把到期的脏条目重新排队。
 *       它同时覆盖「放弃主动重试的条目」和「挂着不动、再也不会触发写入的玩家」——
 *       没有它，后者的数据只能等关服那次 flush，而强杀关服是抓不到的。</li>
 *   <li><b>熔断</b>：同一条数据反复失败且全局连续失败数超阈值时，暂停写队列的排队
 *       （{@code BREAKER_MS}），避免全服写入都堵在连接超时上；窗口过后自动放一次试探。</li>
 *   <li><b>关服补刀</b>：{@link #shutdown} 先排空队列，再 {@link #flushAll()} 同步补一次，
 *       最后才关后端。顺序不能反。</li>
 * </ol>
 * <p>四条都用的是<b>绝对值写入</b>，所以重放任意多次的结果都一样。</p>
 */
public class CachedStorage implements Storage {

    /** 单条写回连续失败多少次之后放弃「主动重排」，但仍然留在脏集合里等兜底扫描。 */
    private static final int MAX_RETRY = 3;
    /** 退避起点：失败 1 次后等这么久再试。 */
    private static final long RETRY_BASE_MS = 250L;
    /** 退避上限。 */
    private static final long RETRY_MAX_MS = 10_000L;
    /** 连续失败达到这个次数就打开熔断器（暂停排队 + 暂停兜底重试）。 */
    private static final int BREAKER_THRESHOLD = 5;
    /** 熔断持续时间；到期后放一次写过去试探后端是否恢复。 */
    private static final long BREAKER_MS = 10_000L;

    private final Storage backend;
    private final Logger logger;
    private final boolean writeBehind;
    private final long flushTimeoutMs;

    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<UUID, Entry>();
    /** 名字（小写）-> UUID。给 {@code /pu g <离线玩家>} 这类反查用，避免每次都查库。 */
    private final ConcurrentHashMap<String, UUID> nameIndex = new ConcurrentHashMap<String, UUID>();

    /** 连续写失败次数（任何一次写成功就清零）。 */
    private volatile int consecutiveFailures;
    /** 熔断器截止时间戳；早于它时不再往写队列里排任务。 */
    private volatile long breakerUntil;
    /** 关服后置位，兜底扫描不再开工。 */
    private volatile boolean closed;

    public CachedStorage(Storage backend, Logger logger, boolean writeBehind, long flushTimeoutMs) {
        this.backend = backend;
        this.logger = logger;
        this.writeBehind = writeBehind;
        this.flushTimeoutMs = flushTimeoutMs;
    }

    /** 单个玩家的内存镜像。所有字段的读写都必须持有该实例自身的锁。 */
    private static final class Entry {
        final UUID uuid;
        String name;
        int failStreak;
        /** 新增（A2）：这些连败是在哪个原料星级档上积累的（0 = 未知）。 */
        int failStar;
        final Map<String, Integer> stickers = new HashMap<String, Integer>();
        final Set<String> dirtyStickers = new HashSet<String>();
        /**
         * 保底进度（连败次数 + 所属档位）有未落盘的改动。
         *
         * <p>两个字段合成一个脏标记：它们在业务上永远一起变（失败时一起前进、
         * 成功时一起清零），分开标只会让写回做两次数据库往返。</p>
         */
        boolean dirtyFailProgress;
        boolean dirtyName;
        /** 玩家已退服，落盘完成后可以驱逐。 */
        boolean evictPending;
        /** 连续写失败次数，成功后清零。 */
        int failures;
        /** 最早可以再试一次的时间戳（失败后按退避推后；未失败过时为 0）。 */
        long retryAt;

        Entry(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        boolean clean() {
            return this.dirtyStickers.isEmpty() && !this.dirtyFailProgress && !this.dirtyName;
        }
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void open() throws Exception {
        this.backend.open();
    }

    @Override
    public void close() {
        this.flushAll();
        this.backend.close();
    }

    @Override
    public void shutdown(long timeoutMs) {
        this.closed = true;
        long effective = timeoutMs > 0 ? timeoutMs : this.flushTimeoutMs;
        if (this.writeBehind && !Async.drain(effective)) {
            this.logger.severe("等待异步写回队列排空的 " + effective + "ms 内没有完成，"
                    + "仍有 " + Async.pending() + " 条写入未落盘；下面会再同步补写一次。");
        }
        // 排空之后再同步补一刀：覆盖「写回失败留了脏标记」和「刚排空又来了一次写」两种情况。
        this.flushAll();
        long left = this.dirtyCount();
        if (left > 0) {
            this.logger.severe("关服时仍有 " + left + " 项数据没能写入存储后端，这部分变动会丢失。");
        }
        this.backend.close();
        this.cache.clear();
        this.nameIndex.clear();
    }

    @Override
    public boolean isOpen() {
        return this.backend.isOpen();
    }

    @Override
    public String kind() {
        return this.backend.kind();
    }

    @Override
    public String describe() {
        return this.backend.describe() + (this.writeBehind ? " —— 异步写回" : " —— 同步写穿");
    }

    @Override
    public boolean checkpoint() {
        return this.backend.checkpoint();
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>必须透传到后端，不能读缓存</b>：这两个方法的用途是跨后端搬迁（{@code /pu migrate}）
     * 与运维盘点，要的是「库里的权威数据」。内存缓存只覆盖<b>在线玩家</b> ——
     * 成千上万个只被发过一次货的离线账号在缓存里根本不存在，读缓存等于把他们的资产丢掉。
     * 调用方（{@link StorageMigrator}）会先 {@link #flushAll()} 再排水，保证脏数据已落盘。</p>
     */
    @Override
    public List<PlayerRecord> listPlayers() {
        return this.backend.listPlayers();
    }

    @Override
    public List<StickerRecord> listStickers() {
        return this.backend.listStickers();
    }

    @Override
    public Storage backend() {
        return this.backend;
    }

    /** 写模式：true = 异步写回（MySQL）。测试与 `/pu debug` 会看它。 */
    public boolean isWriteBehind() {
        return this.writeBehind;
    }

    /** 连续写失败次数（成功一次即清零）。供 `/pu debug` 与测试观察后端健康状况。 */
    public int consecutiveFailures() {
        return this.consecutiveFailures;
    }

    /** 熔断窗口是否生效中。 */
    public boolean isBroken() {
        return System.currentTimeMillis() < this.breakerUntil;
    }

    /** 熔断窗口还剩多少毫秒（未熔断时为 0）。 */
    public long breakerRemainingMs() {
        long left = this.breakerUntil - System.currentTimeMillis();
        return left > 0L ? left : 0L;
    }

    /** 当前缓存里的玩家数（只含在线 / 刚退服的）。 */
    public int cachedPlayers() {
        return this.cache.size();
    }

    /** 还没落盘的条目数（正常运行时应当恒为 0）。 */
    public int dirtyCount() {
        int n = 0;
        for (Entry entry : this.cache.values()) {
            synchronized (entry) {
                if (!entry.clean()) {
                    n++;
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 玩家会话

    /**
     * 玩家进服：把库里的粘剂与保底计数读进内存。
     *
     * <p>如果缓存里已经有这一项（秒退秒进，写回还没完成），就<b>不要</b>去库里重读 ——
     * 库里的值一定比内存旧，重读等于用旧值覆盖新值。</p>
     */
    public void loadPlayer(UUID uuid, String name) {
        String safe = CachedStorage.nameOf(name);
        if (!safe.isEmpty()) {
            this.nameIndex.put(safe.toLowerCase(Locale.ROOT), uuid);
        }
        Entry existing = this.cache.get(uuid);
        if (existing != null) {
            synchronized (existing) {
                existing.name = safe;
                existing.dirtyName = true;
                existing.evictPending = false;
            }
            this.schedule(existing);
            return;
        }
        Entry entry = new Entry(uuid, safe);
        try {
            entry.stickers.putAll(this.backend.getStickers(uuid));
            entry.failStreak = this.backend.getFailStreak(uuid);
            entry.failStar = this.backend.getFailStar(uuid);
        } catch (RuntimeException ex) {
            this.logger.warning("读取玩家数据失败，本次以空数据开始: " + ex);
        }
        this.cache.put(uuid, entry);
        // 顺带把 player 行补上：这样 /pu g <离线> 的名字反查一定找得到他，
        // 不用去问 Mojang（正版验证服上那一下会把主线程卡住）。
        synchronized (entry) {
            entry.dirtyName = true;
        }
        this.schedule(entry);
    }

    /** 玩家退服：落盘后驱逐。 */
    public void unloadPlayer(UUID uuid) {
        Entry entry = this.cache.get(uuid);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            entry.evictPending = true;
        }
        this.schedule(entry);
    }

    /** 把某个玩家的内存镜像立刻写穿（同步，调用线程阻塞）。 */
    public void flushPlayer(UUID uuid) {
        Entry entry = this.cache.get(uuid);
        if (entry != null) {
            this.flushEntry(entry);
        }
    }

    /** 把所有尚未落盘的条目同步写穿。关服收尾用。 */
    public void flushAll() {
        for (Entry entry : this.cache.values()) {
            this.flushEntry(entry);
        }
    }

    // ------------------------------------------------------------------ 读

    @Override
    public boolean hasPlayer(UUID uuid) {
        if (this.cache.containsKey(uuid)) {
            return true;
        }
        return this.backend.hasPlayer(uuid);
    }

    @Override
    public UUID findUuidByName(String name) {
        if (name == null) {
            return null;
        }
        UUID cached = this.nameIndex.get(name.toLowerCase(Locale.ROOT));
        if (cached != null) {
            return cached;
        }
        UUID found = this.backend.findUuidByName(name);
        if (found != null) {
            this.nameIndex.put(name.toLowerCase(Locale.ROOT), found);
        }
        return found;
    }

    @Override
    public int getSticker(UUID uuid, String type) {
        Entry entry = this.cache.get(uuid);
        if (entry == null) {
            return this.backend.getSticker(uuid, type);
        }
        synchronized (entry) {
            Integer value = entry.stickers.get(type);
            return value == null ? 0 : value;
        }
    }

    @Override
    public Map<String, Integer> getStickers(UUID uuid) {
        Entry entry = this.cache.get(uuid);
        if (entry == null) {
            return this.backend.getStickers(uuid);
        }
        synchronized (entry) {
            return new HashMap<String, Integer>(entry.stickers);
        }
    }

    @Override
    public int getFailStreak(UUID uuid) {
        Entry entry = this.cache.get(uuid);
        if (entry == null) {
            return this.backend.getFailStreak(uuid);
        }
        synchronized (entry) {
            return entry.failStreak;
        }
    }

    /** 新增（A2）：连败所属的原料星级档。 */
    @Override
    public int getFailStar(UUID uuid) {
        Entry entry = this.cache.get(uuid);
        if (entry == null) {
            return this.backend.getFailStar(uuid);
        }
        synchronized (entry) {
            return entry.failStar;
        }
    }

    // ------------------------------------------------------------------ 写

    @Override
    public void setSticker(UUID uuid, String name, String type, int amount) {
        String safe = this.remember(uuid, name);
        Entry entry = this.cache.get(uuid);
        if (entry == null) {
            // 离线玩家：不进缓存，直接同步写后端。
            this.backend.setSticker(uuid, safe, type, amount);
            return;
        }
        synchronized (entry) {
            entry.name = safe;
            entry.stickers.put(type, Math.max(amount, 0));
            entry.dirtyStickers.add(type);
        }
        this.schedule(entry);
    }

    @Override
    public void setFailStreak(UUID uuid, String name, int value) {
        String safe = this.remember(uuid, name);
        Entry entry = this.cache.get(uuid);
        if (entry == null) {
            this.backend.setFailStreak(uuid, safe, value);
            return;
        }
        synchronized (entry) {
            entry.name = safe;
            entry.failStreak = Math.max(value, 0);
            entry.dirtyFailProgress = true;
        }
        this.schedule(entry);
    }

    /** 新增（A2）：写入连败所属的原料星级档。 */
    @Override
    public void setFailStar(UUID uuid, String name, int star) {
        String safe = this.remember(uuid, name);
        Entry entry = this.cache.get(uuid);
        if (entry == null) {
            this.backend.setFailStar(uuid, safe, star);
            return;
        }
        synchronized (entry) {
            entry.name = safe;
            entry.failStar = Math.max(star, 0);
            entry.dirtyFailProgress = true;
        }
        this.schedule(entry);
    }

    @Override
    public void touchPlayer(UUID uuid, String name) {
        String safe = this.remember(uuid, name);
        Entry entry = this.cache.get(uuid);
        if (entry == null) {
            this.backend.touchPlayer(uuid, safe);
            return;
        }
        synchronized (entry) {
            entry.name = safe;
            entry.dirtyName = true;
        }
        this.schedule(entry);
    }

    // ------------------------------------------------------------ 委托 / 图鉴（透传）

    /*
     * 这两组方法刻意**不加缓存**，直接透传给后端。理由：
     *
     *   ① 它们不是热路径。委托一天只生成一次、进度在完成动作时才变；图鉴只在
     *      合成成功时写一条、打开界面时读一次。缓存能省下的往返是「每人每天个位数」，
     *      换来的却是「缓存与库不一致」的一整类新 bug（比如刚领完奖又看到可领）。
     *   ② 写入语义里已经有「读改写」的地方（{@link #dexAdd} 的 amount+1 在 SQL 里完成），
     *      一旦中间夹一层内存缓存，这个原子性就没了。
     *   ③ 失败语义要保留。这些方法是同步调用，写失败就该抛给调用方 ——
     *      走写回队列会把「领奖没落库」变成一件谁都看不见的事。
     *
     * 代价是 MySQL 上每次调用多一次网络往返，发生在主线程。实测这条路径每次合成
     * 至多两次（图鉴 +1），单次往返在局域网内是亚毫秒级，可以接受。
     */

    @Override
    public Set<String> listQuestDays(UUID uuid) {
        return this.backend.listQuestDays(uuid);
    }

    @Override
    public List<QuestData> getQuests(UUID uuid, String day) {
        return this.backend.getQuests(uuid, day);
    }

    @Override
    public void insertQuests(UUID uuid, String name, String day, List<QuestData> quests) {
        this.backend.insertQuests(uuid, this.remember(uuid, name), day, quests);
    }

    @Override
    public void setQuestProgress(UUID uuid, String day, int slot, int progress) {
        this.backend.setQuestProgress(uuid, day, slot, progress);
    }

    @Override
    public void setQuestClaimed(UUID uuid, String day, int slot) {
        this.backend.setQuestClaimed(uuid, day, slot);
    }

    @Override
    public int dexSpeciesCount(UUID uuid) {
        return this.backend.dexSpeciesCount(uuid);
    }

    @Override
    public int dexTotalCount(UUID uuid) {
        return this.backend.dexTotalCount(uuid);
    }

    @Override
    public Map<String, Integer> dexAll(UUID uuid) {
        return this.backend.dexAll(uuid);
    }

    @Override
    public int dexAdd(UUID uuid, String name, String species) {
        return this.backend.dexAdd(uuid, this.remember(uuid, name), species);
    }

    @Override
    public int dexAmount(UUID uuid, String species) {
        return this.backend.dexAmount(uuid, species);
    }

    @Override
    public Set<Integer> dexClaimed(UUID uuid) {
        return this.backend.dexClaimed(uuid);
    }

    @Override
    public boolean dexClaim(UUID uuid, String name, int milestone) {
        return this.backend.dexClaim(uuid, this.remember(uuid, name), milestone);
    }

    /** 记下 name -> uuid 并返回规范化后的名字（空名字会被写成空串而不是 NULL）。 */
    private String remember(UUID uuid, String name) {
        String safe = CachedStorage.nameOf(name);
        if (!safe.isEmpty()) {
            this.nameIndex.put(safe.toLowerCase(Locale.ROOT), uuid);
        }
        return safe;
    }

    private static String nameOf(String name) {
        return name == null ? "" : name;
    }

    /**
     * {@inheritDoc}
     *
     * <p>覆写的原因：接口的默认实现是「读 + 写」两步，而在缓存层这两步之间如果被
     * 写回线程插进来，就会出现「扣了两次」或「少扣一次」。这里整段放进条目锁里。</p>
     */
    @Override
    public int addSticker(UUID uuid, String name, String type, int delta) {
        Entry entry = this.cache.get(uuid);
        if (entry == null) {
            return this.backend.addSticker(uuid, name, type, delta);
        }
        int next;
        String safe = CachedStorage.nameOf(name);
        synchronized (entry) {
            entry.name = safe;
            Integer current = entry.stickers.get(type);
            next = Math.max((current == null ? 0 : current) + delta, 0);
            entry.stickers.put(type, next);
            entry.dirtyStickers.add(type);
        }
        this.schedule(entry);
        return next;
    }

    // ------------------------------------------------------------------ 落盘

    /** 同步模式直接写穿；异步模式丢给写队列（熔断期间只标脏，交给 {@link #sweep()}）。 */
    private void schedule(final Entry entry) {
        if (!this.writeBehind) {
            this.flushEntry(entry);
            return;
        }
        long now = System.currentTimeMillis();
        if (now < this.breakerUntil) {
            // 熔断期间不排队：后端正不可用，排队只会让全服所有人的写入一起堵在
            // 那个单线程队列上，而每一条都要等满一次连接超时。脏标记留着，
            // 由 sweep() 在熔断到期后统一补写。
            synchronized (entry) {
                entry.retryAt = this.breakerUntil;
            }
            return;
        }
        Async.write(new Runnable() {
            @Override
            public void run() {
                CachedStorage.this.flushEntry(entry);
            }
        });
    }

    /**
     * 兜底扫描：把「已经到期但还没落盘」的脏条目重新排进写队列。由定时器每秒调一次。
     *
     * <p>它补的是两个洞：</p>
     * <ol>
     *   <li><b>放弃主动重排之后的条目</b>：连续失败超过 {@link #MAX_RETRY} 次后我们不再
     *       自己重排（见 {@link #flushEntry} 的注释），没有它这些数据就只剩关服时
     *       那一次 {@link #flushAll()} 的机会。</li>
     *   <li><b>挂着不动的玩家</b>：脏标记留在内存里，而这个玩家再也没有触发过任何写入 ——
     *       没有兜底扫描的话，他会一直卡到关服，而「强杀关服」是抓不到 flushAll 的。</li>
     * </ol>
     */
    public void sweep() {
        if (this.closed) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < this.breakerUntil) {
            return;
        }
        if (this.breakerUntil != 0L) {
            this.logger.info("存储后端熔断窗口已过，恢复写回。");
            this.breakerUntil = 0L;
        }
        for (Entry entry : this.cache.values()) {
            boolean due;
            synchronized (entry) {
                due = !entry.clean() && now >= entry.retryAt;
            }
            if (due) {
                this.schedule(entry);
            }
        }
    }

    /**
     * 把一条内存镜像写进后端。
     *
     * <p>过程是：先在锁里摘一份「脏字段快照」并清掉脏标记，再在锁外做 IO。摘快照时清标记
     * 是关键 —— 如果 IO 期间玩家又改了一次，那次修改会重新置脏并再排一次写回，
     * 而单线程写队列保证它排在后面，所以最终落盘的一定是最新值。</p>
     */
    private void flushEntry(Entry entry) {
        String name;
        Map<String, Integer> stickerSnapshot = null;
        boolean writeFailProgress = false;
        int streak = 0;
        int star = 0;
        boolean writeName = false;
        synchronized (entry) {
            name = entry.name;
            if (!entry.dirtyStickers.isEmpty()) {
                stickerSnapshot = new HashMap<String, Integer>();
                for (String key : entry.dirtyStickers) {
                    Integer value = entry.stickers.get(key);
                    stickerSnapshot.put(key, value == null ? 0 : value);
                }
                entry.dirtyStickers.clear();
            }
            if (entry.dirtyFailProgress) {
                streak = entry.failStreak;
                star = entry.failStar;
                writeFailProgress = true;
                entry.dirtyFailProgress = false;
            }
            if (entry.dirtyName) {
                writeName = true;
                entry.dirtyName = false;
            }
            if (stickerSnapshot == null && !writeFailProgress && !writeName) {
                this.maybeEvict(entry);
                return;
            }
        }

        try {
            if (writeName) {
                this.backend.touchPlayer(entry.uuid, name);
            }
            if (stickerSnapshot != null) {
                for (Map.Entry<String, Integer> item : stickerSnapshot.entrySet()) {
                    this.backend.setSticker(entry.uuid, name, item.getKey(), item.getValue());
                }
            }
            if (writeFailProgress) {
                // 新增（A2）：档位与次数一起写。它们是同一份进度的两个字段，
                // 只写一个会让「次数记着、档位还是上一档」的状态被持久化，
                // 而那个状态正好会让保底在错误的档位上触发或被错误地拦下。
                this.backend.setFailStreak(entry.uuid, name, streak);
                this.backend.setFailStar(entry.uuid, name, star);
            }
            synchronized (entry) {
                entry.failures = 0;
                entry.retryAt = 0L;
            }
            this.noteSuccess();
        } catch (RuntimeException ex) {
            int failures;
            synchronized (entry) {
                // 把刚摘走的脏标记放回去。放的是「键」而不是当时的「值」——
                // 下一次重试会重新读一遍当前值，那才是最新值。写入用的是绝对值，
                // 所以重放不会多扣一次资产。
                //
                // 保底进度（次数 + 档位）是一个标记管两个字段：失败时要整块回滚，
                // 否则会出现「次数写成功、档位没写成功」的错位。
                if (stickerSnapshot != null) {
                    entry.dirtyStickers.addAll(stickerSnapshot.keySet());
                }
                entry.dirtyFailProgress |= writeFailProgress;
                entry.dirtyName |= writeName;
                entry.failures++;
                failures = entry.failures;
                entry.retryAt = System.currentTimeMillis() + CachedStorage.backoffMs(failures);
            }
            this.logger.severe("写存储后端失败（第 " + failures + " 次，玩家 " + name
                    + "），数据保留在内存中，将在 " + CachedStorage.backoffMs(failures)
                    + "ms 后重试: " + ex);
            this.noteFailure(failures);
            // 从这里开始不再自己重排。立刻重排会把几次重试串在同一个单线程写队列上，
            // 而每一次都可能撞满一次连接超时 —— 最坏十几秒里全服所有人的写入都得排队。
            // 交给 sweep() 按退避时间兜底，全服写入不受牵连。
            if (failures == MAX_RETRY) {
                this.logger.severe("存储后端已连续写入失败 " + MAX_RETRY
                        + " 次，改为兜底重试；上游不会再等待这次写入的结果。");
            }
            return;
        }
        this.maybeEvict(entry);
    }

    /** 失败后的退避时长：250ms 起，每次翻倍，封顶 10 秒。 */
    private static long backoffMs(int failures) {
        long delay = RETRY_BASE_MS;
        for (int i = 1; i < failures && delay < RETRY_MAX_MS; i++) {
            delay <<= 1;
        }
        return delay > RETRY_MAX_MS ? RETRY_MAX_MS : delay;
    }

    /** 记一次写失败；同一条数据反复失败时才升级为「后端不可用」并打开熔断器。 */
    private void noteFailure(int entryFailures) {
        int total = ++this.consecutiveFailures;
        if (entryFailures >= MAX_RETRY && total >= BREAKER_THRESHOLD && this.breakerUntil == 0L) {
            this.breakerUntil = System.currentTimeMillis() + BREAKER_MS;
            this.logger.severe("存储后端连续失败 " + total + " 次，暂停 " + (BREAKER_MS / 1000)
                    + " 秒内的写回排队（避免全服写入都堵在连接超时上）；数据仍在内存里，不会丢。");
        }
    }

    /** 记一次写成功：清掉失败计数并复位熔断器。 */
    private void noteSuccess() {
        this.consecutiveFailures = 0;
        this.breakerUntil = 0L;
    }

    /** 该驱逐的驱逐：只有「已退服」且「没有未落盘的改动」才允许。 */
    private void maybeEvict(Entry entry) {
        synchronized (entry) {
            if (entry.evictPending && entry.clean()) {
                this.cache.remove(entry.uuid, entry);
            }
        }
    }
}
