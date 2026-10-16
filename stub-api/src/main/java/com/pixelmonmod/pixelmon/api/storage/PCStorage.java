package com.pixelmonmod.pixelmon.api.storage;

import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;

/**
 * 编译桩：玩家的电脑盒子（PC）存储。
 *
 * <p><b>只声明插件真正调用的那几个方法</b>，刻意不建模真实类的继承关系与全部成员 ——
 * 空壳的每一行都是「编译期凑合、运行期被真类顶掉」的东西，声明得越多、越容易和真类漂移。
 * 真机上这些方法（以及 {@code getBoxCount()} 等）都直接声明在 {@code PCStorage} 自己身上，
 * 所以运行期解析没有任何歧义。</p>
 *
 * <p>⚠️ 刻意<b>不使用</b> {@code PCBox.POKEMON_PER_BOX}：那是个
 * {@code public static final int}（真值 30），javac 会把常量<b>内联</b>进调用方字节码 ——
 * 一旦空壳里写的值和真机不同，就会静默算错。箱子容量改由
 * {@code PCStorage.getAll().length / getBoxCount()} 在运行期推出。</p>
 *
 * <p>在 Pixelmon 1.12.2 中这是个 <b>类</b>（原插件字节码使用 invokevirtual 调用），
 * 因此这里必须声明为 class 而非 interface。</p>
 */
public class PCStorage {

    /** 箱子数量。 */
    public int getBoxCount() {
        return 0;
    }

    /** 全部箱子里的精灵拉平成一个数组，长度 = 箱子数 × 每箱容量。 */
    public Pokemon[] getAll() {
        return null;
    }

    public Pokemon get(int box, int slot) {
        return null;
    }

    public void set(int box, int slot, Pokemon pokemon) {
    }
}
