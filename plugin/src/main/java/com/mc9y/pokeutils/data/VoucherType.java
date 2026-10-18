package com.mc9y.pokeutils.data;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成券定义：一种「可以直接顶一次合成的金币消耗」的物品。
 *
 * <p>用途是运营侧的：活动、补偿、礼包直接发一个物品就行，不需要动经济系统，
 * 也不会像给金币那样立刻造成通胀。玩家背包里有匹配的券时，合成优先扣券、不扣金币。</p>
 *
 * <p>匹配规则从宽到严三层，全部可选：</p>
 * <ol>
 *   <li>{@code material} —— 必填，Bukkit 材质名；</li>
 *   <li>{@code name} —— 可选，精确匹配物品显示名（区分颜色代码）；</li>
 *   <li>{@code lore-keyword} —— 可选，物品 lore 里必须包含的关键字。</li>
 * </ol>
 *
 * <p>{@code star-limit} 用来限制适用范围：0 = 不限；&gt;0 表示只能用于
 * 「材料星级 &lt;= star-limit」的合成。这样「一星券」就不会被拿去顶七星的百万消耗。</p>
 */
public class VoucherType {

    private final Material material;
    private final String name;
    private final String loreKeyword;
    private final int starLimit;
    private final String display;

    public VoucherType(Material material, String name, String loreKeyword, int starLimit, String display) {
        this.material = material;
        this.name = name;
        this.loreKeyword = loreKeyword;
        this.starLimit = starLimit;
        this.display = display;
    }

    public Material getMaterial() {
        return this.material;
    }

    public int getStarLimit() {
        return this.starLimit;
    }

    /** 提示用名称（没配 display 时用物品显示名或材质名）。 */
    public String getDisplay() {
        if (this.display != null && !this.display.isEmpty()) {
            return this.display;
        }
        if (this.name != null && !this.name.isEmpty()) {
            return this.name;
        }
        return "§f" + this.material.name();
    }

    /** 该券能否用于指定材料星级的合成。 */
    public boolean allowsStar(int star) {
        return this.starLimit <= 0 || star <= this.starLimit;
    }

    /**
     * 按配置造出一张「能被 {@link #matches} 认出来」的券。
     *
     * <p>奖励发放（委托领奖 / 图鉴里程碑）只从这里造物品，不自己拼 ItemStack ——
     * 否则「发出去的券」和「判定时认的券」会各写一遍，改一处忘一处就变成
     * 「发了一张自己花不掉的券」。造法必须与 {@code matches} 的判定条件严格对称：
     * 有 name 就写显示名，有 lore-keyword 就写进 lore。</p>
     */
    public ItemStack createItem(int amount) {
        ItemStack item = new ItemStack(this.material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (this.name != null && !this.name.isEmpty()) {
                meta.setDisplayName(this.name);
            }
            if (this.loreKeyword != null && !this.loreKeyword.isEmpty()) {
                List<String> lore = new ArrayList<String>();
                lore.add("§7" + this.loreKeyword);
                meta.setLore(lore);
                // name 与 lore 都为空时 meta 没有实际改动，但 setItemMeta 无害；
                // 反过来漏掉 setItemMeta 会让上面两行白写。
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 用 display / name / 材质名中的任意一个指认这张券。
     *
     * <p>奖励配置里填的就是这个字符串。优先 display（运营最常改、也最好认），
     * 其次 name，最后才退到材质名 —— 材质名兜底意味着「同材质只配了一张券」时
     * 可以偷懒不写 display，但同材质配多张时请务必写 display，否则会命中第一张。</p>
     */
    public boolean matchesKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        String k = key.trim();
        if (this.display != null && this.display.equalsIgnoreCase(k)) {
            return true;
        }
        if (this.name != null && this.name.equalsIgnoreCase(k)) {
            return true;
        }
        return this.material.name().equalsIgnoreCase(k);
    }

    /** 这个物品是不是本券。 */
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != this.material) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (this.name != null && !this.name.isEmpty()) {
            if (meta == null || !this.name.equals(meta.getDisplayName())) {
                return false;
            }
        }
        if (this.loreKeyword != null && !this.loreKeyword.isEmpty()) {
            if (meta == null || meta.getLore() == null) {
                return false;
            }
            boolean hit = false;
            for (String line : meta.getLore()) {
                if (line != null && line.contains(this.loreKeyword)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return false;
            }
        }
        return true;
    }
}
