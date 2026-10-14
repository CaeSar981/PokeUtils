package com.mc9y.pokeutils.data;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import pokeshaded.SQLiteConfig;
import pokeshaded.SQLiteDataSource;

/**
 * SQLite 存储层（插件持久化的唯一出口）。
 *
 * <p><b>为什么不用 {@code DriverManager}</b>：Bukkit 给每个插件套了一层独立的
 * {@code PluginClassLoader}，而 {@code DriverManager} 是按「调用方的 ClassLoader」去匹配已注册驱动的，
 * 在插件里 {@code DriverManager.getConnection("jdbc:sqlite:...")} 经常会抛
 * {@code No suitable driver found}。这里的驱动是 shade 进本插件 jar 的，所以直接走
 * {@link SQLiteDataSource#getConnection()} —— 它内部直接构造 {@code SQLiteConnection}，
 * 不经过 {@code DriverManager}，绕开了整类 ClassLoader 问题。</p>
 *
 * <p><b>为什么 import 的是 {@code pokeshaded} 而不是 {@code org.sqlite}</b>：CatServer 1.12.2
 * 的 {@code libraries/} 目录里自带 {@code sqlite-jdbc-3.21.0.1.jar}，而插件的类加载器会
 * <b>先委托父加载器</b>。父加载器能解析 {@code org.sqlite.SQLiteConfig}，于是我们自己 shade
 * 进来的 3.42 版类永远不会被用到 —— 实测的结果就是启动时抛
 * {@code NoSuchMethodError: org.sqlite.SQLiteConfig.setBusyTimeout(I)V}（该方法是 3.24+ 才有的）。
 * 构建脚本因此把驱动整体重定位到 {@code pokeshaded} 包（见 {@code tools/relocate.py}），
 * 与任何外部 jar 彻底隔离。</p>
 *
 * <p><b>并发</b>：Bukkit 的事件回调都在主线程，但本插件的写路径也可能被异步代码碰到，
 * 而 JDBC {@code Connection} 并非线程安全。这里所有公开方法一律 {@code synchronized}，
 * 单连接串行化 —— SQLite 本地文件读写是微秒级，这点开销换掉全部并发风险很划算。</p>
 *
 * <p><b>持久性</b>：写入即提交（autocommit），不存在「等退服 / 关服才落盘」的窗口。
 * 原 YAML 版本只在 {@code onDisable} 与 {@code PlayerQuitEvent} 写盘，被 {@code taskkill /F}
 * 结束时会丢掉整个会话的变动。</p>
 *
 * <p><b>在存储体系里的位置</b>：本类是 {@link Storage} 的 SQLite 实现，也就是
 * <b>裸后端</b>。插件业务读写的其实是外面那层带缓存的 {@link CachedStorage} ——
 * 本地文件的读写是微秒级，用缓存换来的收益很小，但换来的是「换后端不改业务代码」
 * 的统一路径。别把本类往上暴露给插件代码。</p>
 */
public class SqliteStorage implements Storage {

    /** 建表语句。粘剂单独一张表，这样新增粘剂类型不需要改表结构。 */
    private static final String DDL_PLAYER =
            "CREATE TABLE IF NOT EXISTS pu_player ("
            + "  uuid        TEXT PRIMARY KEY,"
            + "  name        TEXT NOT NULL DEFAULT '',"
            + "  fail_streak INTEGER NOT NULL DEFAULT 0,"
            + "  updated_at  INTEGER NOT NULL DEFAULT 0)";

    private static final String DDL_STICKER =
            "CREATE TABLE IF NOT EXISTS pu_sticker ("
            + "  uuid   TEXT NOT NULL,"
            + "  type   TEXT NOT NULL,"
            + "  amount INTEGER NOT NULL DEFAULT 0,"
            + "  PRIMARY KEY (uuid, type))";

    private final File dbFile;
    private SQLiteDataSource source;
    private Connection connection;

    public SqliteStorage(File dbFile) {
        this.dbFile = dbFile;
    }

    /** 打开数据库并建表。重复调用是安全的（会先关掉旧连接）。 */
    public synchronized void open() throws SQLException {
        this.close();

        File parent = this.dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        SQLiteConfig cfg = new SQLiteConfig();
        // WAL 让「读」不被「写」阻塞，服务端主线程做其他事时写入不会卡住它。
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
        cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        // 万一有另一个连接（例如运维用工具开了库）持有写锁，等待 5 秒再报错，而不是立刻失败。
        cfg.setBusyTimeout(5000);

        SQLiteDataSource ds = new SQLiteDataSource(cfg);
        // Windows 的反斜杠在 JDBC URL 里会被当转义符，统一转成正斜杠。
        ds.setUrl("jdbc:sqlite:" + this.dbFile.getAbsolutePath().replace('\\', '/'));
        this.source = ds;
        this.connection = ds.getConnection();

        try (Statement st = this.connection.createStatement()) {
            st.executeUpdate(DDL_PLAYER);
            st.executeUpdate(DDL_STICKER);
        }
    }

    public synchronized void close() {
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
            this.connection = null;
        }
    }

    public synchronized boolean isOpen() {
        return this.connection != null;
    }

    @Override
    public String kind() {
        return "sqlite";
    }

    @Override
    public String describe() {
        return "SQLite 文件 " + this.dbFile.getPath();
    }

    /** 裸后端就是自己。 */
    @Override
    public Storage backend() {
        return this;
    }

    /** 数据库文件路径（供日志 / 测试使用）。 */
    public File getDbFile() {
        return this.dbFile;
    }

    // ------------------------------------------------------------------ 玩家行

    /** 该玩家在库里是否已有记录。 */
    public synchronized boolean hasPlayer(UUID uuid) {
        if (this.connection == null) {
            return false;
        }
        try (PreparedStatement ps = this.connection.prepareStatement(
                "SELECT 1 FROM pu_player WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    /**
     * 按玩家名反查 UUID。
     *
     * <p>给「给离线玩家发货」用。优先查库而不是 {@code Bukkit.getOfflinePlayer(name)}，
     * 是因为后者在正版验证服上对新名字会去请求 Mojang 接口，把主线程卡住；
     * 而只要这个玩家曾经进过服，库里就一定有他的 name -> uuid 记录。</p>
     *
     * @return 查不到返回 {@code null}
     */
    public synchronized UUID findUuidByName(String name) {
        if (this.connection == null || name == null) {
            return null;
        }
        try (PreparedStatement ps = this.connection.prepareStatement(
                "SELECT uuid FROM pu_player WHERE name = ? COLLATE NOCASE LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        return UUID.fromString(rs.getString(1));
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    /** 确保玩家行存在并把名字刷新为最新（改名后旧名字不会再被引用）。 */
    public synchronized void touchPlayer(UUID uuid, String name) {
        if (this.connection == null) {
            return;
        }
        String id = uuid.toString();
        try {
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "INSERT OR IGNORE INTO pu_player (uuid, name, fail_streak, updated_at)"
                    + " VALUES (?, ?, 0, 0)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "UPDATE pu_player SET name = ?, updated_at = ? WHERE uuid = ?")) {
                ps.setString(1, name);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, id);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    // ------------------------------------------------------------------ 粘剂

    /** 某种粘剂的持有数量（没有记录时返回 0）。 */
    public synchronized int getSticker(UUID uuid, String type) {
        if (this.connection == null) {
            return 0;
        }
        try (PreparedStatement ps = this.connection.prepareStatement(
                "SELECT amount FROM pu_sticker WHERE uuid = ? AND type = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            return 0;
        }
    }

    /** 该玩家所有粘剂的快照，用于一次查清界面上的「拥有数」。 */
    public synchronized Map<String, Integer> getStickers(UUID uuid) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        if (this.connection == null) {
            return out;
        }
        try (PreparedStatement ps = this.connection.prepareStatement(
                "SELECT type, amount FROM pu_sticker WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getInt(2));
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return out;
    }

    /**
     * 把某种粘剂的数量写成绝对值（{@code amount} 为负时按 0 处理）。
     *
     * <p>先 {@code INSERT OR IGNORE} 保证行存在，再 {@code UPDATE}，而不是用
     * {@code INSERT ... ON CONFLICT DO UPDATE} —— 后者要求 SQLite ≥ 3.24，
     * 两步写法在任何版本上行为一致，也更容易在日志里看懂。</p>
     *
     * <p>写的是绝对值而不是 {@code amount = amount + ?}，是为了让异步写回的
     * <b>重试幂等</b>：同一条写回被重放两次，结果与一次相同。增量写法重放一次
     * 就会多扣一次资产。</p>
     */
    @Override
    public synchronized void setSticker(UUID uuid, String name, String type, int amount) {
        if (this.connection == null) {
            return;
        }
        this.touchPlayer(uuid, name);
        String id = uuid.toString();
        try {
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "INSERT OR IGNORE INTO pu_sticker (uuid, type, amount) VALUES (?, ?, 0)")) {
                ps.setString(1, id);
                ps.setString(2, type);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = this.connection.prepareStatement(
                    "UPDATE pu_sticker SET amount = ? WHERE uuid = ? AND type = ?")) {
                ps.setInt(1, Math.max(amount, 0));
                ps.setString(2, id);
                ps.setString(3, type);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    // ------------------------------------------------------------------ 保底计数

    public synchronized int getFailStreak(UUID uuid) {
        if (this.connection == null) {
            return 0;
        }
        try (PreparedStatement ps = this.connection.prepareStatement(
                "SELECT fail_streak FROM pu_player WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            return 0;
        }
    }

    public synchronized void setFailStreak(UUID uuid, String name, int value) {
        if (this.connection == null) {
            return;
        }
        this.touchPlayer(uuid, name);
        try (PreparedStatement ps = this.connection.prepareStatement(
                "UPDATE pu_player SET fail_streak = ? WHERE uuid = ?")) {
            ps.setInt(1, Math.max(value, 0));
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }
}
