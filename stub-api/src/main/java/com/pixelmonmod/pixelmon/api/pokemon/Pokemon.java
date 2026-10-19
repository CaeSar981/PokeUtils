package com.pixelmonmod.pixelmon.api.pokemon;

import com.pixelmonmod.pixelmon.entities.pixelmon.stats.IVStore;
import com.pixelmonmod.pixelmon.enums.EnumNature;
import com.pixelmonmod.pixelmon.enums.EnumSpecies;

/**
 * 编译桩：Pixelmon 精灵对象。
 *
 * 注意：在 Pixelmon 1.12.2 中这是个 <b>类</b>（原插件字节码使用 invokevirtual 调用），
 * 因此这里必须声明为 class 而非 interface，否则编译出的 invokeinterface
 * 在真机上会抛 IncompatibleClassChangeError。
 *
 * <p>下半部分的方法是为「属性继承 / 洗练」新增的，签名全部照
 * {@code mods/Pixelmon-1.12.2-8.4.3-universal.jar} 上的 {@code javap} 输出抄写：</p>
 * <pre>
 *   public com.pixelmonmod.pixelmon.entities.pixelmon.stats.IVStore getIVs();
 *   public com.pixelmonmod.pixelmon.enums.EnumNature getNature();
 *   public void setNature(com.pixelmonmod.pixelmon.enums.EnumNature);
 *   public java.lang.String getAbilityName();
 *   public void setAbility(java.lang.String);
 *   public int getAbilitySlot();
 *   public void setAbilitySlot(int);
 *   public boolean isShiny();
 *   public void setShiny(boolean);
 * </pre>
 */
public class Pokemon {

    public EnumSpecies getSpecies() {
        return null;
    }

    /**
     * 精灵唯一标识。
     *
     * <p>真机签名（Pixelmon 1.12.2-8.4.2，javap 确认）：{@code public java.util.UUID getUUID()}。
     * 用来判断「某一格里的还是不是当初选中的那一只」—— 坐标只说明位置，不说明身份。</p>
     */
    public java.util.UUID getUUID() {
        return null;
    }

    public boolean isEgg() {
        return false;
    }

    public void setLevel(int level) {
    }

    // ---------------------------------------------------------------- 属性继承 / 洗练

    /** 个体值容器。真实实现返回的是精灵内部那个<b>活的</b> IVStore（改完要调 {@code markDirty()}）。 */
    public IVStore getIVs() {
        return null;
    }

    /** 性格。 */
    public EnumNature getNature() {
        return null;
    }

    public void setNature(EnumNature nature) {
    }

    /**
     * 当前生效特性的<b>名字</b>。
     *
     * <p>注意：特性名是种族相关的 —— 把 A 种族的特性名直接塞给 B 种族的精灵会得到一只
     * 特性无效的精灵。所以「继承特性」用的是 {@link #getAbilitySlot()} 而不是这个方法，
     * 这里只用于界面展示。</p>
     */
    public String getAbilityName() {
        return null;
    }

    public void setAbility(String abilityName) {
    }

    /** 特性槽位：0 = 普通特性，1 = 隐藏特性。跨种族继承用这个才安全。 */
    public int getAbilitySlot() {
        return 0;
    }

    public void setAbilitySlot(int slot) {
    }

    public boolean isShiny() {
        return false;
    }

    public void setShiny(boolean shiny) {
    }
}
