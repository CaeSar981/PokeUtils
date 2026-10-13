package com.pixelmonmod.pixelmon.api.pokemon;

import com.pixelmonmod.pixelmon.enums.EnumSpecies;

/**
 * 编译桩：Pixelmon 精灵对象。
 *
 * 注意：在 Pixelmon 1.12.2 中这是个 <b>类</b>（原插件字节码使用 invokevirtual 调用），
 * 因此这里必须声明为 class 而非 interface，否则编译出的 invokeinterface
 * 在真机上会抛 IncompatibleClassChangeError。
 */
public class Pokemon {

    public EnumSpecies getSpecies() {
        return null;
    }

    public boolean isEgg() {
        return false;
    }

    public void setLevel(int level) {
    }
}
