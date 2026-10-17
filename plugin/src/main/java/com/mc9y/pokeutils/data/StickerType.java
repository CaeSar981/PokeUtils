package com.mc9y.pokeutils.data;

import java.util.Collections;
import java.util.List;
import org.bukkit.Material;

/**
 * 一种「粘剂」的定义。
 *
 * <p>原插件把 {@code PT}（普通粘剂）与 {@code JL}（精良粘剂）硬编码在六处 {@code switch} /
 * {@code if} 里：界面渲染、点击判定、成功率计算、拥有数查询、槽位映射。
 * 想加第三种粘剂（例如「传说粘剂 +50%」）必须改 java 重新编译。现在改为从
 * config.yml 的 {@code Compound.stickers} 列表读取，加一种粘剂只是加一段 yml。</p>
 *
 * <p>{@code potion} 字段是为「普通粘剂」保留的：1.12.2 里要做出一瓶<b>水</b>瓶，
 * 必须往 POTION 的 NBT 里写 {@code Potion: minecraft:water}，光设材质不够。
 * 其它材质不需要这个字段，留空即可。</p>
 *
 * <h3>第四个五轮新增的两个可选字段</h3>
 * <ul>
 *   <li>{@code protect} —— <b>保护符</b>：带上它合成失败时<b>不消耗材料</b>。
 *       六转七的基础成功率只有 20%，一次失败就是 80 万金币加两只六星精灵，
 *       这种「倾家荡产式失败」是玩家干脆不去合成的头号原因。保护符把失败的代价
 *       从「资产蒸发」降到「一次粘剂 + 金币」，玩家的行为会完全不同。</li>
 *   <li>{@code pool} —— <b>定向粘剂</b>：非空时产物只从「目标档位产出池 ∩ 这个列表」里抽。
 *       让玩家能把随机产出收窄到自己想要的那几只，是「有目标地养」而不是「开盲盒」。</li>
 * </ul>
 * <p>两者默认都不生效（{@code false} / 空列表），所以出厂配置的行为与本功能加入前完全一致。</p>
 */
public class StickerType {

    private final String key;
    private final String display;
    private final Material material;
    private final String potion;
    private final int rate;
    private final String insufficient;
    private final int slot;
    private final boolean protect;
    private final List<String> pool;

    public StickerType(String key, String display, Material material, String potion,
                       int rate, String insufficient, int slot,
                       boolean protect, List<String> pool) {
        this.key = key;
        this.display = display;
        this.material = material;
        this.potion = potion;
        this.rate = rate;
        this.insufficient = insufficient;
        this.slot = slot;
        this.protect = protect;
        this.pool = pool == null ? Collections.<String>emptyList() : pool;
    }

    /** 存储键，同时也是 {@code YSData} 里的列名（如 {@code PT}）。 */
    public String getKey() {
        return this.key;
    }

    /** 界面上的显示名（已含颜色代码）。 */
    public String getDisplay() {
        return this.display;
    }

    /** 界面图标材质。 */
    public Material getMaterial() {
        return this.material;
    }

    /** 需要写入的 {@code Potion} NBT 值，空字符串表示不写。 */
    public String getPotion() {
        return this.potion;
    }

    /** 使用该粘剂时提供的成功率加成（%）。 */
    public int getRate() {
        return this.rate;
    }

    /** 玩家没有该粘剂时的提示文案（含颜色代码，直接拼在「你没有足够的」之后）。 */
    public String getInsufficient() {
        return this.insufficient;
    }

    /** 该粘剂在选择界面上的槽位。 */
    public int getSlot() {
        return this.slot;
    }

    /** 保护符：合成失败时是否保留两只材料精灵。 */
    public boolean isProtect() {
        return this.protect;
    }

    /** 定向粘剂指定的产出种族列表（空列表表示不做任何定向）。 */
    public List<String> getPool() {
        return this.pool;
    }
}
