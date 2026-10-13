package com.mc9y.pokeutils.data;

import com.mc9y.pokeutils.PokeUtils;
import java.util.Map;
import java.util.UUID;

/**
 * 单个玩家持久化数据的门面（粘剂数量 + 合成连续失败次数）。
 *
 * <p><b>存储后端已从 YAML 换成 SQLite</b>（见 {@link SqliteStorage}）。本类因此退化成
 * 一层薄门面：自己不再持有任何状态，每次读写都直接打到数据库。这样做的原因有两条：</p>
 * <ol>
 *   <li>数据不可能出现「内存与磁盘不一致」—— 原 YAML 版在 playerdatas 之外还各自缓存了
 *       一份 {@code FileConfiguration}，强杀服务端时缓存里的变动就丢了；</li>
 *   <li>主键从「玩家名」换成 <b>UUID</b>。原实现按名字建文件
 *       （{@code Data/&lt;玩家名&gt;.yml}），玩家改一次 ID 名，粘剂和保底计数就全部归零 ——
 *       对这个玩法来说等于资产蒸发。</li>
 * </ol>
 *
 * <p>旧版 YAML 文件不会丢：玩家进服时会自动导入 SQLite 并把原文件归档到
 * {@code Data-migrated/}，见 {@link LegacyDataMigration}。</p>
 */
public class YSData {

    private final UUID uuid;
    private final String name;

    public YSData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID getUuid() {
        return this.uuid;
    }

    public String getName() {
        return this.name;
    }

    /**
     * 读取某种粘剂的数量。
     *
     * <p>存储未就绪（数据库打开失败）时返回 0 而不是抛异常 —— 上层已有
     * 「粘剂数据尚未加载」的提示分支，让玩家看到一句人话比看到一条栈更有用。</p>
     */
    public int getAmount(String key) {
        SqliteStorage storage = PokeUtils.getInstance().getStorage();
        return storage == null ? 0 : storage.getSticker(this.uuid, key);
    }

    /** 该玩家全部粘剂的数量快照，供界面一次性取用。 */
    public Map<String, Integer> allAmounts() {
        SqliteStorage storage = PokeUtils.getInstance().getStorage();
        return storage == null ? new java.util.HashMap<String, Integer>() : storage.getStickers(this.uuid);
    }

    /** 增加粘剂（数量为负时即减少，结果不会被压到 0 以下）。 */
    public void deposit(String key, int aa) {
        SqliteStorage storage = PokeUtils.getInstance().getStorage();
        if (storage != null) {
            storage.addSticker(this.uuid, this.name, key, aa);
        }
    }

    /** 扣除粘剂。SQLite 侧统一做了 {@code MAX(0, ...)} 夹取，不会扣成负数。 */
    public void withdraw(String key, int aa) {
        SqliteStorage storage = PokeUtils.getInstance().getStorage();
        if (storage != null) {
            storage.addSticker(this.uuid, this.name, key, -aa);
        }
    }

    /** 连续合成失败次数（用于「连续失败保底」）。 */
    public int getFailStreak() {
        SqliteStorage storage = PokeUtils.getInstance().getStorage();
        return storage == null ? 0 : storage.getFailStreak(this.uuid);
    }

    /** 写入连续失败次数。SQLite 模式下写入即提交，无需再调 save()。 */
    public void setFailStreak(int value) {
        SqliteStorage storage = PokeUtils.getInstance().getStorage();
        if (storage != null) {
            storage.setFailStreak(this.uuid, this.name, value);
        }
    }
}
