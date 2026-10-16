package com.mc9y.pokeutils.listener;

import com.mc9y.msgtoggle.MSGToggle;
import com.mc9y.pokemonapi.api.event.CustomEvent;
import com.mc9y.pokemonapi.api.pokemon.PokemonUtil;
import com.mc9y.pokemonapi.api.storage.PlayerStorageUtil;
import com.mc9y.pokestar.PokeStarAPI;
import com.mc9y.pokeutils.PokeUtils;
import com.mc9y.pokeutils.data.CachedStorage;
import com.mc9y.pokeutils.data.LegacyDataMigration;
import com.mc9y.pokeutils.data.PlayerData;
import com.mc9y.pokeutils.data.Storage;
import com.mc9y.pokeutils.data.StickerType;
import com.mc9y.pokeutils.data.YSData;
import com.pixelmonmod.pixelmon.Pixelmon;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.enums.EnumSpecies;
import com.pixelmonmod.pixelmon.storage.PlayerPartyStorage;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 合成界面交互监听器。
 *
 * <p>说明：本方法刻意保持了原插件的深层 {@code if / else if} 嵌套写法（而非提前 return），
 * 以便编译产物与原插件在字节码层面保持一致。</p>
 *
 * <p><b>本类包含十处对原插件的修复</b>（均已在代码处以「修复」注释标出）：</p>
 * <ol>
 *   <li>{@code star1 < 6} → {@code star1 < 7}：放开「六星 → 七星」档位与
 *       {@code 精灵星级不能超过五星} 的提示文案；</li>
 *   <li>VIP 保留一只：修掉写重的 {@code vip.yx || vip.yx}，权限节点改由 config.yml 配置；</li>
 *   <li>{@code onInventoryClose}：把「关掉合成界面就清空会话」改为延迟一 tick 判定，
 *       避免界面被切换（而非被放弃）时会话被误删；</li>
 *   <li>产出池为空预检：在扣金币 / 扣粘剂 / 删精灵 <b>之前</b>判定
 *       {@code getStarPokemon(star1 + 1)} 是否为空，为空则中止合成并提示玩家，
 *       避免依赖数据损坏时玩家净亏且只留一行 {@code IndexOutOfBoundsException}；</li>
 *   <li>{@link #onInventoryDrag}：补上拖拽事件的拦截。原插件只处理
 *       {@code InventoryClickEvent}，而 Bukkit 里拖拽是<b>独立事件</b>，
 *       {@code setCancelled(true)} 管不到它 —— 玩家可以把物品拖进合成界面的 45 格里，
 *       关掉界面后这些物品就消失了；</li>
 *   <li>槽位失效防护：界面开着时，所选槽位上的精灵可能已被移走（其他插件操作队伍、
 *       或连点两次「确认合成」而第一次已删掉精灵）。原实现直接
 *       {@code pps.get(slot).getSpecies()} 解引用，会抛 {@code NullPointerException}；</li>
 *   <li>0 星防护：{@code PokeStarAPI#getPokemonStar} 对未被 stars.yml 收录的种族返回 0，
 *       而判定 {@code star1 == star2 && star1 < 7} 中 {@code 0 == 0} 成立，
 *       会让两只「未收录」精灵以 {@code price[0] = 0}（<b>不花金币</b>）+ 80% 成功率成交。
 *       详见 {@link #onInventoryClick} 内对应注释；</li>
 *   <li>粘剂数据缺失防护：{@code ysdatas.get(...)} 原为直接解引用，且位置在扣金币之后；</li>
 *   <li>数值配置化：各档金币消耗与失败补偿比例改为读 config.yml（默认值与原来一致）；</li>
 *   <li>{@code onInventoryClose} 的延迟重开分支：回调里补一次会话判空 ——
 *       玩家在这 2 tick 内退服时，原实现会在 {@code playerdatas.get(...)} 上抛空指针。</li>
 * </ol>
 *
 * <p>另新增一项非修复类功能：<b>连续失败保底</b>（见 {@link #onInventoryClick} 内注释），
 * 计数持久化在玩家的 {@code Data/&lt;名&gt;.yml} 里。</p>
 */
public class PokeCompoundPlayerListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getInventory().getTitle().equals("§8精灵合成")) {
            e.setCancelled(true);
            if (PokeUtils.getInstance().playerdatas.containsKey(e.getWhoClicked().getName())) {
                Player p = (Player) e.getWhoClicked();
                if (e.getClickedInventory() != null && e.getClickedInventory().getTitle().equals("§8精灵合成")) {
                    switch (e.getSlot()) {
                        case 12:
                            PokeUtils.getInstance().playerdatas.get(p.getName()).openSelectPokemonGui(p, 1);
                            break;
                        case 14:
                            PokeUtils.getInstance().playerdatas.get(p.getName()).openSelectPokemonGui(p, 2);
                            break;
                        case 16:
                            PokeUtils.getInstance().playerdatas.get(p.getName()).openYSGui(p);
                            break;
                        case 26:
                            // 新增（玩家体验）：打开产出预览。
                            PokeUtils.getInstance().playerdatas.get(p.getName()).openPreviewGui(p, 1);
                            break;
                        case 32:
                            if (PokeUtils.getInstance().playerdatas.containsKey(p.getName())) {
                                PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());

                                // 新增（防重复）：同一 tick 内被派发的第二次点击直接拒绝。
                                // 冷却管的是「隔一会儿的连点」，而同 tick 内的两个事件是依次
                                // 同步执行的 —— 原实现只靠「槽位上的精灵已被清空」间接拦住，
                                // 那属于碰巧。这里用显式标志，下一 tick 无条件解除。
                                if (pd.isProcessing()) {
                                    p.sendMessage("§3精灵合成 §f|| §e上一次操作还在处理中, 请稍候！");
                                    break;
                                }
                                pd.setProcessing(true);
                                final PlayerData processingRef = pd;
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        processingRef.setProcessing(false);
                                    }
                                }.runTaskLater(PokeUtils.getInstance(), 1L);

                                // 新增（可配）：材料数量与「是否必须放粘剂」都由 config.yml 决定。
                                int need = PokeUtils.getInstance().getMaterialCount();
                                boolean stickerRequired = PokeUtils.getInstance().isStickerRequired();
                                boolean hasMaterials = pd.getSlot1() > -1 && (need < 2 || pd.getSlot2() > -1);
                                boolean hasSticker = !stickerRequired || pd.getYS() != null;

                                if (hasMaterials && hasSticker) {
                                    PokeStarAPI psa = new PokeStarAPI();
                                    PlayerPartyStorage pps = Pixelmon.storageManager.getParty(p.getUniqueId());
                                    int amount = 0;

                                    for (int i = 0; i < 6; i++) {
                                        Pokemon pokemon = pps.get(i);
                                        if (pokemon != null) {
                                            if (pokemon.isEgg()) {
                                                continue;
                                            }
                                            amount++;
                                        }
                                    }

                                    // 修复（可配）：队伍下限跟着材料数量走。原实现写死「最少 3 只」，
                                    // 对应的是「消耗 2 只、至少要剩 1 只」；材料数配成 1 时下限也应
                                    // 变成 2。need = 2 时文案与原来逐字一致。
                                    if (amount <= need) {
                                        p.sendMessage("§3精灵合成 §f|| §c背包最少需要装备"
                                                + PokeUtils.numeral(need + 1) + "只精灵!");
                                    } else {
                                        // 修复（新增防护）：先把两只精灵取出来判空。
                                        // 界面开着的时候，所选槽位上的精灵可能已经被移走 —— 其他插件/指令操作了队伍，
                                        // 或者玩家连点两次「确认合成」而第一次已经删掉了这两只。原实现直接
                                        // pps.get(slot).getSpecies() 解引用，这里会抛 NullPointerException。
                                        // 材料数为 1 时 slot2 恒为 -1，不能拿它去 pps.get()。
                                        Pokemon pokemon1 = pps.get(pd.getSlot1());
                                        Pokemon pokemon2 = need < 2 ? pokemon1 : pps.get(pd.getSlot2());
                                        if (pokemon1 == null || pokemon2 == null) {
                                            pd.setSlot1(-1);
                                            pd.setSlot2(-1);
                                            p.sendMessage("§3精灵合成 §f|| §c所选槽位上的精灵已不存在, 请重新选择!");
                                            p.closeInventory();
                                            return;
                                        }

                                        int star1 = psa.getPokemonStar(pokemon1.getSpecies().name());
                                        int star2 = psa.getPokemonStar(pokemon2.getSpecies().name());
                                        // 修复：价格原来写死在这里（{0,1000,10000,30000,100000,400000,1000000}），
                                        // 现改由 config.yml 的 Compound.price.<star> 提供，默认值与原来一致。

                                        // 修复（新增防护）：PokeStarAPI#getPokemonStar 内部是 Optional.orElse(0)，
                                        // 凡未被 stars.yml 收录的种族一律返回 0（例如 MissingNo 这类
                                        // getBaseStats() 取不到基础种族值的种族，PokeStar 会把它整个跳过）。
                                        // 而下面的判定是 star1 == star2 && star1 < 7，0 == 0 成立，
                                        // 于是两只「未收录」精灵会以 price[0] = 0（不花金币）+ jl = 80% 成交 ——
                                        // 等于免费换一只一星精灵。1 星是星级系统的最低合法档位，0 一律按「未收录」拒绝。
                                        if (star1 < 1 || star2 < 1) {
                                            p.sendMessage("§3精灵合成 §f|| §c所选精灵未被星级系统收录, 无法参与合成!");
                                            return;
                                        }

                                        if (star1 == star2) {
                                            // 修复（原为 star1 < 6）：与 PlayerData#checkJL 同一处问题，
                                            // 六星是「六星 -> 七星」这一档的合法输入。
                                            if (star1 < 7) {
                                                CustomEvent customEvent = new CustomEvent("PokeCompoundEvent", pokemon1, p);
                                                CustomEvent customEvent1 = new CustomEvent("PokeCompoundEvent", pokemon2, p);
                                                Bukkit.getPluginManager().callEvent(customEvent);
                                                if (customEvent.isCancelled()) {
                                                    return;
                                                }

                                                Bukkit.getPluginManager().callEvent(customEvent1);
                                                if (customEvent1.isCancelled()) {
                                                    return;
                                                }

                                                // 新增（防滥用）：合成冷却。连点器 / 宏能在一瞬间连点确认按钮，
                                                // 冷却期间直接返回，不产生任何消耗。
                                                // 注意「判定通过才记时间」—— 否则冷却期间的连点会不断给冷却续期。
                                                if (!PokeUtils.getInstance().tryUseCompoundCooldown(p)) {
                                                    p.sendMessage("§3精灵合成 §f|| §e操作太快了, 请稍后再试！");
                                                    return;
                                                }

                                                // 新增（防损失）：高星材料二次确认。
                                                // 第一次点击只进入「待确认」状态，不消耗任何东西；
                                                // 玩家改材料 / 改粘剂都会把确认状态清掉，
                                                // 避免「先放低星骗过确认、再换成六星」这种绕过。
                                                int confirmMin = PokeUtils.getInstance().getConfirmMinStar();
                                                if (confirmMin > 0 && star1 >= confirmMin && !pd.isConfirmed()) {
                                                    pd.setConfirmed(true);
                                                    p.sendMessage("§3精灵合成 §f|| §c本次将消耗两只 " + star1
                                                            + " 星精灵, 请再点击一次「确认合成」以确认操作！");
                                                    pd.openGui(p);
                                                    return;
                                                }

                                                // 修复（新增防护）：产出池为空时必须在这里先中止 —— 此时还没动玩家的任何资产。
                                                // 原实现在下面拿到产出池后直接 get(随机下标)，一旦依赖数据损坏
                                                // （例如 PokeStar 的 stars.yml 把某个星级档写丢，见
                                                // ../TestServer/README-测试指南.md 第五节），执行顺序会是：
                                                //   扣金币 → 扣粘剂 → 关界面 → 删掉两只精灵 → get(...) 抛 IndexOutOfBoundsException
                                                // 玩家净亏，而控制台只留一行报错。治本靠修 stars.yml，这里是不依赖外部数据的兜底。
                                                // 注意：提示文案只用档位数字，不引用 psa.getPokeShowName() ——
                                                // 档位缺失时它返回空串，正是本分支要处理的情况。
                                                List<String> starPMs = psa.getStarPokemon(star1 + 1);
                                                if (starPMs == null || starPMs.isEmpty()) {
                                                    p.sendMessage("§3精灵合成 §f|| §c目标 " + (star1 + 1)
                                                            + " 星档位的产出池为空, 已中止合成, 未扣除任何物品!");
                                                    return;
                                                }

                                                // 修复（新增防护）：粘剂数据缺失时直接中止。原实现在下面
                                                // ysdatas.get(...).withdraw(...) 处直接解引用，数据不在就是一条空指针，
                                                // 而且这一行排在扣金币之后 —— 玩家会先被扣钱再看到报错。
                                                YSData ysd = PokeUtils.getInstance().ysdatas.get(p.getName());
                                                if (ysd == null) {
                                                    p.sendMessage("§3精灵合成 §f|| §c粘剂数据尚未加载, 请重新进服后再试!");
                                                    return;
                                                }

                                                // 新增（经济）：优先看背包里有没有可用的合成券。
                                                // 券的判定刻意放在「经济服务是否可用」之前 —— 用券本来就不花
                                                // 金币，经济插件挂掉时不该顺带把「用券合成」也一起废掉。
                                                int voucherSlot = PokeUtils.getInstance().findVoucherSlot(p, star1);
                                                boolean useVoucher = voucherSlot >= 0;
                                                // 新增：用券合成时先留一份券的快照 —— 合成失败要原样还回去。
                                                // 在消耗之前取，是因为 consumeVoucherAt 会把数量减 1（或清空槽位）。
                                                ItemStack voucherSnapshot = null;
                                                if (useVoucher) {
                                                    try {
                                                        ItemStack raw = p.getInventory().getItem(voucherSlot);
                                                        if (raw != null) {
                                                            voucherSnapshot = raw.clone();
                                                            voucherSnapshot.setAmount(1);
                                                        }
                                                    } catch (Throwable ignored) {
                                                        // 拿不到快照就退不了券，但合成流程不该因此中断。
                                                    }
                                                }

                                                // 修复（新增防护）：Vault 装上了但没有可用的经济插件时，
                                                // economy 是 null，原实现直接 economy.getBalance(...) 会抛空指针。
                                                if (!useVoucher && PokeUtils.getInstance().economy == null) {
                                                    p.sendMessage("§3精灵合成 §f|| §c服务器经济服务不可用, 暂时无法合成!");
                                                    return;
                                                }

                                                // 修复：价格改由 config.yml 提供（默认值与原来一致），
                                                // 新增（经济）：再叠加权限组折扣 —— 界面显示的就是这个值。
                                                int money = useVoucher ? 0 : PokeUtils.getInstance().getEffectivePrice(p, star1);
                                                if (useVoucher
                                                        || PokeUtils.getInstance().economy.getBalance((OfflinePlayer) p) >= (double) money) {
                                                    if (useVoucher) {
                                                        PokeUtils.getInstance().consumeVoucherAt(p, voucherSlot);
                                                    } else {
                                                        PokeUtils.getInstance().economy.withdrawPlayer((OfflinePlayer) p, (double) money);
                                                    }
                                                    // 新增（可配）：粘剂非必需时 getYS() 可能为 null，不能硬扣。
                                                    if (pd.getYS() != null) {
                                                        ysd.withdraw(pd.getYS(), 1);
                                                    }

                                                    // 新增：连续失败保底。计数写在该玩家自己的数据文件里（YSData），
                                                    // 换服 / 重启都不会丢。达到阈值时本次直接判成功；成功（无论是否保底）
                                                    // 后清零，失败则 +1。阈值与开关都取自 config.yml。
                                                    int streak = ysd.getFailStreak();
                                                    int threshold = PokeUtils.getInstance().getPityThreshold();
                                                    boolean pity = PokeUtils.getInstance().isPityEnabled()
                                                            && threshold > 0 && streak >= threshold;
                                                    int refund = PokeUtils.getInstance().getRefundPercent();

                                                    int randomJL = (int) (1.0 + Math.random() * 100.0);
                                                    p.closeInventory();
                                                    if (pity) {
                                                        p.sendMessage("§3精灵合成 §f|| §e已连续失败 §f" + streak
                                                                + " §e次, 本次触发保底, 必定成功！");
                                                    }
                                                    if (pity || randomJL <= pd.getSumJL()) {
                                                        ysd.setFailStreak(0);
                                                        // 合成结束，二次确认状态作废 —— 下次合成要重新确认
                                                        pd.setConfirmed(false);
                                                        pps.set(pd.getSlot1(), null);
                                                        // 新增（可配）：材料数为 1 时只有 slot1 有东西可清。
                                                        if (need >= 2) {
                                                            pps.set(pd.getSlot2(), null);
                                                        }
                                                        // starPMs 已在扣费前的空池预检处取好（非空），此处直接复用。
                                                        String rpmn = ((String) starPMs.get((int) (Math.random() * (double) starPMs.size()))).replace("Ho-Oh", "HoOh");
                                                        Pokemon pokemon = Pixelmon.pokemonFactory.create(EnumSpecies.valueOf(rpmn));
                                                        pokemon.setLevel(1);
                                                        PlayerStorageUtil.givePlayerPokemon(p, pokemon);
                                                        p.sendMessage("§3精灵合成 §f|| §a成功合成一只 " + psa.getPokeShowName(star1 + 1) + " §a, 精灵已发放至精灵槽！");
                                                        // 新增（玩家体验）：成功音效 + 一条屏幕 Title。
                                                        // 原插件从点开界面到拿到精灵全程没有任何声音或特效反馈。
                                                        PokeUtils.getInstance().playFeedback(p, "success");
                                                        PokeUtils.getInstance().sendSuccessTitle(p, psa.getPokeShowName(star1 + 1),
                                                                PokemonUtil.getPokemonName(pokemon.getSpecies()));
                                                        // 新增：合成流水日志（原插件一次 getLogger() 都没调过）
                                                        PokeUtils.getInstance().logCompound(p.getName(), star1, pd.getYS(), true,
                                                                money, 0, "产出 " + psa.getPokeShowName(star1 + 1)
                                                                        + (pity ? " (保底)" : "")
                                                                        + (useVoucher ? " (合成券)" : ""));
                                                        // 修复：播报阈值与文案原来写死（>=6 星 + 固定文案），现由配置提供。
                                                        broadcastCompound(p, star1 + 1, pokemon, psa);
                                                    } else {
                                                        ysd.setFailStreak(streak + 1);
                                                        pd.setConfirmed(false);

                                                        // 新增（经济）：失败补偿方式可配。
                                                        // 原实现只有「返还 20% 金币」这一种，而金币对已经投入大量时间
                                                        // 的玩家来说是最没感觉的补偿；配成送粘剂后，玩家手里会多一次
                                                        // 可用于下一次合成的资源，继续投入的意愿完全不同。
                                                        String refundMode = PokeUtils.getInstance().getRefundMode();
                                                        int moneyRefund = 0;
                                                        if (("money".equals(refundMode) || "both".equals(refundMode))
                                                                && money > 0) {
                                                            moneyRefund = (int) ((double) money / 100.0 * (double) refund);
                                                        }
                                                        int stickerRefund = 0;
                                                        StickerType refundSticker = null;
                                                        if ("sticker".equals(refundMode) || "both".equals(refundMode)) {
                                                            int stickerAmount = PokeUtils.getInstance().getRefundStickerAmount();
                                                            refundSticker = PokeUtils.getInstance()
                                                                    .getStickerType(PokeUtils.getInstance().getRefundStickerType());
                                                            if (refundSticker != null && stickerAmount > 0
                                                                    && star1 >= PokeUtils.getInstance().getRefundStickerMinStar()) {
                                                                ysd.deposit(refundSticker.getKey(), stickerAmount);
                                                                stickerRefund = stickerAmount;
                                                            }
                                                        }

                                                        StringBuilder failMsg = new StringBuilder("§3精灵合成 §f|| §c本次合成精灵失败");
                                                        if (moneyRefund > 0) {
                                                            failMsg.append(", 补偿合成所耗金币的").append(refund).append("%");
                                                        }
                                                        if (stickerRefund > 0) {
                                                            failMsg.append(", 并返还 ").append(stickerRefund)
                                                                    .append(" 个").append(refundSticker.getDisplay());
                                                        }
                                                        // 新增（经济）：用券失败时把券退还。
                                                        // 没有这一条时，用券合成的失败补偿是「什么都不返」——
                                                        // money 恒为 0，金币返还那一段永远走不到，
                                                        // 玩家净亏券 + 粘剂 + 两只精灵。
                                                        String voucherRefundMsg = null;
                                                        if (useVoucher && PokeUtils.getInstance().isVoucherRefundEnabled()) {
                                                            voucherRefundMsg = PokeUtils.getInstance()
                                                                    .refundVoucher(p, voucherSnapshot);
                                                        }
                                                        if (voucherRefundMsg != null) {
                                                            failMsg.append(", ").append(voucherRefundMsg);
                                                        }
                                                        failMsg.append("！");
                                                        p.sendMessage(failMsg.toString());

                                                        // 修复：此处原为 if (star1 == 6)，但它被外层 if (star1 < 6) 挡着，
                                                        // star1 最大只能是 5，分支永远不可达 —— config.yml 承诺的
                                                        // 「合成七星失败保留一只精灵」实际从未生效。放开星级上限后本分支才能执行。
                                                        if (star1 == 6) {
                                                            // 修复（原为 if (p.hasPermission("vip.yx") || p.hasPermission("vip.yx"))）：
                                                            // 两个条件完全相同，对应 config.yml 的「优秀训练师 / 传说训练师」两个身份丢了一个。
                                                            // 权限节点改由 config.yml 的 VIP.keep-one 配置，缺省回退到 vip.yx / vip.cs。
                                                            if (hasKeepOnePermission(p)) {
                                                                int r = (int) (Math.random() * 2.0);
                                                                if (r == 0) {
                                                                    pps.set(pd.getSlot1(), null);
                                                                } else if (need >= 2) {
                                                                    pps.set(pd.getSlot2(), null);
                                                                }

                                                                p.sendMessage("§3精灵合成 §f|| §e来自众神的祝福, 保留住了一只精灵！");
                                                            } else {
                                                                pps.set(pd.getSlot1(), null);
                                                                if (need >= 2) {
                                                                    pps.set(pd.getSlot2(), null);
                                                                }
                                                            }
                                                        } else {
                                                            pps.set(pd.getSlot1(), null);
                                                            if (need >= 2) {
                                                                pps.set(pd.getSlot2(), null);
                                                            }
                                                        }

                                                        // 只有真的有钱可返时才调经济 API —— 用合成券合成时 money 为 0，
                                                        // 而券本来就意味着「经济插件缺失也能用」。
                                                        if (moneyRefund > 0) {
                                                            PokeUtils.getInstance().economy
                                                                    .depositPlayer((OfflinePlayer) p, (double) moneyRefund);
                                                        }

                                                        // 新增（玩家体验）：失败音效 + 保底进度提示。
                                                        // 保底是上一轮加的功能，但玩家要打开界面才看得到进度，
                                                        // 等于「机制存在、体感为零」。
                                                        PokeUtils.getInstance().playFeedback(p, "fail");
                                                        if (PokeUtils.getInstance().isPityEnabled()
                                                                && PokeUtils.getInstance().getPityThreshold() > 0) {
                                                            PokeUtils.getInstance().notifyPity(p, streak + 1,
                                                                    PokeUtils.getInstance().getPityThreshold());
                                                        }

                                                        // 新增：合成流水日志
                                                        StringBuilder detail = new StringBuilder(
                                                                star1 == 6 && hasKeepOnePermission(p) ? "六星失败, 保留一只" : "-");
                                                        if (stickerRefund > 0) {
                                                            detail.append(" 返还")
                                                                    .append(stickerRefund)
                                                                    .append("个")
                                                                    .append(refundSticker.getKey());
                                                        }
                                                        if (useVoucher) {
                                                            detail.append(" 使用合成券");
                                                            if (voucherRefundMsg != null) {
                                                                detail.append("(已退还)");
                                                            }
                                                        }
                                                        PokeUtils.getInstance().logCompound(p.getName(), star1, pd.getYS(), false,
                                                                money, moneyRefund, detail.toString());
                                                    }
                                                } else {
                                                    p.sendMessage("§3精灵合成 §f|| §c你没有足够的金币！");
                                                }
                                            } else {
                                                p.sendMessage("§3精灵合成 §f|| §c精灵星级不能超过六星！");
                                            }
                                        } else {
                                            p.sendMessage("§3精灵合成 §f|| §c两只精灵星级不同");
                                        }
                                    }
                                } else {
                                    p.sendMessage("§3精灵合成 §f|| §c未选择齐精灵/合成粘剂");
                                }
                            }
                    }
                }
            }
        } else if (e.getInventory().getTitle().startsWith("§8请选择要合成的精灵: ")) {
            e.setCancelled(true);
            if (e.getClickedInventory() != null && e.getClickedInventory().getTitle().startsWith("§8请选择要合成的精灵: ")) {
                Player p = (Player) e.getWhoClicked();
                if (PokeUtils.getInstance().playerdatas.containsKey(p.getName())) {
                    PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());
                    int slot = Integer.parseInt(e.getClickedInventory().getTitle().split(": ")[1]);
                    int pokeSlot = -1;
                    ItemStack item = e.getCurrentItem();
                    if (item == null || item.getType().equals(Material.AIR) || item.getType().equals(Material.BARRIER)) {
                        return;
                    }

                    switch (e.getSlot()) {
                        case 11:
                            pokeSlot = 0;
                            break;
                        case 13:
                            pokeSlot = 1;
                            break;
                        case 15:
                            pokeSlot = 2;
                            break;
                        case 29:
                            pokeSlot = 3;
                            break;
                        case 31:
                            pokeSlot = 4;
                            break;
                        case 33:
                            pokeSlot = 5;
                    }

                    if (pokeSlot > -1) {
                        PlayerPartyStorage pps = Pixelmon.storageManager.getParty(p.getUniqueId());
                        int amount = pps.getTeam().size();
                        if (pd.getSlot1() == pokeSlot || pd.getSlot2() == pokeSlot) {
                            p.sendMessage("§3精灵合成 §f|| §c该精灵已在其他槽位选择！");
                            return;
                        }

                        // 修复：这里原来是 > 0，但队伍槽位下标 0 是合法的第一格 ——
                        // 「队伍恰好两只、先选第 2 格再选第 1 格」这种顺序下会少减一只，
                        // 让预览算出「还剩 2 只」而实际只剩 1 只。同一件事文件里有 > -1 / == / > 0
                        // 三种写法，以 >= 0 为准（唯一正确的那个）。
                        amount += pd.getSlot1() >= 0 ? -1 : 0;
                        amount += pd.getSlot2() >= 0 ? -1 : 0;
                        if (amount > 1) {
                            if (slot == 1) {
                                pd.setSlot1(pokeSlot);
                            } else {
                                pd.setSlot2(pokeSlot);
                            }

                            PokeUtils.getInstance().playFeedback(p, "select");
                            p.closeInventory();
                        } else {
                            p.sendMessage("§3精灵合成 §f|| §c最少必须有一只精灵！");
                        }
                    }
                }
            }
        } else if (e.getInventory().getTitle().equals("§8请选择要使用的粘剂")) {
            e.setCancelled(true);
            if (e.getClickedInventory() != null && e.getClickedInventory().getTitle().equals("§8请选择要使用的粘剂")) {
                Player p = (Player) e.getWhoClicked();
                if (PokeUtils.getInstance().playerdatas.containsKey(p.getName())) {
                    // 修复：原实现是 case 19 / case 20 两个固定分支，粘剂键写死为 "PT" / "JL"。
                    // 现在改成「界面槽位 -> 粘剂定义」查表，配置里加多少种粘剂都能被正确响应。
                    StickerType sticker = PokeUtils.getInstance().getStickerTypeBySlot(e.getSlot());
                    if (sticker != null) {
                        // 修复（新增防护）：ysdatas.get(...) 原来直接解引用，数据缺失时是一条空指针。
                        YSData ysd = PokeUtils.getInstance().ysdatas.get(p.getName());
                        int amount = ysd == null ? 0 : ysd.getAmount(sticker.getKey());
                        if (amount > 0) {
                            PokeUtils.getInstance().playerdatas.get(p.getName()).setYS(sticker.getKey());
                            PokeUtils.getInstance().playFeedback(p, "select");
                            p.closeInventory();
                        } else {
                            p.sendMessage("§3精灵合成 §f|| §c你没有足够的" + sticker.getInsufficient());
                        }
                    }
                }
            }
        } else if (e.getInventory().getTitle().startsWith("§8可能产出的精灵: ")) {
            // 新增（玩家体验）：产出预览界面的翻页与返回。
            e.setCancelled(true);
            if (e.getClickedInventory() != null
                    && e.getClickedInventory().getTitle().startsWith("§8可能产出的精灵: ")) {
                Player p = (Player) e.getWhoClicked();
                if (PokeUtils.getInstance().playerdatas.containsKey(p.getName())) {
                    PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());
                    int current = 1;
                    try {
                        current = Integer.parseInt(e.getClickedInventory().getTitle().split(": ")[1]);
                    } catch (NumberFormatException ignored) {
                        // 标题被外部改动时按第一页处理，不抛异常
                    }
                    if (e.getSlot() == 45) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.openPreviewGui(p, current - 1);
                    } else if (e.getSlot() == 53) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.openPreviewGui(p, current + 1);
                    } else if (e.getSlot() == 49) {
                        // 按钮叫「返回合成界面」，就得真的回到合成界面 ——
                        // 只 closeInventory 会把玩家丢在一个什么都没有的空屏上。
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.openGui(p);
                    }
                }
            }
        }
    }

    /**
     * 拦截拖拽 —— 修复（新增防护）：原插件只注册了 {@link InventoryClickEvent} 与
     * {@link InventoryCloseEvent}，而 Bukkit 里「拖拽」是独立于「点击」的事件，
     * {@code InventoryClickEvent#setCancelled(true)} 对它没有任何约束力。
     * 合成界面是 {@code Bukkit.createInventory} 造出来的真实容器（45 格），
     * 所以玩家可以把背包里的物品拖进去；关掉界面后这些物品就随界面一起消失了。
     *
     * <p>这里只拦「落在本插件界面内」的那部分拖拽（{@code rawSlot < 顶部界面尺寸}）。
     * 玩家在自己背包范围内整理物品的拖拽不受影响 —— 那种操作本身无害，
     * 一刀切地全 cancel 会平白惹人烦。</p>
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!isPokeUtilsGuiTitle(e.getInventory().getTitle())) {
            return;
        }
        int topSize = e.getInventory().getSize();
        for (int rawSlot : e.getRawSlots()) {
            if (rawSlot < topSize) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        PokeUtils plugin = PokeUtils.getInstance();
        plugin.playerdatas.remove(e.getPlayer().getName());
        plugin.ysdatas.remove(e.getPlayer().getName());
        // 合成冷却按 UUID 记，退服时顺手摘掉 —— 这张表不应该只增不减。
        plugin.forgetCompoundCooldown(e.getPlayer().getUniqueId());
        // 内存缓存的退场：把这名玩家的镜像落盘后驱逐。
        // 用 SQLite（同步写穿）时这一步返回即已落盘，与原实现完全等价；
        // 用 MySQL（异步写回）时它是一次排队 —— 服务端被强杀的话，最后这几毫秒的
        // 变动会丢，这是选异步拿性能时明码标价的代价，写回模式可以在
        // config.yml 的 Storage.async.write-behind 里改成 never 关掉。
        Storage storage = plugin.getStorage();
        if (storage instanceof CachedStorage) {
            ((CachedStorage) storage).unloadPlayer(e.getPlayer().getUniqueId());
        }
    }

    /**
     * 玩家进服：建立会话数据，并顺带完成旧版 YAML 数据的一次性迁移。
     *
     * <p>迁移放在这里而不是插件启动阶段，是因为此刻手里有玩家真实的 UUID —— 旧版数据文件名
     * 是玩家名，若在启动时反查 UUID，正版验证服上可能触发 Mojang 接口查询而卡住主线程。
     * 详见 {@link LegacyDataMigration}。</p>
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PokeUtils plugin = PokeUtils.getInstance();

        // 顺序很重要：先把旧版数据迁进存储，再把存储读进内存缓存。
        // 反过来的话，缓存里存的是迁移前的值，刚导入的旧数据要到下次进服才看得到。
        boolean migrated = LegacyDataMigration.migrateIfNeeded(
                plugin.getDataFolder(), plugin.getStorage(), p.getUniqueId(), p.getName());
        if (migrated) {
            plugin.getLogger().info("[迁移] 已把 " + p.getName()
                    + " 的旧版 YAML 数据导入存储，原文件归档至 Data-migrated/。");
        }
        Storage storage = plugin.getStorage();
        if (storage instanceof CachedStorage) {
            ((CachedStorage) storage).loadPlayer(p.getUniqueId(), p.getName());
        }

        plugin.ysdatas.put(p.getName(), new YSData(p.getUniqueId(), p.getName()));

        // 存储降级（MySQL 连不上 → 回退本地 SQLite）或后端打不开时，提醒在场的 OP。
        // 启动日志已经打过 SEVERE，但运维不一定开着控制台；而这件事的后果是
        // 「玩家看到的粘剂数量来自另一份数据」—— 比一般的报错严重，值得追着人说一遍。
        // 每个 OP 每次进服都会收到，直到重启修好为止，这是刻意的。
        String warning = plugin.getStorageWarning();
        if (warning != null && p.isOp()) {
            p.sendMessage("§c[PokeUtils] 存储没有按预期工作：");
            p.sendMessage("§c  " + warning);
            p.sendMessage("§7  用 §f/pu storage §7查看当前状态；在此之前不要做重要的发放或扣减。");
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        final Player p = (Player) e.getPlayer();
        if (PokeUtils.getInstance().playerdatas.containsKey(p.getName())) {
            boolean isSelect = PokeUtils.getInstance().playerdatas.get(p.getName()).isSelect();
            if (e.getInventory().getTitle().equals("§8精灵合成") && !isSelect) {
                // 修复：原实现在这里直接 remove 会话。但「玩家放弃合成（按 ESC）」与
                // 「界面被切换（例如界面开着时又输入一次 /pu compound）」都会走到这里。
                // PlayerData#openGui 是「先 put 会话、再 openInventory」，而 openInventory
                // 会先关掉旧界面并【同步】派发本事件 —— 于是刚建好的新会话会被当成前者删掉，
                // 之后所有点击都静默落空。所以这里延后一 tick 再判定：只要玩家此刻仍开着
                // 本插件的某个界面，就说明是切换而非放弃。
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!PokeUtils.getInstance().playerdatas.containsKey(p.getName())) {
                            return;
                        }
                        if (p.isOnline() && isPokeUtilsGui(p)) {
                            return;
                        }
                        PokeUtils.getInstance().playerdatas.remove(p.getName());
                    }
                }.runTaskLater(PokeUtils.getInstance(), 1L);
            } else if ((e.getInventory().getTitle().startsWith("§8请选择要合成的精灵: ")
                    || e.getInventory().getTitle().equals("§8请选择要使用的粘剂")
                    || e.getInventory().getTitle().startsWith("§8可能产出的精灵: ")) && isSelect) {
                PokeUtils.getInstance().playerdatas.get(p.getName()).setSelect(false);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        // 修复（新增防护）：这 2 tick 里玩家可能已经退服（会话被 onPlayerQuit 清掉），
                        // 或者会话被其它路径移除。原实现直接 playerdatas.get(...).openGui(p)，
                        // get 返回 null 时抛空指针，而它发生在调度线程里，控制台只留一行栈。
                        PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());
                        if (pd == null || !p.isOnline()) {
                            return;
                        }
                        pd.openGui(p);
                    }
                }.runTaskLater(PokeUtils.getInstance(), 2L);
            }
        }
    }

    /** 玩家当前打开的界面是否属于本插件（用于区分「切换界面」与「放弃合成」）。 */
    private static boolean isPokeUtilsGui(Player p) {
        if (p.getOpenInventory() == null || p.getOpenInventory().getTopInventory() == null) {
            return false;
        }
        return isPokeUtilsGuiTitle(p.getOpenInventory().getTopInventory().getTitle());
    }

    /** 标题是否属于本插件的界面之一（点击分支与拖拽分支共用）。 */
    private static boolean isPokeUtilsGuiTitle(String title) {
        if (title == null) {
            return false;
        }
        return title.equals("§8精灵合成")
                || title.equals("§8请选择要使用的粘剂")
                || title.startsWith("§8请选择要合成的精灵: ")
                || title.startsWith("§8可能产出的精灵: ");
    }

    /** 合成七星失败时是否可保留一只精灵，权限节点由 config.yml 的 {@code VIP.keep-one} 配置。 */
    private static boolean hasKeepOnePermission(Player p) {
        for (String permission : PokeUtils.getInstance().getKeepOnePermissions()) {
            if (p.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 高星合成成功的全服播报。
     *
     * <p>原实现是 {@code if (star1 + 1 >= 6) MSGToggle.getInstance().sendFlowView(写死的文案)}，
     * 阈值和文案都改不了。现在两者都走 config.yml 的 {@code Compound.broadcast}；
     * 并且对 MSGToggle 的调用做了兜底 —— 它虽然是 plugin.yml 里声明的硬依赖，
     * 但运行期真拿到 null 时也只是「少一条播报」，不该连带把合成结果炸掉。</p>
     */
    private static void broadcastCompound(Player p, int star, Pokemon pokemon, PokeStarAPI psa) {
        PokeUtils plugin = PokeUtils.getInstance();
        if (!plugin.isBroadcastEnabled() || star < plugin.getBroadcastMinStar()) {
            return;
        }
        String starName = psa.getPokeShowName(star);
        String pokemonName = pokemon == null ? "" : PokemonUtil.getPokemonName(pokemon.getSpecies());
        String message = plugin.getBroadcastMessage()
                .replace("{player}", p.getName())
                .replace("{star}", starName)
                .replace("{pokemon}", pokemonName);
        try {
            MSGToggle.getInstance().sendFlowView(message);
        } catch (Throwable ignored) {
            // 播报失败不影响合成结果
        }
    }
}
