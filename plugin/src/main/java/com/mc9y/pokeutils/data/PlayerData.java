package com.mc9y.pokeutils.data;

import com.mc9y.blank038api.Blank038API;
import com.mc9y.pokemonapi.api.pokemon.PokemonUtil;
import com.mc9y.pokestar.PokeStarAPI;
import com.mc9y.pokeutils.PokeUtils;
import com.pixelmonmod.pixelmon.Pixelmon;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.enums.EnumSpecies;
import com.pixelmonmod.pixelmon.storage.PlayerPartyStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 单个玩家的合成会话数据：两个待合成槽位、所选粘剂、成功率与选择状态。
 */
public class PlayerData {

    /**
     * 电脑盒子选料界面的标题。
     *
     * <p>公开出来是因为「谁来建界面」与「谁来响应点击」在两个类里，标题必须一字不差 ——
     * 两处各写一遍字面量，改名时漏掉一处界面就点不动了，而且不会报错。</p>
     */
    public static final String GUI_PC_SELECT = "§8从电脑盒子选精灵";

    /** 「从电脑盒子选精灵」入口在选料界面上的槽位（45 格里的一格，原先是灰玻璃）。 */
    public static final int PC_ENTRY_SLOT = 40;

    /** 电脑盒子选料界面上，装精灵的那一段槽位（0 起、连续），也就是一箱的容量上限。 */
    public static final int PC_CONTENT_SLOTS = 30;

    /**
     * 材料槽位 1 / 2。{@code null} = 未选。
     *
     * <p>原本是两个 {@code int}（队伍下标，-1 表示未选）。加「从电脑盒子取料」之后
     * 下标表达不了坐标，改成 {@link MaterialRef}：队伍与电脑盒子各有自己的坐标系，
     * 解析与实际摘除统一交给 {@link MaterialAccess}。</p>
     */
    private MaterialRef material1 = null;
    private MaterialRef material2 = null;
    /** 电脑盒子选料界面正在为哪个材料槽位选料（1 或 2）。 */
    private int pcTargetSlot = 1;
    /** 电脑盒子选料界面当前翻到的箱子下标（0 基）。 */
    private int pcBox = 0;
    /** 电脑盒子选料界面的星级筛选：0 = 不筛，1~7 = 只看该星级。 */
    private int pcStarFilter = 0;
    private String ys = null;
    private boolean select = false;
    private int jl;
    private int ysjl;
    /** 权限组带来的成功率加成（%），由 {@link #checkJL} 按权限实时算出。 */
    private int vipBonus;
    /** 当前两只材料共同的星级（不合法时为 0），由 {@link #checkJL} 顺带算出，界面用来查价格。 */
    private int star;
    /** 「高星材料二次确认」是否已确认。改动材料或粘剂会重置。 */
    private boolean confirmed;
    /**
     * 本次合成是否正在处理中。
     *
     * <p>冷却拦的是「隔了一会儿的连点」，但客户端可以在同一个 tick 里把两次点击一起发过来，
     * 它们会在同一 tick 内被依次派发。第一次成功合成后槽位上的精灵已被清空，第二次理论上
     * 会被槽位判空挡下，但那属于「碰巧拦住」。这里加一个显式标志：处理期间直接拒绝，
     * 下一 tick 自动解除（所以不会影响正常玩家的连续操作）。</p>
     */
    private boolean processing;

    public boolean isSelect() {
        return this.select;
    }

    public MaterialRef getMaterial1() {
        return this.material1;
    }

    public void setMaterial1(MaterialRef ref) {
        this.material1 = ref;
        // 换了材料，之前的二次确认就作废了 —— 否则玩家可以「先放便宜材料骗过确认，再换成六星」
        this.confirmed = false;
    }

    public MaterialRef getMaterial2() {
        return this.material2;
    }

    public void setMaterial2(MaterialRef ref) {
        this.material2 = ref;
        this.confirmed = false;
    }

    /**
     * 队伍下标投影（<b>兼容口径，不是状态</b>）：队伍来源时返回队伍下标，未选或电脑来源返回 -1。
     *
     * <p>材料本身只有 {@link #material1} / {@link #material2} 一份状态，这两个方法是从它推出来的视图。
     * 保留它们是因为有几处消费方只关心队伍那一条路径：{@code /pu debug} 的展示口径，
     * 以及测试替身按名字反射读会话的断言（{@code Harness#sessionSlot1}，断言的是 {@code "0/1"}）。
     * 它们此前拿到的就是队伍下标，这里保持同一个口径。</p>
     *
     * <p>⚠️ <b>不要拿它去判「材料选没选」</b> —— 电脑来源会返回 -1，与「未选」撞车。
     * 那种判断请用 {@code getMaterial1() != null}。</p>
     */
    public int getSlot1() {
        return this.material1 == null ? -1 : this.material1.toPartyIndex();
    }

    public int getSlot2() {
        return this.material2 == null ? -1 : this.material2.toPartyIndex();
    }

    /** {@code /pu debug} 的展示口径：队伍来源写数字、电脑来源写「箱子N·槽M」、未选写 -1。 */
    public String describeMaterial1() {
        return this.material1 == null ? "-1" : this.material1.describe();
    }

    public String describeMaterial2() {
        return this.material2 == null ? "-1" : this.material2.describe();
    }

    public int getPcTargetSlot() {
        return this.pcTargetSlot;
    }

    public int getPcBox() {
        return this.pcBox;
    }

    public void setPcBox(int box) {
        this.pcBox = box;
    }

    public int getPcStarFilter() {
        return this.pcStarFilter;
    }

    public void setPcStarFilter(int starFilter) {
        this.pcStarFilter = starFilter;
    }

    public String getYS() {
        return this.ys;
    }

    public void setYS(String s) {
        this.ys = s;
        this.confirmed = false;
    }

    /** 高星材料是否已被玩家二次确认。 */
    public boolean isConfirmed() {
        return this.confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    /** 是否正在处理本次合成（同 tick 防重复用）。 */
    public boolean isProcessing() {
        return this.processing;
    }

    public void setProcessing(boolean processing) {
        this.processing = processing;
    }

    public void setSelect(boolean s) {
        this.select = s;
    }

    /** 基础成功率（不含粘剂与权限组加成）。 */
    public int getJl() {
        return this.jl;
    }

    /** 粘剂带来的成功率加成。 */
    public int getYsjl() {
        return this.ysjl;
    }

    /** 权限组带来的成功率加成。 */
    public int getVipBonus() {
        return this.vipBonus;
    }

    /** 三部分相加后的总成功率，上限 100%。 */
    public int getSumJL() {
        int total = this.jl + this.ysjl + this.vipBonus;
        return total >= 100 ? 100 : total;
    }

    /** 当前材料星级（0 表示还没选齐或星级不合法）。 */
    public int getStar() {
        return this.star;
    }

    /**
     * 打开合成主界面。
     */
    public void openGui(Player p) {
        this.checkJL(p);

        Inventory inv = Bukkit.createInventory(null, 45, "§8精灵合成");

        ItemStack fg = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 15);
        ItemMeta fgim = fg.getItemMeta();
        fgim.setDisplayName("§a");
        fg.setItemMeta(fgim);
        // 槽 26 留出来放「产出预览」按钮（见下方），故不在此处铺灰玻璃。
        int[] fgslots = new int[]{0, 2, 3, 4, 5, 6, 7, 8, 9, 11, 13, 15, 17, 20, 21, 22, 23, 24, 25, 27, 29, 30, 31, 33, 34, 35, 36, 38, 39, 40, 41, 42, 43, 44};
        for (int i : fgslots) {
            inv.setItem(i, fg);
        }

        ItemStack fg1 = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 11);
        ItemMeta fg1im = fg1.getItemMeta();
        fg1im.setDisplayName("§b« §a使用方法 §f|| §a合成区域 §b»");
        fg1.setItemMeta(fg1im);
        int[] fg1slots = new int[]{1, 10, 19, 28, 37};
        for (int i : fg1slots) {
            inv.setItem(i, fg1);
        }

        ItemStack fg2 = new ItemStack(Material.valueOf("PIXELMON_BEAST_CHEST"), 1);
        ItemMeta fg2im = fg2.getItemMeta();
        fg2im.setDisplayName("§a确认合成");
        List<String> fg2lores = new ArrayList<String>();
        fg2lores.add("§b");
        fg2lores.add("§7 §k||§e 基础成功率: §f" + this.jl + "%");
        fg2lores.add("§7 §k||§e 粘剂成功率: §f" + this.ysjl + "%");
        // 新增（经济）：权限组加成单列一行，但只在真的有加成时才显示 ——
        // 否则默认配置（groups 为空）下界面会多出一行没意义的「权限组加成: 0%」。
        if (this.vipBonus > 0) {
            fg2lores.add("§7 §k||§e 权限组加成: §f" + this.vipBonus + "%");
        }
        fg2lores.add("§7 §k||§e 总成功率: §f" + this.getSumJL() + "%");

        // 新增（玩家体验）：把「这一次要点掉多少钱、兜里还有多少钱」写在按钮上。
        // 原实现只显示成功率，玩家按下去才知道要花多少；钱不够也只是在点击后才收到一句提示。
        // 这里把它提前 —— 余额不足时金额标红并直接说明无法合成。
        //
        // 新增（经济）：显示的是「实际要付的」金额（已计入权限组折扣）。打折时不写原价，
        // 玩家会以为被多扣；所以折扣生效时额外标一行原价。
        VoucherType usableVoucher = null;
        if (this.star > 0) {
            int price = PokeUtils.getInstance().getEffectivePrice(p, this.star);
            int base = PokeUtils.getInstance().getCompoundPrice(this.star);
            usableVoucher = PokeUtils.getInstance().findUsableVoucher(p, this.star);
            double balance = PokeUtils.getInstance().getBalance(p);
            boolean enough = balance >= (double) price;
            fg2lores.add("§3");
            if (usableVoucher != null) {
                fg2lores.add("§7 §k||§d 检测到可用合成券: §f" + usableVoucher.getDisplay());
                fg2lores.add("§7 §k||§7 本次将优先使用合成券, 不扣金币");
            } else {
                fg2lores.add("§7 §k||§e 本次消耗: " + (enough ? "§f" : "§c") + price + " §e金币"
                        + (price != base ? " §7(原价 §f" + base + "§7)" : ""));
                fg2lores.add("§7 §k||§e 当前余额: §f" + (long) balance + " §e金币");
                if (!enough) {
                    fg2lores.add("§7 §k||§c 金币不足, 无法合成！");
                }
            }
        }

        // 新增（玩家体验）：把「连续失败保底」的进度显示出来。
        // 保底是上一轮加的功能，但玩家在界面上看不到进度时，这个机制等于不存在。
        //
        // 新增（A2 保底分档）：进度与阈值都按「当前选中的材料档位」算。
        // 材料还没选齐（this.star == 0）时 getPityThreshold(0) 回退到单值默认 ——
        // 显示的数字与原来一致，所以界面不会因为这次改动而变样。
        if (PokeUtils.getInstance().isPityEnabled()) {
            int streak = PokeUtils.getInstance().getFailStreak(p);
            int failStar = PokeUtils.getInstance().getFailStar(p);
            int threshold = PokeUtils.getInstance().getPityThreshold(this.star);
            // this.star <= 0 时不算「换档」—— 材料都没选，谈不上档位不匹配。
            boolean starMatched = this.star <= 0 || failStar == 0 || failStar == this.star;
            if (threshold > 0) {
                fg2lores.add("§3");
                fg2lores.add("§7 §k||§e 连续失败: §f" + streak + " §e/ §f" + threshold);
                if (!starMatched) {
                    fg2lores.add("§7 §k||§7 已换档位, 失败计数将从 1 重新累计");
                } else if (streak >= threshold) {
                    fg2lores.add("§7 §k||§d 本次必定成功（保底已触发）");
                } else {
                    fg2lores.add("§7 §k||§7 再失败 §f" + (threshold - streak) + " §7次触发保底");
                }
            }
        }

        // 新增（防损失）：高星材料需要再点一次才真正消耗。
        // 手滑把一只六星丢进合成槽就再也拿不回来了，这里给一道拦阻。
        int confirmMin = PokeUtils.getInstance().getConfirmMinStar();
        if (confirmMin > 0 && this.star >= confirmMin && !this.confirmed) {
            fg2lores.add("§3");
            fg2lores.add("§7 §k||§c 本次将消耗 " + this.star + " 星精灵各一只");
            fg2lores.add("§7 §k||§c§l 请再点击一次以确认！");
        }

        fg2lores.add("§3");
        fg2im.setLore(fg2lores);
        fg2.setItemMeta(fg2im);
        inv.setItem(32, fg2);

        // 新增（玩家体验）：产出预览。
        // 原插件从头到尾没告诉过玩家「这一档能出什么」，玩家是在为一个完全未知的结果
        // 支付最高 100 万金币 —— 这不是「惊喜」，是「不敢下手」。这里给一个可点的入口。
        if (PokeUtils.getInstance().isPreviewEnabled()) {
            ItemStack preview = new ItemStack(Material.NETHER_STAR, 1);
            ItemMeta previewim = preview.getItemMeta();
            previewim.setDisplayName("§b产出预览 §f(§e点击查看§f)");
            List<String> previewLores = new ArrayList<String>();
            previewLores.add("§b");
            if (this.star > 0) {
                int poolSize = PokeUtils.getInstance().getPreviewPool(this.star + 1).size();
                previewLores.add("§7 §k||§e 目标档位: §f" + PokeUtils.starName(this.star + 1));
                previewLores.add("§7 §k||§e 可能产出: §f" + poolSize + " §e种精灵");
            } else {
                previewLores.add("§7 §k||§7 放入两只同星级精灵后");
                previewLores.add("§7 §k||§7 这里会显示可能产出的精灵");
            }
            previewLores.add("§d");
            previewim.setLore(previewLores);
            preview.setItemMeta(previewim);
            inv.setItem(26, preview);
        }

        int[] pzslots = new int[]{12, 14};
        for (int i : pzslots) {
            MaterialRef ref = i == 12 ? this.material1 : this.material2;
            Pokemon pokemon = MaterialAccess.resolve(p, ref);
            // 修复（新增防护）：这里的 pokemon 有三种情况会是 null ——
            //   ① 该槽位还没选（ref == null）；
            //   ② 选过，但那只精灵已经被移走（界面开着的时候队伍被其他插件/指令改动）；
            //   ③ 新增（A1）：选过，但那只精灵在电脑盒子里被挪走了。
            // 原实现只判了 ①，②会一路走到 getSpriteItem(null) / getPokemonName(null) 上抛空指针。
            // 顺带在 ② / ③ 的情况下把失效的槽位重置掉，免得玩家一直被锁在一只不存在的精灵上。
            if (pokemon == null) {
                if (ref != null) {
                    if (i == 12) {
                        this.material1 = null;
                    } else {
                        this.material2 = null;
                    }
                }
                ItemStack pz = new ItemStack(Material.BARRIER);
                ItemMeta pzim = pz.getItemMeta();
                pzim.setDisplayName("§b请选择精灵 §f(§e点击选择§f)");
                pz.setItemMeta(pzim);
                inv.setItem(i, pz);
                continue;
            }
            ItemStack item = Blank038API.getPokemonAPI().getSpriteHelper().getSpriteItem(pokemon);
            ItemMeta im = item.getItemMeta();
            im.setDisplayName("§f" + PokemonUtil.getPokemonName(pokemon));
            // 新增（A1）：材料来自电脑盒子时把出处标出来。主界面上两只材料都只是一个图标，
            // 不标的话玩家分不清「这只我已经从队伍拿出来了」还是「还在箱子里」。
            if (ref != null && ref.isPc()) {
                List<String> materialLores = new ArrayList<String>();
                materialLores.add("§7来源: §f" + ref.describe());
                im.setLore(materialLores);
            }
            item.setItemMeta(im);
            inv.setItem(i, item);
        }

        // 已选粘剂的展示位（槽 16）。修复：原来是一段 switch ("PT") / ("JL") 的硬编码，
        // 加第三种粘剂就得改这里；现在按配置里的粘剂定义渲染，不再限定种类。
        StickerType chosenSticker = PokeUtils.getInstance().getStickerType(this.ys);
        if (chosenSticker != null) {
            inv.setItem(16, PokeUtils.getInstance().buildStickerItem(chosenSticker, -1));
        } else {
            ItemStack ys = new ItemStack(Material.GLASS_BOTTLE, 1);
            ItemMeta ysim = ys.getItemMeta();
            ysim.setDisplayName("§b请选择粘剂 §f(§e点击选择§f)");
            ys.setItemMeta(ysim);
            inv.setItem(16, ys);
        }

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoim = info.getItemMeta();
        infoim.setDisplayName(PokeUtils.getInstance().getConfig().getString("Info.name").replace("&", "§"));
        List<String> lores = new ArrayList<String>();
        for (String l : PokeUtils.getInstance().getConfig().getStringList("Info.lore")) {
            // 修复：{price} 会被展开成按 Compound.price 实时生成的价格表。
            // 原实现把六行价格写死在 config.yml 里，改了价格后说明书还是旧数字，
            // 玩家照着说明准备金币、实际扣费却对不上。
            if ("{price}".equals(l.trim())) {
                lores.addAll(PokeUtils.getInstance().buildPriceLore());
            } else {
                lores.add(l.replace("&", "§"));
            }
        }
        infoim.setLore(lores);
        info.setItemMeta(infoim);
        inv.setItem(18, info);

        p.openInventory(inv);
    }

    /**
     * 打开精灵选择界面（slot 为 1 表示第一个槽位，2 表示第二个槽位）。
     */
    public void openSelectPokemonGui(Player p, int slot) {
        this.select = true;
        Inventory inv = Bukkit.createInventory(null, 45, "§8请选择要合成的精灵: " + slot);

        ItemStack fg = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 15);
        ItemMeta fgim = fg.getItemMeta();
        fgim.setDisplayName("§a");
        fg.setItemMeta(fgim);
        for (int i = 0; i < 45; ++i) {
            inv.setItem(i, fg);
        }

        ItemStack fg1 = new ItemStack(Material.BARRIER);
        ItemMeta fg1im = fg1.getItemMeta();
        fg1im.setDisplayName("§c无精灵");
        fg1.setItemMeta(fg1im);
        int[] fg1slots = new int[]{11, 13, 15, 29, 31, 33};
        for (int i : fg1slots) {
            inv.setItem(i, fg1);
        }

        PlayerPartyStorage pps = Pixelmon.storageManager.getParty(p.getUniqueId());
        PokeStarAPI psa = new PokeStarAPI();
        for (int i = 0; i < 6; ++i) {
            Pokemon pokemon = pps.get(i);
            if (pokemon != null) {
                ItemStack item = Blank038API.getPokemonAPI().getSpriteHelper().getSpriteItem(pokemon);
                ItemMeta im = item.getItemMeta();
                int pokeStar = psa.getPokemonStar(pokemon.getSpecies().name());
                im.setDisplayName("§f" + PokemonUtil.getPokemonName(pokemon) + " (" + psa.getPokeShowName(pokeStar) + "§f)");
                item.setItemMeta(im);
                inv.setItem(fg1slots[i], item);
            }
        }

        // 新增（A1）：进电脑盒子的入口。
        // 队伍只有 6 格，而玩家想升的往往是躺在盒子里的收藏品 —— 以前必须先开 PC 把它取进队伍、
        // 还得先腾出空位，才能回来点合成。那一段摩擦每次合成都得付一遍。
        // 开关关掉时不画这个按钮，点上去只会落到灰玻璃上，没有任何反应（也不会误开界面）。
        if (PokeUtils.getInstance().isPcSelectionEnabled()) {
            ItemStack pcEntry = new ItemStack(Material.ENDER_CHEST, 1);
            ItemMeta pcim = pcEntry.getItemMeta();
            pcim.setDisplayName("§b从电脑盒子选精灵 §f(§e点击打开§f)");
            List<String> pclores = new ArrayList<String>();
            pclores.add("§b");
            pclores.add("§7 §k||§7 盒子里的精灵可以直接当合成材料");
            pclores.add("§7 §k||§7 不需要先移进队伍");
            pcim.setLore(pclores);
            pcEntry.setItemMeta(pcim);
            inv.setItem(PC_ENTRY_SLOT, pcEntry);
        }

        p.closeInventory();
        p.openInventory(inv);
    }

    /**
     * 从「选料界面」进入电脑盒子选料：先定位到某个材料槽位，再渲染。
     *
     * <p>每次进来都重置星级筛选（0 = 全部）—— 筛选是上一次的选择，跨会话保留会让人困惑
     * （「为什么我的盒子里只有三只精灵」）。箱子下标则刻意保留，方便连续挑同箱的几只。</p>
     */
    public void beginPcSelect(Player p, int targetSlot) {
        this.pcTargetSlot = targetSlot == 2 ? 2 : 1;
        this.pcStarFilter = 0;
        this.openPcSelectGui(p);
    }

    /**
     * 渲染电脑盒子选料界面。
     *
     * <p>布局：槽 0~29 是当前箱子的精灵，槽 45 上一箱 / 46 箱子信息 / 47 星级筛选 /
     * 49 返回合成界面 / 53 下一箱 —— 翻页按钮的位置与「产出预览」界面保持一致。</p>
     *
     * <p>这里<b>只是渲染</b>，箱子与筛选状态由调用方先改好（{@link #setPcBox} /
     * {@link #setPcStarFilter}）再调本方法。这样「点上一箱」和「点筛选」走的是同一条重绘路径。</p>
     */
    public void openPcSelectGui(Player p) {
        int boxes = MaterialAccess.boxCount(p);
        if (boxes <= 0) {
            p.sendMessage("§3精灵合成 §f|| §c读取不到电脑盒子数据, 请重新进服后再试!");
            return;
        }
        // 兜住越界：箱子数可能因为服务端配置变化而减少，而 pcBox 是上一轮存下来的。
        if (this.pcBox < 0) {
            this.pcBox = 0;
        } else if (this.pcBox >= boxes) {
            this.pcBox = boxes - 1;
        }

        Inventory inv = Bukkit.createInventory(null, 54, GUI_PC_SELECT);

        ItemStack fg = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 15);
        ItemMeta fgim = fg.getItemMeta();
        fgim.setDisplayName("§a");
        fg.setItemMeta(fgim);
        for (int i = PC_CONTENT_SLOTS; i < 54; i++) {
            inv.setItem(i, fg);
        }

        PokeStarAPI psa = new PokeStarAPI();
        int capacity = Math.min(MaterialAccess.boxCapacity(p), PC_CONTENT_SLOTS);
        int total = 0;
        int shown = 0;
        for (int slot = 0; slot < capacity; slot++) {
            MaterialRef ref = MaterialRef.ofPc(this.pcBox, slot);
            Pokemon pokemon = MaterialAccess.resolve(p, ref);
            if (pokemon == null) {
                continue;
            }
            total += 1;
            int pokeStar = psa.getPokemonStar(pokemon.getSpecies().name());
            if (this.pcStarFilter > 0 && pokeStar != this.pcStarFilter) {
                continue;
            }
            ItemStack item = Blank038API.getPokemonAPI().getSpriteHelper().getSpriteItem(pokemon);
            ItemMeta im = item.getItemMeta();
            im.setDisplayName("§f" + PokemonUtil.getPokemonName(pokemon) + " (" + psa.getPokeShowName(pokeStar) + "§f)");
            List<String> lores = new ArrayList<String>();
            lores.add("§7位置: §f" + ref.describe());
            // 已经在另一个材料槽位上选过的，直接标出来 —— 否则玩家点下去只会收到一句拒绝，
            // 而界面上看不出到底哪只冲突了。判定仍以点击时为准（这里只是提示）。
            if (MaterialRef.same(ref, this.material1) || MaterialRef.same(ref, this.material2)) {
                lores.add("§c已在其他材料槽位选择");
            }
            im.setLore(lores);
            item.setItemMeta(im);
            inv.setItem(slot, item);
            shown += 1;
        }

        ItemStack prev = new ItemStack(Material.ARROW, 1);
        ItemMeta previm = prev.getItemMeta();
        previm.setDisplayName("§e上一箱 §7(" + (this.pcBox + 1) + " / " + boxes + ")");
        prev.setItemMeta(previm);
        inv.setItem(45, prev);

        ItemStack boxInfo = new ItemStack(Material.CHEST, 1);
        ItemMeta boxim = boxInfo.getItemMeta();
        boxim.setDisplayName("§b箱子 §f" + (this.pcBox + 1) + " §7/ §f" + boxes);
        List<String> boxLores = new ArrayList<String>();
        boxLores.add("§7本箱精灵: §f" + total + " §7只");
        if (this.pcStarFilter > 0) {
            boxLores.add("§7当前筛选: §f" + this.pcStarFilter + " 星 §7(显示 §f" + shown + " §7只)");
        } else {
            boxLores.add("§7当前筛选: §f全部");
        }
        boxim.setLore(boxLores);
        boxInfo.setItemMeta(boxim);
        inv.setItem(46, boxInfo);

        ItemStack filter = new ItemStack(Material.HOPPER, 1);
        ItemMeta filterim = filter.getItemMeta();
        filterim.setDisplayName("§e星级筛选: §f" + (this.pcStarFilter == 0 ? "全部" : this.pcStarFilter + " 星"));
        List<String> filterLores = new ArrayList<String>();
        filterLores.add("§7点击切换：全部 → 1 星 → … → 7 星 → 全部");
        filterim.setLore(filterLores);
        filter.setItemMeta(filterim);
        inv.setItem(47, filter);

        ItemStack back = new ItemStack(Material.BARRIER, 1);
        ItemMeta backim = back.getItemMeta();
        backim.setDisplayName("§c返回合成界面");
        back.setItemMeta(backim);
        inv.setItem(49, back);

        ItemStack next = new ItemStack(Material.ARROW, 1);
        ItemMeta nextim = next.getItemMeta();
        nextim.setDisplayName("§e下一箱 §7(" + (this.pcBox + 1) + " / " + boxes + ")");
        next.setItemMeta(nextim);
        inv.setItem(53, next);

        p.closeInventory();
        // ⚠️ select 必须在 closeInventory() **之后**置位。
        // closeInventory 会**同步**派发 InventoryCloseEvent，而那个处理器会把 select 清成 false
        // （它把「关闭一个选料类界面」理解为「玩家放弃了选料」）。先置位再关等于白置 ——
        // 后果是盒子界面本身关闭时 isSelect 已经是 false，于是没人负责把玩家送回主界面，
        // 玩家会停在一个空屏（标题变成默认的 container.crafting）上。
        // 2026-10-17 的真机回归就是这么红的（R4 起整片失败，25 条里有 8 条是它引起的）。
        this.select = true;
        p.openInventory(inv);
    }

    /**
     * 打开粘剂选择界面。
     *
     * <p><b>修复（原为异步执行）</b>：原实现把整段逻辑放进
     * {@code runTaskAsynchronously}，但这段代码里有两处必须在主线程做的事：</p>
     * <ol>
     *   <li>最后一句 {@code p.openInventory(inv)} —— 打开界面是要走主线程的
     *       （它会操作玩家的窗口状态并派发事件）。在异步线程里调用属于「撞运气」，
     *       多数时候能跑通，但迟早会撞上一次并发问题；</li>
     *   <li>界面构建过程中要读 {@code PokeUtils.ysdatas} —— 那是一个普通
     *       {@code HashMap}，与主线程的 {@code put / remove} 并发读写在极端情况下会
     *       死循环或读到脏数据。</li>
     * </ol>
     *
     * <p>而这段逻辑本身并没有任何耗时操作（不像主界面那样要为每只精灵取贴图），
     * 因此改为 {@code runTask}：仍然延后一 tick（保持「点击事件先返回、界面后打开」
     * 的原有时序），但全部在主线程执行。顺带给粘剂数据加了判空 ——
     * 原来 {@code ysdatas.get(...)} 之后直接 {@code getAmount()}，数据缺失就是一条空指针。</p>
     */
    public void openYSGui(final Player p) {
        this.select = true;
        new BukkitRunnable() {
            @Override
            public void run() {
                YSData ysd = PokeUtils.getInstance().ysdatas.get(p.getName());
                if (ysd == null) {
                    p.sendMessage("§3精灵合成 §f|| §c粘剂数据尚未加载, 请重新进服后再试!");
                    return;
                }

                Inventory inv = Bukkit.createInventory(null, 54, "§8请选择要使用的粘剂");

                int[] slots = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 45, 46, 47, 48, 49, 50, 51, 52, 53};
                int[] brs = new int[]{21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

                ItemStack fg = new ItemStack(Material.STAINED_GLASS_PANE, 1);
                ItemMeta fgim = fg.getItemMeta();
                fgim.setDisplayName("§f");
                fg.setItemMeta(fgim);
                for (int i : slots) {
                    inv.setItem(i, fg);
                }

                ItemStack br = new ItemStack(Material.BARRIER, 1);
                ItemMeta brim = br.getItemMeta();
                brim.setDisplayName("§c未解锁");
                br.setItemMeta(brim);
                for (int i : brs) {
                    inv.setItem(i, br);
                }

                // 修复：粘剂图标改为按 config.yml 的 Compound.stickers 列表逐个渲染。
                // 列表里加一种粘剂，这里就自动多出一个可选项 —— 槽位由配置的 slot 决定，
                // 缺省时从 19 起依次递增（正好与原实现固定写死的 19 / 20 一致）。
                // 拥有数一次性从库里取快照，避免逐个粘剂各查一遍数据库。
                Map<String, Integer> owned = ysd.allAmounts();
                for (StickerType sticker : PokeUtils.getInstance().getStickerTypes()) {
                    Integer count = owned.get(sticker.getKey());
                    inv.setItem(sticker.getSlot(), PokeUtils.getInstance()
                            .buildStickerItem(sticker, count == null ? 0 : count));
                }

                p.openInventory(inv);
            }
        }.runTask(PokeUtils.getInstance());
    }

    /**
     * 打开「产出预览」界面：把目标档位的全部可能产出列出来，分页显示。
     *
     * <p>数据直接取自真实 PokeStar 的 {@code getStarPokemon} —— 也就是合成成功时真正用来
     * 随机抽取的那张表，因此预览里出现的精灵与能合出来的完全一致，不会出现「预览有、实际没有」。</p>
     */
    public void openPreviewGui(Player p, int page) {
        int level = this.star + 1;
        List<String> pool = PokeUtils.getInstance().getPreviewPool(level);
        int pageSize = PokeUtils.getInstance().getPreviewPageSize();
        int pages = Math.max((pool.size() + pageSize - 1) / pageSize, 1);
        int current = page < 1 ? 1 : (page > pages ? pages : page);

        Inventory inv = Bukkit.createInventory(null, 54, "§8可能产出的精灵: " + current);

        ItemStack fg = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 15);
        ItemMeta fgim = fg.getItemMeta();
        fgim.setDisplayName("§a");
        fg.setItemMeta(fgim);
        for (int i = pageSize; i < 54; i++) {
            inv.setItem(i, fg);
        }

        PokeStarAPI psa = new PokeStarAPI();
        String showName = psa.getPokeShowName(level);
        int start = (current - 1) * pageSize;
        for (int i = 0; i < pageSize && start + i < pool.size(); i++) {
            String species = pool.get(start + i);
            try {
                // PokeStar 的表里个别名字带连字符（如 Ho-Oh），枚举常量名不带，需要归一化。
                EnumSpecies enumSpecies = EnumSpecies.valueOf(species.replace("Ho-Oh", "HoOh"));
                Pokemon pokemon = Pixelmon.pokemonFactory.create(enumSpecies);
                ItemStack item = Blank038API.getPokemonAPI().getSpriteHelper().getSpriteItem(pokemon);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§f" + PokemonUtil.getPokemonName(enumSpecies));
                    item.setItemMeta(meta);
                }
                inv.setItem(i, item);
            } catch (Throwable t) {
                // 单个种族渲染失败不该让整页打不开
                ItemStack broken = new ItemStack(Material.PAPER);
                ItemMeta meta = broken.getItemMeta();
                meta.setDisplayName("§7" + species);
                broken.setItemMeta(meta);
                inv.setItem(i, broken);
            }
        }

        ItemStack prev = new ItemStack(Material.ARROW);
        ItemMeta previm = prev.getItemMeta();
        previm.setDisplayName("§e上一页 §7(" + current + " / " + pages + ")");
        prev.setItemMeta(previm);
        inv.setItem(45, prev);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeim = close.getItemMeta();
        closeim.setDisplayName("§c返回合成界面");
        close.setItemMeta(closeim);
        inv.setItem(49, close);

        ItemStack next = new ItemStack(Material.ARROW);
        ItemMeta nextim = next.getItemMeta();
        nextim.setDisplayName("§e下一页 §7(" + current + " / " + pages + ")");
        next.setItemMeta(nextim);
        inv.setItem(53, next);

        p.closeInventory();
        p.openInventory(inv);
    }

    /**
     * 重新计算基础成功率与粘剂加成。
     */
    public void checkJL(Player p) {
        this.jl = 0;
        this.ysjl = 0;
        this.vipBonus = 0;
        this.star = 0;
        // 新增（可配）：材料数量可配为 1 或 2。为 1 时只看 slot1，slot2 视为不存在 ——
        // 这样「星级必须相同」这条判定自动成立，不需要额外分支。
        int need = PokeUtils.getInstance().getMaterialCount();
        // 新增（A1）：判「选没选」用引用是否为空，不能用队伍下标 —— 电脑来源的引用
        // 投影出来的队伍下标是 -1，与「未选」撞车。
        if (this.material1 != null && (need < 2 || this.material2 != null)) {
            PokeStarAPI psa = new PokeStarAPI();
            Pokemon pm1 = MaterialAccess.resolve(p, this.material1);
            Pokemon pm2 = need < 2 ? pm1 : MaterialAccess.resolve(p, this.material2);
            // 修复（新增防护）：槽位上的精灵可能已经被移走 —— checkJL 由 openGui 调用，
            // 而界面在「选完精灵/粘剂后自动重开」时也会再走一遍。原实现直接 pmX.getSpecies()
            // 解引用，会抛 NullPointerException，把「打开合成界面」这个动作整个炸掉。
            if (pm1 != null && pm2 != null) {
                int star1 = psa.getPokemonStar(pm1.getSpecies().name());
                int star2 = psa.getPokemonStar(pm2.getSpecies().name());
                // 修复（原为 star1 < 6）：config.yml 明确标价「六星 -> 七星 消耗 1000000 金币」，
                // 即 star1 == 6 是合法档位。原条件把它挡在外面，导致六转七永远没有基础成功率
                // （jl 恒为 0），整档合成实际不可达。
                // 修复（新增 star1 >= 1）：PokeStar 对未被 stars.yml 收录的种族返回 0 星，
                // 0 == 0 成立会算出 jl = 80%，与监听器里「0 星不可合成」的防护保持一致。
                if (star1 == star2 && star1 >= 1 && star1 < 7) {
                    // 顺带记下合法星级：界面要用它去查「本次消耗多少金币」。
                    this.star = star1;
                    // 修复：原为写死的公式 (11 - star - 3) * 10（即 1~6 星对应 70/60/50/40/30/20），
                    // 现改由 config.yml 的 Compound.base-rate 提供，默认值与公式完全一致。
                    this.jl = PokeUtils.getInstance().getBaseRate(star1);
                }
            }
        }
        if (this.ys != null) {
            // 修复：粘剂加成同样来自 config.yml（默认 PT=10 / JL=30，与原实现一致）。
            this.ysjl = PokeUtils.getInstance().getStickerRate(this.ys);
        }
        // 新增（经济）：权限组加成。它与材料无关，但「总成功率」是三部分相加，
        // 所以必须和基础/粘剂一起算出来，否则界面上的总成功率会对不上实际判定值。
        this.vipBonus = PokeUtils.getInstance().getGroupRateBonus(p);
    }
}
