package com.pixelmonmod.pixelmon.api.pokemon;

import com.pixelmonmod.pixelmon.enums.EnumSpecies;

/**
 * 编译桩：精灵工厂（由 Pixelmon.pokemonFactory 静态字段持有实例）。
 *
 * 注意：在 Pixelmon 1.12.2 中这是个 <b>类</b>，且 create 是<b>实例方法</b>
 * （原插件字节码为 PokemonFactory.create 的 invokevirtual），
 * 因此这里必须声明为 class 而非 interface。
 */
public class PokemonFactory {

    public Pokemon create(EnumSpecies species) {
        return null;
    }
}
