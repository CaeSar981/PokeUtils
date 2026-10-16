package com.pixelmonmod.pixelmon.api.storage;

import com.pixelmonmod.pixelmon.storage.PlayerPartyStorage;
import java.util.UUID;

public interface IStorageManager {

    PlayerPartyStorage getParty(UUID uuid);

    /**
     * 玩家的电脑盒子存储。
     *
     * <p>新增（A1 盒子选料）：这是「从电脑盒子取合成材料」的唯一入口。
     * 真机上它声明在 {@code IStorageManager} 接口上，所以这里也必须声明为接口方法 ——
     * 否则编译出的 invoke 种类会和真机对不上（原插件在 {@code PlayerPartyStorage} 上
     * 踩过同一类坑：那里是 class，写成 interface 会抛 {@code IncompatibleClassChangeError}）。</p>
     */
    PCStorage getPCForPlayer(UUID uuid);
}
