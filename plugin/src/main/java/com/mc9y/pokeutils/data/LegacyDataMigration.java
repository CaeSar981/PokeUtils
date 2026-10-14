package com.mc9y.pokeutils.data;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 把插件旧版（每个玩家一个 YAML 文件）的数据一次性搬进 SQLite。
 *
 * <p>旧结构：{@code plugins/PokeUtils/Data/&lt;玩家名&gt;.yml}，内容形如</p>
 * <pre>
 * PT: 7
 * JL: 3
 * Compound:
 *   fail-streak: 2
 * </pre>
 *
 * <p><b>为什么在「玩家进服时」迁移，而不是在插件启动时批量迁移</b>：YAML 文件名是玩家名，
 * 而 SQLite 的主键要 UUID。启动时手上只有名字，反查 UUID 必须借助 {@code usercache.json}
 * 或者 {@code Bukkit.getOfflinePlayer(name)} —— 后者在正版验证服上对「名字查不到」的情况
 * 会去请求 Mojang 接口，直接把主线程卡住。玩家进服时手里就是货真价实的 UUID，
 * 零网络、零阻塞、且 100% 与登录态一致。代价是「永远不回来的玩家」数据不会被迁移 ——
 * 而那种数据本来也不会被任何人读到。</p>
 */
public final class LegacyDataMigration {

    /** 旧版保底计数的键名，带层级。 */
    private static final String LEGACY_FAIL_STREAK = "Compound.fail-streak";

    /** 归档目录名，位于 {@code Data/} 之下。 */
    private static final String ARCHIVE_DIR = "Data-migrated";

    private LegacyDataMigration() {
    }

    /** 旧版数据文件的路径。 */
    public static File legacyFile(File dataFolder, String playerName) {
        return new File(dataFolder, "Data/" + playerName + ".yml");
    }

    /**
     * 「玩家进服」时的完整迁移流程：找旧文件 -> 判断是否需要迁移 -> 迁移 -> 归档。
     *
     * @return 是否真的搬了数据（供调用方打日志）
     */
    public static boolean migrateIfNeeded(File dataFolder, Storage storage, UUID uuid, String name) {
        if (storage == null) {
            return false;
        }
        File yml = legacyFile(dataFolder, name);
        if (!yml.isFile()) {
            return false;
        }
        // 库里已经有这个玩家了，说明早就迁过（或归档曾失败留下残影）。
        // 这种情况把残留文件顺手归档，免得每次进服都白跑一趟。
        if (storage.hasPlayer(uuid)) {
            archive(yml);
            return false;
        }
        boolean touched = migrate(storage, yml, uuid, name);
        archive(yml);
        return touched;
    }

    /**
     * 读取旧 YAML 并写入 SQLite。
     *
     * <p>识别规则按旧版的实际结构来：带层级的 {@code Compound.fail-streak} 当作保底计数，
     * 其余「顶层标量键」一律当作粘剂类型（旧版就是 PT / JL，但在粘剂类型配置化之后
     * 不应再假定只有这两种，因此按结构判定而不是按固定名单判定）。</p>
     *
     * @return 是否真的写入了内容
     */
    public static boolean migrate(Storage storage, File yml, UUID uuid, String name) {
        if (storage == null || yml == null || !yml.isFile()) {
            return false;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(yml);
        boolean touched = false;

        // 保底计数是带层级的键（Compound.fail-streak），必须单独取 ——
        // 下面按「顶层标量」遍历的方式碰到 Compound 这个 Map 节点会被直接跳过。
        if (cfg.contains(LEGACY_FAIL_STREAK)) {
            storage.setFailStreak(uuid, name, cfg.getInt(LEGACY_FAIL_STREAK));
            touched = true;
        }

        for (Map.Entry<String, Object> entry : cfg.getValues(false).entrySet()) {
            String key = entry.getKey();
            Object raw = entry.getValue();
            // 只认顶层标量，Map 形式的层级节点（Compound）在这一层跳过
            if (!(raw instanceof Number)) {
                continue;
            }
            // 顶层标量键 = 一种粘剂的持有量
            storage.addSticker(uuid, name, key, ((Number) raw).intValue());
            touched = true;
        }
        return touched;
    }

    /**
     * 把已迁移的旧文件移进 {@code plugins/PokeUtils/Data-migrated/}。
     *
     * <p>刻意<b>不删除</b>：迁移属于一次性、不可逆的数据搬运，原文件留作人工兜底。
     * 归档目录与 {@code Data/} <b>平级</b>而不是放在它里面，这样 {@code Data/} 目录
     * 始终保持「只有未迁移的旧数据文件」这一种内容，人工核对时一眼就能看清。</p>
     */
    public static boolean archive(File yml) {
        if (yml == null || !yml.isFile()) {
            return false;
        }
        File dataDir = yml.getParentFile();
        File dir = new File(dataDir == null ? null : dataDir.getParentFile(), ARCHIVE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File target = new File(dir, yml.getName());
        if (target.exists() && !target.delete()) {
            return false;
        }
        return yml.renameTo(target);
    }
}
