package com.mc9y.pokeutils.data;

/**
 * 存储后端「写」失败时抛出的运行时异常。
 *
 * <p><b>为什么需要它</b>：在这之前，{@code SqliteStorage} 里九处 {@code catch} 都是
 * {@code ex.printStackTrace()} —— 异常走到 stderr 而不是插件日志，而调用方拿到的
 * 只是「默认值 0 / 空表」，<b>分不清「本来就是 0」和「读库炸了」</b>。
 * 读失败降级成默认值是可以接受的（不该因为一次读失败就让玩家合不了成），
 * 但<b>写失败静默吞掉就是静默丢资产</b>：SQLite 是同步写穿路径，玩家看到「操作成功」，
 * 而数据根本没进库。</p>
 *
 * <p>所以现在的约定是：<b>读错了留痕（SEVERE）但返回默认值；写错了抛本异常</b>。
 * 抛出的这点在调用链上正好被 {@link CachedStorage#flushEntry} 接住 ——
 * 它会保留脏标记、按退避重试，并把失败计数如实打进日志。而「离线玩家直接打后端」
 * 那条路径（{@code /pu g}）抛出后会被命令层感知，管理员不会再收到一句假的「操作成功」。</p>
 *
 * <p>继承 {@code RuntimeException} 而不是受检异常：存储失败是运维层面的故障，
 * 硬要求每一层都写 {@code throws} 只会让中间层到处补 {@code try} 然后继续吞掉。</p>
 */
public class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
