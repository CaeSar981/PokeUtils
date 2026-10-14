package com.mc9y.pokeutils.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合成流水的内存索引 —— {@code /pu history|stats|top} 的数据源。
 *
 * <p><b>它替换掉了什么</b>：这三个命令原来每次执行都调 {@link CompoundLog#read}，
 * 也就是「遍历 logs 目录下所有文件 → 全量读进内存 → 逐行按制表符切分 → 聚合」。
 * 日志按天分文件且保留 30 天，一个中等规模的服跑一个月就能有几十万行 ——
 * 于是「玩家随手敲一条查询」变成一次几十到几百毫秒的主线程长阻塞。
 * 配置里的 {@code history.max-scan-lines}（默认 5 万行）就是为了给这件事兜底，
 * 但那只是把「很慢」压成「有点慢」，顺便还让统计结果只覆盖最近的一部分数据。</p>
 *
 * <p><b>现在的做法</b>：启用插件时读一次盘建索引，之后每合成一次就
 * {@link #append} 一行到内存尾部，超出上限就从头部裁掉。命令直接在内存里聚合 ——
 * 单次遍历 5 万条记录在毫秒级，而且没有 IO。</p>
 *
 * <p><b>为什么不维护增量统计量</b>：增量维护要求「裁剪的时候把被裁掉的记录从统计里
 * 反向扣掉」，一旦有一处漏扣，统计就会永久偏掉且很难发现。既然一次全量遍历只要 1 毫秒，
 * 那就每次都遍历，把一类隐蔽的状态不一致直接消灭掉。这是刻意的取舍，不是偷懒。</p>
 *
 * <p><b>并发</b>：{@link #append} 在主线程（写日志时），{@link #rebuild} 可能在
 * {@code Async} 的工作线程上（{@code /pu reload}），查询在主线程。全部 {@code synchronized}，
 * 锁的持有者做的事都是纯内存操作。</p>
 */
public final class LogIndex {

    private final File dataFolder;
    /** 按时间升序，最新的在尾部。 */
    private final List<CompoundLog.Record> records = new ArrayList<CompoundLog.Record>();

    /** 重建进行中：{@link #append} 期间落进来的行先攒在这里，等重建完再并进去。 */
    private boolean rebuilding;
    private final List<CompoundLog.Record> duringRebuild = new ArrayList<CompoundLog.Record>();

    private int keepDays;
    private int maxRecords;

    public LogIndex(File dataFolder, int keepDays, int maxRecords) {
        this.dataFolder = dataFolder;
        this.keepDays = keepDays;
        this.maxRecords = maxRecords;
    }

    /** 更新上限（{@code /pu reload} 改了配置时调用）。 */
    public synchronized void configure(int keepDays, int maxRecords) {
        this.keepDays = keepDays;
        this.maxRecords = maxRecords;
        this.trim();
    }

    /**
     * 从磁盘全量重建。启用插件时调一次；{@code /pu reload} 时再调一次（后台线程）。
     *
     * <p><b>读盘在锁外做</b>。一次全量读盘在日志很大时要几百毫秒，如果整段都抱着锁，
     * 这期间恰好合成了一次的玩家就会在 {@link #append} 上被主线程卡住 ——
     * 那就等于把「异步重建省下的主线程阻塞」又从另一头还回去了。所以锁只用来
     * 标记「重建进行中」和最后换数据；期间新落进来的行先攒在 {@link #duringRebuild} 里。</p>
     */
    public int rebuild() {
        synchronized (this) {
            this.rebuilding = true;
            this.duringRebuild.clear();
        }
        List<CompoundLog.Record> loaded;
        try {
            loaded = CompoundLog.read(this.dataFolder, this.keepDays, this.maxRecords);
        } catch (RuntimeException ex) {
            synchronized (this) {
                this.rebuilding = false;
            }
            throw ex;
        }
        synchronized (this) {
            // 重建期间落进来的那些行：它们同时也已经写进日志文件了，而这次读盘有没有
            // 读到它们取决于时序。判重只看 loaded 的尾部（新行只可能出现在那里），
            // 命中就不重复加，没命中就是读盘漏掉的，补在后面。
            int tail = Math.min(loaded.size(), this.duringRebuild.size() + 2);
            for (CompoundLog.Record pending : this.duringRebuild) {
                if (!LogIndex.tailContains(loaded, tail, pending)) {
                    loaded.add(pending);
                }
            }
            this.duringRebuild.clear();
            this.records.clear();
            this.records.addAll(loaded);
            this.trim();
            this.rebuilding = false;
            return this.records.size();
        }
    }

    /** 追加一行（写流水日志时同步调用，保证「刚合成的」立刻出现在 history 里）。 */
    public void append(CompoundLog.Record record) {
        if (record == null) {
            return;
        }
        synchronized (this) {
            if (this.rebuilding) {
                this.duringRebuild.add(record);
                return;
            }
            this.records.add(record);
            this.trim();
        }
    }

    /** 在 {@code list} 的末尾 {@code tail} 条里找与 {@code target} 等价的那条。 */
    private static boolean tailContains(List<CompoundLog.Record> list, int tail, CompoundLog.Record target) {
        int from = Math.max(list.size() - tail, 0);
        for (int i = from; i < list.size(); i++) {
            if (LogIndex.sameRecord(list.get(i), target)) {
                return true;
            }
        }
        return false;
    }

    /** 两行流水是不是同一条（字段逐个比，因为 Record 没实现 equals）。 */
    private static boolean sameRecord(CompoundLog.Record a, CompoundLog.Record b) {
        return LogIndex.eq(a.date, b.date) && LogIndex.eq(a.time, b.time)
                && LogIndex.eq(a.player, b.player) && a.star == b.star
                && LogIndex.eq(a.sticker, b.sticker) && a.success == b.success
                && a.spent == b.spent && a.refunded == b.refunded;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 超出上限时从头部裁掉最老的记录。 */
    private void trim() {
        int cap = this.maxRecords > 0 ? this.maxRecords : 0;
        if (cap > 0 && this.records.size() > cap) {
            this.records.subList(0, this.records.size() - cap).clear();
        }
    }

    public synchronized int size() {
        return this.records.size();
    }

    /** 全部记录的副本（按时间升序）。查询走下面几个定向方法，这个只给调试与兼容用。 */
    public synchronized List<CompoundLog.Record> all() {
        return new ArrayList<CompoundLog.Record>(this.records);
    }

    /** 某个玩家的全部记录（按时间升序的副本）。 */
    public synchronized List<CompoundLog.Record> forPlayer(String player) {
        List<CompoundLog.Record> out = new ArrayList<CompoundLog.Record>();
        if (player == null) {
            return out;
        }
        for (CompoundLog.Record record : this.records) {
            if (record.player != null && record.player.equalsIgnoreCase(player)) {
                out.add(record);
            }
        }
        return out;
    }

    /**
     * 某个玩家最近 {@code limit} 条记录，按时间<b>倒序</b>返回（最新在前），供展示。
     */
    public synchronized List<CompoundLog.Record> recentFor(String player, int limit) {
        List<CompoundLog.Record> all = this.forPlayer(player);
        List<CompoundLog.Record> out = new ArrayList<CompoundLog.Record>();
        int size = limit < 1 ? 1 : limit;
        for (int i = all.size() - 1; i >= 0 && out.size() < size; i--) {
            out.add(all.get(i));
        }
        return out;
    }

    /**
     * 聚合统计。
     *
     * @param playerOrNull {@code null} 表示全服
     */
    public synchronized CompoundLog.Stats stats(String playerOrNull) {
        if (playerOrNull == null) {
            return CompoundLog.summarize(this.records);
        }
        return CompoundLog.summarize(this.forPlayer(playerOrNull));
    }

    /** 排行榜。{@code byStar} = true 时按「成功过的最高星级」排。 */
    public synchronized List<CompoundLog.Count> top(boolean byStar) {
        Map<String, CompoundLog.Count> map = new LinkedHashMap<String, CompoundLog.Count>();
        for (CompoundLog.Record record : this.records) {
            if (record.player == null || record.player.isEmpty()) {
                continue;
            }
            CompoundLog.Count count = map.get(record.player);
            if (count == null) {
                count = CompoundLog.newCount(record.player);
                map.put(record.player, count);
            }
            count.count++;
            if (record.success) {
                count.success++;
                if (record.star + 1 > count.highestStar) {
                    count.highestStar = record.star + 1;
                }
            }
        }
        List<CompoundLog.Count> out = new ArrayList<CompoundLog.Count>(map.values());
        final boolean star = byStar;
        Collections.sort(out, new java.util.Comparator<CompoundLog.Count>() {
            @Override
            public int compare(CompoundLog.Count a, CompoundLog.Count b) {
                if (star && a.highestStar != b.highestStar) {
                    return b.highestStar - a.highestStar;
                }
                if (a.success != b.success) {
                    return b.success - a.success;
                }
                return b.count - a.count;
            }
        });
        return out;
    }

    /** 日志文件数（供调试 / 测试观察索引与磁盘是否同步）。 */
    public int logFileCount() {
        return CompoundLog.fileNames(this.dataFolder).size();
    }
}
