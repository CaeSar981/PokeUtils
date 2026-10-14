package com.mc9y.pokeutils.data;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * MySQL 存储后端。
 *
 * <p><b>驱动从哪来</b>：不 shade、不打包，直接用服务端自己带的那个。CatServer 1.12.2 的
 * {@code libraries/} 里就有 {@code mysql-connector-java-5.1.45.jar}，而插件的
 * {@code PluginClassLoader} 是父优先的，所以 {@code Class.forName} 一定能解析到它。
 * 这带来两个好处：插件体积一个字节都不涨；驱动版本跟着服务端走，不会出现
 * 「插件带 8.x、服务端带 5.1、两个都注册到 DriverManager」的经典冲突。</p>
 *
 * <p><b>为什么绕开 {@code DriverManager}</b>：{@code DriverManager} 会拿「调用方的
 * ClassLoader」去核对驱动是否可见（{@code isDriverAllowed}），在插件里这件事的成败
 * 取决于驱动是被谁注册的，失败时的报错还是那句最没用的
 * {@code No suitable driver found}。这里改为反射实例化驱动对象后直接调
 * {@code Driver#connect(url, props)}，完全不经过 {@code DriverManager}，
 * 把整类 ClassLoader 问题一次性消掉。{@code DriverManager} 只留作最后的兜底。</p>
 *
 * <p><b>连接池</b>：<b>不</b>用全局 synchronized 串行化。业务线程（主线程 / 写回线程）
 * 与日志线程都要访问它，而一次网络往返是毫秒级 —— 全局锁会把本来并行的查询排成队。
 * 这里用一个极简的 {@link ArrayBlockingQueue} 池：借出时校验连接可用性（MySQL 的
 * {@code wait_timeout} 会把闲置连接悄悄掐掉，不校验就会在第一次用时炸），
 * 用完归还，池满则直接关闭。</p>
 *
 * <p><b>SQL 方言差异</b>都收敛在本类里：{@code TEXT} 主键在 MySQL 上必须带长度
 * （否则报 1170），所以 uuid 用 {@code VARCHAR(36)}；{@code INSERT OR IGNORE}
 * 对应 {@code INSERT ... ON DUPLICATE KEY UPDATE}；{@code COLLATE NOCASE}
 * 由建表时的 {@code utf8mb4_general_ci} 承担。SQLite 那份的写法一个字都不动。</p>
 */
public class MysqlStorage implements Storage {

    /** 驱动类名候选，按「新 → 旧 → MariaDB」顺序尝试。 */
    private static final String[] DRIVER_CLASSES = {
            "com.mysql.cj.jdbc.Driver",
            "com.mysql.jdbc.Driver",
            "org.mariadb.jdbc.Driver",
            "com.mysql.fabric.jdbc.FabricMySQLDriver",
    };

    private final Logger logger;
    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final String tablePrefix;
    private final boolean useSsl;
    private final String serverTimezone;
    private final int poolSize;
    private final int connectTimeoutMs;
    private final int borrowTimeoutMs;

    private BlockingQueue<Connection> pool;
    private volatile boolean open;
    /** 建表只做一次；失败时留个标记，避免每条 SQL 都白跑一遍。 */
    private volatile boolean schemaReady;

    public MysqlStorage(Logger logger, String host, int port, String database, String user,
                        String password, String tablePrefix, boolean useSsl, String serverTimezone,
                        int poolSize, int connectTimeoutMs, int borrowTimeoutMs) {
        this.logger = logger;
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
        this.tablePrefix = tablePrefix == null ? "" : tablePrefix;
        this.useSsl = useSsl;
        this.serverTimezone = serverTimezone == null ? "" : serverTimezone;
        this.poolSize = poolSize < 1 ? 1 : poolSize;
        this.connectTimeoutMs = connectTimeoutMs < 500 ? 500 : connectTimeoutMs;
        this.borrowTimeoutMs = borrowTimeoutMs < 100 ? 100 : borrowTimeoutMs;
    }

    // ------------------------------------------------------------------ 连接

    private String jdbcUrl() {
        StringBuilder sb = new StringBuilder("jdbc:mysql://").append(this.host).append(':').append(this.port)
                .append('/').append(this.database)
                .append("?useSSL=").append(this.useSsl)
                .append("&characterEncoding=utf8")
                .append("&connectTimeout=").append(this.connectTimeoutMs)
                .append("&socketTimeout=").append(Math.max(this.connectTimeoutMs, 30000));
        // serverTimezone 只有 MySQL 8 的 cj 驱动认；留空就不写，免得给 5.1 塞无谓参数。
        if (!this.serverTimezone.isEmpty()) {
            sb.append("&serverTimezone=").append(this.serverTimezone);
        }
        return sb.toString();
    }

    /**
     * 新建一条连接。优先反射驱动直连，失败再退回 {@code DriverManager}。
     */
    private Connection newConnection() throws SQLException {
        String url = this.jdbcUrl();
        Properties props = new Properties();
        props.setProperty("user", this.user);
        props.setProperty("password", this.password);
        props.setProperty("connectTimeout", String.valueOf(this.connectTimeoutMs));

        Throwable last = null;
        for (String className : DRIVER_CLASSES) {
            try {
                Class<?> type = Class.forName(className, true, MysqlStorage.class.getClassLoader());
                Driver driver = (Driver) type.newInstance();
                Connection connection = driver.connect(url, props);
                if (connection != null) {
                    if ("com.mysql.cj.jdbc.Driver".equals(className) && this.serverTimezone.isEmpty()) {
                        this.logger.warning("MySQL 8 的 cj 驱动通常需要 Storage.mysql.server-timezone，"
                                + "连不上时请把该配置填成 Asia/Shanghai。");
                    }
                    return connection;
                }
            } catch (Throwable t) {
                last = t;
            }
        }
        try {
            return DriverManager.getConnection(url, props);
        } catch (SQLException ex) {
            if (last != null) {
                ex.addSuppressed(last);
            }
            throw ex;
        }
    }

    @Override
    public void open() throws SQLException {
        this.close();
        BlockingQueue<Connection> created = new ArrayBlockingQueue<Connection>(this.poolSize);
        // 先建一条：连不上就要在 open() 里如实抛出去，让上层决定「回退 SQLite 还是直接禁用」，
        // 而不是等第一条业务语句才炸。
        Connection first = this.newConnection();
        created.add(first);
        this.pool = created;
        this.open = true;
        for (int i = 1; i < this.poolSize; i++) {
            try {
                created.add(this.newConnection());
            } catch (SQLException ex) {
                this.logger.warning("MySQL 连接池只建成了 " + created.size() + "/" + this.poolSize
                        + " 条连接，继续运行: " + ex.getMessage());
                break;
            }
        }
        this.schemaReady = false;
        this.ensureSchema();
    }

    @Override
    public void close() {
        this.open = false;
        this.schemaReady = false;
        BlockingQueue<Connection> current = this.pool;
        this.pool = null;
        if (current != null) {
            for (Connection connection : current) {
                MysqlStorage.quietClose(connection);
            }
            current.clear();
        }
    }

    @Override
    public boolean isOpen() {
        return this.open && this.pool != null;
    }

    @Override
    public String kind() {
        return "mysql";
    }

    @Override
    public String describe() {
        return "MySQL " + this.host + ":" + this.port + "/" + this.database
                + "（表 " + this.table("player") + " / " + this.table("sticker")
                + "，连接池 " + this.poolSize + "）";
    }

    @Override
    public Storage backend() {
        return this;
    }

    private static void quietClose(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关连接失败没有可做的事
            }
        }
    }

    private Connection borrow() throws SQLException {
        BlockingQueue<Connection> current = this.pool;
        if (current == null) {
            throw new SQLException("MySQL 连接池未初始化");
        }
        Connection connection = null;
        try {
            connection = current.poll(this.borrowTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLException("等待 MySQL 连接时被中断", ex);
        }
        if (connection == null) {
            throw new SQLException("MySQL 连接池已耗尽（" + this.poolSize + " 条连接都在使用中）");
        }
        if (!MysqlStorage.isUsable(connection)) {
            MysqlStorage.quietClose(connection);
            connection = this.newConnection();
        }
        return connection;
    }

    private void release(Connection connection) {
        BlockingQueue<Connection> current = this.pool;
        if (connection == null) {
            return;
        }
        if (current == null || !current.offer(connection)) {
            MysqlStorage.quietClose(connection);
        }
    }

    /** 借来还能不能用。空闲超过 MySQL 的 {@code wait_timeout} 会被服务端单方面掐掉。 */
    private static boolean isUsable(Connection connection) {
        try {
            if (connection.isClosed()) {
                return false;
            }
            return connection.isValid(3);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 玩家表的建表语句。
     *
     * <p>两个 MySQL 专属的坑都在这里：<b>①</b> {@code TEXT} 列做主键必须带长度
     * （否则报 1170 BLOB/TEXT column used in key spec），所以 uuid 是
     * {@code VARCHAR(36)}；<b>②</b> 排序规则显式写成 {@code utf8mb4_general_ci}，
     * 让 {@code WHERE name = ?} 天然大小写不敏感 —— SQLite 那边是靠
     * {@code COLLATE NOCASE} 做到的，MySQL 靠 collation，两边语义要能对上。</p>
     */
    public String ddlPlayer() {
        return "CREATE TABLE IF NOT EXISTS " + this.table("player") + " ("
                + " uuid VARCHAR(36) NOT NULL,"
                + " name VARCHAR(32) NOT NULL DEFAULT '',"
                + " fail_streak INT NOT NULL DEFAULT 0,"
                + " updated_at BIGINT NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (uuid),"
                + " KEY " + this.table("name_idx") + " (name)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";
    }

    /** 粘剂表的建表语句。复合主键 {@code (uuid, type)} 对应 SQLite 那份的同一约束。 */
    public String ddlSticker() {
        return "CREATE TABLE IF NOT EXISTS " + this.table("sticker") + " ("
                + " uuid VARCHAR(36) NOT NULL,"
                + " type VARCHAR(32) NOT NULL,"
                + " amount INT NOT NULL DEFAULT 0,"
                + " PRIMARY KEY (uuid, type)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";
    }

    private void ensureSchema() throws SQLException {
        if (this.schemaReady) {
            return;
        }
        Connection connection = this.borrow();
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(this.ddlPlayer());
            st.executeUpdate(this.ddlSticker());
            this.schemaReady = true;
        } finally {
            this.release(connection);
        }
    }

    /** 两条建表语句合在一起，供启动日志与「不连库也能验证方言」的测试使用。 */
    public String schemaText() {
        return this.ddlPlayer() + " ; " + this.ddlSticker();
    }

    /** 表名 = 配置的前缀 + 固定后缀。 */
    private String table(String suffix) {
        return this.tablePrefix + suffix;
    }

    // ------------------------------------------------------------------ 玩家行

    @Override
    public boolean hasPlayer(UUID uuid) {
        Connection connection = null;
        try {
            connection = this.borrow();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM " + this.table("player") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException ex) {
            this.logger.log(java.util.logging.Level.SEVERE, "MySQL 查询失败(hasPlayer): " + ex, ex);
            return false;
        } finally {
            this.release(connection);
        }
    }

    @Override
    public UUID findUuidByName(String name) {
        if (name == null) {
            return null;
        }
        Connection connection = null;
        try {
            connection = this.borrow();
            // 大小写不敏感由建表时的 utf8mb4_general_ci 承担，不需要 COLLATE 子句。
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT uuid FROM " + this.table("player") + " WHERE name = ? LIMIT 1")) {
                ps.setString(1, MysqlStorage.truncate(name, 32));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        try {
                            return UUID.fromString(rs.getString(1));
                        } catch (IllegalArgumentException ex) {
                            return null;
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            this.logger.log(java.util.logging.Level.SEVERE, "MySQL 查询失败(findUuidByName): " + ex, ex);
        } finally {
            this.release(connection);
        }
        return null;
    }

    @Override
    public void touchPlayer(UUID uuid, String name) {
        Connection connection = null;
        try {
            connection = this.borrow();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + this.table("player") + " (uuid, name, fail_streak, updated_at)"
                            + " VALUES (?, ?, 0, ?)"
                            + " ON DUPLICATE KEY UPDATE name = VALUES(name), updated_at = VALUES(updated_at)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, MysqlStorage.truncate(name, 32));
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            this.logger.log(java.util.logging.Level.SEVERE, "MySQL 写入失败(touchPlayer): " + ex, ex);
            throw new StorageException("MySQL 写入 touchPlayer(" + name + ") 失败: " + ex.getMessage(), ex);
        } finally {
            this.release(connection);
        }
    }

    // ------------------------------------------------------------------ 粘剂

    @Override
    public int getSticker(UUID uuid, String type) {
        Connection connection = null;
        try {
            connection = this.borrow();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT amount FROM " + this.table("sticker") + " WHERE uuid = ? AND type = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, type);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException ex) {
            this.logger.log(java.util.logging.Level.SEVERE, "MySQL 查询失败(getSticker): " + ex, ex);
            return 0;
        } finally {
            this.release(connection);
        }
    }

    @Override
    public Map<String, Integer> getStickers(UUID uuid) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        Connection connection = null;
        try {
            connection = this.borrow();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT type, amount FROM " + this.table("sticker") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
        } catch (SQLException ex) {
            this.logger.log(java.util.logging.Level.SEVERE, "MySQL 查询失败(getStickers): " + ex, ex);
        } finally {
            this.release(connection);
        }
        return out;
    }

    @Override
    public void setSticker(UUID uuid, String name, String type, int amount) {
        this.touchPlayer(uuid, name);
        Connection connection = null;
        try {
            connection = this.borrow();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + this.table("sticker") + " (uuid, type, amount) VALUES (?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE amount = VALUES(amount)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, type);
                ps.setInt(3, Math.max(amount, 0));
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            this.logger.log(java.util.logging.Level.SEVERE, "MySQL 写入失败(setSticker): " + ex, ex);
            throw new StorageException("MySQL 写入 setSticker(" + name + "/" + type + ") 失败: "
                    + ex.getMessage(), ex);
        } finally {
            this.release(connection);
        }
    }

    // ------------------------------------------------------------------ 保底计数

    @Override
    public int getFailStreak(UUID uuid) {
        Connection connection = null;
        try {
            connection = this.borrow();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT fail_streak FROM " + this.table("player") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException ex) {
            this.logger.log(java.util.logging.Level.SEVERE, "MySQL 查询失败(getFailStreak): " + ex, ex);
            return 0;
        } finally {
            this.release(connection);
        }
    }

    @Override
    public void setFailStreak(UUID uuid, String name, int value) {
        this.touchPlayer(uuid, name);
        Connection connection = null;
        try {
            connection = this.borrow();
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE " + this.table("player") + " SET fail_streak = ? WHERE uuid = ?")) {
                ps.setInt(1, Math.max(value, 0));
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            this.logger.log(java.util.logging.Level.SEVERE, "MySQL 写入失败(setFailStreak): " + ex, ex);
            throw new StorageException("MySQL 写入 setFailStreak(" + name + ") 失败: "
                    + ex.getMessage(), ex);
        } finally {
            this.release(connection);
        }
    }

    /** 把字符串截到指定宽度 —— 超长的玩家名 / 粘剂键在 MySQL 上会直接报 1406 而非静默截断。 */
    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 当前池内空闲连接数（供启动自检 / 测试观察）。 */
    public int idleConnections() {
        BlockingQueue<Connection> current = this.pool;
        return current == null ? 0 : current.size();
    }
}
