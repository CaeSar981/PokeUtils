package com.pixelmonmod.pixelmon.api.storage;

import com.pixelmonmod.pixelmon.storage.PlayerPartyStorage;
import java.util.UUID;

public interface IStorageManager {

    PlayerPartyStorage getParty(UUID uuid);
}
