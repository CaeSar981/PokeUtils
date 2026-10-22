package com.mc9y.pokeutils.listener;

import com.mc9y.msgtoggle.MSGToggle;
import com.mc9y.pokemonapi.api.event.CustomEvent;
import com.mc9y.pokemonapi.api.pokemon.PokemonUtil;
import com.mc9y.pokemonapi.api.storage.PlayerStorageUtil;
import com.mc9y.pokeutils.PokeUtils;
import com.mc9y.pokeutils.data.CachedStorage;
import com.mc9y.pokeutils.data.LegacyDataMigration;
import com.mc9y.pokeutils.data.MaterialAccess;
import com.mc9y.pokeutils.data.MaterialRef;
import com.mc9y.pokeutils.data.PlayerData;
import com.mc9y.pokeutils.data.PokemonAttr;
import com.mc9y.pokeutils.data.QuestData;
import com.mc9y.pokeutils.data.QuestType;
import com.mc9y.pokeutils.data.StickerRecipe;
import com.mc9y.pokeutils.data.Storage;
import com.mc9y.pokeutils.data.StickerType;
import com.mc9y.pokeutils.data.YSData;
import com.mc9y.pokeutils.util.Msg;
import com.pixelmonmod.pixelmon.Pixelmon;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.enums.EnumSpecies;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
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

    /** 合成成功/失败时抽取使用的随机源（主线程，单实例足够）。 */
    private static final Random RANDOM = new Random();

    // 第四轮新增功能的显示名，用于「该功能已关闭」的统一提示。
    private static final String ROUND4_INHERIT = "继承设置";
    private static final String ROUND4_SALVAGE = "分解回收";
    private static final String ROUND4_REROLL = "洗练";
    private static final String ROUND4_CRAFT = "粘剂合成";
    private static final String ROUND4_QUEST = "每日委托";
    private static final String ROUND4_DEX = "合成图鉴";

    /** 已经提示过「定向粘剂池与目标档位无交集」的粘剂键，每种只说一次。 */
    private static final Set<String> POOL_WARNED = new HashSet<String>();

    /**
     * 主界面上的功能入口统一走这里：功能被 config 关掉时给一句明确提示，而不是静默无反应。
     *
     * <p>按钮始终渲染（即使功能关闭）是刻意的 —— 让布局在任何配置下都稳定，
     * 玩家也不会因为管理员临时开关而「找不到按钮」。</p>
     */
    private static boolean openSubGuiIfEnabled(Player p, String feature) {
        PokeUtils plugin = PokeUtils.getInstance();
        boolean enabled;
        if (ROUND4_INHERIT.equals(feature)) {
            enabled = plugin.isInheritEnabled();
        } else if (ROUND4_SALVAGE.equals(feature)) {
            enabled = plugin.isSalvageEnabled();
        } else if (ROUND4_REROLL.equals(feature)) {
            enabled = plugin.isRerollEnabled();
        } else if (ROUND4_CRAFT.equals(feature)) {
            enabled = plugin.isStickerCraftEnabled();
        } else if (ROUND4_QUEST.equals(feature)) {
            enabled = plugin.isQuestEnabled();
        } else {
            enabled = plugin.isDexEnabled();
        }
        if (!enabled) {
            p.sendMessage(Msg.get("compound.feature-disabled", "feature", feature));
        }
        return enabled;
    }

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
                        case 20:
                            // 第四轮新增：属性继承设置。
                            if (!openSubGuiIfEnabled(p, ROUND4_INHERIT)) {
                                break;
                            }
                            PokeUtils.getInstance().playerdatas.get(p.getName()).openInheritGui(p);
                            break;
                        case 21:
                            if (!openSubGuiIfEnabled(p, ROUND4_SALVAGE)) {
                                break;
                            }
                            PokeUtils.getInstance().playerdatas.get(p.getName())
                                    .openPartyGui(p, PlayerData.PURPOSE_SALVAGE);
                            break;
                        case 22:
                            if (!openSubGuiIfEnabled(p, ROUND4_REROLL)) {
                                break;
                            }
                            PokeUtils.getInstance().playerdatas.get(p.getName())
                                    .openPartyGui(p, PlayerData.PURPOSE_REROLL);
                            break;
                        case 23:
                            if (!openSubGuiIfEnabled(p, ROUND4_CRAFT)) {
                                break;
                            }
                            PokeUtils.getInstance().playerdatas.get(p.getName()).openStickerCraftGui(p);
                            break;
                        case 24:
                            if (!openSubGuiIfEnabled(p, ROUND4_QUEST)) {
                                break;
                            }
                            PokeUtils.getInstance().playerdatas.get(p.getName()).openQuestGui(p);
                            break;
                        case 25:
                            if (!openSubGuiIfEnabled(p, ROUND4_DEX)) {
                                break;
                            }
                            PokeUtils.getInstance().playerdatas.get(p.getName()).openDexGui(p, 1);
                            break;
                        case 32:
                            if (PokeUtils.getInstance().playerdatas.containsKey(p.getName())) {
                                PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());

                                // 新增（防重复）：同一 tick 内被派发的第二次点击直接拒绝。
                                // 冷却管的是「隔一会儿的连点」，而同 tick 内的两个事件是依次
                                // 同步执行的 —— 原实现只靠「槽位上的精灵已被清空」间接拦住，
                                // 那属于碰巧。这里用显式标志，下一 tick 无条件解除。
                                if (pd.isProcessing()) {
                                    p.sendMessage(Msg.get("compound.processing"));
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
                                // 新增（A1）：判「材料选没选」用引用是否为空 —— 电脑来源的引用
                                // 投影出来的队伍下标是 -1，与「未选」撞车。
                                boolean hasMaterials = pd.getMaterial1() != null && (need < 2 || pd.getMaterial2() != null);
                                boolean hasSticker = !stickerRequired || pd.getYS() != null;

                                if (hasMaterials && hasSticker) {
                                    // 新增（A1）：「队伍至少剩一只」只统计**队伍来源**的材料。
                                    // 从电脑盒子取料不会让队伍变少 —— 若仍按 need 去扣，
                                    // 「材料全在盒子里、队伍只有一只」这种完全正常的用法会被误拒。
                                    int amount = MaterialAccess.partyCount(p);
                                    int partyConsumed = countPartyMaterials(pd, need);

                                    // 修复（可配）：队伍下限跟着材料数量走。原实现写死「最少 3 只」，
                                    // 对应的是「消耗 2 只、至少要剩 1 只」；材料数配成 1 时下限也应
                                    // 变成 2。全部材料都来自队伍时，文案与原来逐字一致。
                                    // 只有真的会动到队伍时才检查 —— 一只队伍来源的材料都没用到，
                                    // 队伍根本不会变少，这条约束无从谈起（材料全在盒子里、
                                    // 队伍空着，是完全合法的用法）。
                                    if (partyConsumed > 0 && amount - partyConsumed < 1) {
                                        p.sendMessage(partyConsumed == need
                                                ? Msg.get("compound.party-need-equip",
                                                        "need", PokeUtils.numeral(need + 1))
                                                : Msg.get("compound.party-keep-one"));
                                    } else {
                                        // 修复（新增防护）：先把两只精灵取出来判空。
                                        // 界面开着的时候，所选槽位上的精灵可能已经被移走 —— 其他插件/指令操作了队伍，
                                        // 或者玩家连点两次「确认合成」而第一次已经删掉了这两只。原实现直接
                                        // pps.get(slot).getSpecies() 解引用，这里会抛 NullPointerException。
                                        // 材料数为 1 时 material2 为 null，不能拿它去 resolve。
                                        // 新增（A1）：这两只也可能在电脑盒子里，解析统一走 MaterialAccess。
                                        Pokemon pokemon1 = MaterialAccess.resolve(p, pd.getMaterial1());
                                        Pokemon pokemon2 = need < 2 ? pokemon1 : MaterialAccess.resolve(p, pd.getMaterial2());
                                        if (pokemon1 == null || pokemon2 == null) {
                                            pd.setMaterial1(null);
                                            pd.setMaterial2(null);
                                            p.sendMessage(Msg.get("compound.material-gone"));
                                            p.closeInventory();
                                            return;
                                        }

                                        // 新增防护：格子里「有精灵」不等于「还是当初那只」。
                                        // 玩家选完材料、还没点确认的这段时间里，那一格的内容可能被换掉
                                        // （自己拖队伍、从盒子取精灵补上空位、其他插件把打工的精灵还回队伍），
                                        // 而坐标仍指在原处。下面的消耗是按坐标 set(null) 的，
                                        // 不校验身份就会把玩家没选的那只当材料吃掉。
                                        // 比对的是选中时记进 MaterialRef 的 UUID（见该类 uuid 字段说明）。
                                        String replaced = identityChanged(pd.getMaterial1(), pokemon1)
                                                ? "第一格" : null;
                                        if (replaced == null && need >= 2
                                                && identityChanged(pd.getMaterial2(), pokemon2)) {
                                            replaced = "第二格";
                                        }
                                        if (replaced != null) {
                                            pd.setMaterial1(null);
                                            pd.setMaterial2(null);
                                            p.sendMessage(Msg.get("compound.material-replaced", "slot", replaced));
                                            p.closeInventory();
                                            return;
                                        }

                                        int star1 = PokeUtils.getInstance().getPokemonStar(pokemon1.getSpecies().name());
                                        int star2 = PokeUtils.getInstance().getPokemonStar(pokemon2.getSpecies().name());
                                        // 修复：价格原来写死在这里（{0,1000,10000,30000,100000,400000,1000000}），
                                        // 现改由 config.yml 的 Compound.price.<star> 提供，默认值与原来一致。

                                        // 修复（新增防护）：PokeStarAPI#getPokemonStar 内部是 Optional.orElse(0)，
                                        // 凡未被 stars.yml 收录的种族一律返回 0（例如 MissingNo 这类
                                        // getBaseStats() 取不到基础种族值的种族，PokeStar 会把它整个跳过）。
                                        // 而下面的判定是 star1 == star2 && star1 < 7，0 == 0 成立，
                                        // 于是两只「未收录」精灵会以 price[0] = 0（不花金币）+ jl = 80% 成交 ——
                                        // 等于免费换一只一星精灵。1 星是星级系统的最低合法档位，0 一律按「未收录」拒绝。
                                        if (star1 < 1 || star2 < 1) {
                                            p.sendMessage(Msg.get("compound.not-in-star-system"));
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
                                                    p.sendMessage(Msg.get("compound.too-fast"));
                                                    return;
                                                }

                                                // 新增（防损失）：高星材料二次确认。
                                                // 第一次点击只进入「待确认」状态，不消耗任何东西；
                                                // 玩家改材料 / 改粘剂都会把确认状态清掉，
                                                // 避免「先放低星骗过确认、再换成六星」这种绕过。
                                                int confirmMin = PokeUtils.getInstance().getConfirmMinStar();
                                                if (confirmMin > 0 && star1 >= confirmMin && !pd.isConfirmed()) {
                                                    pd.setConfirmed(true);
                                                    p.sendMessage(Msg.get("compound.confirm-consume", "star", star1));
                                                    pd.openGui(p);
                                                    return;
                                                }

                                                // 修复（新增防护）：产出池为空时必须在这里先中止 —— 此时还没动玩家的任何资产。
                                                // 原实现在下面拿到产出池后直接 get(随机下标)，一旦依赖数据损坏
                                                // （例如 PokeStar 的 stars.yml 把某个星级档写丢，见
                                                // ../TestServer/README-测试指南.md 第五节），执行顺序会是：
                                                //   扣金币 → 扣粘剂 → 关界面 → 删掉两只精灵 → get(...) 抛 IndexOutOfBoundsException
                                                // 玩家净亏，而控制台只留一行报错。治本靠修 stars.yml，这里是不依赖外部数据的兜底。
                                                // 注意：提示文案只用档位数字，不引用 PokeUtils.getInstance().getStarDisplay() ——
                                                // 档位缺失时它返回空串，正是本分支要处理的情况。
                                                // 修复（收敛查询入口）：原来这里 `new PokeStarAPI()` 直接取池，
                                                // 与主界面预览、预览图标各扫一遍同一张星级表。改走插件里带缓存的
                                                // 唯一入口 —— 数据源没变，只是不再重复扫；空池语义也一致。
                                                List<String> starPMs =
                                                        PokeUtils.getInstance().getPreviewPool(star1 + 1);
                                                if (starPMs == null || starPMs.isEmpty()) {
                                                    p.sendMessage(Msg.get("compound.empty-pool", "star", star1 + 1));
                                                    return;
                                                }

                                                // 修复（新增防护）：粘剂数据缺失时直接中止。原实现在下面
                                                // ysdatas.get(...).withdraw(...) 处直接解引用，数据不在就是一条空指针，
                                                // 而且这一行排在扣金币之后 —— 玩家会先被扣钱再看到报错。
                                                YSData ysd = PokeUtils.getInstance().ysdatas.get(p.getName());
                                                if (ysd == null) {
                                                    p.sendMessage(Msg.get("common.sticker-not-loaded"));
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
                                                    p.sendMessage(Msg.get("compound.economy-unavailable"));
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
                                                    //
                                                    // 新增（A2 保底分档）：计数带「档位」。原实现只有一个全局计数，
                                                    // 于是可以在一星档垫刀把计数推到阈值、再在六星档消费保底 ——
                                                    // 保底被用在了它从未积累过的档位上，而这正是低星材料便宜的意义所在。
                                                    // 现在：换档即重新计数；阈值也可以按档位单独配。
                                                    //
                                                    // 档位 0 表示「不知道」（升级前那一列还不存在，或计数由测试/运维
                                                    // 直接写入），此时按「任意档位都算」处理 —— 否则升级会把老玩家
                                                    // 攒了一半的保底进度一笔勾销。
                                                    int streak = ysd.getFailStreak();
                                                    int failStar = ysd.getFailStar();
                                                    int threshold = PokeUtils.getInstance().getPityThreshold(star1);
                                                    boolean starMatched = failStar == 0 || failStar == star1;
                                                    boolean pity = PokeUtils.getInstance().isPityEnabled()
                                                            && threshold > 0 && starMatched && streak >= threshold;
                                                    int refund = PokeUtils.getInstance().getRefundPercent();

                                                    int randomJL = (int) (1.0 + Math.random() * 100.0);
                                                    p.closeInventory();
                                                    if (pity) {
                                                        p.sendMessage(Msg.get("compound.pity-triggered", "streak", streak));
                                                    }
                                                    if (pity || randomJL <= pd.getSumJL()) {
                                                        ysd.setFailStreak(0);
                                                        // 新增（A2）：档位一起清零 —— 下次失败时会重新记上当时的档位。
                                                        ysd.setFailStar(0);
                                                        // 合成结束，二次确认状态作废 —— 下次合成要重新确认
                                                        pd.setConfirmed(false);
                                                        MaterialAccess.consume(p, pd.getMaterial1());
                                                        // 新增（可配）：材料数为 1 时只有 slot1 有东西可清。
                                                        if (need >= 2) {
                                                            MaterialAccess.consume(p, pd.getMaterial2());
                                                        }
                                                        // starPMs 已在扣费前的空池预检处取好（非空），此处直接复用。
                                                        // 第四轮新增：定向粘剂会先把产出池收窄到它指定的种族上。
                                                        List<String> pool = filterPoolBySticker(starPMs, pd.getYS());
                                                        String rpmn = ((String) pool.get((int) (Math.random() * (double) pool.size()))).replace("Ho-Oh", "HoOh");
                                                        Pokemon pokemon = Pixelmon.pokemonFactory.create(EnumSpecies.valueOf(rpmn));
                                                        pokemon.setLevel(1);
                                                        // 第四轮新增：属性继承。必须在 givePlayerPokemon 之前应用 ——
                                                        // 发放之后再改虽然对象是同一个，但那意味着「玩家背包里
                                                        // 短暂出现过一只白板精灵」，任何异步观察者（其他插件的
                                                        // 精灵扫描、Pixelmon 自己的存档线程）都可能读到中间态。
                                                        // pokemon1 / pokemon2 是 MaterialAccess.resolve 拿到的对象
                                                        // 引用：无论材料来自队伍还是电脑盒子，摘除之后引用依然有效。
                                                        String inheritDetail = applyInheritanceTo(pd, pokemon, pokemon1, pokemon2);
                                                        PlayerStorageUtil.givePlayerPokemon(p, pokemon);
                                                        p.sendMessage(Msg.get("compound.success",
                                                                "star", PokeUtils.getInstance().getStarDisplay(star1 + 1)));
                                                        // 新增（玩家体验）：成功音效 + 一条屏幕 Title。
                                                        // 原插件从点开界面到拿到精灵全程没有任何声音或特效反馈。
                                                        PokeUtils.getInstance().playFeedback(p, "success");
                                                        PokeUtils.getInstance().sendSuccessTitle(p, PokeUtils.getInstance().getStarDisplay(star1 + 1),
                                                                PokemonUtil.getPokemonName(pokemon.getSpecies()));
                                                        // 第四轮新增：图鉴收录 + 委托进度。
                                                        // 图鉴只记「产出过的种族」，同一只重复产出会累加次数。
                                                        PokeUtils.getInstance().recordDex(p, pokemon.getSpecies().name());
                                                        // 一次调用推进两条委托：原来是 SUCCESS / ATTEMPT 各调一次，
                                                        // 同一 tick 内把今天的委托面板从库里读了两遍。
                                                        PokeUtils.getInstance().addQuestProgress(p,
                                                                new String[]{QuestType.SUCCESS, QuestType.ATTEMPT}, 1);
                                                        // 新增：合成流水日志（原插件一次 getLogger() 都没调过）
                                                        PokeUtils.getInstance().logCompound(p.getName(), star1, pd.getYS(), true,
                                                                money, 0, "产出 " + PokeUtils.getInstance().getStarDisplay(star1 + 1)
                                                                        + (pity ? " (保底)" : "")
                                                                        + (useVoucher ? " (合成券)" : "")
                                                                        + (inheritDetail.isEmpty() ? "" : " 继承[" + inheritDetail + "]"));
                                                        // 修复：播报阈值与文案原来写死（>=6 星 + 固定文案），现由配置提供。
                                                        broadcastCompound(p, star1 + 1, pokemon);
                                                    } else {
                                                        // 新增（A2）：换档就重新计数。原来的那些连败属于别的档位，
                                                        // 沿用它们等于让低星档「垫」出来的进度被高星档消费掉。
                                                        int nextStreak = (failStar != 0 && failStar != star1) ? 1 : streak + 1;
                                                        ysd.setFailStreak(nextStreak);
                                                        ysd.setFailStar(star1);
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

                                                        StringBuilder failMsg = new StringBuilder(Msg.get("compound.fail-base"));
                                                        if (moneyRefund > 0) {
                                                            failMsg.append(Msg.get("compound.fail-money", "percent", refund));
                                                        }
                                                        if (stickerRefund > 0) {
                                                            failMsg.append(Msg.get("compound.fail-sticker",
                                                                    "amount", stickerRefund,
                                                                    "sticker", refundSticker.getDisplay()));
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

                                                        // 第四轮新增：保护符（Compound.stickers 里的 protect: true）。
                                                        // 带上它失败时两只材料原样保留，只损失粘剂与金币。
                                                        // 六转七的基础成功率只有 20%，一次失败就是 80 万金币加两只六星精灵；
                                                        // 这种「倾家荡产式失败」正是玩家算完账之后干脆不去合成的头号原因。
                                                        boolean protect = PokeUtils.getInstance().isStickerProtect(pd.getYS());
                                                        if (protect) {
                                                            p.sendMessage(Msg.get("compound.protect-saved"));
                                                        }
                                                        // 修复：此处原为 if (star1 == 6)，但它被外层 if (star1 < 6) 挡着，
                                                        // star1 最大只能是 5，分支永远不可达 —— config.yml 承诺的
                                                        // 「合成七星失败保留一只精灵」实际从未生效。放开星级上限后本分支才能执行。
                                                        else if (star1 == 6) {
                                                            // 修复（原为 if (p.hasPermission("vip.yx") || p.hasPermission("vip.yx"))）：
                                                            // 两个条件完全相同，对应 config.yml 的「优秀训练师 / 传说训练师」两个身份丢了一个。
                                                            // 权限节点改由 config.yml 的 VIP.keep-one 配置，缺省回退到 vip.yx / vip.cs。
                                                            if (hasKeepOnePermission(p)) {
                                                                int r = (int) (Math.random() * 2.0);
                                                                if (r == 0) {
                                                                    MaterialAccess.consume(p, pd.getMaterial1());
                                                                } else if (need >= 2) {
                                                                    MaterialAccess.consume(p, pd.getMaterial2());
                                                                }

                                                                p.sendMessage(Msg.get("compound.god-saved"));
                                                            } else {
                                                                MaterialAccess.consume(p, pd.getMaterial1());
                                                                if (need >= 2) {
                                                                    MaterialAccess.consume(p, pd.getMaterial2());
                                                                }
                                                            }
                                                        } else {
                                                            MaterialAccess.consume(p, pd.getMaterial1());
                                                            if (need >= 2) {
                                                                MaterialAccess.consume(p, pd.getMaterial2());
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
                                                        // 新增（A2）：进度与阈值都取当前档位的 ——
                                                        // 换成 nextStreak 是因为刚判定的那一次已经计进去了（换档时它回到 1）。
                                                        if (PokeUtils.getInstance().isPityEnabled() && threshold > 0) {
                                                            PokeUtils.getInstance().notifyPity(p, nextStreak, threshold);
                                                        }

                                                        // 第四轮新增：委托进度 —— 失败也算「进行了一次合成」。
                                                        // 「进行 N 次合成」这类委托之所以存在，正是因为高星档成功率很低：
                                                        // 只认成功的委托在六转七上会变成几天都做不完。
                                                        PokeUtils.getInstance().addQuestProgress(p, QuestType.ATTEMPT, 1);

                                                        // 新增：合成流水日志
                                                        StringBuilder detail = new StringBuilder(
                                                                protect ? "保护符, 材料保留"
                                                                        : (star1 == 6 && hasKeepOnePermission(p)
                                                                                ? "六星失败, 保留一只" : "-"));
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
                                                    p.sendMessage(Msg.get("common.lack-money"));
                                                }
                                            } else {
                                                p.sendMessage(Msg.get("compound.over-six-star"));
                                            }
                                        } else {
                                            p.sendMessage(Msg.get("compound.star-mismatch"));
                                        }
                                    }
                                } else {
                                    p.sendMessage(Msg.get("compound.not-selected"));
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

                    // 新增（A1）：从选料界面进电脑盒子。
                    // 单独判在这个 switch 之前 —— 它不是「选第几只」，而是一次界面跳转，
                    // 混进下面那个 pokeSlot 赋值链里会让「pokeSlot > -1」的含义变模糊。
                    // 开关关闭时这里不响应，点击会落到灰玻璃上（没有任何反应，也不会误开界面）。
                    if (e.getSlot() == PlayerData.PC_ENTRY_SLOT
                            && PokeUtils.getInstance().isPcSelectionEnabled()) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.beginPcSelect(p, slot);
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
                        // 新增（A1）：材料改成引用对象。重复判定用 MaterialRef#same ——
                        // 原来的 `pd.getSlot1() == pokeSlot` 只能比队伍下标，
                        // 而「队伍 3」与「箱子 2 的第 3 格」在旧写法下会被判成同一只。
                        // 新增：连同 UUID 一起记进引用 —— 坐标只说明「在哪一格」，
                        // 合成前还要确认那一格里仍然是同一只（见 MaterialRef#uuid 的说明）。
                        MaterialRef ref = MaterialRef.ofParty(pokeSlot, uuidOfParty(p, pokeSlot));
                        if (MaterialRef.same(ref, pd.getMaterial1()) || MaterialRef.same(ref, pd.getMaterial2())) {
                            p.sendMessage(Msg.get("compound.already-selected"));
                            return;
                        }

                        // 修复：这里原来是 > 0，但队伍槽位下标 0 是合法的第一格 ——
                        // 「队伍恰好两只、先选第 2 格再选第 1 格」这种顺序下会少减一只，
                        // 让预览算出「还剩 2 只」而实际只剩 1 只。同一件事文件里有 > -1 / == / > 0
                        // 三种写法，以 >= 0 为准（唯一正确的那个）。
                        // 新增（A1）：改由 partyKeepsOne 统一判定 —— 只有队伍来源的材料会让队伍变少。
                        // newFromParty = true，即这次选中的正好是一只队伍里的精灵。
                        if (partyKeepsOne(p, pd, true)) {
                            if (slot == 1) {
                                pd.setMaterial1(ref);
                            } else {
                                pd.setMaterial2(ref);
                            }

                            PokeUtils.getInstance().playFeedback(p, "select");
                            p.closeInventory();
                        } else {
                            p.sendMessage(Msg.get("compound.need-one-pokemon"));
                        }
                    }
                }
            }
        } else if (e.getInventory().getTitle().startsWith(PlayerData.GUI_PC_SELECT)) {
            // ============================================================ 新增（A1）：电脑盒子选料
            e.setCancelled(true);
            if (e.getClickedInventory() != null
                    && e.getClickedInventory().getTitle().startsWith(PlayerData.GUI_PC_SELECT)) {
                Player p = (Player) e.getWhoClicked();
                if (PokeUtils.getInstance().playerdatas.containsKey(p.getName())) {
                    PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());
                    int boxes = MaterialAccess.boxCount(p);
                    if (e.getSlot() == 45) {
                        // 上一箱。首箱再往前翻要绕到最后一箱 —— 翻到底就没反应会让人以为界面卡了。
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.setPcBox(boxes <= 0 ? 0 : (pd.getPcBox() - 1 + boxes) % boxes);
                        pd.openPcSelectGui(p);
                    } else if (e.getSlot() == 53) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.setPcBox(boxes <= 0 ? 0 : (pd.getPcBox() + 1) % boxes);
                        pd.openPcSelectGui(p);
                    } else if (e.getSlot() == 47) {
                        // 星级筛选：全部 -> 1 -> 2 -> … -> 7 -> 全部。
                        // 用「循环」而不是下拉，是因为 1.12.2 的物品栏界面没有下拉控件，
                        // 而多开一个筛选界面又得再记一套「从哪来的」状态。
                        PokeUtils.getInstance().playFeedback(p, "select");
                        int next = pd.getPcStarFilter() + 1;
                        pd.setPcStarFilter(next > 7 ? 0 : next);
                        pd.openPcSelectGui(p);
                    } else if (e.getSlot() == 49) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.openGui(p);
                    } else if (e.getSlot() >= 0 && e.getSlot() < PlayerData.PC_CONTENT_SLOTS) {
                        pickPcMaterial(p, pd, e.getSlot());
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
                            p.sendMessage(Msg.get("common.lack-sticker", "sticker", sticker.getInsufficient()));
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
        } else if (e.getInventory().getTitle().equals(PlayerData.TITLE_INHERIT)) {
            // 第四轮新增：继承设置。
            e.setCancelled(true);
            if (isTop(e, PlayerData.TITLE_INHERIT)) {
                Player p = (Player) e.getWhoClicked();
                PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());
                if (pd != null) {
                    int slot = e.getSlot();
                    if (slot == 10 || slot == 12 || slot == 14) {
                        int max = PokeUtils.getInstance().getInheritMaxPicks();
                        // 只有在「从关到开」时才需要检查上限 —— 关掉一项永远允许，
                        // 否则管理员把 max-picks 调小之后玩家会连取消都做不到。
                        boolean turningOn = (slot == 10 && !pd.isInheritNature())
                                || (slot == 12 && !pd.isInheritAbility())
                                || (slot == 14 && !pd.isInheritIv());
                        if (turningOn && pd.getInheritPicks() >= max) {
                            p.sendMessage(Msg.get("compound.inherit-limit", "max", max));
                        } else {
                            if (slot == 10) {
                                pd.toggleInheritNature();
                            } else if (slot == 12) {
                                pd.toggleInheritAbility();
                            } else {
                                pd.toggleInheritIv();
                            }
                            PokeUtils.getInstance().playFeedback(p, "select");
                        }
                        pd.openInheritGui(p);
                    } else if (slot == 16) {
                        pd.cycleInheritSource();
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.openInheritGui(p);
                    } else if (slot == 22) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.setSelect(false);
                        pd.openGui(p);
                    }
                }
            }
        } else if (e.getInventory().getTitle().equals(PlayerData.TITLE_PARTY_SALVAGE)
                || e.getInventory().getTitle().equals(PlayerData.TITLE_PARTY_REROLL)
                || e.getInventory().getTitle().equals(PlayerData.TITLE_PARTY_SUBMIT)) {
            // 第四轮新增：分解 / 洗练 / 交付 的精灵选择界面（三合一）。
            e.setCancelled(true);
            if (isTop(e, e.getInventory().getTitle())) {
                Player p = (Player) e.getWhoClicked();
                PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());
                if (pd != null) {
                    if (e.getSlot() == 40) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.setSelect(false);
                        pd.openGui(p);
                        return;
                    }
                    int partySlot = partySlotOf(e.getSlot());
                    if (partySlot < 0) {
                        return;
                    }
                    ItemStack item = e.getCurrentItem();
                    if (item == null || item.getType().equals(Material.AIR)
                            || item.getType().equals(Material.BARRIER)) {
                        return;
                    }
                    pd.setPendingPartySlot(partySlot);
                    if (pd.getPendingPurpose() == PlayerData.PURPOSE_SALVAGE) {
                        pd.openSalvageConfirmGui(p);
                    } else if (pd.getPendingPurpose() == PlayerData.PURPOSE_REROLL) {
                        pd.openRerollGui(p);
                    } else {
                        // 交付委托：星级校验与扣精灵都在主类里做，这里只负责把选择传过去。
                        PokeUtils.getInstance().submitQuest(p, pd.getPendingQuestSlot(), partySlot);
                    }
                }
            }
        } else if (e.getInventory().getTitle().equals(PlayerData.TITLE_SALVAGE_CONFIRM)) {
            // 第四轮新增：分解确认。
            e.setCancelled(true);
            if (isTop(e, PlayerData.TITLE_SALVAGE_CONFIRM)) {
                Player p = (Player) e.getWhoClicked();
                PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());
                if (pd != null) {
                    if (e.getSlot() == 11) {
                        PokeUtils.getInstance().salvagePokemon(p, pd.getPendingPartySlot());
                        pd.setPendingPartySlot(-1);
                        pd.setSelect(false);
                        pd.openGui(p);
                    } else if (e.getSlot() == 15) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.openPartyGui(p, PlayerData.PURPOSE_SALVAGE);
                    } else if (e.getSlot() == 22) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.setSelect(false);
                        pd.openGui(p);
                    }
                }
            }
        } else if (e.getInventory().getTitle().equals(PlayerData.TITLE_REROLL)) {
            // 第四轮新增：洗练面板。槽 10~17 依次对应 六项个体值 + 性格 + 特性。
            e.setCancelled(true);
            if (isTop(e, PlayerData.TITLE_REROLL)) {
                Player p = (Player) e.getWhoClicked();
                PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());
                if (pd != null) {
                    int slot = e.getSlot();
                    if (slot >= 10 && slot <= 17) {
                        PokeUtils.getInstance().rerollAttr(p, pd.getPendingPartySlot(), slot - 10);
                        // 洗练后重开面板刷新数值 —— 不刷新的话玩家看不到结果，
                        // 还得关掉再开一次才能确认自己刚才洗出了什么。
                        pd.openRerollGui(p);
                    } else if (slot == 40) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.setSelect(false);
                        pd.openGui(p);
                    }
                }
            }
        } else if (e.getInventory().getTitle().equals(PlayerData.TITLE_QUEST)) {
            // 第四轮新增：每日委托。槽 10 起依次是第 0、1、2… 条委托。
            e.setCancelled(true);
            if (isTop(e, PlayerData.TITLE_QUEST)) {
                Player p = (Player) e.getWhoClicked();
                PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());
                if (pd != null) {
                    int slot = e.getSlot();
                    if (slot >= 10 && slot <= 16) {
                        int questSlot = slot - 10;
                        QuestData quest = findQuest(PokeUtils.getInstance().getQuestBoard(p), questSlot);
                        if (quest == null) {
                            p.sendMessage(Msg.get("quest.not-exist"));
                        } else if (quest.isClaimed()) {
                            p.sendMessage(Msg.get("quest.claimed"));
                        } else if (QuestType.SUBMIT.equals(quest.getType()) && !quest.isComplete()) {
                            // 交付类：先让玩家选一只精灵，而不是直接发奖。
                            pd.setPendingQuestSlot(questSlot);
                            PokeUtils.getInstance().playFeedback(p, "select");
                            pd.openPartyGui(p, PlayerData.PURPOSE_SUBMIT);
                        } else {
                            PokeUtils.getInstance().claimQuest(p, questSlot);
                            pd.openQuestGui(p);
                        }
                    } else if (slot == 22) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.setSelect(false);
                        pd.openGui(p);
                    }
                }
            }
        } else if (e.getInventory().getTitle().startsWith(PlayerData.TITLE_DEX)) {
            // 第四轮新增：合成图鉴。上方 45 格是精灵列表，下方是翻页与里程碑奖励。
            e.setCancelled(true);
            if (e.getClickedInventory() != null
                    && e.getClickedInventory().getTitle().startsWith(PlayerData.TITLE_DEX)) {
                Player p = (Player) e.getWhoClicked();
                PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());
                if (pd != null) {
                    int page = 1;
                    try {
                        page = Integer.parseInt(e.getClickedInventory().getTitle()
                                .substring(PlayerData.TITLE_DEX.length()));
                    } catch (NumberFormatException ignored) {
                        // 标题被外部改动时按第一页处理，不抛异常
                    }
                    int slot = e.getSlot();
                    if (slot == 45) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.openDexGui(p, page - 1);
                    } else if (slot == 53) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.openDexGui(p, page + 1);
                    } else if (slot == 49) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.setSelect(false);
                        pd.openGui(p);
                    } else {
                        int rewardIndex = dexRewardIndex(slot);
                        if (rewardIndex >= 0) {
                            List<PokeUtils.DexReward> rewards = PokeUtils.getInstance().getDexRewards();
                            if (rewardIndex < rewards.size()) {
                                PokeUtils.getInstance().claimDexMilestone(
                                        p, rewards.get(rewardIndex).milestone);
                                pd.openDexGui(p, page);
                            }
                        }
                    }
                }
            }
        } else if (e.getInventory().getTitle().equals(PlayerData.TITLE_CRAFT)) {
            // 第四轮新增：粘剂合成。槽 10 起依次是第 0、1、2… 条配方。
            e.setCancelled(true);
            if (isTop(e, PlayerData.TITLE_CRAFT)) {
                Player p = (Player) e.getWhoClicked();
                PlayerData pd = PokeUtils.getInstance().playerdatas.get(p.getName());
                if (pd != null) {
                    int slot = e.getSlot();
                    if (slot >= 10 && slot <= 16) {
                        PokeUtils.getInstance().craftSticker(p, slot - 10);
                        pd.openStickerCraftGui(p);
                    } else if (slot == 22) {
                        PokeUtils.getInstance().playFeedback(p, "select");
                        pd.setSelect(false);
                        pd.openGui(p);
                    }
                }
            }
        }
    }

    /** 点击的是不是「顶部这个界面本身」（而不是玩家自己的背包区）。 */
    private static boolean isTop(InventoryClickEvent e, String title) {
        return e.getClickedInventory() != null && title.equals(e.getClickedInventory().getTitle());
    }

    /** 队伍槽位 -> 队伍下标（与 PlayerData#openPartyGui 里的槽位表一一对应）。 */
    private static int partySlotOf(int slot) {
        switch (slot) {
            case 11:
                return 0;
            case 13:
                return 1;
            case 15:
                return 2;
            case 29:
                return 3;
            case 31:
                return 4;
            case 33:
                return 5;
            default:
                return -1;
        }
    }

    /** 图鉴界面上里程碑奖励按钮的槽位 -> 奖励下标（与 PlayerData#openDexGui 对应）。 */
    private static int dexRewardIndex(int slot) {
        switch (slot) {
            case 46:
                return 0;
            case 47:
                return 1;
            case 48:
                return 2;
            case 50:
                return 3;
            case 51:
                return 4;
            case 52:
                return 5;
            default:
                return -1;
        }
    }

    private static QuestData findQuest(List<QuestData> quests, int slot) {
        for (QuestData quest : quests) {
            if (quest.getSlot() == slot) {
                return quest;
            }
        }
        return null;
    }

    /**
     * 第四轮新增：按「定向粘剂」把产出池收窄。
     *
     * <p>三种情况：没配 pool（不限）→ 原样返回；配了且与目标档位有交集 → 返回交集；
     * 配了但交集为空（例如把「水系」配到了只有火系的档位）→ 退回不限定，并打一次提示。
     * 最后这种情况刻意<b>不</b>报错中止：粘剂已经消耗掉了，把合成失败掉对玩家不公平，
     * 而「提示一次让管理员去改配置」是代价最小的处理。</p>
     */
    private static List<String> filterPoolBySticker(List<String> pool, String stickerKey) {
        List<String> wanted = PokeUtils.getInstance().getStickerPool(stickerKey);
        if (wanted.isEmpty() || pool == null || pool.isEmpty()) {
            return pool;
        }
        Set<String> lower = new HashSet<String>();
        for (String name : wanted) {
            lower.add(name.toLowerCase(Locale.ROOT));
        }
        List<String> hit = new ArrayList<String>();
        for (String species : pool) {
            if (lower.contains(species.toLowerCase(Locale.ROOT))) {
                hit.add(species);
            }
        }
        if (hit.isEmpty()) {
            if (POOL_WARNED.add(stickerKey)) {
                PokeUtils.getInstance().getLogger().warning("定向粘剂 " + stickerKey
                        + " 的 pool 与本次目标档位的产出池没有交集，本次按不限定处理（请检查配置）。");
            }
            return pool;
        }
        return hit;
    }

    /**
     * 第四轮新增：把玩家在「继承设置」里选的项应用到产物上。
     *
     * <p>闪光不在这里，它由两只父本自动决定（不是玩家能选的项），见 {@link PokemonAttr#shinyBonus}。</p>
     *
     * @return 实际继承内容的描述（供流水日志），什么都没继承时返回空串
     */
    private static String applyInheritanceTo(PlayerData pd, Pokemon result, Pokemon parentA, Pokemon parentB) {
        PokeUtils plugin = PokeUtils.getInstance();
        if (!plugin.isInheritEnabled() || result == null) {
            return "";
        }
        // 按「性格 -> 特性 -> 个体值」的顺序取用允许的额度。这样做是因为
        // max-picks 有可能被管理员在玩家已经勾选之后调小，此时勾选数会超过上限；
        // 与其全部丢弃，不如稳定地保留前几项，行为至少是可预期的。
        int allowed = plugin.getInheritMaxPicks();
        boolean nature = false;
        boolean ability = false;
        boolean iv = false;
        if (allowed > 0 && pd.isInheritNature()) {
            nature = true;
            allowed--;
        }
        if (allowed > 0 && pd.isInheritAbility()) {
            ability = true;
            allowed--;
        }
        if (allowed > 0 && pd.isInheritIv()) {
            iv = true;
        }

        StringBuilder detail = new StringBuilder();
        String inherited = PokemonAttr.applyInheritance(result, parentA, parentB, nature, ability, iv,
                pd.getInheritSource(), plugin.getInheritIvMinPercent(), plugin.getInheritIvMaxPercent(),
                RANDOM);
        if (inherited.length() > 0) {
            detail.append(inherited);
        }
        int bonus = PokemonAttr.shinyBonus(parentA, parentB,
                plugin.getInheritShinySingleBonus(), plugin.getInheritShinyDoubleBonus());
        if (PokemonAttr.applyShiny(result, bonus, RANDOM)) {
            detail.append(detail.length() > 0 ? " " : "").append("闪光");
        }
        return detail.toString();
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
            p.sendMessage(Msg.get("storage.warn-header"));
            p.sendMessage(Msg.get("storage.warn-line", "warning", warning));
            p.sendMessage(Msg.get("storage.warn-hint"));
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
                    || e.getInventory().getTitle().startsWith(PlayerData.GUI_PC_SELECT)
                    || e.getInventory().getTitle().startsWith("§8可能产出的精灵: ")
                    // 第四轮新增的子界面同样走「关掉子界面 -> 回到合成主界面」这条路，
                    // 而不是把玩家丢在一个空屏上。判定条件必须和 isPokeUtilsGuiTitle 保持一致。
                    || e.getInventory().getTitle().equals(PlayerData.TITLE_INHERIT)
                    || e.getInventory().getTitle().equals(PlayerData.TITLE_PARTY_SALVAGE)
                    || e.getInventory().getTitle().equals(PlayerData.TITLE_PARTY_REROLL)
                    || e.getInventory().getTitle().equals(PlayerData.TITLE_PARTY_SUBMIT)
                    || e.getInventory().getTitle().equals(PlayerData.TITLE_SALVAGE_CONFIRM)
                    || e.getInventory().getTitle().equals(PlayerData.TITLE_REROLL)
                    || e.getInventory().getTitle().equals(PlayerData.TITLE_QUEST)
                    || e.getInventory().getTitle().equals(PlayerData.TITLE_CRAFT)
                    || e.getInventory().getTitle().startsWith(PlayerData.TITLE_DEX)) && isSelect) {
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
                        // 修复（融合第七轮 A1 与第四轮的两条补丁）：区分「界面切换 / 子界面重绘」
                        // 与「玩家真的放弃合成」。
                        //   · 第七轮 A1：「从选料界面点进电脑盒子」是先 close 再 open 另一个标题，
                        //     原实现无条件 2 tick 后回主界面 → 玩家看到一进去就弹回。
                        //   · 第四轮：所有子界面的刷新都是 closeInventory() + openInventory()
                        //     同一个标题，原实现把「重绘」也当成「关闭」→ 勾一下性格、翻个页
                        //     都会闪回主界面。
                        // 两种情形用同一个判据覆盖：2 tick 后玩家此刻开着的还是不是本插件界面。
                        //   · 仍是子界面（含刚重绘的、或已切到别的子界面）→ 保持子界面态，
                        //     并把 select 补回来（子界面里按 ESC 仍要能回主界面）
                        //   · 已经回到主界面 → 什么都不做（他本来就在主界面上）
                        //   · 什么界面都没有（按 ESC 退出了）→ 送回合成主界面，别丢在空屏上
                        String current = currentGuiTitle(p);
                        if (current != null && !current.equals(PlayerData.TITLE_MAIN)) {
                            pd.setSelect(true);
                            return;
                        }
                        pd.setSelect(false);
                        if (current == null) {
                            pd.openGui(p);
                        }
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

    /**
     * 玩家此刻真正开着的插件界面标题；不是本插件的界面（含「什么都没开」）返回 {@code null}。
     *
     * <p>与 {@link #isPokeUtilsGui} 的区别：这里要把「主界面」与「子界面」区分开，
     * 所以不能只回一个 boolean。用在 {@code onInventoryClose} 的延迟判定里 ——
     * 子界面刷新与玩家按 ESC 都会走到那条分支，只有看到「当前确实不在任何插件界面」时
     * 才应该把主界面推给他。</p>
     */
    private static String currentGuiTitle(Player p) {
        if (p.getOpenInventory() == null || p.getOpenInventory().getTopInventory() == null) {
            return null;
        }
        String title = p.getOpenInventory().getTopInventory().getTitle();
        return isPokeUtilsGuiTitle(title) ? title : null;
    }

    /** 标题是否属于本插件的界面之一（点击分支与拖拽分支共用）。 */
    private static boolean isPokeUtilsGuiTitle(String title) {
        if (title == null) {
            return false;
        }
        return title.equals(PlayerData.TITLE_MAIN)
                || title.equals("§8请选择要使用的粘剂")
                || title.startsWith("§8请选择要合成的精灵: ")
                || title.startsWith(PlayerData.GUI_PC_SELECT)
                || title.startsWith("§8可能产出的精灵: ")
                // 第四轮新增的九个界面（继承 / 三种选精灵 / 分解确认 / 洗练面板 /
                // 委托 / 粘剂合成 / 图鉴）。任何一个漏在这里，后果都是「关掉它时
                // 会话被当成『玩家放弃合成』清掉」，之后所有点击静默落空 ——
                // 这是本插件历史上最容易复发的一类 bug（见修复记录第 2 条）。
                // 第七轮的电脑盒子选料界面（GUI_PC_SELECT）同样在这个集合里。
                || title.equals(PlayerData.TITLE_INHERIT)
                || title.equals(PlayerData.TITLE_PARTY_SALVAGE)
                || title.equals(PlayerData.TITLE_PARTY_REROLL)
                || title.equals(PlayerData.TITLE_PARTY_SUBMIT)
                || title.equals(PlayerData.TITLE_SALVAGE_CONFIRM)
                || title.equals(PlayerData.TITLE_REROLL)
                || title.equals(PlayerData.TITLE_QUEST)
                || title.equals(PlayerData.TITLE_CRAFT)
                || title.startsWith(PlayerData.TITLE_DEX);
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
     * 这一格里的精灵是否已经<b>不是</b>选中时记下的那一只。
     *
     * <p>判定只看 UUID，不看种类 —— 同种类的两只精灵互换位置同样属于「被换掉」，
     * 因为消耗掉的具体是哪一只（等级、个体值、努力值都不同）对玩家是有区别的。</p>
     *
     * <p>任何一边缺信息都返回 {@code false}（视为没变）：引用为 null 表示这一格没选，
     * 记录为 null 表示旧会话或由不关心身份的调用方构造。宁可放行，也不要因为
     * 拿不到 UUID 就让玩家合不了成 —— 这个防护针对的是「有人换了精灵」，
     * 而不是「无法证明没换」。</p>
     */
    private static boolean identityChanged(MaterialRef ref, Pokemon current) {
        if (ref == null || current == null) {
            return false;
        }
        String expected = ref.getUuid();
        if (expected == null) {
            return false;
        }
        String actual = uuidOf(current);
        return actual != null && !expected.equalsIgnoreCase(actual);
    }

    /** 精灵 UUID 的字符串形式；取不到返回 null。 */
    private static String uuidOf(Pokemon pokemon) {
        if (pokemon == null) {
            return null;
        }
        Object uuid = pokemon.getUUID();
        return uuid == null ? null : uuid.toString();
    }

    /** 队伍某格精灵的 UUID；该格为空或存储未就绪时返回 null。 */
    private static String uuidOfParty(Player p, int slot) {
        // 走 MaterialAccess 而不是直接取 PlayerPartyStorage —— 本类其余地方
        // 已经不直连队伍存储了，这里也保持一致（队伍与盒子的区别只在一处知道）。
        return uuidOf(MaterialAccess.resolve(p, MaterialRef.ofParty(slot)));
    }

    /**
     * 新增（A1）：把电脑盒子里的一只精灵选为合成材料。
     *
     * <p>三道判定与队伍那条路径<b>共用同一套规则</b>（重复、队伍保底），
     * 差别只在「这只精灵在哪」以及「消耗它会不会让队伍变少」。</p>
     */
    private static void pickPcMaterial(Player p, PlayerData pd, int pcSlot) {
        // 先按坐标建一个「探测引用」拿到当前那只精灵，再带着它的 UUID 重建正式引用 ——
        // 坐标引用本身不知道格子里是哪一只（见 MaterialRef#uuid）。
        MaterialRef probe = MaterialRef.ofPc(pd.getPcBox(), pcSlot);
        Pokemon resolved = MaterialAccess.resolve(p, probe);
        if (resolved == null) {
            // 点到了空格子。界面上本该只有灰玻璃或精灵，走到这里说明箱子内容在
            // 界面开着的时候变了 —— 静默忽略，不提示也不改任何东西。
            return;
        }
        MaterialRef ref = MaterialRef.ofPc(pd.getPcBox(), pcSlot, uuidOf(resolved));
        if (MaterialRef.same(ref, pd.getMaterial1()) || MaterialRef.same(ref, pd.getMaterial2())) {
            p.sendMessage(Msg.get("compound.already-selected"));
            return;
        }
        // newFromParty = false：从盒子取料不会让队伍变少。
        if (!partyKeepsOne(p, pd, false)) {
            p.sendMessage(Msg.get("compound.need-one-pokemon"));
            return;
        }
        if (pd.getPcTargetSlot() == 2) {
            pd.setMaterial2(ref);
        } else {
            pd.setMaterial1(ref);
        }
        PokeUtils.getInstance().playFeedback(p, "select");
        p.closeInventory();
    }

    /**
     * 已选材料里有多少只来自队伍。
     *
     * <p>把原实现「消耗几只、队伍至少剩一只」的判定推广到两种材料来源时，
     * 「队伍会变少多少」只取决于队伍来源的那几只 —— 从盒子取料不动队伍。</p>
     */
    private static int countPartyMaterials(PlayerData pd, int need) {
        int count = 0;
        if (pd.getMaterial1() != null && pd.getMaterial1().isParty()) {
            count += 1;
        }
        if (need >= 2 && pd.getMaterial2() != null && pd.getMaterial2().isParty()) {
            count += 1;
        }
        return count;
    }

    /**
     * 「选完这一只之后，队伍里至少还要剩一只」。
     *
     * <p>沿用了原实现的算法（队伍占格数 − 已选中的队伍来源材料数 − 本次这一只），
     * 只把「已选中的材料数」换成「已选中的<b>队伍来源</b>材料数」。
     * 一只队伍来源的材料都没用到时直接放行 —— 那种情况下队伍根本不会变少。</p>
     *
     * <p>⚠️ 刻意保留了原实现的一个小毛病：它把<b>两个</b>已选槽位都算作扣减，
     * 包括正在被替换的那一个。所以「两个槽位都选满之后再改选槽位 1」会被拒
     * （队伍恰好 3 只时：3 − 1 − 1 = 1，不大于 1）。修它会悄悄放宽一条已有边界，
     * 而这不属于本轮要动的东西 —— 真要修应该单独一轮并配专门的用例。</p>
     */
    private static boolean partyKeepsOne(Player p, PlayerData pd, boolean newFromParty) {
        int team = MaterialAccess.partySize(p);
        int consumed = countPartyMaterials(pd, PokeUtils.getInstance().getMaterialCount())
                + (newFromParty ? 1 : 0);
        return consumed == 0 || team - consumed >= 1;
    }

    /**
     * 高星合成成功的全服播报。
     *
     * <p>原实现是 {@code if (star1 + 1 >= 6) MSGToggle.getInstance().sendFlowView(写死的文案)}，
     * 阈值和文案都改不了。现在两者都走 config.yml 的 {@code Compound.broadcast}；
     * 并且对 MSGToggle 的调用做了兜底 —— 它虽然是 plugin.yml 里声明的硬依赖，
     * 但运行期真拿到 null 时也只是「少一条播报」，不该连带把合成结果炸掉。</p>
     */
    private static void broadcastCompound(Player p, int star, Pokemon pokemon) {
        PokeUtils plugin = PokeUtils.getInstance();
        if (!plugin.isBroadcastEnabled() || star < plugin.getBroadcastMinStar()) {
            return;
        }
        String starName = PokeUtils.getInstance().getStarDisplay(star);
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
