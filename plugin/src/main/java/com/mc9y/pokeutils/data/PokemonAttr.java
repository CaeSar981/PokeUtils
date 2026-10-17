package com.mc9y.pokeutils.data;

import com.mc9y.pokeutils.PokeUtils;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.entities.pixelmon.stats.IVStore;
import com.pixelmonmod.pixelmon.enums.EnumNature;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 精灵属性的读取、写入与继承。
 *
 * <p><b>为什么需要这个类</b>：原插件的合成只看「星级」一个维度 —— 用哪两只同星级精灵去合
 * 完全等价，玩家没有任何决策空间，抓精灵这件事也就失去了意义。有了属性继承之后，
 * 「个体值 150 的两只」和「随便抓的两只」开始有了区别，玩家才会主动去筛选材料。</p>
 *
 * <h3>接口签名来源</h3>
 * <p>本类调用的 Pixelmon API 全部照 {@code mods/Pixelmon-1.12.2-8.4.3-universal.jar} 上
 * {@code javap} 的真实输出核对过（见 stub-api 里对应的编译桩）。任何一条签名对不上，
 * 运行期都会是 {@code NoSuchMethodError}，因此这里刻意不做任何「猜」。</p>
 *
 * <h3>异常策略</h3>
 * <p>所有公开方法都不向外抛异常：属性继承是<b>附加体验</b>，它失败不应该让一次正常的合成
 * 变成「玩家被扣了钱却没拿到精灵」。但也不会像原实现那样静默吞掉 —— 每类失败只打一次 WARN，
 * 既不会刷屏，也不会让问题变得查不出来。</p>
 */
public final class PokemonAttr {

    /** IV 项数。索引顺序与 {@link IVStore#getArray()} 一致。 */
    public static final int IV_COUNT = 6;

    private static final String[] IV_NAMES = {"HP", "攻击", "防御", "特攻", "特防", "速度"};

    /** 25 种性格的中文名。枚举名对不上时直接回落到枚举名，不会显示空白。 */
    private static final Map<String, String> NATURE_CN = natureCn();

    /** 已经打过告警的键，避免同一个问题反复刷屏。 */
    private static final java.util.Set<String> WARNED = new java.util.HashSet<String>();

    private PokemonAttr() {
    }

    private static Map<String, String> natureCn() {
        Map<String, String> m = new HashMap<String, String>();
        // 五项「无修正」性格
        m.put("Hardy", "勤奋");
        m.put("Docile", "坦率");
        m.put("Serious", "认真");
        m.put("Bashful", "害羞");
        m.put("Quirky", "浮躁");
        // +攻击
        m.put("Lonely", "孤僻");
        m.put("Brave", "勇敢");
        m.put("Adamant", "固执");
        m.put("Naughty", "顽皮");
        // +防御
        m.put("Bold", "大胆");
        m.put("Relaxed", "悠闲");
        m.put("Impish", "淘气");
        m.put("Lax", "乐天");
        // +速度
        m.put("Timid", "胆小");
        m.put("Hasty", "急躁");
        m.put("Jolly", "爽朗");
        m.put("Naive", "天真");
        // +特攻
        m.put("Modest", "内敛");
        m.put("Mild", "慢吞吞");
        m.put("Quiet", "冷静");
        m.put("Rash", "马虎");
        // +特防
        m.put("Calm", "温和");
        m.put("Gentle", "温顺");
        m.put("Sassy", "自大");
        m.put("Careful", "慎重");
        return m;
    }

    private static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            PokeUtils plugin = PokeUtils.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("[属性] " + message);
            }
        }
    }

    // ------------------------------------------------------------------ 展示名

    /** 第 index 项 IV 的中文名（越界时返回「IV#index」而不是抛异常）。 */
    public static String ivName(int index) {
        return index >= 0 && index < IV_COUNT ? IV_NAMES[index] : ("IV#" + index);
    }

    /** 性格的中文名；传入 null 或未知枚举名时回落到枚举名本身。 */
    public static String natureName(String enumName) {
        if (enumName == null) {
            return "-";
        }
        String cn = NATURE_CN.get(enumName);
        return cn == null ? enumName : cn;
    }

    public static String natureName(EnumNature nature) {
        return nature == null ? "-" : natureName(nature.name());
    }

    // ------------------------------------------------------------------ IV 读写

    /**
     * 读取六项 IV 的快照；任何一步失败都返回 {@code null}（调用方需判空）。
     *
     * <p>注意 {@link IVStore#getArray()} 在真实实现里是新建一个数组返回，
     * 因此这里拿到的是快照，改它不会影响精灵 —— 写回去要走 {@link #writeIvs}。</p>
     */
    public static int[] readIvs(Pokemon pokemon) {
        if (pokemon == null) {
            return null;
        }
        try {
            IVStore store = pokemon.getIVs();
            if (store == null) {
                return null;
            }
            int[] arr = store.getArray();
            if (arr == null || arr.length < IV_COUNT) {
                return null;
            }
            int[] out = new int[IV_COUNT];
            System.arraycopy(arr, 0, out, 0, IV_COUNT);
            return out;
        } catch (Throwable t) {
            warnOnce("read-ivs", "读取精灵个体值失败: " + t);
            return null;
        }
    }

    /** 单项 IV；读不到返回 -1。 */
    public static int readIv(Pokemon pokemon, int index) {
        int[] all = readIvs(pokemon);
        return all == null || index < 0 || index >= IV_COUNT ? -1 : all[index];
    }

    /**
     * 把六项 IV 写回精灵。
     *
     * <p>关键点：必须改<b>精灵内部那个活的</b> {@code IVStore} 并调 {@code markDirty()} ——
     * 只把值写进 {@link #readIvs} 拿到的数组是没用的，那只是个快照。</p>
     *
     * @return 是否写入成功
     */
    public static boolean writeIvs(Pokemon pokemon, int[] values) {
        if (pokemon == null || values == null || values.length < IV_COUNT) {
            return false;
        }
        try {
            IVStore store = pokemon.getIVs();
            if (store == null) {
                return false;
            }
            store.hp = clampIv(values[0]);
            store.attack = clampIv(values[1]);
            store.defence = clampIv(values[2]);
            store.specialAttack = clampIv(values[3]);
            store.specialDefence = clampIv(values[4]);
            store.speed = clampIv(values[5]);
            store.markDirty();
            return true;
        } catch (Throwable t) {
            warnOnce("write-ivs", "写入精灵个体值失败: " + t);
            return false;
        }
    }

    /** 写入单项 IV（内部走「读全部 -> 改一项 -> 写全部」）。 */
    public static boolean writeIv(Pokemon pokemon, int index, int value) {
        if (index < 0 || index >= IV_COUNT) {
            return false;
        }
        int[] all = readIvs(pokemon);
        if (all == null) {
            return false;
        }
        all[index] = value;
        return writeIvs(pokemon, all);
    }

    /** Pixelmon 的合法 IV 区间是 0~31，越界值会被夹住而不是报错。 */
    private static int clampIv(int value) {
        return value < 0 ? 0 : (value > 31 ? 31 : value);
    }

    // ------------------------------------------------------------------ 性格 / 特性 / 闪光

    public static String abilityName(Pokemon pokemon) {
        try {
            return pokemon == null ? "-" : pokemon.getAbilityName();
        } catch (Throwable t) {
            warnOnce("ability-name", "读取特性名失败: " + t);
            return "-";
        }
    }

    public static int abilitySlot(Pokemon pokemon) {
        try {
            return pokemon == null ? 0 : pokemon.getAbilitySlot();
        } catch (Throwable t) {
            warnOnce("ability-slot", "读取特性槽位失败: " + t);
            return 0;
        }
    }

    /**
     * 随机一个性格。
     *
     * <p>{@code EnumNature.values()} 长度由服务端真实的枚举决定（25）。
     * 桩里刻意没写常量表，所以这里不假设任何具体值。</p>
     */
    public static EnumNature randomNature(Random random) {
        try {
            EnumNature[] all = EnumNature.values();
            if (all.length == 0) {
                return null;
            }
            return all[random.nextInt(all.length)];
        } catch (Throwable t) {
            warnOnce("random-nature", "随机性格失败: " + t);
            return null;
        }
    }

    /** 全部性格名，供界面展示可选范围。 */
    public static String[] natureNames() {
        try {
            EnumNature[] all = EnumNature.values();
            String[] out = new String[all.length];
            for (int i = 0; i < all.length; i++) {
                out[i] = natureName(all[i]);
            }
            return out;
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /** 把性格设置为「除当前之外」的随机一种（洗练用，保证真的变了）。 */
    public static EnumNature randomNatureExcept(Random random, EnumNature current) {
        EnumNature[] all;
        try {
            all = EnumNature.values();
        } catch (Throwable t) {
            return null;
        }
        if (all.length == 0) {
            return null;
        }
        if (all.length == 1) {
            return all[0];
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            EnumNature candidate = all[random.nextInt(all.length)];
            if (candidate != current) {
                return candidate;
            }
        }
        // 极小概率连续撞上同一个 —— 直接线性找一个不同的，保证结果一定变化
        for (EnumNature candidate : all) {
            if (candidate != current) {
                return candidate;
            }
        }
        return all[0];
    }

    // ------------------------------------------------------------------ 继承

    /**
     * 把两只父本的属性按玩家选择的项继承给产物。
     *
     * @param result     产物精灵（已经 {@code pokemonFactory.create} 出来、发下去之前）
     * @param parentA    父本 A
     * @param parentB    父本 B
     * @param nature     是否继承性格
     * @param ability    是否继承特性（按<b>槽位</b>继承，不按名字 —— 见下方说明）
     * @param iv         是否继承个体值
     * @param source     来源：0 = 父本 A，1 = 父本 B，其他 = 两者随机取一
     * @param ivMinRatio 个体值取点在两只父本区间内的下界（0~100）
     * @param ivMaxRatio 个体值取点在两只父本区间内的上界（0~100）
     * @return 本次实际继承内容的描述（用于写进合成流水日志），什么都没继承时返回空串
     */
    public static String applyInheritance(Pokemon result, Pokemon parentA, Pokemon parentB,
            boolean nature, boolean ability, boolean iv,
            int source, int ivMinRatio, int ivMaxRatio, Random random) {
        if (result == null || parentA == null) {
            return "";
        }
        Pokemon donor = pickDonor(parentA, parentB, source, random);
        if (donor == null) {
            donor = parentA;
        }
        StringBuilder detail = new StringBuilder();

        if (nature) {
            try {
                EnumNature inherited = donor.getNature();
                if (inherited != null) {
                    result.setNature(inherited);
                    detail.append(detail.length() > 0 ? " " : "")
                            .append("性格=").append(natureName(inherited));
                }
            } catch (Throwable t) {
                warnOnce("inherit-nature", "继承性格失败: " + t);
            }
        }

        if (ability) {
            // 按「槽位」而不是「名字」继承，是本功能里最容易被忽略、后果却最严重的一点：
            // 产物是另一个种族，A 种族的特性名在 B 种族上往往根本不存在。
            // 槽位（0 / 1 = 普通特性，2 = 隐藏特性）则任何种族都有，跨种族继承天然合法。
            // 注意 setAbilitySlot 对空槽会向下回退（见 rerollAbility 的说明），
            // 所以当产物种族没有对应槽位时，会落到它最近的一个有效特性上 —— 这是可接受的语义。
            try {
                int slot = donor.getAbilitySlot();
                result.setAbilitySlot(slot);
                detail.append(detail.length() > 0 ? " " : "")
                        .append("特性=槽").append(slot);
            } catch (Throwable t) {
                warnOnce("inherit-ability", "继承特性失败: " + t);
            }
        }

        if (iv) {
            String ivDetail = inheritIvs(result, parentA, parentB, ivMinRatio, ivMaxRatio, random);
            if (ivDetail.length() > 0) {
                detail.append(detail.length() > 0 ? " " : "").append(ivDetail);
            }
        }

        return detail.toString();
    }

    private static Pokemon pickDonor(Pokemon parentA, Pokemon parentB, int source, Random random) {
        if (parentB == null || parentB == parentA) {
            return parentA;
        }
        if (source == 0) {
            return parentA;
        }
        if (source == 1) {
            return parentB;
        }
        return random.nextBoolean() ? parentA : parentB;
    }

    /**
     * 个体值继承：产物的第 i 项在「两只父本该值构成的区间」内按比例取点。
     *
     * <p>默认比例是 50%~100%，含义是「不低于两只父本的中位、最好能取到较高的那一只」——
     * 这条规则让「用个体值高的材料」有明确回报，又不会让产物稳定 31。</p>
     */
    private static String inheritIvs(Pokemon result, Pokemon parentA, Pokemon parentB,
            int minRatio, int maxRatio, Random random) {
        int[] ivsA = readIvs(parentA);
        int[] ivsB = parentB == null ? null : readIvs(parentB);
        int[] ivs;
        if (ivsA == null && ivsB == null) {
            return "";
        }
        if (ivsB == null) {
            ivs = ivsA;
        } else if (ivsA == null) {
            ivs = ivsB;
        } else {
            ivs = new int[IV_COUNT];
            int lo = Math.max(0, Math.min(minRatio, maxRatio));
            int hi = Math.min(100, Math.max(minRatio, maxRatio));
            for (int i = 0; i < IV_COUNT; i++) {
                int a = ivsA[i];
                int b = ivsB[i];
                int low = Math.min(a, b);
                int high = Math.max(a, b);
                int ratio = lo >= hi ? hi : lo + random.nextInt(hi - lo + 1);
                ivs[i] = low + (int) Math.round((high - low) * (ratio / 100.0D));
            }
        }
        if (!writeIvs(result, ivs)) {
            return "";
        }
        int total = 0;
        for (int value : ivs) {
            total += value;
        }
        return "IV合计=" + total;
    }

    /**
     * 计算「父本闪光」带来的产物闪光加成（%）。
     *
     * <p>刻意不做成「父本闪光 -> 产物必定闪光」：那等于把闪光的稀缺性直接抹平，
     * 反而会让闪光贬值。这里是概率加成 —— 投入（拿闪光当材料）有回报，但结果仍有波动。</p>
     */
    public static int shinyBonus(Pokemon parentA, Pokemon parentB, int singleBonus, int doubleBonus) {
        int shinies = 0;
        try {
            if (parentA != null && parentA.isShiny()) {
                shinies++;
            }
            if (parentB != null && parentB != parentA && parentB.isShiny()) {
                shinies++;
            }
        } catch (Throwable t) {
            warnOnce("shiny-read", "读取闪光状态失败: " + t);
            return 0;
        }
        if (shinies >= 2) {
            return doubleBonus;
        }
        return shinies == 1 ? singleBonus : 0;
    }

    /** 按概率把产物设为闪光；已经是闪光则不动。 */
    public static boolean applyShiny(Pokemon result, int bonusPercent, Random random) {
        if (result == null || bonusPercent <= 0) {
            return false;
        }
        try {
            if (result.isShiny()) {
                return true;
            }
            if (random.nextInt(100) < bonusPercent) {
                result.setShiny(true);
                return true;
            }
            return false;
        } catch (Throwable t) {
            warnOnce("shiny-write", "设置闪光失败: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 洗练

    /**
     * 重掷单项个体值。
     *
     * @param improveOnly true 时只有洗出的值更好才替换（纯提升，不会把好个体洗坏）
     * @return 洗练后的新值；失败返回 -1
     */
    public static int rerollIv(Pokemon pokemon, int index, int min, int max, boolean improveOnly, Random random) {
        int[] all = readIvs(pokemon);
        if (all == null || index < 0 || index >= IV_COUNT) {
            return -1;
        }
        int lo = Math.max(0, Math.min(min, max));
        int hi = Math.min(31, Math.max(min, max));
        int rolled = lo >= hi ? hi : lo + random.nextInt(hi - lo + 1);
        int before = all[index];
        if (improveOnly && rolled <= before) {
            // 纯提升模式：不如原来的就保留原值（但仍然算「洗过一次」，费用已经扣了）
            return before;
        }
        return writeIv(pokemon, index, rolled) ? rolled : -1;
    }

    /** 洗练性格（结果一定与原来不同，避免「花了钱没变化」的体感）。 */
    public static String rerollNature(Pokemon pokemon, Random random) {
        try {
            if (pokemon == null) {
                return null;
            }
            EnumNature next = randomNatureExcept(random, pokemon.getNature());
            if (next == null) {
                return null;
            }
            pokemon.setNature(next);
            return natureName(next);
        } catch (Throwable t) {
            warnOnce("reroll-nature", "洗练性格失败: " + t);
            return null;
        }
    }

    /**
     * 洗练特性：在<b>该种族实际存在</b>的特性槽之间循环。
     *
     * <p>⚠️ 第四轮修正。Pixelmon 的特性槽是三格，语义是 <b>0 / 1 = 普通特性、2 = 隐藏特性</b>
     * （不是「0 = 普通、1 = 隐藏」）。更麻烦的是反编译 {@code Pokemon#setAbilitySlot} 可见：
     * 目标槽在 {@code BaseStats} 里为空时会 <b>向下回退</b>（{@code i--} 直到某格有值）。
     * 于是原来那句 {@code getAbilitySlot() == 0 ? 1 : 0} 对<b>只有一个普通特性</b>的种族
     * （Bulbasaur、Gengar 这类）是一次空操作：槽 1 是空槽，{@code setAbilitySlot(1)} 实际落回 0，
     * 提示照发、金币照扣，特性却纹丝不动。</p>
     *
     * <p>这里改成挨个试探 0/1/2 —— 用「设完读回」确认真生效，再在有效槽位之间循环。
     * 这样无论种族有几个特性都成立，而且不需要去读 {@code BaseStats}。</p>
     *
     * @return 实际生效的新槽位描述；翻不动（只有一种特性）时返回 {@code null}
     */
    public static String rerollAbility(Pokemon pokemon) {
        try {
            if (pokemon == null) {
                return null;
            }
            int current = pokemon.getAbilitySlot();
            for (int step = 1; step <= 2; step++) {
                int candidate = (current + step) % 3;
                pokemon.setAbilitySlot(candidate);
                if (pokemon.getAbilitySlot() == candidate) {
                    return "槽" + candidate;
                }
            }
            pokemon.setAbilitySlot(current);   // 只有一种特性，翻不动 —— 还原，别留下半改状态
            return null;
        } catch (Throwable t) {
            warnOnce("reroll-ability", "洗练特性失败: " + t);
            return null;
        }
    }
}
