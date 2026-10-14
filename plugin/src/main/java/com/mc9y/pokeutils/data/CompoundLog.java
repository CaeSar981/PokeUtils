package com.mc9y.pokeutils.data;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合成流水日志的读取与聚合。
 *
 * <p>日志由 {@code PokeUtils#logCompound} 写成
 * {@code plugins/PokeUtils/logs/compound-<日期>.log}，每行 8 个制表符分列字段：</p>
 *
 * <pre>
 * 时间  玩家  材料星级  粘剂  结果  消耗  返还  备注
 * 07:12:03	Steve	4	JL	成功	100000	0	产出 五星精灵
 * </pre>
 *
 * <p>这个类只做「读 + 汇总」，不碰任何 Bukkit API，因此可以被命令层与测试直接调用：
 * {@code /pu history} 取某个玩家的最近若干条，{@code /pu stats} 与 {@code /pu top}
 * 在全量上做聚合。</p>
 *
 * <p><b>为什么要有扫描上限</b>：全量聚合要读完所有日志文件。正常服一天几百行无感，
 * 但如果有人在命令里反复触发、而日志已经堆到几十万行，那就会变成一次主线程长阻塞。
 * 所以这里统一按「最多往回扫 N 行」处理，超出部分直接丢弃最早的那些 ——
 * 统计类命令要的是趋势，不是精确账本。</p>
 */
public final class CompoundLog {

    /** 一行流水。 */
    public static final class Record {
        public final String date;
        public final String time;
        public final String player;
        public final int star;
        public final String sticker;
        public final boolean success;
        public final int spent;
        public final int refunded;
        public final String detail;

        Record(String date, String time, String player, int star, String sticker,
               boolean success, int spent, int refunded, String detail) {
            this.date = date;
            this.time = time;
            this.player = player;
            this.star = star;
            this.sticker = sticker;
            this.success = success;
            this.spent = spent;
            this.refunded = refunded;
            this.detail = detail;
        }
    }

    /** 聚合结果。 */
    public static final class Stats {
        public int total;
        public int success;
        public int fail;
        public long spent;
        public long refunded;
        /** 各材料星级的总次数 / 成功次数，下标 = 星级（0 不使用）。 */
        public final int[] starTotal = new int[8];
        public final int[] starSuccess = new int[8];

        public int rate() {
            return this.total == 0 ? 0 : (int) Math.round(this.success * 100.0D / this.total);
        }

        public int starRate(int star) {
            int t = star >= 0 && star < this.starTotal.length ? this.starTotal[star] : 0;
            return t == 0 ? 0 : (int) Math.round(this.starSuccess[star] * 100.0D / t);
        }
    }

    /** 排行榜条目。 */
    public static final class Count {
        public final String player;
        public int count;
        public int success;
        public int highestStar;

        Count(String player) {
            this.player = player;
        }
    }

    /** 造一个空的计数条目（供 {@link LogIndex} 在内存里聚合）。 */
    public static Count newCount(String player) {
        return new Count(player);
    }

    private CompoundLog() {
    }

    /** 日志目录。 */
    public static File logDir(File dataFolder) {
        return new File(dataFolder, "logs");
    }

    /**
     * 读全部流水（按时间升序）。
     *
     * @param keepDays 只读最近多少天的文件；&lt;= 0 表示不限
     * @param maxLines 最多返回多少行（保留最新的那批）；&lt;= 0 表示不限
     */
    public static List<Record> read(File dataFolder, int keepDays, int maxLines) {
        List<Record> out = new ArrayList<Record>();
        File dir = logDir(dataFolder);
        if (!dir.isDirectory()) {
            return out;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        // 文件名里带 yyyy-MM-dd，排序即时间顺序。
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        String cutoff = keepDays > 0 ? dayStringAgo(keepDays) : null;
        for (File file : files) {
            if (!file.isFile() || !file.getName().startsWith("compound-") || !file.getName().endsWith(".log")) {
                continue;
            }
            String date = file.getName().substring("compound-".length(), file.getName().length() - 4);
            if (cutoff != null && date.compareTo(cutoff) < 0) {
                continue;
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                continue;
            }
            for (String line : lines) {
                Record record = parse(date, line);
                if (record != null) {
                    out.add(record);
                }
            }
        }
        if (maxLines > 0 && out.size() > maxLines) {
            return new ArrayList<Record>(out.subList(out.size() - maxLines, out.size()));
        }
        return out;
    }

    /**
     * 解析一行；格式不对（手工编辑过 / 半行）时返回 null 而不是抛异常。
     *
     * <p>是 {@code public} 的：写入侧（{@code PokeUtils#logCompound}）拼完那一行之后
     * 也要用它把同一行解析成 {@link Record} 塞进内存索引。宁可多走一次解析，
     * 也不要在写入侧把格式再手写一遍 —— 两处格式一旦漂移，索引里的数据和文件里的就对不上了。</p>
     */
    public static Record parse(String date, String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] f = trimmed.split("\t", -1);
        if (f.length < 7) {
            return null;
        }
        try {
            return new Record(date, f[0], f[1], Integer.parseInt(f[2].trim()), f[3],
                    "成功".equals(f[4]), Integer.parseInt(f[5].trim()), Integer.parseInt(f[6].trim()),
                    f.length > 7 ? f[7] : "-");
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 某个玩家的流水（按时间升序）。 */
    public static List<Record> forPlayer(List<Record> all, String player) {
        List<Record> out = new ArrayList<Record>();
        for (Record record : all) {
            if (record.player != null && record.player.equalsIgnoreCase(player)) {
                out.add(record);
            }
        }
        return out;
    }

    /** 汇总。 */
    public static Stats summarize(List<Record> records) {
        Stats stats = new Stats();
        for (Record record : records) {
            stats.total++;
            if (record.success) {
                stats.success++;
            } else {
                stats.fail++;
            }
            stats.spent += record.spent;
            stats.refunded += record.refunded;
            if (record.star >= 0 && record.star < stats.starTotal.length) {
                stats.starTotal[record.star]++;
                if (record.success) {
                    stats.starSuccess[record.star]++;
                }
            }
        }
        return stats;
    }

    /**
     * 排行榜。
     *
     * @param byStar true = 按「成功过的最高星级」排（同级再按次数）；false = 按合成次数排
     */
    public static List<Count> top(List<Record> records, boolean byStar) {
        Map<String, Count> map = new LinkedHashMap<String, Count>();
        for (Record record : records) {
            if (record.player == null || record.player.isEmpty()) {
                continue;
            }
            Count count = map.get(record.player);
            if (count == null) {
                count = new Count(record.player);
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
        List<Count> out = new ArrayList<Count>(map.values());
        final boolean star = byStar;
        java.util.Collections.sort(out, new Comparator<Count>() {
            @Override
            public int compare(Count a, Count b) {
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

    /** 删除超过 keepDays 天的日志文件，返回删除数量（keepDays &lt;= 0 时不做任何事）。 */
    public static int cleanup(File dataFolder, int keepDays) {
        if (keepDays <= 0) {
            return 0;
        }
        File dir = logDir(dataFolder);
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        String cutoff = dayStringAgo(keepDays);
        int removed = 0;
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.startsWith("compound-") || !name.endsWith(".log")) {
                continue;
            }
            String date = name.substring("compound-".length(), name.length() - 4);
            if (date.compareTo(cutoff) < 0 && file.delete()) {
                removed++;
            }
        }
        return removed;
    }

    /** 该玩家按材料星级统计的成功/失败次数，供 `/pu stats <玩家>` 展开。 */
    public static Map<Integer, int[]> byStar(List<Record> records) {
        Map<Integer, int[]> map = new HashMap<Integer, int[]>();
        for (Record record : records) {
            int[] pair = map.get(record.star);
            if (pair == null) {
                pair = new int[2];
                map.put(record.star, pair);
            }
            pair[0]++;
            if (record.success) {
                pair[1]++;
            }
        }
        return map;
    }

    private static String dayStringAgo(int days) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -Math.abs(days));
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(calendar.getTime());
    }

    /**
     * 列出日志目录里最近的文件名（调试用）。
     */
    public static List<String> fileNames(File dataFolder) {
        List<String> out = new ArrayList<String>();
        File[] files = logDir(dataFolder).listFiles();
        if (files == null) {
            return out;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        for (File file : files) {
            out.add(file.getName());
        }
        return out;
    }
}
