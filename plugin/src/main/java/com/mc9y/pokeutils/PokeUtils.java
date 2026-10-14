package com.mc9y.pokeutils;

import com.mc9y.pokestar.PokeStarAPI;
import com.mc9y.pokeutils.data.Async;
import com.mc9y.pokeutils.data.CachedStorage;
import com.mc9y.pokeutils.data.CompoundLog;
import com.mc9y.pokeutils.data.LogIndex;
import com.mc9y.pokeutils.data.PlayerData;
import com.mc9y.pokeutils.data.Storage;
import com.mc9y.pokeutils.data.StorageFactory;
import com.mc9y.pokeutils.data.StickerType;
import com.mc9y.pokeutils.data.VoucherType;
import com.mc9y.pokeutils.data.YSData;
import com.mc9y.pokeutils.listener.PokeCompoundPlayerListener;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.minecraft.server.v1_12_R1.NBTTagCompound;
import net.minecraft.server.v1_12_R1.NBTTagString;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_12_R1.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PokeUtils 主类
 *
 * <p>提供精灵合成（2 只同星级精灵 + 粘剂 + 金币 -> 1 只高星级精灵）功能的 GUI 插件。
 * 依赖 PokeStar / Vault / PokemonAPI / MSGToggle。</p>
 */
public class PokeUtils extends JavaPlugin {

    private static PokeUtils pu;

    /**
     * 合成消耗的默认值：{@code price[star]} = 「star 星 → star+1 星」的金币消耗。
     * 下标 0 不使用。对应 config.yml 的 {@code Compound.price.<star>}。
     */
    private static final int[] DEFAULT_PRICE = {0, 1000, 10000, 30000, 100000, 400000, 1000000};

    /**
     * 基础成功率的默认值：{@code rate[star]} = 以 star 星为原料时的基础成功率（%）。
     * 下标 0 不使用。对应 config.yml 的 {@code Compound.base-rate.<star>}。
     */
    private static final int[] DEFAULT_RATE = {0, 70, 60, 50, 40, 30, 20};

    private static final int DEFAULT_REFUND_PERCENT = 20;
    private static final int DEFAULT_PITY_THRESHOLD = 5;

    // ---- 粘剂的内置默认（config.yml 缺少 Compound.stickers 时使用，与原实现逐位一致）----
    private static final String[] DEFAULT_STICKER_KEYS = {"PT", "JL"};
    private static final String[] DEFAULT_STICKER_DISPLAYS = {"§f普通粘剂", "§a精良粘剂"};
    private static final String[] DEFAULT_STICKER_MATERIALS = {"POTION", "DRAGONS_BREATH"};
    private static final String[] DEFAULT_STICKER_POTIONS = {"minecraft:water", ""};
    private static final int[] DEFAULT_STICKER_RATES = {10, 30};
    private static final String[] DEFAULT_STICKER_INSUFFICIENT = {"§f 普通粘剂", "§a 精良粘剂"};

    /** 粘剂选择界面上第一个粘剂所在的槽位，后续粘剂依次 +1（原实现固定为 19 / 20）。 */
    private static final int STICKER_SLOT_BASE = 19;

    /**
     * 出厂默认音效名（与 jar 内 config.yml 的 {@code Compound.feedback.sounds.*} 逐一对应）。
     *
     * <p>⚠️ <b>为什么必须把出厂值抄进代码</b>：Bukkit 的
     * {@code MemorySection#get(path, def)} 是「只查本段内存，查不到就直接返回传入的 def」，
     * <b>不会</b>回退到 jar 内置 config.yml —— 那份配置是 {@code setDefaults()} 挂上去的，
     * 只有<b>单参</b>的 {@code get(path)} 家族才会去看它。在 CatServer 1.12.2 自带的
     * org.bukkit.configuration 上实测（磁盘缺键、jar 端有值）：</p>
     * <pre>
     *   cfg.getString("Compound.feedback.sounds.select", "")  -&gt; ""
     *   cfg.getString("Compound.feedback.sounds.select")      -&gt; "UI_BUTTON_CLICK"
     * </pre>
     * <p>后果就是「只换 jar、沿用旧 config.yml」的服（老配置没有 feedback 段）里
     * 音效<b>静默失效</b>，日志上一点线索都没有。所以出厂值必须写进代码，
     * 与本类既有的 DEFAULT_PRICE / DEFAULT_STICKER_* 保持同一套做法。</p>
     *
     * <p>配置里<b>显式</b>把某个键留空（键存在且为 {@code ""}）仍然表示「该处不发声」，
     * 不会被这里的默认值顶掉 —— 双参读取只有在「键不存在」时才用 def。</p>
     */
    private static final Map<String, String> DEFAULT_SOUNDS = defaultSounds();

    private static Map<String, String> defaultSounds() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("open", "BLOCK_CHEST_OPEN");
        m.put("select", "UI_BUTTON_CLICK");
        m.put("success", "ENTITY_PLAYER_LEVELUP");
        m.put("fail", "BLOCK_ANVIL_LAND");
        return m;
    }

    /** 星级的汉字写法（命令输出用）。 */
    private static final String[] NUMERALS = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    /** 粘剂定义缓存；{@code /pu reload} 时置空以便下次重新读配置。 */
    private List<StickerType> stickers;

    /** 合成券定义缓存；同样在 {@code /pu reload} 时失效。 */
    private List<VoucherType> vouchers;

    /** 「本服务端没有 sendActionBar」的告警只打一次，避免刷屏。 */
    private boolean actionBarWarned;

    /** 音效相关的告警键（非法的枚举名 / 播放失败），每种只说一次。 */
    private final Set<String> soundWarned = new java.util.HashSet<String>();

    public HashMap<String, PlayerData> playerdatas = new HashMap<String, PlayerData>();
    public HashMap<String, YSData> ysdatas = new HashMap<String, YSData>();
    public Economy economy;

    /**
     * 存储门面（持久化的唯一出口）。
     *
     * <p>它背后可能是 SQLite（默认，零依赖）或 MySQL（多服共用一份数据时用），
     * 由 {@code config.yml} 的 {@code Storage.type} 决定；业务代码只认
     * {@link Storage} 这个接口，换后端不需要改一行业务逻辑。</p>
     *
     * <p>打开失败时为 {@code null}，此时插件仍会加载，但所有读写退化成「读到 0 / 写入丢弃」，
     * 玩家会看到既有的「粘剂数据尚未加载」提示。这是有意为之：存储层故障不该让整台服务器
     * 的合成玩法连带崩掉，但控制台一定会留下一条 {@code SEVERE}。</p>
     */
    private Storage storage;

    /** 合成流水的内存索引（{@code /pu history|stats|top} 的数据源，见 {@link LogIndex}）。 */
    private LogIndex logIndex;

    /** 上次合成的时间戳（毫秒），按玩家名索引，用于合成冷却。 */
    private final HashMap<String, Long> lastCompoundAt = new HashMap<String, Long>();

    /** 合成流水日志目录（首次写入时创建）。 */
    private File compoundLogDir;

    public Storage getStorage() {
        return this.storage;
    }

    /** 未经缓存包装的裸存储后端（只有测试会用它来验证「数据真的落库了」）。 */
    public Storage getBackend() {
        return this.storage == null ? null : this.storage.backend();
    }

    /** 合成流水的内存索引。 */
    public LogIndex getLogIndex() {
        return this.logIndex;
    }

    /**
     * 等异步写回队列排空。
     *
     * @return true = 已排空；false = 超时
     */
    public boolean awaitAsync(long timeoutMs) {
        return Async.drain(timeoutMs);
    }

    public static PokeUtils getInstance() {
        return pu;
    }

    // ================================================================ 数值配置（修复：原本全部硬编码在代码里）
    // 原插件把「各档金币消耗」「基础成功率公式」「粘剂加成」「失败补偿比例」全部写死在
    // 监听器与 PlayerData 里，`/pu reload` 只能重载说明书与 VIP 节点，改一个数值就得重新编译。
    // 现在统一走 config.yml：读不到时回退到与原实现完全一致的默认值，所以老配置文件
    // （没有 Compound 段）的行为与本插件重写前一模一样。
    // 注意 getConfig() 返回的是内存中的配置对象，因此改动会在下一次读取时立即生效，
    // 配合 /pu reload 即可在运行时调整。

    /** 某星级作为原料时的金币消耗（读不到则用内置默认值）。 */
    public int getCompoundPrice(int star) {
        if (star < 0 || star >= DEFAULT_PRICE.length) {
            return 0;
        }
        return this.getConfig().getInt("Compound.price." + star, DEFAULT_PRICE[star]);
    }

    /** 某星级作为原料时的基础成功率（%）。 */
    public int getBaseRate(int star) {
        if (star < 0 || star >= DEFAULT_RATE.length) {
            return 0;
        }
        return this.getConfig().getInt("Compound.base-rate." + star, DEFAULT_RATE[star]);
    }

    // ================================================================ 粘剂定义（修复：原本硬编码 PT / JL）
    // 原实现把两种粘剂的显示名、材质、槽位、成功率的判断散在 PlayerData 与监听器的六处
    // switch / if 里，加一种粘剂必须改 java 重新编译。现在统一由 config.yml 的
    // Compound.stickers 列表描述，加粘剂就是加一段 yml。

    /** 全部粘剂定义（按配置顺序）。首次访问时读 config.yml 并缓存。 */
    public List<StickerType> getStickerTypes() {
        if (this.stickers == null) {
            this.stickers = this.loadStickerTypes();
        }
        return this.stickers;
    }

    /** 清空粘剂定义缓存，使下一次读取重新走 config.yml（`/pu reload` 调用）。 */
    public void invalidateStickerTypes() {
        this.stickers = null;
    }

    private List<StickerType> loadStickerTypes() {
        List<StickerType> out = new ArrayList<StickerType>();
        for (Map<?, ?> raw : this.getConfig().getMapList("Compound.stickers")) {
            Object keyRaw = raw.get("key");
            if (keyRaw == null || String.valueOf(keyRaw).isEmpty()) {
                continue;
            }
            String key = String.valueOf(keyRaw);
            int index = out.size();
            String display = stringOf(raw.get("display"), defaultOr(index, DEFAULT_STICKER_DISPLAYS, key));
            String materialName = stringOf(raw.get("material"), defaultOr(index, DEFAULT_STICKER_MATERIALS, "GLASS_BOTTLE"));
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                this.getLogger().warning("粘剂 " + key + " 的材质 " + materialName + " 无法识别，已回退为 GLASS_BOTTLE。");
                material = Material.GLASS_BOTTLE;
            }
            String potion = stringOf(raw.get("potion"), defaultOr(index, DEFAULT_STICKER_POTIONS, ""));
            int rate = intOf(raw.get("rate"), index < DEFAULT_STICKER_RATES.length ? DEFAULT_STICKER_RATES[index] : 0);
            String insufficient = stringOf(raw.get("insufficient"), " " + display);
            int slot = intOf(raw.get("slot"), STICKER_SLOT_BASE + index);
            out.add(new StickerType(key, display, material, potion, rate, insufficient, slot));
        }

        if (!out.isEmpty()) {
            return out;
        }
        // 配置里没有 Compound.stickers（老配置文件就是这种情况）—— 用与原实现一致的默认值。
        for (int i = 0; i < DEFAULT_STICKER_KEYS.length; i++) {
            out.add(new StickerType(DEFAULT_STICKER_KEYS[i], DEFAULT_STICKER_DISPLAYS[i],
                    Material.valueOf(DEFAULT_STICKER_MATERIALS[i]), DEFAULT_STICKER_POTIONS[i],
                    DEFAULT_STICKER_RATES[i], DEFAULT_STICKER_INSUFFICIENT[i], STICKER_SLOT_BASE + i));
        }
        return out;
    }

    /** 按存储键查粘剂定义，找不到返回 {@code null}。 */
    public StickerType getStickerType(String key) {
        if (key == null) {
            return null;
        }
        for (StickerType sticker : this.getStickerTypes()) {
            if (sticker.getKey().equals(key)) {
                return sticker;
            }
        }
        return null;
    }

    /** 按界面槽位查粘剂定义，找不到返回 {@code null}（点击判定用）。 */
    public StickerType getStickerTypeBySlot(int slot) {
        for (StickerType sticker : this.getStickerTypes()) {
            if (sticker.getSlot() == slot) {
                return sticker;
            }
        }
        return null;
    }

    /** 粘剂提供的成功率加成（%）。 */
    public int getStickerRate(String type) {
        StickerType sticker = this.getStickerType(type);
        return sticker == null ? 0 : sticker.getRate();
    }

    /**
     * 构建一个粘剂图标（界面上的展示物品）。
     *
     * @param owned 玩家持有数量；传负数表示不渲染「拥有数」这一行
     */
    public ItemStack buildStickerItem(StickerType type, int owned) {
        ItemStack item = new ItemStack(type.getMaterial(), 1);
        // 「普通粘剂」在 1.12.2 里必须是「水瓶」——只设 POTION 材质会显示成别的东西，
        // 得把 NBT 的 Potion 写成 minecraft:water 才是真正的空瓶外观。
        if (type.getPotion() != null && !type.getPotion().isEmpty()) {
            net.minecraft.server.v1_12_R1.ItemStack nms = CraftItemStack.asNMSCopy(item);
            NBTTagCompound tag = nms.hasTag() ? nms.getTag() : new NBTTagCompound();
            tag.set("Potion", new NBTTagString(type.getPotion()));
            nms.setTag(tag);
            item = CraftItemStack.asBukkitCopy(nms);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(type.getDisplay());
            List<String> lore = new ArrayList<String>();
            lore.add("§b");
            lore.add("§7 §k||§a 说明: §f使用后增加 " + type.getRate() + "% 合成概率");
            if (owned >= 0) {
                lore.add("§7 §k||§a 拥有数: §f" + owned);
            }
            lore.add("§d");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String defaultOr(int index, String[] defaults, String fallback) {
        return index >= 0 && index < defaults.length ? defaults[index] : fallback;
    }

    private static String stringOf(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // 配置里写了非数字，静默回退到默认值
            }
        }
        return fallback;
    }

    /** 合成失败时补偿已消耗金币的百分比。 */
    public int getRefundPercent() {
        return this.getConfig().getInt("Compound.refund-percent", DEFAULT_REFUND_PERCENT);
    }

    /** 是否启用「连续失败保底」。 */
    public boolean isPityEnabled() {
        return this.getConfig().getBoolean("Compound.pity.enabled", true);
    }

    /** 连续失败多少次后下一次必定成功（&lt;= 0 视为不启用）。 */
    public int getPityThreshold() {
        return this.getConfig().getInt("Compound.pity.threshold", DEFAULT_PITY_THRESHOLD);
    }

    // ================================================================ 其它可配置行为
    // 以下都是本次新增的能力，原实现是写死的。缺省值刻意与原行为保持一致，
    // 因此老的 config.yml（没有这些键）跑起来跟以前一模一样。

    /** 高星合成成功是否全服播报（原实现无条件播报）。 */
    public boolean isBroadcastEnabled() {
        return this.getConfig().getBoolean("Compound.broadcast.enabled", true);
    }

    /** 触发全服播报的最低成品星级（原实现写死为 6）。 */
    public int getBroadcastMinStar() {
        return this.getConfig().getInt("Compound.broadcast.min-star", 6);
    }

    /** 播报文案模板，占位符：{player} / {star} / {pokemon}。 */
    public String getBroadcastMessage() {
        return this.getConfig().getString("Compound.broadcast.message",
                "§a恭喜玩家 §f{player} §a合成出了一只 §f{star} §f{pokemon}");
    }

    /** 合成冷却毫秒数；&lt;= 0 表示不限制。 */
    public long getCooldownMs() {
        return this.getConfig().getLong("Compound.cooldown-ms", 0L);
    }

    /**
     * 冷却判定。允许本次操作时返回 {@code true} 并记下时间；仍在冷却窗口内返回 {@code false}。
     *
     * <p>判定了才记时间（而不是先记时间再判定），这样「冷却期间狂点」不会把冷却不断续期，
     * 否则连点器只要保持每秒一次就永远合不了成。</p>
     */
    public boolean tryUseCompoundCooldown(Player p) {
        long cooldown = this.getCooldownMs();
        if (cooldown <= 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = this.lastCompoundAt.get(p.getName());
        if (last != null && now - last < cooldown) {
            return false;
        }
        this.lastCompoundAt.put(p.getName(), now);
        return true;
    }

    /** 高星材料二次确认的起始星级；&lt;= 0 表示关闭二次确认。 */
    public int getConfirmMinStar() {
        return this.getConfig().getInt("Compound.confirm-min-star", 5);
    }

    /** 是否记录合成流水日志。 */
    public boolean isCompoundLogEnabled() {
        return this.getConfig().getBoolean("Compound.log.enabled", true);
    }

    /** 打开合成界面所需的权限节点；空串表示不限制。 */
    public String getUsePermission() {
        return this.getConfig().getString("Compound.use-permission", "");
    }

    /**
     * 玩家是否有权打开合成界面。
     *
     * <p>权限节点在 plugin.yml 里声明为 {@code pokeutils.use}，但「是否需要」由
     * {@code Compound.use-permission} 决定 —— 留空即人人可用，与插件原本的行为一致。</p>
     */
    public boolean canUseCompound(CommandSender sender) {
        String node = this.getUsePermission();
        return node == null || node.isEmpty() || sender.hasPermission(node);
    }

    /**
     * 追加一行合成流水日志到 {@code plugins/PokeUtils/logs/compound-&lt;日期&gt;.log}。
     *
     * <p>字段用制表符分隔（时间 / 玩家 / 材料星级 / 粘剂 / 结果 / 消耗 / 返还 / 备注），
     * 可以直接丢进 Excel 或 awk 做统计。原插件从头到尾没有调用过一次 {@code getLogger()}，
     * 一旦出现「我明明合成了却什么都没拿到」这类纠纷，服务端没有任何证据可查。</p>
     */
    public void logCompound(String playerName, int star, String sticker, boolean success,
                            int spent, int refunded, String detail) {
        if (!this.isCompoundLogEnabled()) {
            return;
        }
        if (this.compoundLogDir == null) {
            this.compoundLogDir = new File(this.getDataFolder(), "logs");
        }
        if (!this.compoundLogDir.exists() && !this.compoundLogDir.mkdirs()) {
            return;
        }
        java.util.Date now = new java.util.Date();
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd").format(now);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(now);
        File file = new File(this.compoundLogDir, "compound-" + day + ".log");
        String line = time + "\t" + playerName + "\t" + star + "\t"
                + (sticker == null ? "-" : sticker) + "\t" + (success ? "成功" : "失败")
                + "\t" + spent + "\t" + refunded + "\t" + (detail == null ? "-" : detail);
        try (java.io.Writer writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file, true), "UTF-8")) {
            writer.write(line + System.lineSeparator());
        } catch (java.io.IOException ex) {
            this.getLogger().warning("写合成日志失败: " + ex.getMessage());
        }
        // 同一行再塞进内存索引。用 parse 而不是手工构造 Record：格式只在这一个地方定义，
        // 写入侧和读取侧因此不可能漂移。文件写失败时索引仍然收下它 ——
        // 索引是给玩家看「我刚才合成了什么」的，磁盘上的那份才是审计凭证。
        if (this.logIndex != null) {
            this.logIndex.append(CompoundLog.parse(day, line));
        }
    }

    /**
     * 展开说明书里的 {@code {price}} 占位符为按 {@link #getCompoundPrice} 生成的价格表。
     *
     * <p>原来的价格说明是 config.yml 里六行写死的文本，改了 {@code Compound.price} 之后
     * 说明书还是旧数字 —— 玩家照着说明准备金币，结果扣费对不上。现在价格表由数值生成。</p>
     */
    public List<String> buildPriceLore() {
        String[] numerals = {"", "一", "二", "三", "四", "五", "六", "七"};
        String[] colors = {"", "§f", "§a", "§b", "§d", "§e", "§3", "§c"};
        List<String> lines = new ArrayList<String>();
        for (int star = 1; star <= 6; star++) {
            // 这里必须已经是 § 形式：config.yml 里其它说明书行是由
            // `replace("&", "§")` 转换过来的，而本段是直接构造后 addAll 进去，
            // 不会再走那一步转换。
            lines.add(" §3§k||§a " + colors[star] + numerals[star] + "星 §f-> "
                    + colors[star + 1] + numerals[star + 1] + "星 §f: §e消耗 "
                    + this.getCompoundPrice(star) + " 金币");
        }
        return lines;
    }

    /**
     * 玩家当前余额。没有经济服务（Vault 在但经济插件缺失）时返回 0，
     * 界面直接显示为「余额 0」比抛空指针更让人看得懂。
     */
    public double getBalance(Player p) {
        return this.economy == null ? 0.0D : this.economy.getBalance(p);
    }

    /** 玩家当前的连续合成失败次数；会话数据缺失时按 0 处理。 */
    public int getFailStreak(Player p) {
        YSData data = this.ysdatas.get(p.getName());
        return data == null ? 0 : data.getFailStreak();
    }

    /**
     * 合成七星失败时「保留一只精灵」所需的权限节点，对应 config.yml 的 {@code VIP.keep-one}。
     *
     * <p>原插件这一处把两个条件写成了同一个 {@code vip.yx}（{@code a || a}），
     * 对应 config.yml 里「优秀训练师 / 传说训练师」的两个身份丢了一个。第二个节点
     * 在反编译产物里已无从得知，因此做成可配置项；配置缺失或为空时回退到内置默认值。</p>
     */
    public List<String> getKeepOnePermissions() {
        List<String> permissions = this.getConfig().getStringList("VIP.keep-one");
        return permissions.isEmpty() ? Arrays.asList("vip.yx", "vip.cs") : permissions;
    }

    // ================================================================ 配方（本次新增，可配）
    // 「必须放 2 只同星精灵 + 必须放粘剂」原来是写死的：槽位判断、队伍数量下限、扣减与
    // 清空都按 2 只写。这里抽成两个开关，默认值与原实现一致（2 只 + 必须粘剂）。

    /** 参与合成的同星级精灵数量（1 或 2）。 */
    public int getMaterialCount() {
        int n = this.getConfig().getInt("Compound.material-count", 2);
        return n < 1 ? 1 : (n > 2 ? 2 : n);
    }

    /** 是否必须放入粘剂。 */
    public boolean isStickerRequired() {
        return this.getConfig().getBoolean("Compound.sticker-required", true);
    }

    // ================================================================ 权限组折扣 / 加成
    // 原实现里「VIP」只体现在七星失败保留一只，付费层级几乎没有差别。这里让权限组可以
    // 影响金币消耗与成功率：配置是一张有序表，取第一个命中的组，避免多重继承时数额叠加
    // 到一个不可控的值。默认整张表为空 —— 不配就是原行为。

    /** 按权限取金币折扣系数（1.0 = 不打折）。 */
    public double getGroupPriceMultiplier(Player p) {
        for (Map<?, ?> group : this.getConfig().getMapList("Compound.groups")) {
            Object permission = group.get("permission");
            if (permission == null || String.valueOf(permission).isEmpty()) {
                continue;
            }
            if (!p.hasPermission(String.valueOf(permission))) {
                continue;
            }
            Object raw = group.get("price-multiplier");
            double mult = raw instanceof Number ? ((Number) raw).doubleValue() : 1.0D;
            return mult < 0.0D ? 1.0D : mult;
        }
        return 1.0D;
    }

    /** 按权限取成功率加成（%）。 */
    public int getGroupRateBonus(Player p) {
        for (Map<?, ?> group : this.getConfig().getMapList("Compound.groups")) {
            Object permission = group.get("permission");
            if (permission == null || String.valueOf(permission).isEmpty()) {
                continue;
            }
            if (!p.hasPermission(String.valueOf(permission))) {
                continue;
            }
            return intOf(group.get("rate-bonus"), 0);
        }
        return 0;
    }

    /** 某个玩家在某档位上实际要付的金币（已计入权限组折扣）。 */
    public int getEffectivePrice(Player p, int star) {
        int base = this.getCompoundPrice(star);
        double mult = this.getGroupPriceMultiplier(p);
        if (mult >= 1.0D) {
            return base;
        }
        return (int) Math.round(base * mult);
    }

    // ================================================================ 合成券

    /** 全部合成券定义（按配置顺序）。 */
    public List<VoucherType> getVouchers() {
        if (this.vouchers == null) {
            this.vouchers = this.loadVouchers();
        }
        return this.vouchers;
    }

    /** 清空合成券缓存（`/pu reload` 调用）。 */
    public void invalidateVouchers() {
        this.vouchers = null;
    }

    private List<VoucherType> loadVouchers() {
        List<VoucherType> out = new ArrayList<VoucherType>();
        for (Map<?, ?> raw : this.getConfig().getMapList("Compound.vouchers")) {
            Object materialName = raw.get("material");
            if (materialName == null) {
                continue;
            }
            Material material = Material.matchMaterial(String.valueOf(materialName));
            if (material == null) {
                this.getLogger().warning("合成券的材质 " + materialName + " 无法识别，已跳过该项。");
                continue;
            }
            out.add(new VoucherType(material,
                    stringOf(raw.get("name"), ""),
                    stringOf(raw.get("lore-keyword"), ""),
                    intOf(raw.get("star-limit"), 0),
                    stringOf(raw.get("display"), "")));
        }
        return out;
    }

    /**
     * 在玩家背包里找第一个「本次可用的合成券」所在的槽位。
     *
     * @return 槽位下标；没有可用券时返回 {@code -1}
     */
    public int findVoucherSlot(Player p, int star) {
        List<VoucherType> list = this.getVouchers();
        if (list.isEmpty()) {
            return -1;
        }
        PlayerInventory inventory;
        try {
            inventory = p.getInventory();
        } catch (Throwable t) {
            return -1;
        }
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null) {
                continue;
            }
            for (VoucherType voucher : list) {
                if (voucher.allowsStar(star) && voucher.matches(item)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 消耗一个指定槽位上的合成券。数量为 1 时把槽位清空，否则只减 1。 */
    public void consumeVoucherAt(Player p, int slot) {
        PlayerInventory inventory;
        try {
            inventory = p.getInventory();
        } catch (Throwable t) {
            return;
        }
        ItemStack item = inventory.getItem(slot);
        if (item == null) {
            return;
        }
        if (item.getAmount() <= 1) {
            inventory.setItem(slot, null);
            return;
        }
        ItemStack reduced = item.clone();
        reduced.setAmount(item.getAmount() - 1);
        inventory.setItem(slot, reduced);
    }

    /** 判断某档合成时玩家是否有可用券，供界面提示。 */
    public VoucherType findUsableVoucher(Player p, int star) {
        int slot = this.findVoucherSlot(p, star);
        if (slot < 0) {
            return null;
        }
        ItemStack item;
        try {
            item = p.getInventory().getItem(slot);
        } catch (Throwable t) {
            return null;
        }
        for (VoucherType voucher : this.getVouchers()) {
            if (voucher.allowsStar(star) && voucher.matches(item)) {
                return voucher;
            }
        }
        return null;
    }

    // ================================================================ 失败补偿（可配为送粘剂）

    /** 失败补偿方式：{@code money} / {@code sticker} / {@code both}。 */
    public String getRefundMode() {
        String mode = this.getConfig().getString("Compound.refund.mode", "money");
        if (mode == null) {
            return "money";
        }
        String lower = mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!lower.equals("money") && !lower.equals("sticker") && !lower.equals("both")) {
            return "money";
        }
        return lower;
    }

    /** 送粘剂时用哪种粘剂。 */
    public String getRefundStickerType() {
        return this.getConfig().getString("Compound.refund.sticker-type", "PT");
    }

    /** 送粘剂的数量。 */
    public int getRefundStickerAmount() {
        return this.getConfig().getInt("Compound.refund.sticker-amount", 1);
    }

    /** 只有材料星级达到本值时才会送粘剂（0 = 不限）。 */
    public int getRefundStickerMinStar() {
        return this.getConfig().getInt("Compound.refund.min-star", 0);
    }

    // ================================================================ 产出预览

    /** 是否在合成界面提供「产出预览」。 */
    public boolean isPreviewEnabled() {
        return this.getConfig().getBoolean("Compound.preview.enabled", true);
    }

    /** 产出预览每页显示多少项。 */
    public int getPreviewPageSize() {
        int size = this.getConfig().getInt("Compound.preview.page-size", 45);
        return size < 9 ? 9 : (size > 54 ? 54 : size);
    }

    /** 某星级档位（成品星级，1~7）的全部可能产出，取自真实 PokeStar。 */
    public List<String> getPreviewPool(int level) {
        try {
            List<String> pool = new PokeStarAPI().getStarPokemon(level);
            return pool == null ? new ArrayList<String>() : pool;
        } catch (Throwable t) {
            return new ArrayList<String>();
        }
    }

    /**
     * 启动（与 `/pu reload`）时的产出池自检。
     *
     * <p>PokeStar 自带模板里「四星」曾把 {@code star} 写成 {@code 4m}，被 {@code getInt()}
     * 读成 0 之后整档丢失。这个错误在玩家合成前完全不可见，事后也只表现为一条
     * {@code IndexOutOfBoundsException}。与其等玩家撞上，不如开机就说清楚。</p>
     */
    public void checkStarPools() {
        PokeStarAPI api;
        try {
            api = new PokeStarAPI();
        } catch (Throwable t) {
            this.getLogger().warning("无法初始化 PokeStarAPI，跳过产出池自检: " + t);
            return;
        }
        List<String> empty = new ArrayList<String>();
        for (int level = 1; level <= 7; level++) {
            List<String> pool;
            try {
                pool = api.getStarPokemon(level);
            } catch (Throwable t) {
                pool = null;
            }
            if (pool == null || pool.isEmpty()) {
                empty.add(String.valueOf(level));
            }
        }
        if (empty.isEmpty()) {
            this.getLogger().info("产出池自检通过：1~7 星档位全部非空。");
            return;
        }
        this.getLogger().warning("产出池自检发现空档位: " + joinWith(empty, "、")
                + " 星 —— 这些档位的合成会被直接中止（不会扣任何东西），请检查 PokeStar 的 stars.yml。");
    }

    // ================================================================ 反馈（音效 / Title）

    /**
     * 播一个可配的音效；键为 open / select / success / fail。
     *
     * <p>键<b>不存在</b>时用 {@link #DEFAULT_SOUNDS} 里的出厂值（老 config.yml 没有
     * feedback 段的情况），键存在但为空串时不发声 —— 见 DEFAULT_SOUNDS 的说明。</p>
     */
    public void playFeedback(Player p, String key) {
        // ⚠️ 第二个参数不能省成 ""：双参读取看不到 jar 内置默认，
        //    省掉就等于「老配置文件下永远不发声」。
        String name = this.getConfig().getString("Compound.feedback.sounds." + key,
                DEFAULT_SOUNDS.get(key));
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        Sound sound;
        try {
            sound = Sound.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // 配错一个字符原来会「静默不发声」—— 表现为「功能做了但玩家没感觉」，
            // 排查时又完全看不到线索。这里明说一次（每种键只说一次，不刷屏）。
            if (this.soundWarned.add(key)) {
                this.getLogger().warning("Compound.feedback.sounds." + key + " = \"" + name
                        + "\" 不是合法的 Bukkit Sound 枚举名，该处音效将被跳过。");
            }
            return;
        }
        float volume = (float) this.getConfig().getDouble("Compound.feedback.sounds.volume", 1.0D);
        float pitch = (float) this.getConfig().getDouble("Compound.feedback.sounds.pitch", 1.0D);
        try {
            p.playSound(p.getLocation(), sound, volume, pitch);
        } catch (Throwable t) {
            // 音效只是锦上添花，任何失败都不该影响合成流程
            if (this.soundWarned.add("play:" + key)) {
                this.getLogger().warning("播放音效 " + sound + " 失败（" + key + "）: " + t);
            }
        }
    }

    /** 合成成功时的屏幕 Title（占位符 {star} / {pokemon}）。 */
    public void sendSuccessTitle(Player p, String starName, String pokemonName) {
        if (!this.getConfig().getBoolean("Compound.feedback.title.enabled", true)) {
            return;
        }
        String title = this.getConfig().getString("Compound.feedback.title.title", "§a合成成功");
        String subtitle = this.getConfig().getString("Compound.feedback.title.subtitle", "§f{star} §f{pokemon}");
        subtitle = subtitle.replace("{star}", starName == null ? "" : starName)
                .replace("{pokemon}", pokemonName == null ? "" : pokemonName);
        int fadeIn = this.getConfig().getInt("Compound.feedback.title.fade-in", 5);
        int stay = this.getConfig().getInt("Compound.feedback.title.stay", 30);
        int fadeOut = this.getConfig().getInt("Compound.feedback.title.fade-out", 10);
        try {
            p.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ================================================================ 保底进度提示

    /** 保底进度提示方式：{@code chat} / {@code actionbar} / {@code both} / {@code none}。 */
    public String getPityNotify() {
        String mode = this.getConfig().getString("Compound.pity.notify", "chat");
        return mode == null || mode.trim().isEmpty() ? "chat" : mode.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 发一条保底进度提示。
     *
     * <p>关于 actionbar：1.12.2 的 spigot-api <b>没有</b> {@code Player#sendActionBar}，
     * 唯一的路径是走 NMS 自己构造 {@code PacketPlayOutChat}。而本机拿不到可信的 NMS 签名
     * （{@code libraries} 里是混淆原版，靠运行时 remap），照猜签名一旦错了就是
     * {@code IncompatibleClassChangeError}。所以这里改成<b>反射尝试</b>：
     * 服务端的 CraftPlayer 上存在就发，不存在就回落成聊天栏消息，并打一次 WARN。</p>
     */
    public void notifyPity(Player p, int streak, int threshold) {
        String mode = this.getPityNotify();
        if ("none".equals(mode)) {
            return;
        }
        int left = threshold - streak;
        String message = this.getConfig().getString("Compound.pity.message",
                "§e连续失败 §f{streak} §e/ §f{threshold} §7· 再失败 §f{left} §7次必定成功")
                .replace("&", "§")
                .replace("{streak}", String.valueOf(streak))
                .replace("{threshold}", String.valueOf(threshold))
                .replace("{left}", String.valueOf(Math.max(left, 0)));
        boolean wantBar = "actionbar".equals(mode) || "both".equals(mode);
        boolean wantChat = !"actionbar".equals(mode);
        boolean barSent = wantBar && sendActionBar(p, message);
        if (wantChat || !barSent) {
            try {
                p.sendMessage(message);
            } catch (Throwable ignored) {
                // 玩家恰好在这时掉线
            }
        }
    }

    private boolean sendActionBar(Player p, String message) {
        try {
            java.lang.reflect.Method method = p.getClass().getMethod("sendActionBar", String.class);
            method.invoke(p, message);
            return true;
        } catch (Throwable t) {
            if (!this.actionBarWarned) {
                this.actionBarWarned = true;
                this.getLogger().warning("本服务端的 Player 没有 sendActionBar，保底提示已回落为聊天栏消息。");
            }
            return false;
        }
    }

    // ================================================================ 日志读取与统计

    /** `/pu history` 默认返回多少条。 */
    public int getHistorySize() {
        int size = this.getConfig().getInt("Compound.history.size", 10);
        return size < 1 ? 1 : (size > 50 ? 50 : size);
    }

    /** 统计类命令最多往回扫多少行日志。 */
    public int getLogMaxScanLines() {
        int lines = this.getConfig().getInt("Compound.history.max-scan-lines", 50000);
        return lines < 100 ? 100 : lines;
    }

    /** 日志保留天数。 */
    public int getLogKeepDays() {
        return this.getConfig().getInt("Compound.log.keep-days", 30);
    }

    /**
     * 读全部合成流水（受 keep-days 与 max-scan-lines 限制）。
     *
     * <p>数据来自启动时建好的内存索引，<b>不再读盘</b>。索引里的内容与磁盘上的日志文件
     * 在语义上等价（同样的保留天数、同样的扫描上限），但一次查询从「几十万行的文件解析」
     * 变成「遍历一个内存数组」。</p>
     */
    public List<CompoundLog.Record> readLogs() {
        return this.logIndex == null
                ? CompoundLog.read(this.getDataFolder(), this.getLogKeepDays(), this.getLogMaxScanLines())
                : this.logIndex.all();
    }

    /** 清理超期日志，返回删除数量（启动时与 `/pu reload` 时调用）。 */
    public int cleanupLogs() {
        return CompoundLog.cleanup(this.getDataFolder(), this.getLogKeepDays());
    }

    private static String joinWith(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    @Override
    public void onEnable() {
        pu = this;

        if (!this.getDataFolder().exists()) {
            this.getDataFolder().mkdir();
        }

        File config = new File(this.getDataFolder(), "config.yml");
        if (!config.exists()) {
            this.saveDefaultConfig();
        }

        File data = new File(this.getDataFolder(), "Data");
        if (!data.exists()) {
            data.mkdir();
        }

        // 后台线程池要先起来 —— 存储层的异步写回就挂在它上面。
        Async.init(this, StorageFactory.workerThreads(this));

        // 存储：插件的持久化全部走这里（粘剂数量 + 连续失败计数）。
        // 具体用 SQLite（默认）还是 MySQL 由 config.yml 的 Storage 段决定；
        // MySQL 连不上会自动回退 SQLite 并打 SEVERE，绝不因为连不上库就让插件启用失败。
        StorageFactory.Opened opened = StorageFactory.open(this, this.getDataFolder(), this.getLogger());
        this.storage = opened.storage;
        if (this.storage == null) {
            this.getLogger().severe("存储后端不可用，本插件将无法保存任何数据（合成仍可进行，但粘剂与保底计数不会持久化）。");
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            this.ysdatas.put(player.getName(), new YSData(player.getUniqueId(), player.getName()));
            if (this.storage instanceof CachedStorage) {
                // /reload 的情况下可能有玩家在线，先把他们的数据读进缓存。
                ((CachedStorage) this.storage).loadPlayer(player.getUniqueId(), player.getName());
            }
        }

        // 修复（新增防护）：原实现直接 getRegistration(Economy.class).getProvider()，
        // Vault 在但没有经济插件（或者经济插件加载失败）时 registration 为 null，
        // 这一行就会在 enable 阶段抛空指针，插件直接启用失败。改为判空 + 明确日志。
        RegisteredServiceProvider<Economy> econ = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (econ == null) {
            this.getLogger().severe("未找到 Vault 经济服务 (Economy)，合成功能将无法扣费/退款！");
        } else {
            this.economy = econ.getProvider();
        }

        Bukkit.getPluginManager().registerEvents(new PokeCompoundPlayerListener(), this);

        // 启动自检三件事：清掉超期日志、建流水内存索引、检查各星级产出池是否为空。
        // 三者都只写日志、绝不抛异常 —— 自检本身不该成为「插件启用失败」的原因。
        try {
            int removed = this.cleanupLogs();
            if (removed > 0) {
                this.getLogger().info("已清理 " + removed + " 个超过 "
                        + this.getLogKeepDays() + " 天的合成日志文件。");
            }
        } catch (Throwable t) {
            this.getLogger().warning("清理旧合成日志失败: " + t);
        }
        // 索引要在清理之后建，免得把马上要被删的文件读一遍。
        int indexed = -1;
        try {
            this.logIndex = new LogIndex(this.getDataFolder(),
                    this.getLogKeepDays(), this.getLogMaxScanLines());
            indexed = this.logIndex.rebuild();
        } catch (Throwable t) {
            this.getLogger().warning("建立合成流水索引失败: " + t);
        }
        if (this.logIndex == null) {
            // 兜底：给个空索引，保证 logCompound / 命令永远不会踩空指针。
            this.logIndex = new LogIndex(this.getDataFolder(),
                    this.getLogKeepDays(), this.getLogMaxScanLines());
        }
        if (indexed > 0) {
            this.getLogger().info("合成流水索引已建立：" + indexed + " 条记录（上限 "
                    + this.getLogMaxScanLines() + " 条）。");
        }
        try {
            this.checkStarPools();
        } catch (Throwable t) {
            this.getLogger().warning("产出池自检失败: " + t);
        }
    }

    @Override
    public void onDisable() {
        // 关服收尾的顺序不能反：先把异步写回队列排空、再补一次同步 flush，最后才关连接。
        // 反过来（先关连接）会让排在队列里的写入全部打在已关闭的连接上。
        // 用 MySQL + 异步写回时这一步尤其重要 —— 不开 onDisable 的强杀（taskkill /F）
        // 一定会丢掉最后那几毫秒的写入，这是选异步必然的代价。
        if (this.storage != null) {
            try {
                this.storage.shutdown(this.getConfig().getLong("Storage.async.flush-timeout-ms", 5000L));
            } catch (Throwable t) {
                this.getLogger().warning("关服落盘时出错: " + t);
            }
        }
        // 线程池放在存储之后关：存储的排空要依赖它。
        Async.shutdown(2000L);
        this.ysdatas.clear();
        this.playerdatas.clear();
        this.lastCompoundAt.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§a==============================================");
            sender.sendMessage("§6/pu §fcompound - §e打开合成界面");
            sender.sendMessage("§6/pu §fhistory [玩家] [条数] - §e查看最近的合成记录");
            sender.sendMessage("§6/pu §fstats [玩家|all] - §e合成统计（成功率 / 消耗）");
            sender.sendMessage("§6/pu §ftop [count|star] - §e合成排行榜");
            if (sender.hasPermission("pokeutils.admin")) {
                sender.sendMessage("§6/pu §fg <玩家> <类型> <数量> - §e给予粘剂（支持离线玩家）");
                sender.sendMessage("§6/pu §ftake <玩家> <类型> <数量> - §e扣除粘剂（支持离线玩家）");
                sender.sendMessage("§6/pu §fbal [玩家] - §e查询粘剂余额");
                sender.sendMessage("§6/pu §fdebug <玩家> - §e查看某个玩家的合成状态与成功率构成");
                sender.sendMessage("§6/pu §freload - §e重载插件配置文件");
            }
            sender.sendMessage("§a==============================================");
        } else if (args[0].equalsIgnoreCase("compound")) {
            // 顺带修的同类问题：原实现直接 (Player) sender，
            // 控制台敲 /pu compound 会抛 ClassCastException。
            if (!(sender instanceof Player)) {
                sender.sendMessage("§b精灵信息 §f// §c该指令只能由玩家执行！");
                return true;
            }
            // 新增：是否需要权限由 config.yml 的 Compound.use-permission 决定
            // （留空即人人可用，与插件原本的行为一致）。
            if (!this.canUseCompound(sender)) {
                sender.sendMessage("§b精灵信息 §f// §c你没有权限打开合成界面！");
                return true;
            }
            String sendername = sender.getName();
            if (this.playerdatas.containsKey(sendername)) {
                this.playerdatas.remove(sendername);
            }
            this.playerdatas.put(sendername, new PlayerData());
            // 新增（玩家体验）：打开界面的音效。放在命令入口而不是 openGui 里 ——
            // openGui 在「选完材料自动重开」时也会被调用，放那里会变成每次选择都响一声。
            this.playFeedback((Player) sender, "open");
            this.playerdatas.get(sendername).openGui((Player) sender);
        } else if (args[0].equalsIgnoreCase("reload") && sender.hasPermission("pokeutils.admin")) {
            this.reloadConfig();
            // 修复：粘剂定义是按 config.yml 缓存过的，重载配置时必须一并失效，
            // 否则改了 Compound.stickers 要重启服务器才看得到效果。
            this.invalidateStickerTypes();
            // 合成券定义同样是缓存过的，一并失效。
            this.invalidateVouchers();
            // 产出池可能被 PokeStar 的 stars.yml 改动影响，重载时再自检一次。
            this.checkStarPools();
            // 日志索引的上限（history.max-scan-lines）可能被改了，磁盘上的日志也可能
            // 被运维清理过 —— 重建一次索引。放后台线程：全量读盘在几十万行时上百毫秒，
            // 不该卡在管理员敲下 /pu reload 的这一帧上。
            final LogIndex index = this.logIndex;
            if (index != null) {
                index.configure(this.getLogKeepDays(), this.getLogMaxScanLines());
                Async.compute(new Runnable() {
                    @Override
                    public void run() {
                        index.rebuild();
                    }
                });
            }
            sender.sendMessage("§b精灵信息 §f// §a插件配置重载完成！");
        } else if (args[0].equalsIgnoreCase("history")) {
            this.handleHistoryCommand(sender, args);
        } else if (args[0].equalsIgnoreCase("stats")) {
            this.handleStatsCommand(sender, args);
        } else if (args[0].equalsIgnoreCase("top")) {
            this.handleTopCommand(sender, args);
        } else if (args[0].equalsIgnoreCase("debug") && sender.hasPermission("pokeutils.admin")) {
            this.handleDebugCommand(sender, args);
        } else if (args[0].equalsIgnoreCase("g") && sender.hasPermission("pokeutils.admin")) {
            this.handleStickerCommand(sender, args, true);
        } else if (args[0].equalsIgnoreCase("take") && sender.hasPermission("pokeutils.admin")) {
            this.handleStickerCommand(sender, args, false);
        } else if (args[0].equalsIgnoreCase("bal")) {
            this.handleBalanceCommand(sender, args);
        }
        return false;
    }

    // ================================================================ 流水 / 统计 / 排查
    // 这三个命令全部建立在第一轮加的合成流水日志之上：日志本来只是「事后取证」用的，
    // 现在把它变成玩家能自己查、运营能拿来看的数据源。

    /** 把 1~10 写成汉字，供命令输出拼接（超过范围直接返回数字）。 */
    public static String numeral(int n) {
        return n >= 1 && n < NUMERALS.length ? NUMERALS[n] : String.valueOf(n);
    }

    /** 星级的汉字写法，用于命令输出。 */
    public static String starName(int star) {
        return numeral(star) + "星";
    }

    /**
     * {@code /pu history [玩家] [条数]}：列出某个玩家最近的合成记录。
     *
     * <p>没有这个命令时，玩家想知道「我上一次到底合成了没」只能找管理翻服务器日志。
     * 数据早就在本地了，缺的只是入口。</p>
     */
    private void handleHistoryCommand(CommandSender sender, String[] args) {
        String target;
        if (args.length >= 2) {
            if (!sender.hasPermission("pokeutils.admin")) {
                sender.sendMessage("§b精灵信息 §f// §c你没有权限查询其他玩家的记录！");
                return;
            }
            target = args[1];
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§b精灵信息 §f// §c控制台请使用 /pu history <玩家> [条数]");
                return;
            }
            target = ((Player) sender).getName();
        }
        int size = args.length >= 3 ? parseInt(args[2], this.getHistorySize()) : this.getHistorySize();
        size = size < 1 ? 1 : (size > 50 ? 50 : size);

        // 索引直接给出「最近 N 条、最新在前」，不用先把该玩家的全部记录拉出来再截。
        List<CompoundLog.Record> mine = this.logIndex.recentFor(target, size);
        sender.sendMessage("§a============ §e" + target + " 的最近合成记录 §a============");
        if (mine.isEmpty()) {
            sender.sendMessage("§7 没有查到这个玩家的合成记录。");
            sender.sendMessage("§a==============================================");
            return;
        }
        for (CompoundLog.Record record : mine) {
            StringBuilder line = new StringBuilder();
            line.append("§7 ").append(record.date.length() >= 5 ? record.date.substring(5) : record.date)
                    .append(" ").append(record.time).append(" §f").append(starName(record.star))
                    .append(record.success ? " §a成功" : " §c失败")
                    .append(" §7消耗 §f").append(record.spent);
            if (record.refunded > 0) {
                line.append(" §7返还 §f").append(record.refunded);
            }
            if (record.sticker != null && !"-".equals(record.sticker)) {
                line.append(" §7粘剂 §f").append(record.sticker);
            }
            sender.sendMessage(line.toString());
        }
        CompoundLog.Stats stats = this.logIndex.stats(target);
        sender.sendMessage("§7 共 §f" + stats.total + " §7次 · 成功 §a" + stats.success
                + " §7· 失败 §c" + stats.fail + " §7· 成功率 §f" + stats.rate() + "%");
        sender.sendMessage("§a==============================================");
    }

    /** {@code /pu stats [玩家|all]}：从流水日志聚合成功率与金币消耗。 */
    private void handleStatsCommand(CommandSender sender, String[] args) {
        boolean all = args.length >= 2 && "all".equalsIgnoreCase(args[1]);
        String target = null;
        if (args.length >= 2 && !all && !"me".equalsIgnoreCase(args[1])) {
            target = args[1];
        }
        if ((all || target != null) && !sender.hasPermission("pokeutils.admin")) {
            sender.sendMessage("§b精灵信息 §f// §c你没有权限查看其他玩家的统计！");
            return;
        }
        if (!all && target == null) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§b精灵信息 §f// §c控制台请使用 /pu stats <玩家|all>");
                return;
            }
            target = ((Player) sender).getName();
        }
        CompoundLog.Stats stats = this.logIndex.stats(all ? null : target);
        sender.sendMessage("§a============ §e" + (all ? "全服" : target) + " 合成统计 §a============");
        sender.sendMessage("§7 总次数 §f" + stats.total + " §7· 成功 §a" + stats.success
                + " §7· 失败 §c" + stats.fail + " §7· 成功率 §f" + stats.rate() + "%");
        sender.sendMessage("§7 消耗金币合计 §f" + stats.spent + " §7· 返还合计 §f" + stats.refunded);
        for (int star = 1; star <= 6; star++) {
            if (stats.starTotal[star] <= 0) {
                continue;
            }
            sender.sendMessage("§7 " + starName(star) + "§7 → §7" + starName(star + 1)
                    + " §f" + stats.starTotal[star] + " §7次 · 成功 §a" + stats.starSuccess[star]
                    + " §7· 成功率 §f" + stats.starRate(star) + "%");
        }
        sender.sendMessage("§7 数据来源: 最近 " + this.getLogKeepDays() + " 天的合成日志");
        sender.sendMessage("§a==============================================");
    }

    /** {@code /pu top [count|star]}：合成排行榜。 */
    private void handleTopCommand(CommandSender sender, String[] args) {
        boolean byStar = args.length >= 2 && "star".equalsIgnoreCase(args[1]);
        List<CompoundLog.Count> top = this.logIndex.top(byStar);
        sender.sendMessage("§a============ §e合成排行榜（" + (byStar ? "最高星级" : "成功次数") + "）§a============");
        if (top.isEmpty()) {
            sender.sendMessage("§7 还没有任何合成记录。");
            sender.sendMessage("§a==============================================");
            return;
        }
        int limit = Math.min(top.size(), 10);
        for (int i = 0; i < limit; i++) {
            CompoundLog.Count count = top.get(i);
            sender.sendMessage("§7 " + (i + 1) + ". §f" + count.player
                    + " §7· 成功 §a" + count.success + " §7/ 总计 §f" + count.count
                    + (count.highestStar > 0 ? " §7· 最高 §e" + starName(count.highestStar) : ""));
        }
        sender.sendMessage("§a==============================================");
    }

    /**
     * {@code /pu debug <玩家>}：把「这次合成成功率为什么是这个数」摊开。
     *
     * <p>成功率其实是三个来源相加：该档基础值 + 粘剂加成 + 权限组加成，再夹到 100。
     * 玩家看到界面上的数字对不上自己预期时，管理只能猜。这里直接把三项与各自来源列出来。</p>
     */
    private void handleDebugCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§b精灵信息 §f// §c用法: /pu debug <玩家>");
            return;
        }
        String name = args[1];
        Player p = Bukkit.getPlayerExact(name);
        UUID uuid = p != null ? p.getUniqueId()
                : (this.storage == null ? null : this.storage.findUuidByName(name));

        sender.sendMessage("§a========= §e" + name + " 的合成状态 §a=========");
        if (uuid == null) {
            sender.sendMessage("§c 找不到该玩家（他可能从未进过本服）");
            sender.sendMessage("§a==============================================");
            return;
        }
        sender.sendMessage("§7 UUID: §f" + uuid);
        sender.sendMessage("§7 在线: §f" + (p != null));

        if (this.storage == null) {
            sender.sendMessage("§c 存储不可用，无法读取粘剂与保底计数。");
        } else {
            Map<String, Integer> owned = this.storage.getStickers(uuid);
            StringBuilder sb = new StringBuilder();
            for (StickerType sticker : this.getStickerTypes()) {
                Integer count = owned.get(sticker.getKey());
                if (sb.length() > 0) {
                    sb.append(" §7| ");
                }
                sb.append(sticker.getDisplay()).append("§f ").append(count == null ? 0 : count);
            }
            sender.sendMessage("§7 粘剂: " + (sb.length() == 0 ? "§7（配置里没有定义粘剂）" : sb.toString()));
            sender.sendMessage("§7 连续失败: §f" + this.storage.getFailStreak(uuid)
                    + " §7/ §f" + this.getPityThreshold()
                    + " §7(保底" + (this.isPityEnabled() ? "开启" : "关闭") + ")");
        }

        if (p == null) {
            sender.sendMessage("§a==============================================");
            return;
        }
        sender.sendMessage("§7 余额: §f" + (long) this.getBalance(p));
        sender.sendMessage("§7 权限组折扣系数: §f" + this.getGroupPriceMultiplier(p)
                + " §7· 成功率加成: §f" + this.getGroupRateBonus(p) + "%");

        PlayerData pd = this.playerdatas.get(p.getName());
        if (pd == null) {
            sender.sendMessage("§7 合成会话: §7（当前没有打开合成界面）");
        } else {
            sender.sendMessage("§7 会话: 槽位1=§f" + pd.getSlot1() + " §7槽位2=§f" + pd.getSlot2()
                    + " §7粘剂=§f" + (pd.getYS() == null ? "无" : pd.getYS())
                    + " §7材料星级=§f" + pd.getStar());
            sender.sendMessage("§7 成功率构成: 基础 §f" + pd.getJl() + "% §7+ 粘剂 §f" + pd.getYsjl()
                    + "% §7+ 权限组 §f" + pd.getVipBonus() + "% §7= §f" + pd.getSumJL() + "%");
            sender.sendMessage("§7 二次确认=§f" + pd.isConfirmed() + " §7· 处理中=§f" + pd.isProcessing());
            if (pd.getStar() > 0) {
                sender.sendMessage("§7 本次应扣金币: §f" + this.getEffectivePrice(p, pd.getStar())
                        + " §7(基础 §f" + this.getCompoundPrice(pd.getStar()) + "§7)");
                VoucherType voucher = this.findUsableVoucher(p, pd.getStar());
                sender.sendMessage("§7 可用合成券: §f" + (voucher == null ? "无" : voucher.getDisplay()));
            }
        }
        sender.sendMessage("§a==============================================");
    }

    /**
     * {@code /pu g|take <玩家> <类型> <数量>} 的统一实现。
     *
     * <p>相比原实现补齐了两处硬伤：</p>
     * <ol>
     *   <li><b>支持离线玩家</b> —— 原实现玩家不在线就直接拒绝，运维想给下线的玩家
     *       补发粘剂根本做不到（而「发东西」这件事恰恰经常发生在对方不在线的时候）；</li>
     *   <li><b>支持扣减</b> —— 原来只有 {@code deposit}，发错了没法撤回，
     *       只能「再发一个反向数量」把账目越搞越乱。现在有 {@code /pu take}。</li>
     * </ol>
     *
     * <p>顺带保留原有的两处防护：数量解析失败不再抛 {@code NumberFormatException}；
     * 目标玩家解析失败（从未进服）会明确告知，而不是丢一条空指针栈。</p>
     */
    private void handleStickerCommand(CommandSender sender, String[] args, boolean give) {
        String verb = give ? "g" : "take";
        if (args.length < 4) {
            sender.sendMessage("§b精灵信息 §f// §c用法: /pu " + verb + " <玩家> <类型> <数量>");
            return;
        }
        String playerName = args[1];
        String type = args[2];

        // 修复（新增防护）：原实现直接 Integer.parseInt(args[3])，
        // `/pu g <玩家> PT abc` 会抛 NumberFormatException，落到控制台只是一条栈。
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§b精灵信息 §f// §c数量必须是整数！");
            return;
        }
        if (amount <= 0) {
            sender.sendMessage("§b精灵信息 §f// §c数量必须大于 0！");
            return;
        }
        // 修复：类型不再写死 PT / JL，改为核对配置里的粘剂列表。
        if (this.getStickerType(type) == null) {
            sender.sendMessage("§b精灵信息 §f// §c类型错误！可用类型: " + this.stickerKeyList());
            return;
        }
        if (this.storage == null) {
            sender.sendMessage("§b精灵信息 §f// §c存储不可用（数据库未打开）, 操作已取消！");
            return;
        }

        // 目标解析顺序：在线玩家 -> 库里的 name->uuid 记录 -> Bukkit 离线解析。
        // 中间那一步是关键：正版验证服上 Bukkit.getOfflinePlayer(新名字) 会去请求
        // Mojang 接口并把主线程卡住，而只要这人进过服，库里就有记录。
        Player online = Bukkit.getPlayerExact(playerName);
        UUID uuid = online != null ? online.getUniqueId() : this.storage.findUuidByName(playerName);
        String resolvedName = online != null ? online.getName() : playerName;
        if (uuid == null) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
            if (offline == null || !offline.hasPlayedBefore()) {
                sender.sendMessage("§b精灵信息 §f// §c找不到该玩家（他可能从未进过本服）！");
                return;
            }
            uuid = offline.getUniqueId();
        }

        int after = this.storage.addSticker(uuid, resolvedName, type, give ? amount : -amount);
        sender.sendMessage("§b精灵信息 §f// §a操作成功！§f" + resolvedName + " §7的 "
                + this.stickerDisplay(type) + "§7 现在为 §f" + after
                + " §7(" + (give ? "+" : "-") + amount + ")");
        if (online != null) {
            online.sendMessage("§3精灵合成 §f|| §e你的 " + this.stickerDisplay(type) + "§e 数量已变更为 §f" + after);
        }
    }

    /** {@code /pu bal [玩家]}：查询粘剂余额与保底进度。 */
    private void handleBalanceCommand(CommandSender sender, String[] args) {
        if (this.storage == null) {
            sender.sendMessage("§b精灵信息 §f// §c存储不可用（数据库未打开）！");
            return;
        }
        String target;
        UUID uuid;
        if (args.length >= 2) {
            if (!sender.hasPermission("pokeutils.admin")) {
                sender.sendMessage("§b精灵信息 §f// §c你没有权限查询其他玩家！");
                return;
            }
            target = args[1];
            Player online = Bukkit.getPlayerExact(target);
            uuid = online != null ? online.getUniqueId() : this.storage.findUuidByName(target);
            if (uuid == null) {
                sender.sendMessage("§b精灵信息 §f// §c找不到该玩家的数据（他可能从未进过本服）！");
                return;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§b精灵信息 §f// §c控制台请使用 /pu bal <玩家>");
                return;
            }
            Player p = (Player) sender;
            target = p.getName();
            uuid = p.getUniqueId();
        }

        Map<String, Integer> amounts = this.storage.getStickers(uuid);
        sender.sendMessage("§a=========== §f" + target + " §a的粘剂 ===========");
        boolean any = false;
        for (StickerType sticker : this.getStickerTypes()) {
            Integer count = amounts.get(sticker.getKey());
            sender.sendMessage("§7 - " + sticker.getDisplay() + "§7: §f" + (count == null ? 0 : count));
            any = true;
        }
        if (!any) {
            sender.sendMessage("§7 （配置里没有定义任何粘剂）");
        }
        if (this.isPityEnabled() && this.getPityThreshold() > 0) {
            sender.sendMessage("§7 - 连续失败: §f" + this.storage.getFailStreak(uuid)
                    + " §7/ §f" + this.getPityThreshold());
        }
        sender.sendMessage("§a==============================================");
    }

    /** 配置里全部粘剂的键，供命令提示用。 */
    private String stickerKeyList() {
        StringBuilder sb = new StringBuilder();
        for (StickerType sticker : this.getStickerTypes()) {
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append(sticker.getKey());
        }
        return sb.toString();
    }

    /** 某粘剂的显示名（查不到时返回其键）。 */
    private String stickerDisplay(String key) {
        StickerType sticker = this.getStickerType(key);
        return sticker == null ? key : sticker.getDisplay();
    }
}
