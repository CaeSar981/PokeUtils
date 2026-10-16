package com.mc9y.pokeutils.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 把玩家数据<b>整体</b>从一个存储后端搬到另一个（{@code /pu migrate}）。
 *
 * <h3>为什么需要它</h3>
 * <p>后端的切换只在 {@code onEnable} 读一次 {@code Storage.type}。运维从 SQLite
 * 换到 MySQL 时，之前所有玩家的粘剂与保底计数都留在本地库里 —— 切过去等于
 * <b>资产蒸发</b>。{@link StorageFactory} 在降级时会打一句「本地 SQLite 里的数据与
 * MySQL 里的是两份不同的数据」提醒这件事，但提醒不等于解决。这个类就是那个解决。</p>
 *
 * <h3>五条实现约束</h3>
 * <ol>
 *   <li><b>先落盘再读</b>：源端如果有内存缓存，先把脏条目刷下去、把异步写回队列排空，
 *       否则搬的是「几毫秒前的旧值」。这一步不能省。</li>
 *   <li><b>一次查完</b>：走 {@link Storage#listPlayers()} / {@link Storage#listStickers()}
 *       两个批量接口，不做「先列 UUID 再逐个查」的 N+1 —— 一万个玩家在 MySQL 上就是
 *       一万次网络往返。</li>
 *   <li><b>只读源端</b>：迁移<b>不删不改</b>源库的任何数据。目标端用绝对值 upsert
 *       （{@link Storage#setSticker} 的语义就是写绝对值），所以重复执行结果一致 ——
 *       中途失败之后修好再跑一次即可对齐，不需要先回滚。</li>
 *   <li><b>目标端打不开就中止</b>：绝不写半份数据。这也是 {@code dry-run} 存在的意义 ——
 *       它能先验证目标端可达，而不需要真的写。</li>
 *   <li><b>读失败与「源库本来就是空的」必须能区分</b>：两个后端的读路径都是「失败留痕 + 降级
 *       成空表」，于是读失败的表现和空库一模一样。这里在 0 条时额外提醒去看日志，
 *       否则运维会把「读失败」当成「源库没数据」而安心跳过。</li>
 * </ol>
 */
public final class StorageMigrator {

    private StorageMigrator() {
    }

    /** 迁移结果。{@link #messages} 是给命令执行者看的逐行输出（已带颜色代码）。 */
    public static final class Result {
        public final boolean ok;
        public final boolean dryRun;
        public final int players;
        public final int stickers;
        public final List<String> messages;

        Result(boolean ok, boolean dryRun, int players, int stickers, List<String> messages) {
            this.ok = ok;
            this.dryRun = dryRun;
            this.players = players;
            this.stickers = stickers;
            this.messages = messages;
        }
    }

    /**
     * 执行迁移。
     *
     * <p><b>为什么目标端是参数而不是在这里打开</b>：打开后端要读 {@code config.yml}，
     * 而 Bukkit 的 {@code FileConfiguration} 不是线程安全的（{@code reloadConfig} 会在同一个
     * 对象上重新解析）。本方法跑在后台线程上，所以「读配置 + 建连接」这一步必须由调用方
     * 在主线程完成。副作用是这个类不依赖 Bukkit，只依赖 {@link Storage} 接口 —— 好测。</p>
     *
     * <p>本方法<b>负责关闭</b> {@code target}（无论成功失败）。</p>
     *
     * @param source     当前后端（读写都走它，本方法不会修改它）
     * @param target     已打开的目标后端
     * @param targetKind 目标端标识，只用于输出文案
     * @param dryRun     true = 只读源端 + 验证目标端可达，不写入任何数据
     */
    public static Result migrate(Logger logger, Storage source, Storage target,
                                 String targetKind, boolean dryRun) {
        List<String> messages = new ArrayList<String>();

        // ---- 第 1 步：把源端内存里还没写下去的先落地，否则搬的是旧值 ----
        if (source instanceof CachedStorage) {
            ((CachedStorage) source).flushAll();
        }
        if (!Async.drain(5000L)) {
            messages.add("§e 源端写队列在 5 秒内没有排空，最后几条变更可能没被搬走。");
            messages.add("§7 迁移结束后再执行一次即可对齐（upsert 是幂等的）。");
        }

        // ---- 第 2 步：一次读完源端 ----
        List<Storage.PlayerRecord> players;
        List<Storage.StickerRecord> stickers;
        try {
            players = source.listPlayers();
            stickers = source.listStickers();
        } catch (RuntimeException ex) {
            StorageMigrator.closeQuietly(target);
            messages.add("§c 读取源端失败：" + ex.getMessage());
            return new Result(false, dryRun, 0, 0, messages);
        }

        if (players.isEmpty() && stickers.isEmpty()) {
            StorageMigrator.closeQuietly(target);
            messages.add("§e 源库里读到 0 名玩家、0 条粘剂行。");
            messages.add("§7 这两种后端的读失败都会降级成空表（留痕但不抛异常），所以"
                    + "「读失败」和「源库本来就是空的」在这里长得一模一样 ——"
                    + "如果确认源库有数据，请查服务端日志里最近一次的 SEVERE。");
            return new Result(false, dryRun, 0, 0, messages);
        }

        try {
            messages.add("§7 源端: §f" + source.kind() + " §7读到 §f"
                    + players.size() + " §7名玩家 / §f" + stickers.size() + " §7条粘剂行");
            messages.add("§7 目标端: §f" + target.describe());

            if (dryRun) {
                messages.add("§a 干跑完成：目标端可达，正式执行会写入上面这些数据。");
                messages.add("§7 干跑不写任何数据；去掉参数末尾的 dry-run 即正式执行。");
                return new Result(true, true, players.size(), stickers.size(), messages);
            }

            // ---- 第 3 步：写入（绝对值 upsert，幂等）----
            Map<UUID, String> names = new HashMap<UUID, String>();
            for (Storage.PlayerRecord player : players) {
                names.put(player.uuid, player.name);
            }

            int wrotePlayers = 0;
            for (Storage.PlayerRecord player : players) {
                target.touchPlayer(player.uuid, player.name);
                target.setFailStreak(player.uuid, player.name, player.failStreak);
                // 新增（A2）：保底档位必须一起搬 —— 只搬次数不搬档位的话，
                // 目标端会变成「次数记着、档位是 0」，而 0 被当作「不限定档位」，
                // 于是刚迁完的服上保底可以在任意档位触发。这一条漏了就很难看出来。
                target.setFailStar(player.uuid, player.name, player.failStar);
                wrotePlayers++;
            }

            int wroteStickers = 0;
            for (Storage.StickerRecord sticker : stickers) {
                String name = names.get(sticker.uuid);
                // 有粘剂行却没有玩家行属于脏数据（外键缺失）。补一行玩家记录，
                // 名字留空 —— 那个人下次进服时会被刷成真名，总好过让这条粘剂搬不过去。
                target.setSticker(sticker.uuid, name == null ? "" : name, sticker.type, sticker.amount);
                wroteStickers++;
            }

            messages.add("§a 迁移完成：§f" + wrotePlayers + " §a名玩家 / §f" + wroteStickers
                    + " §a条粘剂行已写入 " + targetKind + "。");
            messages.add("§7 源端数据未做任何改动。目标端现有 §f"
                    + target.listPlayers().size() + " §7名玩家。");
            messages.add("§7 下一步：把 config.yml 的 §fStorage.type §7改成 §f" + targetKind
                    + " §7并重启服务器。");
            return new Result(true, false, wrotePlayers, wroteStickers, messages);
        } catch (RuntimeException ex) {
            logger.severe("迁移到 " + targetKind + " 中途失败: " + ex);
            messages.add("§c 迁移中途失败：" + ex.getMessage());
            messages.add("§7 已写入的部分保留在目标库里（源端未动）。本命令是幂等 upsert，"
                    + "修好问题后重跑一次即可对齐，不需要先清理目标端。");
            return new Result(false, dryRun, 0, 0, messages);
        } finally {
            StorageMigrator.closeQuietly(target);
        }
    }

    private static void closeQuietly(Storage target) {
        try {
            target.close();
        } catch (Throwable ignored) {
            // 关不掉目标端的独立连接不影响迁移结果
        }
    }
}
