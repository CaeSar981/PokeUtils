package com.mc9y.pokeutils.data;

import java.io.File;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * 按 {@code config.yml} 的 {@code Storage} 段决定用哪种后端，并把它包进缓存层。
 *
 * <p><b>失败一律降级，绝不连坐</b>：存储后端连不上是运维问题，不该让整台服务器的合成玩法
 * 一起下线。所以 MySQL 连不上时的行为是「<b>回退 SQLite + 一条 SEVERE</b>」，
 * 而不是抛异常终止启用。这里唯一的例外是 SQLite 也打不开 —— 那时才返回 {@code null}，
 * 插件带着「存储不可用」的状态继续加载（上层各处本来就有这个分支）。</p>
 *
 * <p><b>默认值必须与出厂 {@code config.yml} 逐字一致</b>：Bukkit 的双参
 * {@code getString(path, def)} <b>不会</b>回退到 jar 内置的 config.yml —— 它只查本段
 * 的 map，查不到就直接返回 {@code def}。于是「代码里的字面量默认值」和
 * 「出厂 config.yml 的值」只要不一致，那份配置键在沿用旧 config.yml 的服上就会静默
 * 失效。这个坑本项目已经踩过一次（4 个音效名不响），所以新增键时两边必须同步改。</p>
 */
public final class StorageFactory {

    // ---- 出厂默认值：与 plugin/src/main/resources/config.yml 的 Storage 段逐字对应 ----
    static final String DEFAULT_TYPE = "sqlite";
    static final String DEFAULT_SQLITE_FILE = "pokeutils.db";
    static final String DEFAULT_MYSQL_HOST = "127.0.0.1";
    static final int DEFAULT_MYSQL_PORT = 3306;
    static final String DEFAULT_MYSQL_DATABASE = "pokeutils";
    static final String DEFAULT_MYSQL_USER = "root";
    static final String DEFAULT_MYSQL_PASSWORD = "";
    static final String DEFAULT_MYSQL_TABLE_PREFIX = "pu_";
    static final boolean DEFAULT_MYSQL_USE_SSL = false;
    static final String DEFAULT_MYSQL_SERVER_TIMEZONE = "";
    static final int DEFAULT_MYSQL_POOL_SIZE = 4;
    static final int DEFAULT_MYSQL_CONNECT_TIMEOUT = 5000;
    static final int DEFAULT_MYSQL_BORROW_TIMEOUT = 3000;
    static final String DEFAULT_WRITE_BEHIND = "auto";
    static final long DEFAULT_FLUSH_TIMEOUT = 5000L;
    static final int DEFAULT_WORKER_THREADS = 2;
    static final int DEFAULT_SQLITE_CHECKPOINT_MINUTES = 10;

    private StorageFactory() {
    }

    /** 打开结果：门面本身 + 「有没有发生过降级」的说明（供启动日志）。 */
    public static final class Opened {
        public final CachedStorage storage;
        /** 非空表示发生了降级，内容是要写进日志的说明。 */
        public final String fallbackReason;

        Opened(CachedStorage storage, String fallbackReason) {
            this.storage = storage;
            this.fallbackReason = fallbackReason;
        }
    }

    /**
     * 打开存储。返回 {@code null} 表示 SQLite 也打不开（调用方应当把 storage 置空并打 SEVERE）。
     */
    public static Opened open(Plugin plugin, File dataFolder, Logger logger) {
        FileConfiguration config = plugin.getConfig();
        String type = config.getString("Storage.type", DEFAULT_TYPE);
        type = type == null ? DEFAULT_TYPE : type.trim().toLowerCase();

        Storage backend = null;
        String fallback = null;

        if ("mysql".equals(type)) {
            MysqlStorage mysql = new MysqlStorage(logger,
                    config.getString("Storage.mysql.host", DEFAULT_MYSQL_HOST),
                    config.getInt("Storage.mysql.port", DEFAULT_MYSQL_PORT),
                    config.getString("Storage.mysql.database", DEFAULT_MYSQL_DATABASE),
                    config.getString("Storage.mysql.user", DEFAULT_MYSQL_USER),
                    config.getString("Storage.mysql.password", DEFAULT_MYSQL_PASSWORD),
                    config.getString("Storage.mysql.table-prefix", DEFAULT_MYSQL_TABLE_PREFIX),
                    config.getBoolean("Storage.mysql.use-ssl", DEFAULT_MYSQL_USE_SSL),
                    config.getString("Storage.mysql.server-timezone", DEFAULT_MYSQL_SERVER_TIMEZONE),
                    config.getInt("Storage.mysql.pool-size", DEFAULT_MYSQL_POOL_SIZE),
                    config.getInt("Storage.mysql.connect-timeout-ms", DEFAULT_MYSQL_CONNECT_TIMEOUT),
                    config.getInt("Storage.mysql.borrow-timeout-ms", DEFAULT_MYSQL_BORROW_TIMEOUT));
            try {
                mysql.open();
                backend = mysql;
                logger.info("MySQL 存储已就绪: " + mysql.describe());
            } catch (Exception ex) {
                logger.severe("MySQL 存储连接失败: " + ex);
                fallback = "MySQL 不可用（" + StorageFactory.rootCause(ex) + "），已自动回退到 SQLite。"
                        + "本地 SQLite 里的数据与 MySQL 里的是两份不同的数据，请确认这是预期行为。";
                logger.severe(fallback);
            }
        } else if (!"sqlite".equals(type)) {
            logger.warning("Storage.type 的值 \"" + type + "\" 无法识别（可选 sqlite / mysql），按 sqlite 处理。");
        }

        if (backend == null) {
            SqliteStorage sqlite = new SqliteStorage(StorageFactory.resolveFile(
                    dataFolder, config.getString("Storage.sqlite.file", DEFAULT_SQLITE_FILE)), logger);
            try {
                sqlite.open();
                backend = sqlite;
                logger.info("SQLite 存储已就绪 -> " + sqlite.getDbFile().getPath());
            } catch (Exception ex) {
                logger.severe("SQLite 存储打开失败，本插件将无法保存任何数据！");
                logger.severe("原因: " + ex);
                return new Opened(null, fallback);
            }
        }

        boolean writeBehind = StorageFactory.resolveWriteBehind(
                config.getString("Storage.async.write-behind", DEFAULT_WRITE_BEHIND), backend.kind(), logger);
        long flushTimeout = config.getLong("Storage.async.flush-timeout-ms", DEFAULT_FLUSH_TIMEOUT);
        CachedStorage cached = new CachedStorage(backend, logger, writeBehind, flushTimeout);
        logger.info("存储写模式: " + (writeBehind
                ? "异步写回（主线程不等待落库，关服时排空 " + flushTimeout + "ms）"
                : "同步写穿（写入返回即已落盘）"));
        return new Opened(cached, fallback);
    }

    /** 后台计算线程数（供 {@link Async#init}）。 */
    public static int workerThreads(Plugin plugin) {
        return plugin.getConfig().getInt("Storage.async.worker-threads", DEFAULT_WORKER_THREADS);
    }

    /**
     * SQLite 的 WAL checkpoint 间隔（分钟）。0 = 不开启。
     *
     * <p>只有 SQLite 后端用得上：WAL 模式下的已提交数据先落在 {@code -wal} 附属文件里，
     * 默认的自动 checkpoint 不截断文件，一个从不重启的服能把它涨到很大，
     * 而 WAL 越大「打开数据库时回放」越慢。</p>
     */
    public static int sqliteCheckpointMinutes(Plugin plugin) {
        return plugin.getConfig().getInt("Storage.sqlite.checkpoint-minutes",
                DEFAULT_SQLITE_CHECKPOINT_MINUTES);
    }

    /**
     * {@code auto} 表示「看后端下菜」：网络后端异步写回，本地文件同步写穿。
     */
    static boolean resolveWriteBehind(String mode, String kind, Logger logger) {
        String value = mode == null ? DEFAULT_WRITE_BEHIND : mode.trim().toLowerCase();
        if ("always".equals(value)) {
            return true;
        }
        if ("never".equals(value)) {
            return false;
        }
        if (!DEFAULT_WRITE_BEHIND.equals(value)) {
            logger.warning("Storage.async.write-behind 的值 \"" + value
                    + "\" 无法识别（可选 auto / always / never），按 auto 处理。");
        }
        return "mysql".equals(kind);
    }

    /** 配置里给的是相对路径就放到插件目录下，给绝对路径就用它本身。 */
    static File resolveFile(File dataFolder, String name) {
        String path = name == null || name.trim().isEmpty() ? DEFAULT_SQLITE_FILE : name.trim();
        File file = new File(path);
        return file.isAbsolute() ? file : new File(dataFolder, path);
    }

    /** 异常链里最根上的那条信息，用来打一句人话给运维。 */
    private static String rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isEmpty() ? current.getClass().getSimpleName() : message;
    }
}
