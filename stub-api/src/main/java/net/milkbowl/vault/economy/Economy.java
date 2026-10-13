package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

/**
 * 编译桩：Vault 经济接口（仅保留本插件用到的方法）。
 * 运行期由服务端的 Vault 插件提供真实实现。
 */
public interface Economy {

    double getBalance(OfflinePlayer player);

    EconomyResponse withdrawPlayer(OfflinePlayer player, double amount);

    EconomyResponse depositPlayer(OfflinePlayer player, double amount);
}
