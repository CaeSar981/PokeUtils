package com.pixelmonmod.pixelmon.entities.pixelmon.stats;

/**
 * 编译桩：个体值（IV）存储。
 *
 * <p><b>为什么要有这个桩</b>：PokeUtils 的「属性继承 / 洗练」功能必须直接读写精灵的 IV，
 * 而这些字段在 Pixelmon 8.4.3 里是 {@code public int} —— 不声明出来 javac 无法解析。</p>
 *
 * <p><b>签名来源</b>：全部照 {@code mods/Pixelmon-1.12.2-8.4.3-universal.jar} 上
 * {@code javap} 的真实输出抄写（字段名、类型、方法描述符逐一核对），
 * 避免运行期 {@code NoSuchFieldError} / {@code NoSuchMethodError}。
 * 真实类声明为 {@code public class IVStore implements IStatStore}，
 * 因此这里也必须是 class（不能是 interface，否则 invokevirtual 会炸）。</p>
 *
 * <p><b>索引顺序约定</b>：{@code hp, attack, defence, specialAttack, specialDefence, speed}
 * —— 与真实类的字段声明顺序一致，也是 {@link #getArray()} 的返回顺序。</p>
 */
public class IVStore {

    public int hp;
    public int attack;
    public int defence;
    public int specialAttack;
    public int specialDefence;
    public int speed;

    /** 六项 IV 的数组形式：[hp, attack, defence, specialAttack, specialDefence, speed]。 */
    public int[] getArray() {
        return null;
    }

    /** 把参数里的六项 IV 复制到当前对象（注意方向：{@code this.CopyIVs(other)} 是 other -> this）。 */
    public void CopyIVs(IVStore other) {
    }

    public int getTotal() {
        return 0;
    }

    /** 标记「已改动」，让 Pixelmon 把新的 IV 写回精灵自身的 NBT。 */
    public void markDirty() {
    }
}
