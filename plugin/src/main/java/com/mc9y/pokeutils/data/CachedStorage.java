package com.mc9y.pokeutils.data;

import java.util.HashMap;
import java.util.HashSet;
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
 */
public class CachedStorage implements Storage {

    /** 单条写回连续失败多少次之后放弃重试（放弃时打 SEVERE，数据仍留在内存里）。 */
    private static final int MAX_RETRY = 3;

    private final Storage backend;
    private final Logger logger;
    private final boolean writeBehind;
    private final long flushTimeoutMs;

    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<UUID, Entry>();
    /** 名字（小写）-> UUID。给 {@code /pu g <离线玩家>} 这类反查用，避免每次都查库。 */
    private final ConcurrentHashMap<String, UUID> nameIndex = new ConcurrentHashMap<String, UUID>();

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
        final Map<String, Integer> stickers = new HashMap<String, Integer>();
        final Set<String> dirtyStickers = new HashSet<String>();
        boolean dirtyStreak;
        boolean dirtyName;
        /** 玩家已退服，落盘完成后可以驱逐。 */
        boolean evictPending;
        /** 连续写失败次数，成功后清零。 */
        int failures;

        Entry(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        boolean clean() {
            return this.dirtyStickers.isEmpty() && !this.dirtyStreak && !this.dirtyName;
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
    public Storage backend() {
        return this.backend;
    }

    /** 写模式：true = 异步写回（MySQL）。测试与 `/pu debug` 会看它。 */
    public boolean isWriteBehind() {
        return this.writeBehind;
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
            entry.dirtyStreak = true;
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

    /** 同步模式直接写穿；异步模式丢给写队列。 */
    private void schedule(final Entry entry) {
        if (!this.writeBehind) {
            this.flushEntry(entry);
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
     * 把一条内存镜像写进后端。
     *
     * <p>过程是：先在锁里摘一份「脏字段快照」并清掉脏标记，再在锁外做 IO。摘快照时清标记
     * 是关键 —— 如果 IO 期间玩家又改了一次，那次修改会重新置脏并再排一次写回，
     * 而单线程写队列保证它排在后面，所以最终落盘的一定是最新值。</p>
     */
    private void flushEntry(Entry entry) {
        String name;
        Map<String, Integer> stickerSnapshot = null;
        boolean writeStreak = false;
        int streak = 0;
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
            if (entry.dirtyStreak) {
                streak = entry.failStreak;
                writeStreak = true;
                entry.dirtyStreak = false;
            }
            if (entry.dirtyName) {
                writeName = true;
                entry.dirtyName = false;
            }
            if (stickerSnapshot == null && !writeStreak && !writeName) {
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
            if (writeStreak) {
                this.backend.setFailStreak(entry.uuid, name, streak);
            }
            synchronized (entry) {
                entry.failures = 0;
            }
        } catch (RuntimeException ex) {
            boolean retry;
            int failures;
            synchronized (entry) {
                // 把刚摘走的脏标记放回去。放的是「键」而不是当时的「值」——
                // 下一次重试会重新读一遍当前值，那才是最新值。
                if (stickerSnapshot != null) {
                    entry.dirtyStickers.addAll(stickerSnapshot.keySet());
                }
                entry.dirtyStreak |= writeStreak;
                entry.dirtyName |= writeName;
                entry.failures++;
                failures = entry.failures;
                retry = entry.failures <= MAX_RETRY;
            }
            this.logger.severe("写存储后端失败（第 " + failures + " 次，玩家 " + name + "）: " + ex);
            if (retry && this.writeBehind) {
                this.schedule(entry);
            }
            return;
        }
        this.maybeEvict(entry);
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
