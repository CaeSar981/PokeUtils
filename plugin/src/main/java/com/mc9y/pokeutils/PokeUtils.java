package com.mc9y.pokeutils;

import com.mc9y.pokestar.PokeStarAPI;
import com.mc9y.pokeutils.data.Async;
import com.mc9y.pokeutils.data.CachedStorage;
import com.mc9y.pokeutils.data.CompoundLog;
import com.mc9y.pokeutils.data.LogIndex;
import com.mc9y.pokeutils.data.LogWriter;
import com.mc9y.pokeutils.data.PlayerData;
import com.mc9y.pokeutils.data.PokemonAttr;
import com.mc9y.pokeutils.data.PreviewIcons;
import com.mc9y.pokeutils.data.QuestData;
import com.mc9y.pokeutils.data.QuestType;
import com.mc9y.pokeutils.data.StickerRecipe;
import com.mc9y.pokeutils.data.Storage;
import com.mc9y.pokeutils.data.StorageFactory;
import com.mc9y.pokeutils.data.StorageMigrator;
import com.mc9y.pokeutils.data.StickerType;
import com.mc9y.pokeutils.data.VoucherType;
import com.mc9y.pokeutils.data.YSData;
import com.mc9y.pokeutils.listener.PokeCompoundPlayerListener;
import com.pixelmonmod.pixelmon.Pixelmon;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.storage.PlayerPartyStorage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
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
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
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
public class PokeUtils extends JavaPlugin implements TabCompleter {

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

    // ================================================================ 第四轮新增能力的出厂默认值
    //
    // ⚠️ 与 DEFAULT_PRICE / DEFAULT_SOUNDS 同一套约定：这里的字面量必须与 jar 内置
    //    config.yml 里的值<b>逐位一致</b>。原因是 Bukkit 的双参读取
    //    （getInt(path, def) / getString(path, def) / getBoolean(path, def)）
    //    <b>不会</b>回退到 jar 内置配置 —— 磁盘上的旧 config.yml 里没有这些新键时，
    //    def 就是最终生效值。详见 DEFAULT_SOUNDS 上方那段说明。
    //
    // 另一个后果值得单独提醒：这些新能力在「只换 jar、没换 config.yml」的老服上
    // 依然会按出厂值正常工作（而不是静默失效），这是刻意设计的。

    private static final boolean DEFAULT_INHERIT_ENABLED = true;
    private static final int DEFAULT_INHERIT_MAX_PICKS = 1;
    private static final int DEFAULT_INHERIT_IV_MIN = 50;
    private static final int DEFAULT_INHERIT_IV_MAX = 100;
    private static final int DEFAULT_INHERIT_SHINY_SINGLE = 5;
    private static final int DEFAULT_INHERIT_SHINY_DOUBLE = 25;

    private static final boolean DEFAULT_REROLL_ENABLED = true;
    private static final int DEFAULT_REROLL_PRICE = 20000;
    private static final String DEFAULT_REROLL_STICKER = "PT";
    private static final int DEFAULT_REROLL_STICKER_AMOUNT = 1;
    private static final int DEFAULT_REROLL_IV_MIN = 0;
    private static final int DEFAULT_REROLL_IV_MAX = 31;
    private static final boolean DEFAULT_REROLL_IMPROVE_ONLY = false;

    private static final boolean DEFAULT_SALVAGE_ENABLED = true;
    /** 下标 = 精灵星级；即「分解一只 N 星精灵返还多少粘剂」。 */
    private static final int[] DEFAULT_SALVAGE_STICKER = {0, 0, 0, 1, 2, 3, 5, 8};
    private static final String DEFAULT_SALVAGE_STICKER_TYPE = "PT";
    private static final int DEFAULT_SALVAGE_MONEY_PERCENT = 10;

    private static final boolean DEFAULT_STICKER_CRAFT_ENABLED = true;

    private static final boolean DEFAULT_QUEST_ENABLED = true;
    private static final int DEFAULT_QUEST_AMOUNT = 3;
    /** 数量类参数的补全提示。只是「常用值」，不限制玩家手打别的数字。 */
    private static final List<String> TAB_AMOUNTS =
            Collections.unmodifiableList(Arrays.asList("1", "10", "50", "100"));
    /** 委托记录保留天数（含今天）。0 = 不清理。 */
    private static final int DEFAULT_QUEST_KEEP_DAYS = 7;
    private static final String[] DEFAULT_QUEST_KEYS = {"SUCCESS", "ATTEMPT", "SALVAGE", "STICKER", "SUBMIT"};
    private static final int[] DEFAULT_QUEST_WEIGHTS = {3, 3, 2, 2, 2};
    private static final String[] DEFAULT_QUEST_TARGETS = {"2,3,5", "3,5,8", "1,2,3", "1,2,3", "1"};
    private static final String[] DEFAULT_QUEST_DISPLAYS = {
        "§a成功合成 §f{target} §a次精灵",
        "§a进行 §f{target} §a次精灵合成",
        "§a分解回收 §f{target} §a只精灵",
        "§a合成 §f{target} §a次粘剂",
        "§a交付一只 §f{star}星 §a及以上的精灵"};
    private static final int DEFAULT_QUEST_SUBMIT_STAR = 4;
    private static final int DEFAULT_QUEST_REWARD_MONEY = 20000;
    private static final String DEFAULT_QUEST_REWARD_STICKER = "PT";
    private static final int DEFAULT_QUEST_REWARD_STICKER_AMOUNT = 2;

    private static final boolean DEFAULT_DEX_ENABLED = true;
    private static final int[] DEFAULT_DEX_MILESTONES = {5, 10, 20};
    private static final int[] DEFAULT_DEX_MONEY = {50000, 200000, 500000};
    private static final String[] DEFAULT_DEX_STICKERS = {"PT", "JL", "JL"};
    private static final int[] DEFAULT_DEX_STICKER_AMOUNTS = {2, 1, 3};

    /** 星级的汉字写法（命令输出用）。 */
    private static final String[] NUMERALS = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    /** 粘剂定义缓存；{@code /pu reload} 时置空以便下次重新读配置。 */
    private List<StickerType> stickers;

    /** 合成券定义缓存；同样在 {@code /pu reload} 时失效。 */
    private List<VoucherType> vouchers;

    /** 粘剂合成配方缓存；{@code /pu reload} 时失效。 */
    private List<StickerRecipe> stickerRecipes;

    /** 每日委托类型定义缓存；{@code /pu reload} 时失效。 */
    private List<QuestType> questTypes;

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

    /** 合成流水的落盘器（句柄常开，写入跑在 {@link Async} 的写队列上）。 */
    private LogWriter logWriter;

    /**
     * 上次合成的时间戳（毫秒），按 <b>UUID</b> 索引，用于合成冷却。
     *
     * <p>用 UUID 而不是玩家名：玩家改名会产生一个新的名字键，而旧键永远不会被清掉，
     * 这张表只增不减（原实现就是这样）。UUID 与账号一一对应，退服时顺手摘掉即可。</p>
     */
    private final HashMap<UUID, Long> lastCompoundAt = new HashMap<UUID, Long>();

    /** 合成流水日志目录（首次写入时创建）。 */
    private File compoundLogDir;

    /**
     * 存储迁移是否正在进行。
     *
     * <p>迁移跑在后台线程上（全量读 + 几千次 upsert 往返，放主线程会卡服），
     * 所以要有这个闸门挡住「管理员连敲两次 /pu migrate」导致的并发迁移 ——
     * 两个迁移同时往同一个目标表 upsert 虽然幂等，但会让「读到的条数」互相干扰。</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean migrating =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 启用时拍下的 {@code Storage} 段快照（键 -> 值的字符串形式）。
     *
     * <p>用途只有一个：{@code /pu reload} 时判断存储相关的配置是不是被改过。存储后端在
     * {@code onEnable} 就建好了，reload <b>不会</b>重建它 —— 不提示的话，运维会以为
     * 「改成 MySQL 已经生效」，而实际上要重启服务器。这里刻意<b>不</b>在 reload 后更新快照：
     * 提示会一直出现，直到真正重启为止。</p>
     */
    private final Map<String, String> storageSnapshot = new HashMap<String, String>();

    /**
     * 存储没能按预期工作时的人话说明（MySQL 降级到 SQLite、或后端根本打不开）；
     * 正常时为 {@code null}。降级发生时会给进服的 OP 发一次提醒 —— 详见
     * {@link StorageFactory#DEFAULT_MYSQL_FALLBACK} 对「带错数据上线」的说明。
     */
    private String storageWarning;

    public Storage getStorage() {
        return this.storage;
    }

    /** 未经缓存包装的裸存储后端（只有测试会用它来验证「数据真的落库了」）。 */
    public Storage getBackend() {
        return this.storage == null ? null : this.storage.backend();
    }

    /**
     * 存储没能按预期工作时的人话说明（MySQL 降级回 SQLite、或后端打不开），正常时为 {@code null}。
     *
     * <p>启动日志当然已经打过 SEVERE，但运维不一定开着控制台 —— 所以让进服的 OP 也看得见。</p>
     */
    public String getStorageWarning() {
        return this.storageWarning;
    }

    /**
     * 两份 {@code Storage} 段快照的差异键（按字母序，输出稳定，便于测试断言）。
     *
     * <p>「一边有一边没有」也算差异 —— 那正是「原本没配 Storage 段的服后来补了一段」的情形。</p>
     */
    private List<String> diffStorageConfig(Map<String, String> before, Map<String, String> after) {
        java.util.TreeSet<String> keys = new java.util.TreeSet<String>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        List<String> changed = new ArrayList<String>();
        for (String key : keys) {
            String old = before.get(key);
            String now = after.get(key);
            if (old == null ? now != null : !old.equals(now)) {
                changed.add(key);
            }
        }
        return changed;
    }

    /**
     * 把 config.yml 的 {@code Storage} 段拍平成「键 -> 值字符串」，供 {@code /pu reload}
     * 比对「哪些改动需要重启服务器才生效」。
     *
     * <p><b>两个 Bukkit 坑要避开</b>：① {@code getConfigurationSection} 在「本段没配、
     * 但 jar 内置 config.yml 有」时返回的是一个<b>空段</b>而不是 null，所以这里把
     * 「null」与「空段」当成同一件事（这个服根本没有 Storage 段）；② 用 {@code getKeys(true)}
     * 拿全部嵌套键，否则 {@code Storage.mysql.host} 这种改动比不出来。</p>
     */
    private Map<String, String> snapshotStorageConfig() {
        Map<String, String> out = new HashMap<String, String>();
        ConfigurationSection section = this.getConfig().getConfigurationSection("Storage");
        if (section == null || section.getKeys(true).isEmpty()) {
            return out;
        }
        for (String key : section.getKeys(true)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection) {
                continue;
            }
            out.put(key, String.valueOf(value));
        }
        return out;
    }

    /** 合成流水的内存索引。 */
    public LogIndex getLogIndex() {
        return this.logIndex;
    }

    /** 合成流水的落盘器（测试用它验证「句柄复用」与「写后即刷」）。 */
    public LogWriter getLogWriter() {
        return this.logWriter;
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
            // 第四轮新增的两个可选字段。缺省值与原实现的行为完全一致：
            // protect=false（失败照常消耗材料）、pool 为空（不做定向）。
            boolean protect = false;
            Object protectRaw = raw.get("protect");
            if (protectRaw instanceof Boolean) {
                protect = (Boolean) protectRaw;
            } else if (protectRaw != null) {
                protect = Boolean.parseBoolean(String.valueOf(protectRaw));
            }
            List<String> pool = new ArrayList<String>();
            Object poolRaw = raw.get("pool");
            if (poolRaw instanceof List<?>) {
                for (Object item : (List<?>) poolRaw) {
                    if (item != null && !String.valueOf(item).trim().isEmpty()) {
                        pool.add(String.valueOf(item).trim());
                    }
                }
            }
            out.add(new StickerType(key, display, material, potion, rate, insufficient, slot, protect, pool));
        }

        if (!out.isEmpty()) {
            return out;
        }
        // 配置里没有 Compound.stickers（老配置文件就是这种情况）—— 用与原实现一致的默认值。
        for (int i = 0; i < DEFAULT_STICKER_KEYS.length; i++) {
            out.add(new StickerType(DEFAULT_STICKER_KEYS[i], DEFAULT_STICKER_DISPLAYS[i],
                    Material.valueOf(DEFAULT_STICKER_MATERIALS[i]), DEFAULT_STICKER_POTIONS[i],
                    DEFAULT_STICKER_RATES[i], DEFAULT_STICKER_INSUFFICIENT[i], STICKER_SLOT_BASE + i,
                    false, Collections.<String>emptyList()));
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

    /**
     * 去掉字符串开头的第一个颜色码（如 {@code §a精良粘剂 -> 精良粘剂}）。
     *
     * <p>粘剂 / 精灵这类名字在配置里本身就带颜色（{@code display: "§a精良粘剂"}），
     * 而它们经常被拼进「自带颜色」的模板里 —— 直接拼就会得到 {@code §a个§a精良粘剂}
     * 这种重复色码。渲染出来看不出差别，但一条日志、一个断言都会被它坑，
     * 所以拼之前先把名字自带的颜色剥掉一层。
     */
    private static String stripLeadColor(String text) {
        if (text == null || text.length() < 2) {
            return text;
        }
        char c = text.charAt(0);
        if ((c == '\u00a7' || c == '&') && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(text.charAt(1)) >= 0) {
            return text.substring(2);
        }
        return text;
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

    /** 连续失败多少次后下一次必定成功（&lt;= 0 视为不启用）。这是所有档位共用的默认值。 */
    public int getPityThreshold() {
        return this.getConfig().getInt("Compound.pity.threshold", DEFAULT_PITY_THRESHOLD);
    }

    /**
     * 某个原料星级档的保底阈值（新增，A2 保底分档）。
     *
     * <p>{@code Compound.pity.threshold-by-star} 可以给个别档位单独定阈值，
     * 没配的档位回退到 {@link #getPityThreshold()} 那个单值。
     * {@code star <= 0} 表示「还不知道是哪个档位」（界面刚打开、材料还没选齐），
     * 此时一律用单值。</p>
     *
     * <p>⚠️ 判定「这一段配没配」必须<b>同时判 null 与空段</b>：Bukkit 的
     * {@code getConfigurationSection} 在「本段没这个键、但 jar 内置 config.yml 有」时
     * 会<b>凭空造一个空段</b>返回（配置坑 ②）。出厂 config.yml 里写的是
     * {@code threshold-by-star: {}}，所以正常情况下拿到的是「真实的空段」，
     * 这条兜底是为了「换 jar 但沿用旧 config.yml」那种场景。</p>
     */
    public int getPityThreshold(int star) {
        int fallback = this.getPityThreshold();
        if (star <= 0) {
            return fallback;
        }
        ConfigurationSection section = this.getConfig()
                .getConfigurationSection("Compound.pity.threshold-by-star");
        if (section == null || section.getKeys(false).isEmpty()) {
            return fallback;
        }
        // 逐键取值：段本身非空，所以这里不会踩「凭空造空段」那个坑。
        return section.getInt(String.valueOf(star), fallback);
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
        Long last = this.lastCompoundAt.get(p.getUniqueId());
        if (last != null && now - last < cooldown) {
            return false;
        }
        this.lastCompoundAt.put(p.getUniqueId(), now);
        return true;
    }

    /** 玩家退服时摘掉他的冷却记录（这张表不再需要按名字无限增长）。 */
    public void forgetCompoundCooldown(UUID uuid) {
        if (uuid != null) {
            this.lastCompoundAt.remove(uuid);
        }
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
        java.util.Date now = new java.util.Date();
        final String day = new java.text.SimpleDateFormat("yyyy-MM-dd").format(now);
        final String time = new java.text.SimpleDateFormat("HH:mm:ss").format(now);
        final String line = time + "\t" + playerName + "\t" + star + "\t"
                + (sticker == null ? "-" : sticker) + "\t" + (success ? "成功" : "失败")
                + "\t" + spent + "\t" + refunded + "\t" + (detail == null ? "-" : detail);

        // 落盘交给单线程写队列 —— 原来这里是「主线程 new FileOutputStream + 写 + close」，
        // 每次合成都是一次 open/write/close 的系统调用。这里有个尴尬的不对称：
        // 我们花大力气用 write-behind 把 MySQL 的网络往返挪出了主线程，却把文件 IO 留在了主线程。
        // 用同一个写队列（而不是另一个线程池）是为了保证「同一条流水的顺序」，
        // 也避免了关服时多一处需要单独排空的地方。
        final File dir = this.compoundLogDir;
        final LogWriter writer = this.logWriter;
        if (writer != null) {
            Async.write(new Runnable() {
                @Override
                public void run() {
                    writer.append(dir, day, line);
                }
            });
        }

        // 同一行再塞进内存索引，而且是**同步**追加 ——「刚合完敲 /pu history 立刻就能看到」
        // 这个语义不能因为落盘改成异步就变。用 parse 而不是手工构造 Record：
        // 格式只在这一个地方定义，写入侧和读取侧因此不可能漂移。
        // 文件写失败时索引仍然收下它 —— 索引是给玩家看「我刚才合成了什么」的，
        // 磁盘上的那份才是审计凭证。
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
     * 新增（A2）：玩家的保底进度是在哪个原料星级档上积累的（0 = 未知 / 不限定档位）。
     *
     * <p>0 不是「没档位」的意思，而是<b>不知道</b>：它来自旧数据（升级前那一列还不存在）
     * 或测试替身直接写入的计数。判定时把 0 当作「任意档位都算」，这样升级不会把
     * 老玩家攒了一半的保底进度一笔勾销。</p>
     */
    public int getFailStar(Player p) {
        YSData data = this.ysdatas.get(p.getName());
        return data == null ? 0 : data.getFailStar();
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

    /**
     * 把一张合成券还给玩家（开关见 {@code Compound.refund.voucher-on-failure}）。
     *
     * <p><b>为什么需要它</b>：用券合成时 {@code money} 恒为 0，于是失败补偿里
     * 「返还 x% 金币」那一条对用券的玩家完全不生效（{@code moneyRefund > 0} 永远不成立）。
     * 净效果是券 + 粘剂 + 两只精灵全部损失、一无所获 —— 而用金币失败至少还能拿回 20%。
     * 券是玩家用真金白银换来的（活动 / 礼包 / 补偿），这个不对等没有道理。</p>
     *
     * @param voucher 消耗之前留下的券快照（数量会被规整为 1）
     * @return 给玩家的说明文案；{@code null} 表示没有退还任何东西
     */
    public String refundVoucher(Player p, ItemStack voucher) {
        if (voucher == null) {
            return null;
        }
        try {
            ItemStack item = voucher.clone();
            item.setAmount(1);
            java.util.Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
            if (leftover == null || leftover.isEmpty()) {
                return "合成券已退还";
            }
            // 背包塞不下：掉在脚下而不是凭空消失 —— 玩家的资产不该因为背包满就蒸发。
            for (ItemStack drop : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), drop);
            }
            return "背包已满, 合成券已掉落在你脚下";
        } catch (Throwable t) {
            this.getLogger().warning("退还合成券失败: " + t);
            return null;
        }
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

    /**
     * 用合成券合成失败时，是否把券退还给玩家。
     *
     * <p>默认开。理由见 {@link #refundVoucher}：用券时金币消耗为 0，于是原有的
     * 「返还 x% 金币」这条对用券玩家完全不生效，不退还就等于券白烧。</p>
     */
    public boolean isVoucherRefundEnabled() {
        return this.getConfig().getBoolean("Compound.refund.voucher-on-failure", true);
    }

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

    // ================================================================ 电脑盒子选料

    /**
     * 是否允许从电脑盒子取合成材料（{@code Compound.pc.enabled}）。
     *
     * <p><b>注意这是个会改变玩法的开关</b>：关掉之后，材料只能从队伍出，
     * 于是「一次能合几只」被 6 格队伍天然限制住 —— 那本身就是一道节奏阀。
     * 默认开，是因为开着才符合「把收藏品拿出来升星」的直觉；
     * 想按原来的节奏走就把它关掉。</p>
     */
    public boolean isPcSelectionEnabled() {
        return this.getConfig().getBoolean("Compound.pc.enabled", true);
    }

    /** 产出预览每页显示多少项。 */
    public int getPreviewPageSize() {
        int size = this.getConfig().getInt("Compound.preview.page-size", 45);
        return size < 9 ? 9 : (size > 54 ? 54 : size);
    }

    /**
     * 某星级档位（成品星级，1~7）的全部可能产出，取自真实 PokeStar。
     *
     * <p><b>这是「产出池」的唯一入口</b> —— 主界面预览、预览图标、合成时的空池防护、
     * 启动自检全部走它。以前每处各写一遍 {@code new PokeStarAPI().getStarPokemon(...)}，
     * 于是同一份数据在一次合成里被扫了好几遍（每次都是整张星级表线性扫描）。</p>
     *
     * <p>返回的是<b>不可变视图</b>，调用方只读；缓存键是档位（1~7），空间固定，
     * 不需要上限保护。失效点与星级缓存同一个（{@link #clearStarCache()}）。</p>
     *
     * <p>空池<b>不入缓存</b>：PokeStar 尚未就绪、或 stars.yml 某一档写坏时都会返回空，
     * 把它固化下来会让这一档永远合不了（只有重启才恢复）。空池每次重新问一次，
     * 代价只在这种异常路径上。</p>
     */
    public List<String> getPreviewPool(int level) {
        Integer key = Integer.valueOf(level);
        List<String> cached = this.poolCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<String> pool;
        try {
            List<String> raw = this.starApi.getStarPokemon(level);
            // 复制一份：PokeStar 返回的可能是它自己的内部列表，不能直接当缓存引用出去。
            pool = raw == null ? new ArrayList<String>() : new ArrayList<String>(raw);
        } catch (Throwable t) {
            pool = new ArrayList<String>();
        }
        if (pool.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> immutable = Collections.unmodifiableList(pool);
        List<String> raced = this.poolCache.putIfAbsent(key, immutable);
        return raced != null ? raced : immutable;
    }

    // ================================================================ 星级查询（带缓存）
    //
    // 为什么要缓存：PokeStarAPI#getPokemonStar 内部是
    //   STAR_DATA.stream().filter(...).map(...).findAny()
    // 也就是「每个种族都把整张星级表线性扫一遍」。而界面渲染是**逐格**调用它的：
    // 电脑盒子选料一页 30 格、产出预览一页 45 格，翻一次箱/页就是几十次全表扫描。
    // 单次几微秒看着无所谓，叠在一页 30 格上就是一次肉眼可见的顿挫。
    //
    // 安全边界：
    //   · 数据源是 PokeStar 的 stars.yml，只在 /pu reload（进而 PokeStar reload）时变化 ——
    //     所以用 reload 作为失效点，和插件里其它配置缓存完全一致；
    //   · 键是种族名，取值空间由 EnumSpecies 决定（千级），再加一个上限兜住
    //     「有人拿任意字符串调它」这种不可能但也不必赌的情况；
    //   · 用 ConcurrentHashMap 而不是 HashMap：不赌调用线程，反正代价可以忽略。

    /** 种族名 -> 星级（PokeStar 的 stars.yml 投影）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> starCache =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    /** 星级 -> 显示名（如「一星」）。空串表示 PokeStar 没配这一档。 */
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> starDisplayCache =
            new java.util.concurrent.ConcurrentHashMap<Integer, String>();

    /**
     * {@code /pu g|take} 单次允许变更的粘剂数量上限。
     *
     * <p>不是业务限额，而是<b>防整数溢出</b>：粘剂在存储层是 INT，且 {@code addSticker}
     * 走「读改写」。正常运营不会单次发到百万，取这个数只是把「不可能出现的输入」
     * 关在门外，同时留足余量给任何合理的批量补偿。</p>
     */
    private static final int MAX_STICKER_AMOUNT = 1000000;

    /** 星级缓存条目上限。种族总数远小于它，超限只会出现在异常调用下。 */
    private static final int STAR_CACHE_LIMIT = 4096;

    /**
     * 成品星级（1~7）-> 该档位的产出池（不可变）。
     *
     * <p>键空间固定 7 个，没有增长风险；空池不写入，理由见 {@link #getPreviewPool(int)}。</p>
     */
    private final java.util.concurrent.ConcurrentHashMap<Integer, List<String>> poolCache =
            new java.util.concurrent.ConcurrentHashMap<Integer, List<String>>();

    /** 复用的 API 实例（构造是空实现，但没必要每次 new）。 */
    private final PokeStarAPI starApi = new PokeStarAPI();

    /** 查询某种族属于几星（0 = PokeStar 未收录）。结果带缓存，见上方说明。 */
    public int getPokemonStar(String species) {
        if (species == null) {
            return 0;
        }
        Integer cached = this.starCache.get(species);
        if (cached != null) {
            return cached.intValue();
        }
        int star;
        try {
            star = this.starApi.getPokemonStar(species);
        } catch (Throwable t) {
            // 查不到就当 0（0 星在合成链路上是不可合成的守卫值），但不缓存 ——
            // 这可能是 PokeStar 正在 reload 的瞬态，别把一次失败永久固化下来。
            return 0;
        }
        if (this.starCache.size() >= STAR_CACHE_LIMIT) {
            this.starCache.clear();
        }
        this.starCache.put(species, Integer.valueOf(star));
        return star;
    }

    /** 星级的显示名（PokeStar 未配置时返回空串，调用方不必判空）。 */
    public String getStarDisplay(int star) {
        String cached = this.starDisplayCache.get(Integer.valueOf(star));
        if (cached != null) {
            return cached;
        }
        String name;
        try {
            String raw = this.starApi.getPokeShowName(star);
            name = raw == null ? "" : raw;
        } catch (Throwable t) {
            return "";
        }
        this.starDisplayCache.put(Integer.valueOf(star), name);
        return name;
    }

    /**
     * 清空星级相关缓存。
     *
     * <p>调用点只有两处：{@link #checkStarPools()}（启用与 {@code /pu reload} 都会走）
     * 和关服收尾。PokeStar 的 stars.yml 本身没有热重载通知，所以「改完 stars.yml 就
     * {@code /pu reload}」是本插件对运维的既有约定，缓存跟着它失效正好。</p>
     */
    public void clearStarCache() {
        this.starCache.clear();
        this.starDisplayCache.clear();
        // 产出池与星级同源（都来自 PokeStar 的 stars.yml），同样一起失效。
        this.poolCache.clear();
        // 产出预览的图标完全由 stars.yml 决定，跟星级缓存同源同命，一起失效。
        PreviewIcons.invalidate();
    }

    /**
     * 启动（与 `/pu reload`）时的产出池自检。
     *
     * <p>PokeStar 自带模板里「四星」曾把 {@code star} 写成 {@code 4m}，被 {@code getInt()}
     * 读成 0 之后整档丢失。这个错误在玩家合成前完全不可见，事后也只表现为一条
     * {@code IndexOutOfBoundsException}。与其等玩家撞上，不如开机就说清楚。</p>
     */
    public void checkStarPools() {
        // 自检本身要读的就是「当前生效的 stars.yml」，顺手把星级缓存清掉 ——
        // 启用与 /pu reload 都会走这里，正好是这套缓存唯一的失效点。
        this.clearStarCache();
        // 顺带把 7 个档位的产出池灌进缓存：紧接着的界面渲染与合成判定都直接命中，
        // 不必再各扫一遍整张星级表。
        List<String> empty = new ArrayList<String>();
        for (int level = 1; level <= 7; level++) {
            if (this.getPreviewPool(level).isEmpty()) {
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

    // ================================================================ 第四轮新增：属性继承
    // 这一节与下一节的读法完全统一：全部走「双参读取 + 代码内字面量默认值」，
    // 且字面量与出厂 config.yml 逐位一致。于是「只换 jar、沿用旧 config.yml」的服
    // 也能拿到完整的新玩法，而不是静默失效。

    /** 属性继承总开关。关闭后继承设置入口不再出现。 */
    public boolean isInheritEnabled() {
        return this.getConfig().getBoolean("Compound.inherit.enabled", DEFAULT_INHERIT_ENABLED);
    }

    /** 一次合成最多能同时继承几项。0 视为关闭继承。 */
    public int getInheritMaxPicks() {
        int picks = this.getConfig().getInt("Compound.inherit.max-picks", DEFAULT_INHERIT_MAX_PICKS);
        return Math.max(picks, 0);
    }

    public int getInheritIvMinPercent() {
        return this.getConfig().getInt("Compound.inherit.iv-percent.min", DEFAULT_INHERIT_IV_MIN);
    }

    public int getInheritIvMaxPercent() {
        return this.getConfig().getInt("Compound.inherit.iv-percent.max", DEFAULT_INHERIT_IV_MAX);
    }

    public int getInheritShinySingleBonus() {
        return this.getConfig().getInt("Compound.inherit.shiny.single-bonus", DEFAULT_INHERIT_SHINY_SINGLE);
    }

    public int getInheritShinyDoubleBonus() {
        return this.getConfig().getInt("Compound.inherit.shiny.double-bonus", DEFAULT_INHERIT_SHINY_DOUBLE);
    }

    // ================================================================ 第四轮新增：洗练

    public boolean isRerollEnabled() {
        return this.getConfig().getBoolean("Compound.reroll.enabled", DEFAULT_REROLL_ENABLED);
    }

    public int getRerollPrice() {
        return this.getConfig().getInt("Compound.reroll.price", DEFAULT_REROLL_PRICE);
    }

    public String getRerollStickerType() {
        return this.getConfig().getString("Compound.reroll.sticker-type", DEFAULT_REROLL_STICKER);
    }

    public int getRerollStickerAmount() {
        return this.getConfig().getInt("Compound.reroll.sticker-amount", DEFAULT_REROLL_STICKER_AMOUNT);
    }

    public int getRerollIvMin() {
        return this.getConfig().getInt("Compound.reroll.iv-min", DEFAULT_REROLL_IV_MIN);
    }

    public int getRerollIvMax() {
        return this.getConfig().getInt("Compound.reroll.iv-max", DEFAULT_REROLL_IV_MAX);
    }

    public boolean isRerollImproveOnly() {
        return this.getConfig().getBoolean("Compound.reroll.improve-only", DEFAULT_REROLL_IMPROVE_ONLY);
    }

    // ================================================================ 第四轮新增：分解回收

    public boolean isSalvageEnabled() {
        return this.getConfig().getBoolean("Compound.salvage.enabled", DEFAULT_SALVAGE_ENABLED);
    }

    /** 分解一只 star 星精灵返还的粘剂数量。 */
    public int getSalvageSticker(int star) {
        int fallback = star >= 0 && star < DEFAULT_SALVAGE_STICKER.length ? DEFAULT_SALVAGE_STICKER[star] : 0;
        return this.getConfig().getInt("Compound.salvage.sticker." + star, fallback);
    }

    public String getSalvageStickerType() {
        return this.getConfig().getString("Compound.salvage.sticker-type", DEFAULT_SALVAGE_STICKER_TYPE);
    }

    public int getSalvageMoneyPercent() {
        return this.getConfig().getInt("Compound.salvage.money-percent", DEFAULT_SALVAGE_MONEY_PERCENT);
    }

    /** 分解一只 star 星精灵返还的金币（按该星级合成价格的百分比算）。 */
    public int getSalvageMoney(int star) {
        return (int) ((long) this.getCompoundPrice(star) * this.getSalvageMoneyPercent() / 100L);
    }

    // ================================================================ 第四轮新增：粘剂合成链

    public boolean isStickerCraftEnabled() {
        return this.getConfig().getBoolean("Compound.sticker-craft.enabled", DEFAULT_STICKER_CRAFT_ENABLED);
    }

    /** 粘剂合成配方（惰性加载 + 缓存，{@code /pu reload} 时失效）。 */
    public List<StickerRecipe> getStickerRecipes() {
        if (this.stickerRecipes == null) {
            this.stickerRecipes = this.loadStickerRecipes();
        }
        return this.stickerRecipes;
    }

    public void invalidateStickerRecipes() {
        this.stickerRecipes = null;
    }

    private List<StickerRecipe> loadStickerRecipes() {
        List<StickerRecipe> out = new ArrayList<StickerRecipe>();
        List<Map<?, ?>> raw = this.getConfig().getMapList("Compound.sticker-craft.recipes");
        if (raw.isEmpty()) {
            // 出厂默认：3 个普通粘剂 + 5000 金币 -> 1 个精良粘剂
            out.add(new StickerRecipe("PT", "JL", 3, 1, 5000));
            return out;
        }
        for (Map<?, ?> entry : raw) {
            String from = stringOf(entry.get("from"), null);
            String to = stringOf(entry.get("to"), null);
            int amount = intOf(entry.get("amount"), 0);
            int result = intOf(entry.get("result"), 0);
            int money = intOf(entry.get("money"), 0);
            StickerRecipe recipe = new StickerRecipe(from, to, amount, result, money);
            if (recipe.isValid()) {
                out.add(recipe);
            } else {
                this.getLogger().warning("Compound.sticker-craft.recipes 里有一条配方字段不完整，已跳过: "
                        + from + " -> " + to + " (amount=" + amount + ", result=" + result + ", money=" + money + ")");
            }
        }
        return out;
    }

    // ================================================================ 第四轮新增：每日委托

    public boolean isQuestEnabled() {
        return this.getConfig().getBoolean("Compound.quest.enabled", DEFAULT_QUEST_ENABLED);
    }

    public int getQuestDailyAmount() {
        return Math.max(this.getConfig().getInt("Compound.quest.daily-amount", DEFAULT_QUEST_AMOUNT), 0);
    }

    /**
     * 委托记录保留多少天（含今天）。{@code 0} = 不清理。
     *
     * <p>委托表是「每个玩家每天 3 行、只增不减」，而查询永远只看当天。默认保留 7 天：
     * 足够运维回头查「这玩家的委托是不是没刷新」，又不至于让它无限长。</p>
     */
    public int getQuestKeepDays() {
        return Math.max(this.getConfig().getInt("Compound.quest.keep-days", DEFAULT_QUEST_KEEP_DAYS), 0);
    }

    /**
     * 删掉超出保留期的委托行，返回删除行数。
     *
     * <p>截止日按「保留最近 N 天（含今天）」算：{@code keep-days = 7} 时，删掉
     * {@code day < 今天-6} 的行。用 {@code LocalDate} 而不是手工减毫秒 —— 跨夏令时 /
     * 闰秒的边界上手工算术会算错一天，而这里只是维护动作，不值得引入这种风险。</p>
     */
    public int purgeOldQuests() {
        if (this.storage == null) {
            return 0;
        }
        int keep = this.getQuestKeepDays();
        if (keep <= 0) {
            return 0;
        }
        String cutoff;
        try {
            cutoff = java.time.LocalDate.now().minusDays(keep - 1L).toString();
        } catch (Throwable t) {
            this.getLogger().warning("计算委托清理截止日失败，本次跳过: " + t);
            return 0;
        }
        return this.storage.purgeQuestsBefore(cutoff);
    }

    /** 委托类型定义（只保留 weight > 0 的），惰性加载 + 缓存。 */
    public List<QuestType> getQuestTypes() {
        if (this.questTypes == null) {
            this.questTypes = this.loadQuestTypes();
        }
        return this.questTypes;
    }

    public void invalidateQuestTypes() {
        this.questTypes = null;
    }

    private List<QuestType> loadQuestTypes() {
        List<QuestType> out = new ArrayList<QuestType>();
        org.bukkit.configuration.ConfigurationSection section =
                this.getConfig().getConfigurationSection("Compound.quest.types");
        for (int i = 0; i < DEFAULT_QUEST_KEYS.length; i++) {
            String key = DEFAULT_QUEST_KEYS[i];
            if (section == null) {
                // 整段缺失：用出厂默认（管理员把 quest 段删了也仍然可用）
                out.add(new QuestType(key, DEFAULT_QUEST_WEIGHTS[i],
                        parseIntList(DEFAULT_QUEST_TARGETS[i], null),
                        DEFAULT_QUEST_SUBMIT_STAR, DEFAULT_QUEST_DISPLAYS[i]));
                continue;
            }
            // 注意这里是单参 get：它才看得到 jar 内置的出厂值，双参看不到。
            Object rawWeight = section.get(key + ".weight");
            Object rawTargets = section.get(key + ".targets");
            Object rawStar = section.get(key + ".star");
            Object rawDisplay = section.get(key + ".display");
            int weight = rawWeight == null ? DEFAULT_QUEST_WEIGHTS[i] : intOf(rawWeight, DEFAULT_QUEST_WEIGHTS[i]);
            int star = rawStar == null ? DEFAULT_QUEST_SUBMIT_STAR : intOf(rawStar, DEFAULT_QUEST_SUBMIT_STAR);
            String display = rawDisplay == null ? DEFAULT_QUEST_DISPLAYS[i] : String.valueOf(rawDisplay);
            List<Integer> targets = rawTargets == null
                    ? parseIntList(DEFAULT_QUEST_TARGETS[i], null)
                    : parseIntList(null, rawTargets);
            out.add(new QuestType(key, weight, targets, star, display));
        }
        List<QuestType> enabled = new ArrayList<QuestType>();
        for (QuestType type : out) {
            if (type.getWeight() > 0) {
                enabled.add(type);
            }
        }
        return enabled;
    }

    /** 把 {@code "2,3,5"} 或一个 {@code List} 解析成整数列表。 */
    private static List<Integer> parseIntList(String csv, Object rawList) {
        List<Integer> out = new ArrayList<Integer>();
        if (rawList instanceof List<?>) {
            for (Object item : (List<?>) rawList) {
                int value = intOf(item, Integer.MIN_VALUE);
                if (value != Integer.MIN_VALUE) {
                    out.add(value);
                }
            }
        } else if (csv != null) {
            for (String part : csv.split(",")) {
                int value = parseInt(part, Integer.MIN_VALUE);
                if (value != Integer.MIN_VALUE) {
                    out.add(value);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(1);
        }
        return out;
    }

    public int getQuestRewardMoney() {
        return this.getConfig().getInt("Compound.quest.reward.money", DEFAULT_QUEST_REWARD_MONEY);
    }

    public String getQuestRewardSticker() {
        return this.getConfig().getString("Compound.quest.reward.sticker-type", DEFAULT_QUEST_REWARD_STICKER);
    }

    public int getQuestRewardStickerAmount() {
        return this.getConfig().getInt("Compound.quest.reward.sticker-amount", DEFAULT_QUEST_REWARD_STICKER_AMOUNT);
    }

    /**
     * 今天的日期标识（{@code yyyy-MM-dd}）。
     *
     * <p>用服务端本地时区而不是 UTC —— 玩家感知到的「今天」是本地时间的今天，
     * 用 UTC 会让每天的刷新发生在半夜之外的奇怪时刻。</p>
     */
    public String today() {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    }

    /**
     * 取某个玩家今天的委托面板。
     *
     * <p>库里还没有今天的记录时才生成并落库 —— 委托是「当天固定」的，
     * 生成算法只依赖 (UUID, 日期) 所以结果稳定，落库只是为了让进度有地方存。</p>
     *
     * <p>顺序刻意是「先查库、再生成」：这个方法的主要调用方是 {@link #addQuestProgress}，
     * 也就是<b>每次合成都走一遍</b>。原实现不管库里有没有都先跑一次加权随机生成一整组委托，
     * 再在「库里已有」时把刚算出来的那组丢掉 —— 白算一次随机，纯浪费。</p>
     */
    public List<QuestData> getQuestBoard(Player p) {
        String day = this.today();
        if (this.storage == null) {
            return QuestData.generate(p.getUniqueId(), day,
                    this.getQuestTypes(), this.getQuestDailyAmount());
        }
        List<QuestData> stored = this.storage.getQuests(p.getUniqueId(), day);
        if (!stored.isEmpty()) {
            return stored;
        }
        List<QuestData> generated = QuestData.generate(p.getUniqueId(), day,
                this.getQuestTypes(), this.getQuestDailyAmount());
        this.storage.insertQuests(p.getUniqueId(), p.getName(), day, generated);
        stored = this.storage.getQuests(p.getUniqueId(), day);
        // 落库失败（或者这一瞬间数据库有问题）时也别让界面空着
        return stored.isEmpty() ? generated : stored;
    }

    /**
     * 累加某类委托的进度。
     *
     * <p>一次操作可能同时推进多条同类委托（例如两条都是「成功合成」），所以这里遍历全部
     * 而不是找到第一条就返回。已领取或已满的委托不再累加，避免进度无限增长之后
     * 界面显示「7 / 3」这种数字。</p>
     */
    public void addQuestProgress(Player p, String type, int delta) {
        this.addQuestProgress(p, new String[]{type}, delta);
    }

    /**
     * 一次推进<b>多类</b>委托的进度。
     *
     * <p>存在的理由是「少一次往返」：一次成功合成会同时推进「成功合成」与「尝试合成」
     * 两条委托，原实现是调两次上面的单类版本 —— 于是同一个 tick 内把今天的委托面板
     * 从库里读了两遍。这里只读一遍、把要改的槽位攒起来、一次批量写回。</p>
     *
     * <p>顺序上仍然是「先读后写」，语义与单类版本逐字一致：跳过已领取 / 已完成的，
     * 进度封顶到 target，没有实际变化的槽位不写。</p>
     */
    public void addQuestProgress(Player p, String[] types, int delta) {
        if (!this.isQuestEnabled() || delta <= 0 || types == null || types.length == 0) {
            return;
        }
        String day = this.today();
        List<QuestData> board = this.getQuestBoard(p);
        Map<Integer, Integer> updates = new LinkedHashMap<Integer, Integer>();
        for (QuestData quest : board) {
            if (quest.isClaimed() || quest.isComplete() || !containsType(types, quest.getType())) {
                continue;
            }
            int next = Math.min(quest.getProgress() + delta, quest.getTarget());
            if (next == quest.getProgress()) {
                continue;
            }
            // 就地更新内存对象：界面正开着时下次重绘读的就是这份 board。
            quest.setProgress(next);
            updates.put(quest.getSlot(), next);
        }
        if (updates.isEmpty() || this.storage == null) {
            return;
        }
        this.storage.setQuestProgressBatch(p.getUniqueId(), day, updates);
    }

    private static boolean containsType(String[] types, String type) {
        if (type == null) {
            return false;
        }
        for (String candidate : types) {
            if (type.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 交付类委托：把选中的精灵交给系统。
     *
     * @return 交付是否成功（成功时已完成委托并发放奖励）
     */
    public boolean submitQuest(Player p, int questSlot, int partySlot) {
        PlayerPartyStorage pps = Pixelmon.storageManager.getParty(p.getUniqueId());
        Pokemon pokemon = pps == null ? null : pps.get(partySlot);
        if (pokemon == null) {
            p.sendMessage("§3精灵合成 §f|| §c所选精灵已不存在!");
            return false;
        }
        QuestData target = null;
        for (QuestData quest : this.getQuestBoard(p)) {
            if (quest.getSlot() == questSlot) {
                target = quest;
                break;
            }
        }
        if (target == null || !QuestType.SUBMIT.equals(target.getType())) {
            p.sendMessage("§3精灵合成 §f|| §c该委托不存在或不是交付类委托!");
            return false;
        }
        if (target.isClaimed()) {
            p.sendMessage("§3精灵合成 §f|| §c这条委托已经领取过了!");
            return false;
        }
        int star = this.getPokemonStar(pokemon.getSpecies().name());
        if (star < target.getStar()) {
            p.sendMessage("§3精灵合成 §f|| §c这条委托需要 " + starName(target.getStar())
                    + " 及以上的精灵, 你选的是 " + starName(star) + "!");
            return false;
        }

        // 先扣精灵再发奖。反过来的话，发奖过程中一旦出异常，玩家可以靠重复交付白拿奖励。
        pps.set(partySlot, null);
        p.closeInventory();
        target.setProgress(target.getTarget());
        if (this.storage != null) {
            this.storage.setQuestProgress(p.getUniqueId(), this.today(), questSlot, target.getTarget());
        }
        this.grantQuestReward(p);
        this.markClaimed(p, target);
        p.sendMessage("§3精灵合成 §f|| §a已交付 " + starName(star) + " 精灵, 委托完成并领取奖励!");
        this.addQuestProgress(p, QuestType.SUBMIT, 0);
        return true;
    }

    /**
     * 领取一条已经达标的委托。
     *
     * @return 是否真的领到了（false 时已发过对应提示）
     */
    public boolean claimQuest(Player p, int questSlot) {
        QuestData target = null;
        for (QuestData quest : this.getQuestBoard(p)) {
            if (quest.getSlot() == questSlot) {
                target = quest;
                break;
            }
        }
        if (target == null) {
            p.sendMessage("§3精灵合成 §f|| §c该委托不存在!");
            return false;
        }
        if (QuestType.SUBMIT.equals(target.getType())) {
            // 交付类不能空手领 —— 否则「交付一只四星精灵」可以靠点一下按钮白拿。
            p.sendMessage("§3精灵合成 §f|| §c这条是交付类委托, 请在界面上选择要交付的精灵!");
            return false;
        }
        if (target.isClaimed()) {
            p.sendMessage("§3精灵合成 §f|| §c这条委托已经领取过了!");
            return false;
        }
        if (!target.isComplete()) {
            p.sendMessage("§3精灵合成 §f|| §c这条委托还没完成 (§f"
                    + target.getProgress() + "§c/§f" + target.getTarget() + "§c)!");
            return false;
        }
        this.grantQuestReward(p);
        this.markClaimed(p, target);
        p.sendMessage("§3精灵合成 §f|| §a委托奖励已领取!");
        return true;
    }

    private void markClaimed(Player p, QuestData quest) {
        quest.setClaimed(true);
        if (this.storage != null) {
            this.storage.setQuestClaimed(p.getUniqueId(), this.today(), quest.getSlot());
        }
    }

    private void grantQuestReward(Player p) {
        int money = this.getQuestRewardMoney();
        String stickerKey = this.getQuestRewardSticker();
        int stickerAmount = this.getQuestRewardStickerAmount();
        if (money > 0 && this.economy != null) {
            this.economy.depositPlayer((OfflinePlayer) p, (double) money);
        }
        StickerType sticker = this.getStickerType(stickerKey);
        if (sticker != null && stickerAmount > 0) {
            YSData ysd = this.ysdatas.get(p.getName());
            if (ysd != null) {
                ysd.deposit(stickerKey, stickerAmount);
            }
        }
        StringBuilder msg = new StringBuilder("§3精灵合成 §f|| §a获得委托奖励: ");
        boolean any = false;
        if (money > 0) {
            msg.append("§f").append(money).append(" §a金币");
            any = true;
        }
        if (sticker != null && stickerAmount > 0) {
            msg.append(any ? " §a+ " : "").append(stickerAmount).append(" 个").append(sticker.getDisplay());
        }
        p.sendMessage(msg.toString());
    }

    // ================================================================ 第四轮新增：合成图鉴

    public boolean isDexEnabled() {
        return this.getConfig().getBoolean("Compound.dex.enabled", DEFAULT_DEX_ENABLED);
    }

    /** 一个图鉴里程碑档位及其奖励。 */
    public static class DexReward {
        public final int milestone;
        public final int money;
        public final String sticker;
        public final int stickerAmount;

        DexReward(int milestone, int money, String sticker, int stickerAmount) {
            this.milestone = milestone;
            this.money = money;
            this.sticker = sticker;
            this.stickerAmount = stickerAmount;
        }
    }

    /** 全部图鉴里程碑（按门槛升序）。 */
    public List<DexReward> getDexRewards() {
        List<DexReward> out = new ArrayList<DexReward>();
        org.bukkit.configuration.ConfigurationSection section =
                this.getConfig().getConfigurationSection("Compound.dex.rewards");
        // 修复：这里原来的判定是 `section == null`，挡不住「磁盘上的 config.yml 早于本功能」的情况。
        // Bukkit 的 MemorySection#getConfigurationSection 在「本段没有这个键、但 jar 内置 config.yml 有」时，
        // 会走 get(path, getDefault(path)) 拿到内置段，再 createSection(path) 造一个**空段**返回 ——
        // 非 null、但 getKeys() 为空。于是奖励表静默变空：图鉴界面「奖励档位: 0 个」，
        // 里程碑格子全成灰玻璃，玩家点了没反应。（字节码已逐条核对：getDefault → get → createSection）
        // 所以必须把「空段」也当成「没配」，否则老服的默认奖励永远读不出来。
        if (section == null || section.getKeys(false).isEmpty()) {
            for (int i = 0; i < DEFAULT_DEX_MILESTONES.length; i++) {
                out.add(new DexReward(DEFAULT_DEX_MILESTONES[i], DEFAULT_DEX_MONEY[i],
                        DEFAULT_DEX_STICKERS[i], DEFAULT_DEX_STICKER_AMOUNTS[i]));
            }
        } else {
            for (String key : section.getKeys(false)) {
                int milestone = parseInt(key, -1);
                if (milestone <= 0) {
                    continue;
                }
                Object rawMoney = section.get(key + ".money");
                Object rawSticker = section.get(key + ".sticker-type");
                Object rawStickerAmount = section.get(key + ".sticker-amount");
                out.add(new DexReward(milestone,
                        rawMoney == null ? 0 : intOf(rawMoney, 0),
                        rawSticker == null ? null : String.valueOf(rawSticker),
                        rawStickerAmount == null ? 1 : intOf(rawStickerAmount, 1)));
            }
            Collections.sort(out, new java.util.Comparator<DexReward>() {
                @Override
                public int compare(DexReward a, DexReward b) {
                    return Integer.compare(a.milestone, b.milestone);
                }
            });
        }
        return out;
    }

    /** 记录一次合成产出（图鉴按「产出过的种族」收录）。 */
    public void recordDex(Player p, String species) {
        if (!this.isDexEnabled() || this.storage == null || species == null || species.isEmpty()) {
            return;
        }
        this.storage.dexAdd(p.getUniqueId(), p.getName(), species);
    }

    /** 图鉴里已收录的种族数。 */
    public int getDexSpeciesCount(Player p) {
        return this.storage == null ? 0 : this.storage.dexSpeciesCount(p.getUniqueId());
    }

    /** 图鉴里某个种族的产出次数。 */
    public int getDexAmount(Player p, String species) {
        return this.storage == null ? 0 : this.storage.dexAmount(p.getUniqueId(), species);
    }

    /** 图鉴内容快照（种族 -> 产出次数）。 */
    public Map<String, Integer> getDexAll(Player p) {
        return this.storage == null
                ? new LinkedHashMap<String, Integer>() : this.storage.dexAll(p.getUniqueId());
    }

    /** 已领取的图鉴里程碑。 */
    public Set<Integer> getDexClaimed(Player p) {
        return this.storage == null
                ? new java.util.HashSet<Integer>() : this.storage.dexClaimed(p.getUniqueId());
    }

    /**
     * 领取一个图鉴里程碑奖励。
     *
     * @return 是否领取成功
     */
    public boolean claimDexMilestone(Player p, int milestone) {
        if (this.storage == null) {
            p.sendMessage("§3精灵合成 §f|| §c存储尚未就绪, 无法领取!");
            return false;
        }
        int have = this.getDexSpeciesCount(p);
        if (have < milestone) {
            p.sendMessage("§3精灵合成 §f|| §c图鉴还差 §f" + (milestone - have)
                    + " §c种精灵才能领取这个奖励!");
            return false;
        }
        // 判重交给数据库主键：玩家连点两下时两次调用在同一 tick 内依次执行，
        // 「先查后写」会被绕过，INSERT OR IGNORE 不会。
        if (!this.storage.dexClaim(p.getUniqueId(), p.getName(), milestone)) {
            p.sendMessage("§3精灵合成 §f|| §c这个图鉴奖励已经领取过了!");
            return false;
        }
        for (DexReward reward : this.getDexRewards()) {
            if (reward.milestone != milestone) {
                continue;
            }
            if (reward.money > 0 && this.economy != null) {
                this.economy.depositPlayer((OfflinePlayer) p, (double) reward.money);
            }
            StickerType sticker = this.getStickerType(reward.sticker);
            if (sticker != null && reward.stickerAmount > 0) {
                YSData ysd = this.ysdatas.get(p.getName());
                if (ysd != null) {
                    ysd.deposit(reward.sticker, reward.stickerAmount);
                }
            }
            StringBuilder msg = new StringBuilder("§3精灵合成 §f|| §a图鉴奖励已领取: ");
            if (reward.money > 0) {
                msg.append("§f").append(reward.money).append(" §a金币");
            }
            if (sticker != null && reward.stickerAmount > 0) {
                msg.append(reward.money > 0 ? " §a+ " : "")
                        .append(reward.stickerAmount).append(" 个").append(sticker.getDisplay());
            }
            p.sendMessage(msg.toString());
            break;
        }
        return true;
    }

    // ================================================================ 第四轮新增：粘剂扩展字段

    /** 该粘剂是否带「保护符」效果（合成失败时保留材料）。 */
    public boolean isStickerProtect(String key) {
        StickerType type = this.getStickerType(key);
        return type != null && type.isProtect();
    }

    /** 该粘剂的定向产出池；没有定向时返回空列表。 */
    public List<String> getStickerPool(String key) {
        StickerType type = this.getStickerType(key);
        return type == null ? Collections.<String>emptyList() : type.getPool();
    }

    // ================================================================ 第四轮新增：界面动作
    // 这三个方法都在界面里被点击触发，统一放在主类里 —— 它们要碰经济、要碰 SQLite、
    // 要写聊天栏提示，放在 PlayerData（只该管渲染与会话）里会让那个类变成第二个上帝类。

    /** 洗练用的随机源。界面与合成都在主线程，单实例足够。 */
    private final Random random = new Random();

    /**
     * 分解一只精灵，返还粘剂与金币。
     *
     * <p>顺序很关键：<b>先删精灵，再发东西</b>。反过来的话，发奖过程中一旦抛异常，
     * 玩家就白拿了一次返还而精灵还在。</p>
     *
     * @return 是否分解成功
     */
    public boolean salvagePokemon(Player p, int partySlot) {
        if (!this.isSalvageEnabled()) {
            p.sendMessage("§3精灵合成 §f|| §c分解回收功能已关闭!");
            return false;
        }
        PlayerPartyStorage pps = Pixelmon.storageManager.getParty(p.getUniqueId());
        Pokemon pokemon = pps == null ? null : pps.get(partySlot);
        if (pokemon == null) {
            p.sendMessage("§3精灵合成 §f|| §c所选精灵已不存在!");
            return false;
        }
        if (pokemon.isEgg()) {
            p.sendMessage("§3精灵合成 §f|| §c精灵蛋无法分解!");
            return false;
        }
        int star = this.getPokemonStar(pokemon.getSpecies().name());
        if (star < 1) {
            p.sendMessage("§3精灵合成 §f|| §c该精灵未被星级系统收录, 无法分解!");
            return false;
        }
        int money = this.getSalvageMoney(star);
        int stickerAmount = this.getSalvageSticker(star);
        StickerType sticker = this.getStickerType(this.getSalvageStickerType());

        pps.set(partySlot, null);

        if (money > 0 && this.economy != null) {
            this.economy.depositPlayer((OfflinePlayer) p, (double) money);
        }
        int granted = 0;
        if (sticker != null && stickerAmount > 0) {
            YSData ysd = this.ysdatas.get(p.getName());
            if (ysd != null) {
                ysd.deposit(sticker.getKey(), stickerAmount);
                granted = stickerAmount;
            }
        }

        StringBuilder msg = new StringBuilder("§3精灵合成 §f|| §a分解完成, 获得 §f")
                .append(money).append(" §a金币");
        if (granted > 0) {
            msg.append(" §a+ §f").append(granted).append(" §a个").append(sticker.getDisplay());
        }
        msg.append("！");
        p.sendMessage(msg.toString());
        this.playFeedback(p, "success");
        this.addQuestProgress(p, QuestType.SALVAGE, 1);
        return true;
    }

    /**
     * 洗练精灵的某一项属性。
     *
     * @param attr 0~5 = 六项个体值，{@code ATTR_NATURE} = 性格，{@code ATTR_ABILITY} = 特性
     * @return 是否洗练成功
     */
    public boolean rerollAttr(Player p, int partySlot, int attr) {
        if (!this.isRerollEnabled()) {
            p.sendMessage("§3精灵合成 §f|| §c洗练功能已关闭!");
            return false;
        }
        PlayerPartyStorage pps = Pixelmon.storageManager.getParty(p.getUniqueId());
        Pokemon pokemon = pps == null ? null : pps.get(partySlot);
        if (pokemon == null) {
            p.sendMessage("§3精灵合成 §f|| §c所选精灵已不存在!");
            return false;
        }

        int price = this.getRerollPrice();
        StickerType sticker = this.getStickerType(this.getRerollStickerType());
        int stickerAmount = sticker == null ? 0 : Math.max(this.getRerollStickerAmount(), 0);
        YSData ysd = this.ysdatas.get(p.getName());
        if (ysd == null) {
            p.sendMessage("§3精灵合成 §f|| §c粘剂数据尚未加载, 请重新进服后再试!");
            return false;
        }
        if (price > 0) {
            if (this.economy == null) {
                p.sendMessage("§3精灵合成 §f|| §c服务器经济服务不可用, 暂时无法洗练!");
                return false;
            }
            if (this.economy.getBalance((OfflinePlayer) p) < (double) price) {
                p.sendMessage("§3精灵合成 §f|| §c你没有足够的金币！");
                return false;
            }
        }
        if (stickerAmount > 0 && ysd.getAmount(sticker.getKey()) < stickerAmount) {
            p.sendMessage("§3精灵合成 §f|| §c你没有足够的" + sticker.getInsufficient());
            return false;
        }

        // 先扣费再洗炼，洗炼失败时退款 —— 顺序反过来会出现「洗成功了但扣费失败」，
        // 那种情况没法回滚（属性已经被改掉了），玩家等于白拿一次洗练。
        if (price > 0) {
            this.economy.withdrawPlayer((OfflinePlayer) p, (double) price);
        }
        if (stickerAmount > 0) {
            ysd.withdraw(sticker.getKey(), stickerAmount);
        }

        String detail = null;
        if (attr >= 0 && attr < PokemonAttr.IV_COUNT) {
            int value = PokemonAttr.rerollIv(pokemon, attr, this.getRerollIvMin(), this.getRerollIvMax(),
                    this.isRerollImproveOnly(), this.random);
            if (value >= 0) {
                detail = PokemonAttr.ivName(attr) + " §7-> §f" + value;
            }
        } else if (attr == PlayerData.ATTR_NATURE) {
            String nature = PokemonAttr.rerollNature(pokemon, this.random);
            if (nature != null) {
                detail = "性格 §7-> §f" + nature;
            }
        } else if (attr == PlayerData.ATTR_ABILITY) {
            String slot = PokemonAttr.rerollAbility(pokemon);
            if (slot != null) {
                detail = "特性 §7-> §f" + slot;
            }
        }

        if (detail == null) {
            // 洗练本身失败：把刚扣的费用原样退回去，不让玩家为一次没发生的事付费。
            if (price > 0 && this.economy != null) {
                this.economy.depositPlayer((OfflinePlayer) p, (double) price);
            }
            if (stickerAmount > 0) {
                ysd.deposit(sticker.getKey(), stickerAmount);
            }
            p.sendMessage("§3精灵合成 §f|| §c洗练失败, 本次消耗已全额退还(可能是该精灵属性不可读)!");
            return false;
        }

        p.sendMessage("§3精灵合成 §f|| §a洗练成功: " + detail + " §7(消耗 §f" + price + " §7金币"
                + (stickerAmount > 0 ? " + " + stickerAmount + " 个" + sticker.getDisplay() : "") + "§7)");
        this.playFeedback(p, "success");
        return true;
    }

    /**
     * 按配方合成粘剂。
     *
     * @param recipeIndex 配方下标（对应 {@link #getStickerRecipes()} 的顺序）
     * @return 是否合成成功
     */
    public boolean craftSticker(Player p, int recipeIndex) {
        if (!this.isStickerCraftEnabled()) {
            p.sendMessage("§3精灵合成 §f|| §c粘剂合成功能已关闭!");
            return false;
        }
        List<StickerRecipe> recipes = this.getStickerRecipes();
        if (recipeIndex < 0 || recipeIndex >= recipes.size()) {
            p.sendMessage("§3精灵合成 §f|| §c配方不存在!");
            return false;
        }
        StickerRecipe recipe = recipes.get(recipeIndex);
        StickerType from = this.getStickerType(recipe.getFrom());
        StickerType to = this.getStickerType(recipe.getTo());
        if (from == null || to == null) {
            p.sendMessage("§3精灵合成 §f|| §c配方引用了不存在的粘剂类型, 请检查配置!");
            return false;
        }
        YSData ysd = this.ysdatas.get(p.getName());
        if (ysd == null) {
            p.sendMessage("§3精灵合成 §f|| §c粘剂数据尚未加载, 请重新进服后再试!");
            return false;
        }
        if (ysd.getAmount(from.getKey()) < recipe.getAmount()) {
            p.sendMessage("§3精灵合成 §f|| §c你没有足够的" + from.getInsufficient());
            return false;
        }
        if (recipe.getMoney() > 0) {
            if (this.economy == null) {
                p.sendMessage("§3精灵合成 §f|| §c服务器经济服务不可用, 暂时无法合成!");
                return false;
            }
            if (this.economy.getBalance((OfflinePlayer) p) < (double) recipe.getMoney()) {
                p.sendMessage("§3精灵合成 §f|| §c你没有足够的金币！");
                return false;
            }
        }

        ysd.withdraw(from.getKey(), recipe.getAmount());
        if (recipe.getMoney() > 0) {
            this.economy.withdrawPlayer((OfflinePlayer) p, (double) recipe.getMoney());
        }
        ysd.deposit(to.getKey(), recipe.getResult());

        p.sendMessage("§3精灵合成 §f|| §a合成完成, 获得 §f" + recipe.getResult() + " §a个"
                + stripLeadColor(to.getDisplay()) + " §7(消耗 §f" + recipe.getAmount() + " §7个"
                + stripLeadColor(from.getDisplay()) + " §7+ §f" + recipe.getMoney() + " §7金币)");
        this.playFeedback(p, "success");
        this.addQuestProgress(p, QuestType.STICKER, 1);
        return true;
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

        // 后台线程池要先起来 —— 存储层的异步写回、流水日志落盘、兜底扫描都挂在它上面。
        Async.init(this, StorageFactory.workerThreads(this));

        // 流水日志的落盘器：句柄常开（省掉每行一次 open/close），写入跑在写队列上。
        this.logWriter = new LogWriter(this.getLogger());

        // 存储：插件的持久化全部走这里（粘剂数量 + 连续失败计数）。
        // 具体用 SQLite（默认）还是 MySQL 由 config.yml 的 Storage 段决定；
        // MySQL 连不上会自动回退 SQLite 并打 SEVERE，绝不因为连不上库就让插件启用失败。
        StorageFactory.Opened opened = StorageFactory.open(this, this.getDataFolder(), this.getLogger());
        this.storage = opened.storage;
        // 拍一份 Storage 段快照，供 /pu reload 判断「哪些改动需要重启才生效」。
        this.storageSnapshot.clear();
        this.storageSnapshot.putAll(this.snapshotStorageConfig());
        if (this.storage == null) {
            this.getLogger().severe("存储后端不可用，本插件将无法保存任何数据（合成仍可进行，但粘剂与保底计数不会持久化）。");
            this.storageWarning = opened.fallbackReason != null ? opened.fallbackReason
                    : "存储后端不可用，粘剂与保底计数不会持久化。";
        } else {
            // 非 null 表示发生过降级（当前只有「MySQL 连不上 → 回退 SQLite」这一种）。
            // 后台日志已经打过 SEVERE，但运维不一定开着控制台 —— 这里留着给进服的 OP 看。
            this.storageWarning = opened.fallbackReason;
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
        //
        // 建索引要全量读盘（上限 history.max-scan-lines 条，默认 5 万行）。这段以前是
        // 同步跑的 —— 启用阶段无所谓，但 `/reload` 是「先把插件关了再启用」，
        // 那一刻服里还有玩家，几十万行的读盘会结结实实卡在主线程上。改成后台重建：
        // LogIndex 自己会在提交任务**之前**置位「重建中」，所以这中间合成的流水
        // 不会漏（见 LogIndex#rebuildLater）。
        try {
            this.logIndex = new LogIndex(this.getDataFolder(),
                    this.getLogKeepDays(), this.getLogMaxScanLines());
            this.logIndex.rebuildLater();
            this.getLogger().info("合成流水索引正在后台建立（上限 "
                    + this.getLogMaxScanLines() + " 条，保留 " + this.getLogKeepDays() + " 天）。");
        } catch (Throwable t) {
            this.getLogger().warning("建立合成流水索引失败: " + t);
        }
        if (this.logIndex == null) {
            // 兜底：给个空索引，保证 logCompound / 命令永远不会踩空指针。
            this.logIndex = new LogIndex(this.getDataFolder(),
                    this.getLogKeepDays(), this.getLogMaxScanLines());
        }
        try {
            this.checkStarPools();
        } catch (Throwable t) {
            this.getLogger().warning("产出池自检失败: " + t);
        }

        // 委托表只进不出：每个玩家每天 3 行，而查询永远只看当天 —— 历史行纯占磁盘。
        // 启动时清一次超出保留期（Compound.quest.keep-days）的行。
        // 放异步：MySQL 上这条 DELETE 走全表扫（day 不是主键前缀），大表能跑几百毫秒，
        // 不该压在启用阶段的主线程上。失败只留痕，不影响任何玩法。
        Async.compute(new Runnable() {
            public void run() {
                try {
                    final int purged = PokeUtils.this.purgeOldQuests();
                    if (purged > 0) {
                        PokeUtils.this.getLogger().info("已清理 " + purged + " 条超过 "
                                + PokeUtils.this.getQuestKeepDays() + " 天的委托记录。");
                    }
                } catch (Throwable t) {
                    PokeUtils.this.getLogger().warning("清理旧委托记录失败: " + t);
                }
            }
        });

        // 两个周期性维护任务，都跑在异步线程上，绝不占主线程：
        //   ① CachedStorage.sweep() —— 每秒扫一遍没落盘的脏数据，补上「重试放弃」与
        //      「玩家挂着不动」这两类永远不会自己再试的情况（详见 CachedStorage 类注释）。
        //   ② SQLite WAL checkpoint —— 把 -wal 合并回主库并截断，防止长跑服上它一路涨上去。
        final Storage storageRef = this.storage;
        if (storageRef instanceof CachedStorage) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
                @Override
                public void run() {
                    ((CachedStorage) storageRef).sweep();
                }
            }, 20L, 20L);
        }
        final int checkpointMinutes = StorageFactory.sqliteCheckpointMinutes(this);
        if (storageRef != null && checkpointMinutes > 0 && "sqlite".equals(storageRef.kind())) {
            long period = checkpointMinutes * 60L * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
                @Override
                public void run() {
                    storageRef.checkpoint();
                }
            }, period, period);
            this.getLogger().info("SQLite WAL checkpoint 已开启，每 " + checkpointMinutes + " 分钟一次。");
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
        // 日志写任务也排在这同一个写队列上，可能还剩几条没执行完。storage 为 null 时
        // 上面那一步的排空不会发生，所以这里单独排空一次，之后才关日志句柄 ——
        // 顺序反了会让最后几条流水打在已关闭的句柄上。
        Async.drain(2000L);
        if (this.logWriter != null) {
            this.logWriter.close();
            this.logWriter = null;
        }
        // 线程池放在存储之后关：存储的排空要依赖它。
        Async.shutdown(2000L);
        this.ysdatas.clear();
        this.playerdatas.clear();
        this.lastCompoundAt.clear();
        // 星级换算与产出预览图标都是纯缓存，关服/重载时主动放掉 ——
        // 别指望「类被卸载时自然回收」，插件类在主线程/调度器里都有引用。
        this.clearStarCache();
        this.soundWarned.clear();
        this.logIndex = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§a==============================================");
            sender.sendMessage("§6/pu §fcompound - §e打开合成界面");
            sender.sendMessage("§6/pu §fquest - §e查看今日委托");
            sender.sendMessage("§6/pu §fdex - §e查看合成图鉴");
            // 这三个入口只有在对应功能开启时才出现在帮助里 —— 否则玩家点进去只会看到
            // 「该功能已关闭」，等于把帮助信息变成噪音。
            if (this.isSalvageEnabled()) {
                sender.sendMessage("§6/pu §fsalvage - §e分解回收精灵（换回粘剂与金币）");
            }
            if (this.isRerollEnabled()) {
                sender.sendMessage("§6/pu §freroll - §e洗练精灵的单项属性");
            }
            if (this.isStickerCraftEnabled()) {
                sender.sendMessage("§6/pu §fcraft - §e用低阶粘剂合成高阶粘剂");
            }
            sender.sendMessage("§6/pu §fhistory [玩家] [条数] - §e查看最近的合成记录");
            sender.sendMessage("§6/pu §fstats [玩家|all] - §e合成统计（成功率 / 消耗）");
            sender.sendMessage("§6/pu §ftop [count|star] - §e合成排行榜");
            if (sender.hasPermission("pokeutils.admin")) {
                sender.sendMessage("§6/pu §fg <玩家> <类型> <数量> - §e给予粘剂（支持离线玩家）");
                sender.sendMessage("§6/pu §ftake <玩家> <类型> <数量> - §e扣除粘剂（支持离线玩家）");
                sender.sendMessage("§6/pu §fbal [玩家] - §e查询粘剂余额");
                sender.sendMessage("§6/pu §fdebug <玩家> - §e查看某个玩家的合成状态与成功率构成");
                sender.sendMessage("§6/pu §fstorage - §e查看存储后端状态（缓存 / 落盘 / 熔断）");
                sender.sendMessage("§6/pu §fmigrate <sqlite|mysql> [dry-run] - §e把数据整体搬到另一个后端");
                sender.sendMessage("§6/pu §fquestreset <玩家> - §e清空某玩家今天的委托进度");
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
            // 第四轮新增的两处缓存同样要失效，否则改了配方 / 委托权重要重启才生效。
            this.invalidateStickerRecipes();
            this.invalidateQuestTypes();
            // 产出池可能被 PokeStar 的 stars.yml 改动影响，重载时再自检一次。
            this.checkStarPools();
            // 日志索引的上限（history.max-scan-lines）可能被改了，磁盘上的日志也可能
            // 被运维清理过 —— 重建一次索引。放后台线程：全量读盘在几十万行时上百毫秒，
            // 不该卡在管理员敲下 /pu reload 的这一帧上。
            final LogIndex index = this.logIndex;
            if (index != null) {
                index.configure(this.getLogKeepDays(), this.getLogMaxScanLines());
                // 用 rebuildLater 而不是 Async.compute(rebuild)：
                // 前者在**返回之前**就把「重建中」置上了，这中间合成的流水不会丢。
                index.rebuildLater();
            }
            sender.sendMessage("§b精灵信息 §f// §a插件配置重载完成！");
            // 存储段是 reload 唯一覆盖不到的部分：后端在 onEnable 就建好了，换 type、
            // 改 host、调 pool-size 都得重启服务器。不提示的话运维会以为已经生效。
            // 快照刻意不在这里更新 —— 提示会一直出现，直到真正重启为止。
            List<String> storageChanged = this.diffStorageConfig(this.storageSnapshot,
                    this.snapshotStorageConfig());
            if (!storageChanged.isEmpty()) {
                sender.sendMessage("§e 注意：以下 " + storageChanged.size()
                        + " 项存储配置本次重载**不会**生效（存储后端只在启用时建立，需重启服务器）：");
                sender.sendMessage("§7  " + joinWith(storageChanged, "§7, §f"));
                sender.sendMessage("§7  改完记得把 config.yml 的 Storage 段定稿，重启后用 /pu storage 复核。");
            }
        } else if (args[0].equalsIgnoreCase("history")) {
            this.handleHistoryCommand(sender, args);
        } else if (args[0].equalsIgnoreCase("stats")) {
            this.handleStatsCommand(sender, args);
        } else if (args[0].equalsIgnoreCase("top")) {
            this.handleTopCommand(sender, args);
        } else if (args[0].equalsIgnoreCase("debug") && sender.hasPermission("pokeutils.admin")) {
            this.handleDebugCommand(sender, args);
        } else if (args[0].equalsIgnoreCase("salvage") || args[0].equalsIgnoreCase("fenjie")) {
            this.handleSalvageCommand(sender);
        } else if (args[0].equalsIgnoreCase("reroll") || args[0].equalsIgnoreCase("xilian")) {
            this.handleRerollCommand(sender);
        } else if (args[0].equalsIgnoreCase("quest") || args[0].equalsIgnoreCase("daily")
                || args[0].equalsIgnoreCase("weituo")) {
            this.handleQuestCommand(sender);
        } else if (args[0].equalsIgnoreCase("dex") || args[0].equalsIgnoreCase("tujian")) {
            this.handleDexCommand(sender);
        } else if (args[0].equalsIgnoreCase("craft") || args[0].equalsIgnoreCase("stickercraft")) {
            this.handleCraftCommand(sender);
        } else if (args[0].equalsIgnoreCase("questreset") && sender.hasPermission("pokeutils.admin")) {
            this.handleQuestResetCommand(sender, args);
        } else if (args[0].equalsIgnoreCase("g") && sender.hasPermission("pokeutils.admin")) {
            this.handleStickerCommand(sender, args, true);
        } else if (args[0].equalsIgnoreCase("take") && sender.hasPermission("pokeutils.admin")) {
            this.handleStickerCommand(sender, args, false);
        } else if (args[0].equalsIgnoreCase("bal")) {
            this.handleBalanceCommand(sender, args);
        } else if (args[0].equalsIgnoreCase("storage") && sender.hasPermission("pokeutils.admin")) {
            this.handleStorageCommand(sender);
        } else if (args[0].equalsIgnoreCase("migrate") && sender.hasPermission("pokeutils.admin")) {
            this.handleMigrateCommand(sender, args);
        }
        return false;
    }

    // ================================================================ Tab 补全
    // 规则与 /pu 无参帮助逐条对齐：
    //   · 没有 pokeutils.admin 的人看不到管理指令（补全栏里推荐一个「敲下去只会说
    //     没权限」的选项，比不补更糟）；
    //   · 功能被 config 关掉时对应子命令也不出现（同上）。
    // 只补**在线**玩家名：离线名要么查库、要么走 Bukkit.getOfflinePlayer（正版服会向
    // Mojang 请求），都不能出现在一个玩家每秒可能触发多次的路径上。
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args == null || args.length == 0) {
            return new ArrayList<String>();
        }
        // Bukkit 的约定：args 的最后一项是「玩家当前正在输入的那截」，
        // 前面的都是已经敲完的完整参数。
        String current = args[args.length - 1];
        if (args.length == 1) {
            return completeFrom(this.tabSubcommands(sender), current);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // ---- /pu g | take <玩家> <类型> <数量>
        if ("g".equals(sub) || "take".equals(sub)) {
            if (!sender.hasPermission("pokeutils.admin")) {
                return new ArrayList<String>();
            }
            if (args.length == 2) {
                return completeFrom(this.tabOnlineNames(), current);
            }
            if (args.length == 3) {
                return completeFrom(this.tabStickerKeys(), current);
            }
            if (args.length == 4) {
                return completeFrom(TAB_AMOUNTS, current);
            }
            return new ArrayList<String>();
        }

        // ---- /pu top [count|star]
        if ("top".equals(sub)) {
            if (args.length == 2) {
                return completeFrom(Arrays.asList("count", "star"), current);
            }
            return new ArrayList<String>();
        }

        // ---- /pu migrate <sqlite|mysql> [dry-run]
        if ("migrate".equals(sub)) {
            if (!sender.hasPermission("pokeutils.admin")) {
                return new ArrayList<String>();
            }
            if (args.length == 2) {
                return completeFrom(Arrays.asList("mysql", "sqlite"), current);
            }
            if (args.length == 3) {
                return completeFrom(Collections.singletonList("dry-run"), current);
            }
            return new ArrayList<String>();
        }

        // ---- /pu history [玩家] [条数]
        if ("history".equals(sub)) {
            if (args.length == 2) {
                return completeFrom(this.tabOnlineNames(), current);
            }
            if (args.length == 3) {
                return completeFrom(TAB_AMOUNTS, current);
            }
            return new ArrayList<String>();
        }

        // ---- /pu stats [玩家|all]
        if ("stats".equals(sub)) {
            if (args.length == 2) {
                List<String> names = this.tabOnlineNames();
                names.add("all");
                return completeFrom(names, current);
            }
            return new ArrayList<String>();
        }

        // ---- /pu bal | debug | questreset <玩家>
        if ("bal".equals(sub) || "debug".equals(sub) || "questreset".equals(sub)) {
            if (args.length == 2) {
                return completeFrom(this.tabOnlineNames(), current);
            }
            return new ArrayList<String>();
        }

        return new ArrayList<String>();
    }

    /** 与 /pu 无参帮助同源的子命令候选（顺序无关紧要，返回前会排序）。 */
    private List<String> tabSubcommands(CommandSender sender) {
        List<String> out = new ArrayList<String>();
        out.add("compound");
        out.add("quest");
        if (this.isDexEnabled()) {
            out.add("dex");
        }
        if (this.isSalvageEnabled()) {
            out.add("salvage");
        }
        if (this.isRerollEnabled()) {
            out.add("reroll");
        }
        if (this.isStickerCraftEnabled()) {
            out.add("craft");
        }
        out.add("history");
        out.add("stats");
        out.add("top");
        out.add("bal");
        if (sender.hasPermission("pokeutils.admin")) {
            out.add("g");
            out.add("take");
            out.add("debug");
            out.add("storage");
            out.add("migrate");
            out.add("questreset");
            out.add("reload");
        }
        return out;
    }

    /** 在线玩家名（含自己）。刻意不查离线玩家 —— 见 onTabComplete 的说明。 */
    private List<String> tabOnlineNames() {
        List<String> out = new ArrayList<String>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null && p.getName() != null) {
                out.add(p.getName());
            }
        }
        return out;
    }

    /** 配置里定义的粘剂键（改 config 后由 invalidateStickerTypes 失效，不会读到旧值）。 */
    private List<String> tabStickerKeys() {
        List<String> out = new ArrayList<String>();
        for (StickerType sticker : this.getStickerTypes()) {
            out.add(sticker.getKey());
        }
        return out;
    }

    /**
     * 前缀过滤 + 去重 + 排序。
     *
     * <p>Bukkit 不会替插件过滤候选项 —— 不自己筛的话，敲 {@code /pu re} 会看到全部
     * 二十多个子命令，补全反而变成噪音。</p>
     */
    private static List<String> completeFrom(List<String> all, String prefix) {
        String head = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        Set<String> picked = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (String item : all) {
            if (item == null) {
                continue;
            }
            if (head.isEmpty() || item.toLowerCase(Locale.ROOT).startsWith(head)) {
                picked.add(item);
            }
        }
        return new ArrayList<String>(picked);
    }

    // ================================================================ 第四轮新增命令入口
    // 这五个命令都遵循同一条设计：命令只负责「校验 + 打开界面」，真正的操作在界面上完成。
    // 原因是它们全都要针对「队伍里的某一只精灵」做事，而玩家在聊天栏里没法直观地指认
    // 「第几只」；用界面选就不会选错，也不需要额外记一串参数顺序。

    /** {@code /pu salvage}：打开分解回收界面。 */
    private void handleSalvageCommand(CommandSender sender) {
        if (!this.isSalvageEnabled()) {
            sender.sendMessage("§b精灵信息 §f// §c分解回收功能已关闭！");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("§b精灵信息 §f// §c该指令只能由玩家执行！");
            return;
        }
        Player p = (Player) sender;
        PlayerData pd = this.playerdatas.get(p.getName());
        if (pd == null) {
            pd = new PlayerData();
            this.playerdatas.put(p.getName(), pd);
        }
        this.playFeedback(p, "open");
        pd.openPartyGui(p, PlayerData.PURPOSE_SALVAGE);
    }

    /** {@code /pu reroll}：打开洗练的精灵选择界面。 */
    private void handleRerollCommand(CommandSender sender) {
        if (!this.isRerollEnabled()) {
            sender.sendMessage("§b精灵信息 §f// §c洗练功能已关闭！");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("§b精灵信息 §f// §c该指令只能由玩家执行！");
            return;
        }
        Player p = (Player) sender;
        PlayerData pd = this.playerdatas.get(p.getName());
        if (pd == null) {
            pd = new PlayerData();
            this.playerdatas.put(p.getName(), pd);
        }
        this.playFeedback(p, "open");
        pd.openPartyGui(p, PlayerData.PURPOSE_REROLL);
    }

    /** {@code /pu quest}：打开每日委托界面。 */
    private void handleQuestCommand(CommandSender sender) {
        if (!this.isQuestEnabled()) {
            sender.sendMessage("§b精灵信息 §f// §c每日委托功能已关闭！");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("§b精灵信息 §f// §c该指令只能由玩家执行！");
            return;
        }
        Player p = (Player) sender;
        PlayerData pd = this.playerdatas.get(p.getName());
        if (pd == null) {
            pd = new PlayerData();
            this.playerdatas.put(p.getName(), pd);
        }
        this.playFeedback(p, "open");
        pd.openQuestGui(p);
    }

    /** {@code /pu dex}：打开合成图鉴。 */
    private void handleDexCommand(CommandSender sender) {
        if (!this.isDexEnabled()) {
            sender.sendMessage("§b精灵信息 §f// §c合成图鉴功能已关闭！");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("§b精灵信息 §f// §c该指令只能由玩家执行！");
            return;
        }
        Player p = (Player) sender;
        PlayerData pd = this.playerdatas.get(p.getName());
        if (pd == null) {
            pd = new PlayerData();
            this.playerdatas.put(p.getName(), pd);
        }
        this.playFeedback(p, "open");
        pd.openDexGui(p, 1);
    }

    /** {@code /pu craft}：打开粘剂合成界面。 */
    private void handleCraftCommand(CommandSender sender) {
        if (!this.isStickerCraftEnabled()) {
            sender.sendMessage("§b精灵信息 §f// §c粘剂合成功能已关闭！");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("§b精灵信息 §f// §c该指令只能由玩家执行！");
            return;
        }
        Player p = (Player) sender;
        PlayerData pd = this.playerdatas.get(p.getName());
        if (pd == null) {
            pd = new PlayerData();
            this.playerdatas.put(p.getName(), pd);
        }
        this.playFeedback(p, "open");
        pd.openStickerCraftGui(p);
    }

    /**
     * {@code /pu questreset <玩家>}：清空某玩家「今天」的委托进度。
     *
     * <p>作用是给运营一个补救手段 —— 委托是当天固定的，玩家如果因为某次误操作
     * （例如把要交付的精灵分解了）卡住，除了等第二天没有别的出路。</p>
     */
    private void handleQuestResetCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§b精灵信息 §f// §c用法: /pu questreset <玩家>");
            return;
        }
        if (this.storage == null) {
            sender.sendMessage("§b精灵信息 §f// §c存储尚未就绪!");
            return;
        }
        String name = args[1];
        Player online = Bukkit.getPlayerExact(name);
        UUID uuid = online != null ? online.getUniqueId() : this.storage.findUuidByName(name);
        if (uuid == null) {
            sender.sendMessage("§b精灵信息 §f// §c找不到该玩家（他可能从未进过本服）");
            return;
        }
        String day = this.today();
        for (QuestData quest : this.storage.getQuests(uuid, day)) {
            this.storage.setQuestProgress(uuid, day, quest.getSlot(), 0);
            this.storage.setQuestClaimed(uuid, day, quest.getSlot());
            // 上面把 claimed 也置 1：否则重置进度后玩家可以立刻再刷一次奖励。
            // 「重置」的语义是「这条作废」，不是「白送一次」。
        }
        sender.sendMessage("§b精灵信息 §f// §a已把 " + name + " 今天的委托全部作废。");
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
     * 保底进度「积累档位」的展示文案。
     *
     * <p>0 显示成「不限」而不是「0 星」—— 它的含义是<b>不知道</b>
     * （旧数据，或计数被测试/运维直接写入），判定时会当作「任意档位都算」。</p>
     */
    public static String pityStarText(int star) {
        return star <= 0 ? "不限" : star + " 星";
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
            sender.sendMessage("§7 会话: 槽位1=§f" + pd.describeMaterial1() + " §7槽位2=§f" + pd.describeMaterial2()
                    + " §7粘剂=§f" + (pd.getYS() == null ? "无" : pd.getYS())
                    + " §7材料星级=§f" + pd.getStar());
            sender.sendMessage("§7 成功率构成: 基础 §f" + pd.getJl() + "% §7+ 粘剂 §f" + pd.getYsjl()
                    + "% §7+ 权限组 §f" + pd.getVipBonus() + "% §7= §f" + pd.getSumJL() + "%");
            // 新增（A2）：保底进度现在带档位 —— 排查「为什么没触发保底」时，
            // 光看次数是没用的，还得知道这些次数是不是记在当前这一档上。
            sender.sendMessage("§7 保底进度: §f" + this.getFailStreak(p)
                    + " §7/ §f" + this.getPityThreshold(pd.getStar())
                    + " §7· 积累档位=§f" + pityStarText(this.getFailStar(p)));
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
     * {@code /pu storage} —— 存储后端的一眼诊断。
     *
     * <p><b>为什么要有这条命令</b>：{@link CachedStorage} 一直在维护「连续失败次数 /
     * 熔断窗口 / 未落盘条目数 / 缓存玩家数」这些量，但在此之前<b>只有测试替身能读到它们</b>
     * （靠反射）。运维遇到「玩家说粘剂没到账」时，唯一的手段是翻 {@code latest.log} 找
     * SEVERE，而 SEVERE 只在写入失败那一刻打一次 —— 事后去翻常常已经被滚掉了。</p>
     *
     * <p>这条命令只是把<b>已经存在</b>的可观测性接出来：不新增状态、不做任何 IO、
     * 不碰任何一端的数据，所以它在后端已经出问题的时候依然能跑。</p>
     */
    private void handleStorageCommand(CommandSender sender) {
        sender.sendMessage("§a========= §e存储后端状态 §a=========");
        Storage storage = this.storage;
        if (storage == null) {
            sender.sendMessage("§c 存储不可用 —— 合成仍可正常进行，但粘剂与保底计数不会持久化。");
            sender.sendMessage("§7 请查启动日志里的 SEVERE（数据库文件不可写 / MySQL 不可达 / 已禁止回退）。");
            sender.sendMessage("§a==============================================");
            return;
        }
        sender.sendMessage("§7 后端: §f" + storage.describe());
        if (storage instanceof CachedStorage) {
            CachedStorage cached = (CachedStorage) storage;
            sender.sendMessage("§7 写模式: §f" + (cached.isWriteBehind()
                    ? "异步写回（主线程不等落库）" : "同步写穿（写入返回即已落盘）"));
            int queued = Async.pending();
            sender.sendMessage("§7 写队列积压: §f" + queued + " §7条" + (queued > 0
                    ? " §8（关服时会等待排空，超时才放弃）" : ""));
            int dirty = cached.dirtyCount();
            sender.sendMessage("§7 缓存玩家: §f" + cached.cachedPlayers()
                    + " §7| 未落盘条目: §f" + dirty + (dirty > 0
                    ? " §c（正在按退避重试，或等待下一次扫描）" : ""));
            sender.sendMessage("§7 连续写失败: §f" + cached.consecutiveFailures() + " §7| 熔断: "
                    + (cached.isBroken()
                    ? "§c已触发，剩余 " + cached.breakerRemainingMs() + "ms"
                    : "§a未触发"));
        }
        if (this.logIndex != null) {
            sender.sendMessage("§7 流水索引: §f" + this.logIndex.size() + " §7条（上限 §f"
                    + this.getLogMaxScanLines() + " §7条，保留 §f" + this.getLogKeepDays() + " §7天）");
        }
        sender.sendMessage("§a==============================================");
    }

    /**
     * {@code /pu migrate <sqlite|mysql> [dry-run]} —— 把玩家数据整体搬到另一个后端。
     *
     * <p>典型场景：先用 SQLite 跑着，后来改上 MySQL（或反过来做一次冷备份）。
     * 命令<b>只读源端、只写目标端</b>，目标端用绝对值 upsert，所以中途失败后重跑一次
     * 即对齐，不需要先清理（细节见 {@link StorageMigrator}）。</p>
     *
     * <p><b>跑在后台线程上</b>：全量读 + 几千次 upsert 往返，放主线程足够卡服几秒到几十秒。</p>
     */
    private void handleMigrateCommand(final CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§b精灵信息 §f// §c用法: /pu migrate <sqlite|mysql> [dry-run]");
            return;
        }
        String target = args[1].trim().toLowerCase();
        if (!"sqlite".equals(target) && !"mysql".equals(target)) {
            sender.sendMessage("§b精灵信息 §f// §c目标后端只能是 sqlite 或 mysql。");
            return;
        }
        final Storage source = this.storage;
        if (source == null) {
            sender.sendMessage("§b精灵信息 §f// §c当前没有可用的存储后端，无法迁移。");
            return;
        }
        final String current = source.kind();
        if (target.equals(current)) {
            sender.sendMessage("§b精灵信息 §f// §e目标后端与当前后端相同（" + current + "），无需迁移。");
            return;
        }
        if (!this.migrating.compareAndSet(false, true)) {
            sender.sendMessage("§b精灵信息 §f// §e已经有一个迁移在进行中，请等它结束。");
            return;
        }
        final boolean dryRun = args.length >= 3 && "dry-run".equalsIgnoreCase(args[2]);

        // 目标端在**主线程**打开：打开后端要读 config.yml，而 FileConfiguration 不是线程安全的
        // （reloadConfig 会在同一个对象上重新解析）。建连接本身只是一次性的几毫秒。
        // 之后的「全量读 + 几千次 upsert」才挪到后台线程。
        final Storage targetBackend;
        try {
            targetBackend = StorageFactory.openBackend(this, this.getDataFolder(), this.getLogger(), target);
        } catch (Exception ex) {
            this.migrating.set(false);
            sender.sendMessage("§b精灵信息 §f// §c目标后端 " + target + " 打不开：" + ex.getMessage());
            sender.sendMessage("§7 没有写入任何数据。请先核对 config.yml 的 Storage 段与 /pu storage。");
            return;
        }

        sender.sendMessage("§b精灵信息 §f// §e开始迁移 §f" + current + " §e-> §f" + target
                + (dryRun ? "§e（干跑，不会写入任何数据）" : "§e，跑在后台线程上，结果稍后贴出"));
        final PokeUtils plugin = this;
        Async.compute(new Runnable() {
            @Override
            public void run() {
                StorageMigrator.Result result = null;
                String failure = null;
                try {
                    result = StorageMigrator.migrate(plugin.getLogger(), source, targetBackend, target, dryRun);
                } catch (Throwable t) {
                    plugin.getLogger().severe("迁移出现未预期的错误: " + t);
                    failure = String.valueOf(t);
                }
                final StorageMigrator.Result done = result;
                final String error = failure;
                // 消息必须回到主线程发：玩家 / 控制台的 sendMessage 契约都在主线程。
                Async.main(new Runnable() {
                    @Override
                    public void run() {
                        if (done == null) {
                            sender.sendMessage("§c迁移因未预期的错误中断：" + error);
                            plugin.getLogger().severe("[migrate] 因未预期的错误中断: " + error);
                            plugin.migrating.set(false);
                            return;
                        }
                        for (String line : done.messages) {
                            sender.sendMessage(line);
                            // 同时写进服务端日志。原因：通过 RCON 执行时，命令返回后那条
                            // 连接就断了，异步回来的结果 sendMessage 给谁都没人接 ——
                            // 而 RCON 恰恰是「后台托管的服」唯一能用的通道（本项目试验过：
                            // 拿不到控制台 stdin）。运维不该因为换了通道就看不到迁移结果。
                            plugin.getLogger().info("[migrate] " + PokeUtils.stripColors(line));
                        }
                        plugin.migrating.set(false);
                    }
                });
            }
        });
    }

    /**
     * 去掉 {@code §} / {@code &} 颜色代码，供写进服务端日志。
     *
     * <p>日志文件里不该出现终端转义序列 —— 那样 grep 与日志平台都很难处理。</p>
     */
    private static String stripColors(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' || c == '&') {
                i++;   // 连同后面的颜色码字符一起跳过
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
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
        // 修复（新增防护）：数量原本只判 >0，没有上限。而 addSticker 是「读当前值 → 加 delta
        // → 写回」的整数运算，`/pu g 玩家 PT 2000000000` 连敲两次就会溢出成负数，再被
        // Math.max(0, ...) 夹回 0 —— 玩家的粘剂被一次「给予」清空，而控制台一切正常。
        // 上限取得远高于任何正常用途（单次 100 万），超出直接截断并说明按多少执行，
        // 免得管理员以为发的是自己敲的那个数。
        if (amount > MAX_STICKER_AMOUNT) {
            sender.sendMessage("§b精灵信息 §f// §e单次最多 " + MAX_STICKER_AMOUNT
                    + " 个，已按 " + MAX_STICKER_AMOUNT + " 处理。");
            amount = MAX_STICKER_AMOUNT;
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
            // 修复（新增防护）：正版验证模式下 Bukkit.getOfflinePlayer(新名字) 会同步
            // 请求 Mojang 的 profile 接口，网络不好时能把服务端主线程卡住好几秒。
            // 而走到这里说明「库里也查不到他」—— 与其卡主线程，不如让管理员知道原因。
            // 离线模式（online-mode=false）不会有这个请求，保留原有的兜底解析。
            if (Bukkit.getOnlineMode()) {
                sender.sendMessage("§b精灵信息 §f// §c找不到该玩家的数据（需要他至少进过一次本服）。");
                return;
            }
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
            int failStar = this.storage.getFailStar(uuid);
            sender.sendMessage("§7 - 连续失败: §f" + this.storage.getFailStreak(uuid)
                    + " §7/ §f" + this.getPityThreshold(failStar)
                    + " §7· 积累档位=§f" + pityStarText(failStar));
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
