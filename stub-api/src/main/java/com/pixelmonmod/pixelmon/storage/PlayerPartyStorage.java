package com.pixelmonmod.pixelmon.storage;

import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import java.util.List;

/**
 * 编译桩：玩家队伍存储。
 *
 * 注意：在 Pixelmon 1.12.2 中这是个 <b>类</b>（原插件字节码使用 invokevirtual 调用），
 * 因此这里必须声明为 class 而非 interface。
 */
public class PlayerPartyStorage {

    public Pokemon get(int slot) {
        return null;
    }

    public void set(int slot, Pokemon pokemon) {
    }

    public List<Pokemon> getTeam() {
        return null;
    }
}
