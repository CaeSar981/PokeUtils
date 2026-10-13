package com.pixelmonmod.pixelmon;

import com.pixelmonmod.pixelmon.api.pokemon.PokemonFactory;
import com.pixelmonmod.pixelmon.api.storage.IStorageManager;

/**
 * 编译桩：Pixelmon 主类静态入口。
 * 运行期由服务端的 Pixelmon 模组提供真实实现。
 */
public class Pixelmon {

    public static IStorageManager storageManager;

    public static PokemonFactory pokemonFactory;
}
