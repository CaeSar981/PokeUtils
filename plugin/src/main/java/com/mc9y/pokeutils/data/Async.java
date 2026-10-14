package com.mc9y.pokeutils.data;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * 插件自己的两个后台线程池。
 *
 * <p><b>为什么要分两个</b>：它们的失败代价完全不同，混在一起会互相拖累。</p>
 * <ul>
 *   <li>{@link #writer()} —— <b>单线程</b>。所有异步落库走这里。单线程不是省资源，
 *       而是「<b>同一玩家的写入必须有序</b>」这条约束的实现方式：换成多线程之后，
 *       两次写回可能并发抵达数据库，最后落盘的是哪一次取决于网络抖动，而不是
 *       取决于谁先发生。串行化是这里唯一的正确解。</li>
 *   <li>{@link #worker()} —— 通用后台计算（当前是重扫合成日志）。一个几十万行的
 *       日志扫描要几百毫秒，如果和落库挤同一个线程，这几百毫秒里所有玩家的
 *       存档写入都得排队。</li>
 * </ul>
 *
 * <p><b>线程都是 daemon</b>：服务端被 {@code taskkill /F} 结束时不指望 JVM 的
 * 关闭钩子（那正是本项目反复踩的坑），非 daemon 线程会让进程吊住不退。</p>
 */
public final class Async {

    /** 已排队 + 正在执行的写回任务数。测试用它判断「异步队列是不是空了」。 */
    private static final AtomicInteger PENDING = new AtomicInteger();

    private static volatile ExecutorService writer;
    private static volatile ExecutorService worker;
    private static Plugin plugin;

    private Async() {
    }

    /** 启用插件时调一次。重复调用是安全的（会先关掉旧的）。 */
    public static synchronized void init(Plugin owner, int workerThreads) {
        shutdown(500L);
        plugin = owner;
        writer = Executors.newSingleThreadExecutor(Async.factory("PokeUtils-Write"));
        worker = Executors.newFixedThreadPool(workerThreads < 1 ? 1 : workerThreads,
                Async.factory("PokeUtils-Work"));
    }

    private static ThreadFactory factory(final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, prefix + "-" + this.seq.incrementAndGet());
                thread.setDaemon(true);
                // 后台线程永远不该以「优先级」去和主线程抢 CPU —— 服务端卡顿的观感
                // 比落库慢 10 毫秒严重得多。
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }
        };
    }

    /** 提交一个落库任务（按提交顺序串行执行）。池未初始化时直接同步执行，绝不静默丢弃。 */
    public static void write(Runnable task) {
        ExecutorService current = writer;
        if (current == null) {
            Async.runCounted(task);
            return;
        }
        PENDING.incrementAndGet();
        try {
            current.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        task.run();
                    } finally {
                        PENDING.decrementAndGet();
                    }
                }
            });
        } catch (RuntimeException ex) {
            // 池已关闭（关服途中）—— 退化成同步执行，好过丢一笔写。
            PENDING.decrementAndGet();
            Async.runCounted(task);
        }
    }

    /** 提交一个后台计算任务。 */
    public static void compute(Runnable task) {
        ExecutorService current = worker;
        if (current == null) {
            task.run();
            return;
        }
        try {
            current.execute(task);
        } catch (RuntimeException ex) {
            task.run();
        }
    }

    private static void runCounted(Runnable task) {
        PENDING.incrementAndGet();
        try {
            task.run();
        } finally {
            PENDING.decrementAndGet();
        }
    }

    /** 回到主线程执行（已经在主线程时立即执行）。 */
    public static void main(Runnable task) {
        Plugin owner = plugin;
        if (owner == null || Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        Bukkit.getScheduler().runTask(owner, task);
    }

    /** 已排队 + 正在执行的写回任务数。 */
    public static int pending() {
        return PENDING.get();
    }

    /**
     * 等写队列排空。
     *
     * <p>做法是往同一个单线程池里丢一个空任务然后等它 —— 由线程池的 FIFO 语义保证
     * 「它跑完了，排在它前面的写就一定都跑完了」，比轮询计数器可靠
     * （计数器归零的瞬间，队列里可能刚好还压着一条没开始计数的）。</p>
     *
     * @return true = 已排空；false = 超时（此时数据可能还没落盘，调用方应当记日志）
     */
    public static boolean drain(long timeoutMs) {
        ExecutorService current = writer;
        if (current == null) {
            return true;
        }
        try {
            Future<?> barrier = current.submit(new Runnable() {
                @Override
                public void run() {
                    // 屏障任务本身什么都不做，它的意义只在于「排到队尾」
                }
            });
            barrier.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 关服：先排空再关池。 */
    public static synchronized void shutdown(long timeoutMs) {
        ExecutorService currentWriter = writer;
        ExecutorService currentWorker = worker;
        writer = null;
        worker = null;
        if (currentWriter != null) {
            Async.drain(currentWriter, timeoutMs);
            currentWriter.shutdown();
        }
        if (currentWorker != null) {
            currentWorker.shutdown();
        }
        plugin = null;
    }

    private static boolean drain(ExecutorService pool, long timeoutMs) {
        try {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            return false;
        }
    }
}
