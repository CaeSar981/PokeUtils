package com.mc9y.pokeutils.data;

import java.util.Map;
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
 * {@link #getFailStreak} / {@link #setFailStreak} 这四个方法必须能被多线程并发调用
 * （缓存层的写回线程与主线程可能同时进来）。</p>
 */
public interface Storage {

    /** 打开连接（必要时建表）。重复调用应当幂等。 */
    void open() throws Exception;

    /** 关闭连接。已关闭时调用不应抛异常。 */
    void close();

    /** 连接是否可用。为 {@code false} 时上层会退化成「读到 0 / 写入丢弃」。 */
    boolean isOpen();

    /** 后端类型标识，取值 {@code "sqlite"} / {@code "mysql"}，用于日志与测试断言。 */
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
}
