package com.mc9y.pokeutils.data;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 纯文本后端：<b>每个玩家一个 YAML 文件</b>，直接把数据摆在运维眼皮底下。
 *
 * <h3>为什么要有它</h3>
 * <p>SQLite 与 MySQL 都要靠外部工具才能看数据：前者需要一个 DB 浏览器，后者要账号密码和
 * 一句 {@code SELECT}。但有一类场景是「看得见」本身比性能更值钱 —— 小服 / 单机测试 /
 * 出事时要把某个人的粘剂数量手工改回来 / 想用 git 或网盘给数据做版本管理。这时一个
 * 能直接用记事本打开、能 diff 的目录，比任何数据库都好用。</p>
 *
 * <p>它<b>不是</b> MySQL 的替代品：不适合高并发，也<b>完全不能</b>多服共享
 * （见下面「三条硬限制」）。</p>
 *
 * <h3>目录布局</h3>
 * <pre>
 * plugins/PokeUtils-remake/yaml/
 *   players/
 *     5f2c...-....yml      ← 一个玩家一个文件，文件名就是 UUID
 * </pre>
 * <p>单个文件的内容：</p>
 * <pre>
 * name: Steve
 * fail-streak: 2
 * fail-star: 5
 * stickers:
 *   PT: 12
 *   JL: 3
 * quests:
 *   d2026-10-19:          ← 注意前缀 d，理由见「坑」一节
 *     '0': {type: SUCCESS, target: 3, star: 0, progress: 1, claimed: false}
 * dex:
 *   Pikachu: 3
 * dex-claimed: [10, 20]
 * </pre>
 *
 * <h3>为什么是「每玩家一个文件」而不是一个大文件</h3>
 * <p>YAML 没有「改一行」这种操作 —— {@code save()} 每次都会把整份文档重新序列化。
 * 如果所有玩家挤在一个文件里，那么任何一个人合成一次，都要把全服的数据重写一遍：
 * 1000 人服就是每次点击重写几 MB。按玩家拆开之后，重写的范围被压到那一个人的几 KB，
 * 代价是「跨玩家查询」（{@code listPlayers} / {@code purgeQuestsBefore}）要遍历目录 ——
 * 而那两件事一个只在 {@code /pu migrate} 时做，一个每天做一次，都很便宜。</p>
 *
 * <h3>三条硬限制（都是刻意不解决，而不是没想到）</h3>
 * <ol>
 *   <li><b>单实例</b>：文件没有锁、没有版本号，两个服务端同时写同一份目录必然互相覆盖。
 *       想多服共享，只能用 MySQL。</li>
 *   <li><b>写入成本与字段数成正比</b>：{@link CachedStorage#flushEntry} 一次回写会连着
 *       调 {@code setSticker}（每种脏粘剂一次）、{@code setFailStreak}、{@code setFailStar}，
 *       而这里的每个方法都要各自重写一次文件。小服感觉不到（几 KB 的写入在毫秒以下），
 *       大服应该换 SQLite —— 它一次事务就能把这几处合并成一次提交。</li>
 *   <li><b>没有索引</b>：{@code findUuidByName} 依赖启动时扫出来的内存表，外部手改
 *       文件「新增了一个人」之后，要重启或等下一次扫描才会被认到。</li>
 * </ol>
 *
 * <h3>两个必须避开的坑</h3>
 * <p><b>① 日期键要加前缀。</b>SnakeYAML 会把裸写的 {@code 2026-10-19} 隐式解析成
 * {@code java.util.Date}，于是 {@code getKeys(false)} 拿回来的是
 * 「Mon Oct 19 00:00:00 CST 2026」而不是日期串，委托就再也读不出来了。所有日期键统一
 * 写成 {@code d2026-10-19}，前缀让这个标量不可能命中时间戳规则。</p>
 *
 * <p><b>② 落盘必须是「写临时文件再原子改名」。</b>直接 {@code save(目标文件)} 会在
 * 序列化到一半时把原文件截断 —— 服务端被 {@code taskkill /F} 或断电正好撞上那一刻，
 * 这个玩家的全部数据就只剩半份 YAML（而且是语法不完整的半份，连解析都过不去）。
 * 先写 {@code .yml.tmp} 再 rename，rename 在同一卷上是原子的：要么是旧文件，要么是
 * 完整的新文件，不存在中间态。</p>
 *
 * <p><b>读坏文件的处理</b>：解析失败时<b>不</b>按空数据处理 —— 那会让接下来的第一次
 * 写入把原文件覆盖成空壳。这里的做法是把坏文件改名成 {@code .yml.corrupt-<时间戳>}
 * 留证，再以空数据继续，并在日志里喊一声。</p>
 */
public class YamlStorage implements Storage {

    private static final String KEY_NAME = "name";
    private static final String KEY_FAIL_STREAK = "fail-streak";
    private static final String KEY_FAIL_STAR = "fail-star";
    private static final String KEY_STICKERS = "stickers";
    private static final String KEY_QUESTS = "quests";
    private static final String KEY_DEX = "dex";
    private static final String KEY_DEX_CLAIMED = "dex-claimed";
    /** 日期键的前缀，用来躲开 SnakeYAML 的时间戳隐式解析（见类注释「坑 ①」）。 */
    private static final String DAY_PREFIX = "d";
    private static final String SUFFIX = ".yml";
    private static final String TEMP_SUFFIX = ".yml.tmp";

    /** 数据根目录（配置里的 {@code Storage.yaml.dir}）。 */
    private final File rootDir;
    /** 玩家文件目录，恒为 {@code rootDir/players}。 */
    private final File playersDir;
    private final Logger logger;

    private volatile boolean open;
    /** 名字（小写）-> UUID。启动时扫一遍建起来，之后由写路径增量维护。 */
    private final Map<String, UUID> nameIndex = new HashMap<String, UUID>();
    /** 上次全量重建索引的时间，用来挡住「连续查不存在的名字」把目录扫爆。 */
    private long lastIndexRebuild;

    public YamlStorage(File rootDir) {
        this(rootDir, Logger.getLogger("PokeUtils"));
    }

    public YamlStorage(File rootDir, Logger logger) {
        this.rootDir = rootDir;
        this.playersDir = new File(rootDir, "players");
        this.logger = logger == null ? Logger.getLogger("PokeUtils") : logger;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public synchronized void open() throws Exception {
        if (!this.playersDir.isDirectory() && !this.playersDir.mkdirs()) {
            throw new IOException("无法创建 YAML 数据目录: " + this.playersDir.getPath());
        }
        this.open = true;
        int players = this.rebuildNameIndex();
        this.logger.info("YAML 存储已就绪 -> " + this.playersDir.getPath()
                + "（已识别 " + players + " 名玩家）");
    }

    @Override
    public synchronized void close() {
        this.open = false;
        this.nameIndex.clear();
    }

    @Override
    public boolean isOpen() {
        return this.open;
    }

    @Override
    public String kind() {
        return "yaml";
    }

    @Override
    public String describe() {
        return "YAML 文件 -> " + this.playersDir.getPath() + "（每玩家一个文件）";
    }

    @Override
    public Storage backend() {
        return this;
    }

    /** 供启动日志与测试查看数据目录。 */
    public File getPlayersDir() {
        return this.playersDir;
    }

    @Override
    public boolean checkpoint() {
        // YAML 没有 WAL / 没有需要合并的附属文件，这里天然无事可做。
        return false;
    }

    // ------------------------------------------------------------------ 文件读写

    private File fileOf(UUID uuid) {
        return new File(this.playersDir, uuid.toString() + SUFFIX);
    }

    /**
     * 读一个玩家的文档。文件不存在就给一份空文档。
     *
     * <p>解析失败时先把坏文件改名留证，再返回空文档 —— 直接返回空文档会让下一次写入
     * 把原文件覆盖掉，那等于「因为文件坏了所以把文件删了」。</p>
     */
    private YamlConfiguration read(UUID uuid) {
        File file = this.fileOf(uuid);
        if (!file.isFile()) {
            return new YamlConfiguration();
        }
        try {
            return YamlConfiguration.loadConfiguration(file);
        } catch (RuntimeException ex) {
            File backup = new File(this.playersDir,
                    uuid.toString() + ".yml.corrupt-" + System.currentTimeMillis());
            boolean renamed = file.renameTo(backup);
            this.logger.severe("YAML 玩家文件解析失败，已" + (renamed ? "改名留证: " + backup.getName()
                    : "尝试留证但改名失败（原文件仍在原地）") + "。本次按空数据继续: " + ex);
            return new YamlConfiguration();
        }
    }

    /**
     * 写一个玩家的文档：先写 {@code .yml.tmp}，再原子改名覆盖目标。
     *
     * <p>{@code ATOMIC_MOVE} 在个别文件系统（部分网络盘、FAT32）上不被支持，这时退回
     * 普通覆盖 —— 语义上仍然正确，只是失去了「断电不产生半份文件」的保证，所以不静默：
     * 这一点写进类注释，运维该知道自己的盘属于哪一类。</p>
     */
    private void write(UUID uuid, YamlConfiguration cfg) {
        File target = this.fileOf(uuid);
        File temp = new File(this.playersDir, uuid.toString() + TEMP_SUFFIX);
        try {
            cfg.save(temp);
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new StorageException("写入 YAML 玩家文件失败: " + target.getPath()
                    + " -> " + ex.getMessage(), ex);
        } finally {
            if (temp.isFile() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    private void ensureOpen() {
        if (!this.open) {
            throw new StorageException("YAML 存储未打开，无法写入(" + this.playersDir.getPath() + ")");
        }
    }

    /** 把名字写进文档（只在变化时写，避免为此多一次落盘）。 */
    private void touchName(YamlConfiguration cfg, String name) {
        String safe = name == null ? "" : name;
        if (!safe.isEmpty() && !safe.equals(cfg.getString(KEY_NAME, ""))) {
            cfg.set(KEY_NAME, safe);
        }
    }

    private void rememberName(String name, UUID uuid) {
        if (name == null || name.isEmpty() || uuid == null) {
            return;
        }
        this.nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
    }

    private static UUID parseUuid(String fileName) {
        if (fileName == null || !fileName.endsWith(SUFFIX)) {
            return null;
        }
        String raw = fileName.substring(0, fileName.length() - SUFFIX.length());
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private List<File> playerFiles() {
        List<File> out = new ArrayList<File>();
        File[] files = this.playersDir.listFiles();
        if (files == null) {
            return out;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(SUFFIX)
                    && !file.getName().endsWith(".tmp")) {
                out.add(file);
            }
        }
        return out;
    }

    /**
     * 全量扫一遍目录重建「名字 -> UUID」索引，返回识别到的名字数。
     *
     * <p>启动时跑一次；之后 {@link #findUuidByName} 未命中时会以 1 秒为间隔重试 ——
     * 间隔是为了挡住「连续查一堆不存在的名字」把目录反复扫爆，而那正是
     * {@code /pu g <离线玩家>} 打错字时会发生的事。</p>
     */
    private int rebuildNameIndex() {
        this.nameIndex.clear();
        this.lastIndexRebuild = System.currentTimeMillis();
        int broken = 0;
        for (File file : this.playerFiles()) {
            UUID uuid = parseUuid(file.getName());
            if (uuid == null) {
                continue;
            }
            try {
                this.rememberName(YamlConfiguration.loadConfiguration(file).getString(KEY_NAME, ""), uuid);
            } catch (RuntimeException ex) {
                broken++;
            }
        }
        if (broken > 0) {
            this.logger.warning("YAML 玩家目录里有 " + broken + " 个文件解析失败，已跳过："
                    + "这些人的名字反查会查不到，数据本身未被改动。");
        }
        return this.nameIndex.size();
    }

    // ------------------------------------------------------------------ 玩家主记录

    @Override
    public synchronized boolean hasPlayer(UUID uuid) {
        return this.open && uuid != null && this.fileOf(uuid).isFile();
    }

    @Override
    public synchronized UUID findUuidByName(String name) {
        if (!this.open || name == null) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT);
        UUID cached = this.nameIndex.get(key);
        if (cached != null) {
            return cached;
        }
        // 可能是「外部手改了文件」或「这个名字确实不存在」。限频重扫一次再判断。
        if (System.currentTimeMillis() - this.lastIndexRebuild > 1000L) {
            this.rebuildNameIndex();
            return this.nameIndex.get(key);
        }
        return null;
    }

    @Override
    public synchronized void touchPlayer(UUID uuid, String name) {
        this.ensureOpen();
        String safe = name == null ? "" : name;
        boolean exists = this.fileOf(uuid).isFile();
        YamlConfiguration cfg = this.read(uuid);
        if (!exists || !safe.equals(cfg.getString(KEY_NAME, ""))) {
            cfg.set(KEY_NAME, safe);
            this.write(uuid, cfg);
        }
        this.rememberName(safe, uuid);
    }

    @Override
    public synchronized List<Storage.PlayerRecord> listPlayers() {
        List<Storage.PlayerRecord> out = new ArrayList<Storage.PlayerRecord>();
        if (!this.open) {
            return out;
        }
        for (File file : this.playerFiles()) {
            UUID uuid = parseUuid(file.getName());
            if (uuid == null) {
                continue;
            }
            YamlConfiguration cfg = this.read(uuid);
            out.add(new Storage.PlayerRecord(uuid, cfg.getString(KEY_NAME, ""),
                    Math.max(cfg.getInt(KEY_FAIL_STREAK, 0), 0),
                    Math.max(cfg.getInt(KEY_FAIL_STAR, 0), 0)));
        }
        return out;
    }

    @Override
    public synchronized List<Storage.StickerRecord> listStickers() {
        List<Storage.StickerRecord> out = new ArrayList<Storage.StickerRecord>();
        if (!this.open) {
            return out;
        }
        for (File file : this.playerFiles()) {
            UUID uuid = parseUuid(file.getName());
            if (uuid == null) {
                continue;
            }
            ConfigurationSection stickers = this.read(uuid).getConfigurationSection(KEY_STICKERS);
            if (stickers == null) {
                continue;
            }
            for (String type : stickers.getKeys(false)) {
                out.add(new Storage.StickerRecord(uuid, type, Math.max(stickers.getInt(type, 0), 0)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 粘剂

    @Override
    public synchronized int getSticker(UUID uuid, String type) {
        if (!this.open || uuid == null || type == null) {
            return 0;
        }
        ConfigurationSection stickers = this.read(uuid).getConfigurationSection(KEY_STICKERS);
        return stickers == null ? 0 : Math.max(stickers.getInt(type, 0), 0);
    }

    @Override
    public synchronized Map<String, Integer> getStickers(UUID uuid) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        if (!this.open || uuid == null) {
            return out;
        }
        ConfigurationSection stickers = this.read(uuid).getConfigurationSection(KEY_STICKERS);
        if (stickers == null) {
            return out;
        }
        for (String type : stickers.getKeys(false)) {
            out.put(type, Math.max(stickers.getInt(type, 0), 0));
        }
        return out;
    }

    @Override
    public synchronized void setSticker(UUID uuid, String name, String type, int amount) {
        this.ensureOpen();
        if (uuid == null || type == null) {
            return;
        }
        YamlConfiguration cfg = this.read(uuid);
        ConfigurationSection stickers = cfg.getConfigurationSection(KEY_STICKERS);
        if (stickers == null) {
            stickers = cfg.createSection(KEY_STICKERS);
        }
        stickers.set(type, Math.max(amount, 0));
        this.touchName(cfg, name);
        this.write(uuid, cfg);
        this.rememberName(name, uuid);
    }

    // ------------------------------------------------------------------ 保底计数

    @Override
    public synchronized int getFailStreak(UUID uuid) {
        if (!this.open || uuid == null) {
            return 0;
        }
        return Math.max(this.read(uuid).getInt(KEY_FAIL_STREAK, 0), 0);
    }

    @Override
    public synchronized void setFailStreak(UUID uuid, String name, int value) {
        this.ensureOpen();
        YamlConfiguration cfg = this.read(uuid);
        cfg.set(KEY_FAIL_STREAK, Math.max(value, 0));
        this.touchName(cfg, name);
        this.write(uuid, cfg);
        this.rememberName(name, uuid);
    }

    @Override
    public synchronized int getFailStar(UUID uuid) {
        if (!this.open || uuid == null) {
            return 0;
        }
        return Math.max(this.read(uuid).getInt(KEY_FAIL_STAR, 0), 0);
    }

    @Override
    public synchronized void setFailStar(UUID uuid, String name, int star) {
        this.ensureOpen();
        YamlConfiguration cfg = this.read(uuid);
        cfg.set(KEY_FAIL_STAR, Math.max(star, 0));
        this.touchName(cfg, name);
        this.write(uuid, cfg);
        this.rememberName(name, uuid);
    }

    /**
     * 一次读回粘剂 + 连败次数 + 档位。
     *
     * <p>三个后端都是「一次往返」：SQLite / MySQL 靠一条 {@code LEFT JOIN}，这里靠
     * 「只读一个文件」。进服路径因此没有额外成本。</p>
     */
    @Override
    public synchronized PlayerSnapshot loadSnapshot(UUID uuid) {
        Map<String, Integer> stickers = new HashMap<String, Integer>();
        if (!this.open || uuid == null) {
            return new PlayerSnapshot(stickers, 0, 0);
        }
        YamlConfiguration cfg = this.read(uuid);
        ConfigurationSection section = cfg.getConfigurationSection(KEY_STICKERS);
        if (section != null) {
            for (String type : section.getKeys(false)) {
                stickers.put(type, Math.max(section.getInt(type, 0), 0));
            }
        }
        return new PlayerSnapshot(stickers, cfg.getInt(KEY_FAIL_STREAK, 0), cfg.getInt(KEY_FAIL_STAR, 0));
    }

    // ------------------------------------------------------------------ 每日委托

    /** 该玩家存在委托记录的日期（升序）。只给 {@code /pu migrate} 用。 */
    @Override
    public synchronized Set<String> listQuestDays(UUID uuid) {
        Set<String> out = new LinkedHashSet<String>();
        if (!this.open || uuid == null) {
            return out;
        }
        ConfigurationSection quests = this.read(uuid).getConfigurationSection(KEY_QUESTS);
        if (quests == null) {
            return out;
        }
        List<String> days = new ArrayList<String>();
        for (String key : quests.getKeys(false)) {
            if (key.startsWith(DAY_PREFIX)) {
                days.add(key.substring(DAY_PREFIX.length()));
            }
        }
        Collections.sort(days);
        out.addAll(days);
        return out;
    }

    @Override
    public synchronized List<QuestData> getQuests(UUID uuid, String day) {
        List<QuestData> out = new ArrayList<QuestData>();
        if (!this.open || uuid == null || day == null) {
            return out;
        }
        ConfigurationSection daySection = this.read(uuid)
                .getConfigurationSection(KEY_QUESTS + "." + DAY_PREFIX + day);
        if (daySection == null) {
            return out;
        }
        List<Integer> slots = new ArrayList<Integer>();
        for (String key : daySection.getKeys(false)) {
            try {
                slots.add(Integer.valueOf(key));
            } catch (NumberFormatException ignored) {
                // 非数字槽位是外部手改留下的脏键，跳过即可，不影响其它条目。
            }
        }
        Collections.sort(slots);
        for (Integer slot : slots) {
            String path = String.valueOf(slot);
            out.add(new QuestData(slot.intValue(),
                    daySection.getString(path + ".type", ""),
                    daySection.getInt(path + ".target", 1),
                    daySection.getInt(path + ".star", 0),
                    Math.max(daySection.getInt(path + ".progress", 0), 0),
                    daySection.getBoolean(path + ".claimed", false)));
        }
        return out;
    }

    /**
     * 落库一组委托，语义等同 {@code INSERT OR IGNORE}：已存在的槽位一律不动。
     *
     * <p>这条「已存在就不碰」是委托功能能正常工作的前提 —— 否则玩家每打开一次界面
     * 都会把已有进度冲回 0。</p>
     */
    @Override
    public synchronized void insertQuests(UUID uuid, String name, String day, List<QuestData> quests) {
        this.ensureOpen();
        if (day == null || quests == null || quests.isEmpty()) {
            return;
        }
        YamlConfiguration cfg = this.read(uuid);
        String base = KEY_QUESTS + "." + DAY_PREFIX + day;
        ConfigurationSection daySection = cfg.getConfigurationSection(base);
        if (daySection == null) {
            daySection = cfg.createSection(base);
        }
        boolean changed = false;
        for (QuestData quest : quests) {
            String slot = String.valueOf(quest.getSlot());
            if (daySection.contains(slot)) {
                continue;
            }
            ConfigurationSection item = daySection.createSection(slot);
            item.set("type", quest.getType());
            item.set("target", quest.getTarget());
            item.set("star", quest.getStar());
            item.set("progress", 0);
            item.set("claimed", false);
            changed = true;
        }
        if (changed) {
            this.touchName(cfg, name);
            this.write(uuid, cfg);
        }
        this.rememberName(name, uuid);
    }

    /**
     * 写某条委托的进度（绝对值）。
     *
     * <p>目标槽位不存在时<b>静默跳过</b>，与 SQL 的 {@code UPDATE ... WHERE} 匹配到 0 行
     * 完全一致。不在这一步「顺手建一条」—— 那会让「先写进度、后插委托」的错误顺序
     * 在 YAML 后端上反而看起来能跑，掩盖真正的 bug。</p>
     */
    @Override
    public synchronized void setQuestProgress(UUID uuid, String day, int slot, int progress) {
        this.ensureOpen();
        if (day == null) {
            return;
        }
        YamlConfiguration cfg = this.read(uuid);
        String path = KEY_QUESTS + "." + DAY_PREFIX + day + "." + slot + ".progress";
        if (!cfg.contains(path)) {
            return;
        }
        cfg.set(path, Math.max(progress, 0));
        this.write(uuid, cfg);
    }

    @Override
    public synchronized void setQuestProgressBatch(UUID uuid, String day,
                                                   Map<Integer, Integer> progressBySlot) {
        if (progressBySlot == null || progressBySlot.isEmpty()) {
            return;
        }
        this.ensureOpen();
        if (day == null) {
            return;
        }
        YamlConfiguration cfg = this.read(uuid);
        boolean changed = false;
        for (Map.Entry<Integer, Integer> entry : progressBySlot.entrySet()) {
            String path = KEY_QUESTS + "." + DAY_PREFIX + day + "." + entry.getKey() + ".progress";
            if (!cfg.contains(path)) {
                continue;
            }
            cfg.set(path, Math.max(entry.getValue().intValue(), 0));
            changed = true;
        }
        if (changed) {
            this.write(uuid, cfg);
        }
    }

    @Override
    public synchronized void setQuestClaimed(UUID uuid, String day, int slot) {
        this.ensureOpen();
        if (day == null) {
            return;
        }
        YamlConfiguration cfg = this.read(uuid);
        String path = KEY_QUESTS + "." + DAY_PREFIX + day + "." + slot + ".claimed";
        if (!cfg.contains(path)) {
            return;
        }
        cfg.set(path, Boolean.TRUE);
        this.write(uuid, cfg);
    }

    /**
     * 删掉早于 {@code day} 的委托，返回删掉的<b>委托条数</b>。
     *
     * <p>返回值的口径要和 SQL 后端的 {@code DELETE ... executeUpdate()} 对齐 —— 那边数的是
     * 行数（一条委托一行），不是天数。口径不一致会让 {@code /pu quest purge} 的日志
     * 在换后端之后忽然差一个数量级。</p>
     */
    @Override
    public synchronized int purgeQuestsBefore(String day) {
        if (!this.open || day == null || day.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (File file : this.playerFiles()) {
            UUID uuid = parseUuid(file.getName());
            if (uuid == null) {
                continue;
            }
            YamlConfiguration cfg = this.read(uuid);
            ConfigurationSection quests = cfg.getConfigurationSection(KEY_QUESTS);
            if (quests == null) {
                continue;
            }
            boolean changed = false;
            for (String key : new ArrayList<String>(quests.getKeys(false))) {
                if (!key.startsWith(DAY_PREFIX)) {
                    continue;
                }
                if (key.substring(DAY_PREFIX.length()).compareTo(day) >= 0) {
                    continue;
                }
                ConfigurationSection old = quests.getConfigurationSection(key);
                removed += old == null ? 0 : old.getKeys(false).size();
                quests.set(key, null);
                changed = true;
            }
            if (changed) {
                this.write(uuid, cfg);
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ 合成图鉴

    @Override
    public synchronized int dexSpeciesCount(UUID uuid) {
        if (!this.open || uuid == null) {
            return 0;
        }
        ConfigurationSection dex = this.read(uuid).getConfigurationSection(KEY_DEX);
        return dex == null ? 0 : dex.getKeys(false).size();
    }

    @Override
    public synchronized int dexTotalCount(UUID uuid) {
        int total = 0;
        for (Integer amount : this.dexAll(uuid).values()) {
            total += amount == null ? 0 : amount.intValue();
        }
        return total;
    }

    /**
     * 图鉴内容快照：种族 -> 产出次数，按「次数倒序、种族名升序」排好。
     *
     * <p>排序口径必须和 SQL 后端的 {@code ORDER BY amount DESC, species ASC} 一致：
     * 界面是直接顺序渲染的，换后端就换个顺序会让玩家以为图鉴被打乱了。</p>
     */
    @Override
    public synchronized Map<String, Integer> dexAll(UUID uuid) {
        final Map<String, Integer> raw = new HashMap<String, Integer>();
        if (this.open && uuid != null) {
            ConfigurationSection dex = this.read(uuid).getConfigurationSection(KEY_DEX);
            if (dex != null) {
                for (String species : dex.getKeys(false)) {
                    raw.put(species, Math.max(dex.getInt(species, 0), 0));
                }
            }
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(raw.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> left, Map.Entry<String, Integer> right) {
                int byAmount = right.getValue().compareTo(left.getValue());
                return byAmount != 0 ? byAmount : left.getKey().compareTo(right.getKey());
            }
        });
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> entry : entries) {
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /** 记录一次产出（该种族 +1）。 */
    @Override
    public synchronized void dexAdd(UUID uuid, String name, String species) {
        this.ensureOpen();
        if (species == null || species.isEmpty()) {
            return;
        }
        YamlConfiguration cfg = this.read(uuid);
        ConfigurationSection dex = cfg.getConfigurationSection(KEY_DEX);
        if (dex == null) {
            dex = cfg.createSection(KEY_DEX);
        }
        dex.set(species, Math.max(dex.getInt(species, 0), 0) + 1);
        this.touchName(cfg, name);
        this.write(uuid, cfg);
        this.rememberName(name, uuid);
    }

    @Override
    public synchronized int dexAmount(UUID uuid, String species) {
        if (!this.open || uuid == null || species == null) {
            return 0;
        }
        ConfigurationSection dex = this.read(uuid).getConfigurationSection(KEY_DEX);
        return dex == null ? 0 : Math.max(dex.getInt(species, 0), 0);
    }

    @Override
    public synchronized Set<Integer> dexClaimed(UUID uuid) {
        Set<Integer> out = new LinkedHashSet<Integer>();
        if (!this.open || uuid == null) {
            return out;
        }
        List<Integer> claimed = this.read(uuid).getIntegerList(KEY_DEX_CLAIMED);
        if (claimed != null) {
            out.addAll(claimed);
        }
        return out;
    }

    /**
     * 领取一个里程碑，返回 {@code true} 表示「本次是首次领取」。
     *
     * <p>判重任凭接口契约由存储层负责。SQL 后端靠主键 + {@code INSERT IGNORE}；这里是
     * 先查后写，中间夹着一次文件 IO —— 安全性来自方法级的 {@code synchronized}，
     * 也就是「单实例」这个前提。多服共享同一份目录时，这里会发出第二份奖励，
     * 这正是本后端不支持多服的原因之一。</p>
     */
    @Override
    public synchronized boolean dexClaim(UUID uuid, String name, int milestone) {
        this.ensureOpen();
        YamlConfiguration cfg = this.read(uuid);
        List<Integer> stored = cfg.getIntegerList(KEY_DEX_CLAIMED);
        List<Integer> claimed = stored == null
                ? new ArrayList<Integer>() : new ArrayList<Integer>(stored);
        if (claimed.contains(Integer.valueOf(milestone))) {
            return false;
        }
        claimed.add(Integer.valueOf(milestone));
        Collections.sort(claimed);
        cfg.set(KEY_DEX_CLAIMED, claimed);
        this.touchName(cfg, name);
        this.write(uuid, cfg);
        this.rememberName(name, uuid);
        return true;
    }
}
