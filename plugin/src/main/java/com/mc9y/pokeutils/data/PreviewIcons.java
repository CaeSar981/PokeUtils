package com.mc9y.pokeutils.data;

import com.mc9y.blank038api.Blank038API;
import com.mc9y.pokemonapi.api.pokemon.PokemonUtil;
import com.mc9y.pokeutils.PokeUtils;
import com.pixelmonmod.pixelmon.Pixelmon;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.enums.EnumSpecies;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 「产出预览」界面的图标缓存。
 *
 * <p><b>它替换掉了什么</b>：原来每翻一页都要对当页的每一只种族做一次</p>
 * <pre>
 *   EnumSpecies.valueOf(...) -> Pixelmon.pokemonFactory.create(...)
 *   -> Blank038API.getSpriteHelper().getSpriteItem(pokemon)   // 建 NBT，最贵的一步
 * </pre>
 * <p>而一页最多 45 格。也就是说「点一下下一页」= 在主线程上创建 45 只精灵并渲染 45 份
 * 物品 NBT。这个操作本身不报错、不进日志，玩家只会觉得「翻页卡一下」——
 * 而它完全可以避免：预览内容对同一个星级档是<b>恒定</b>的，不随玩家、时间、状态变化。</p>
 *
 * <p><b>为什么要 clone</b>：ItemStack 是可变对象，Bukkit 的 {@code Inventory#setItem}
 * 只存引用。同一个实例被两个界面共享时，任何一处改名/改 lore 都会串到另一处。缓存里存
 * 模板，取用时 {@code clone()} 一份 —— clone 只复制 NMS Tag，比重新建精灵便宜一个量级。</p>
 *
 * <p><b>失效点</b>：{@link PokeUtils#clearStarCache()}（启用 / {@code /pu reload}）。
 * 预览内容完全由 PokeStar 的 {@code stars.yml} 决定，跟星级缓存是同一份数据源，
 * 因此共用同一个失效点，不额外引入第二套状态。</p>
 *
 * <p><b>内存边界</b>：每个星级档最多缓存 {@link #MAX_PER_LEVEL} 个图标，整张表
 * {@link #MAX_TOTAL} 个。超过就不缓存（照样能看，只是翻页回到原来的成本）——
 * 宁可慢一点，也不要让一个超大的 stars.yml 把堆吃掉。</p>
 */
public final class PreviewIcons {

    /** 单个星级档最多缓存多少个图标（覆盖 45 格 × 数页的常见情况）。 */
    private static final int MAX_PER_LEVEL = 200;

    /** 整张缓存最多保留多少个图标（约 800 个精灵物品，量级在几百 KB）。 */
    private static final int MAX_TOTAL = 800;

    private static final Map<Integer, List<ItemStack>> CACHE =
            new ConcurrentHashMap<Integer, List<ItemStack>>();

    private PreviewIcons() {
    }

    /**
     * 取某个「成品星级」档位的全部预览图标（顺序与 PokeStar 的产出池一致）。
     *
     * <p>返回的是模板对象，调用方<b>必须</b> {@code clone()} 之后再放进界面。
     * 返回 {@code null} 在实现上不可能，调用方不必判空。</p>
     */
    public static List<ItemStack> icons(int level) {
        Integer key = Integer.valueOf(level);
        List<ItemStack> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        List<String> pool = PokeUtils.getInstance().getPreviewPool(level);
        List<ItemStack> built = new ArrayList<ItemStack>(pool.size());
        for (int i = 0; i < pool.size(); i++) {
            built.add(PreviewIcons.render(pool.get(i)));
        }
        if (pool.size() <= MAX_PER_LEVEL && PreviewIcons.total() + built.size() <= MAX_TOTAL) {
            List<ItemStack> raced = CACHE.putIfAbsent(key, built);
            if (raced != null) {
                return raced;
            }
        } else if (pool.size() > MAX_PER_LEVEL) {
            PokeUtils.getInstance().getLogger().info("产出预览 " + level
                    + " 星档位有 " + pool.size() + " 个种族，超过缓存上限，本次不缓存。");
        }
        return built;
    }

    /** 清空缓存。由 {@link PokeUtils#clearStarCache()} 触发。 */
    public static void invalidate() {
        CACHE.clear();
    }

    private static int total() {
        int sum = 0;
        for (List<ItemStack> list : CACHE.values()) {
            sum += list.size();
        }
        return sum;
    }

    private static ItemStack render(String species) {
        try {
            // PokeStar 的表里个别名字带连字符（如 Ho-Oh），枚举常量名不带，需要归一化。
            EnumSpecies enumSpecies = EnumSpecies.valueOf(
                    species == null ? "" : species.replace("Ho-Oh", "HoOh"));
            Pokemon pokemon = Pixelmon.pokemonFactory.create(enumSpecies);
            ItemStack item = Blank038API.getPokemonAPI().getSpriteHelper().getSpriteItem(pokemon);
            if (item == null) {
                throw new IllegalStateException("sprite 为 null");
            }
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§f" + PokemonUtil.getPokemonName(enumSpecies));
                item.setItemMeta(meta);
            }
            return item;
        } catch (Throwable t) {
            // 单个种族渲染失败不该让整页打不开
            ItemStack broken = new ItemStack(Material.PAPER);
            ItemMeta meta = broken.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§7" + species);
                broken.setItemMeta(meta);
            }
            return broken;
        }
    }
}
