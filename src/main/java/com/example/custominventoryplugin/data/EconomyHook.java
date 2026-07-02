package com.example.custominventoryplugin.data;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Optional Vault economy bridge used for capacity-tier purchases. Resolved
 * reflectively so the plugin doesn't need a compile-time Vault dependency:
 * if Vault (or a backing economy) is missing, {@link #isAvailable()} is false
 * and tier upgrades that cost money are refused with a clear message.
 */
public class EconomyHook {

    private Object economy;
    private Method hasMethod;
    private Method withdrawMethod;
    private Method responseSuccess;

    public EconomyHook(Plugin plugin) {
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp == null) return;
            this.economy = rsp.getProvider();
            if (economy == null) return;
            this.hasMethod = economyClass.getMethod("has", OfflinePlayer.class, double.class);
            this.withdrawMethod = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            Class<?> respClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            this.responseSuccess = respClass.getMethod("transactionSuccess");
            plugin.getLogger().info("Vault economy detected — backpack tier upgrades enabled.");
        } catch (Throwable t) {
            this.economy = null;
        }
    }

    public boolean isAvailable() {
        return economy != null && hasMethod != null && withdrawMethod != null;
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (!isAvailable()) return false;
        try {
            return (boolean) hasMethod.invoke(economy, player, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Withdraws {@code amount}; returns true on success. */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!isAvailable()) return false;
        try {
            Object resp = withdrawMethod.invoke(economy, player, amount);
            if (responseSuccess != null && resp != null) {
                return (boolean) responseSuccess.invoke(resp);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
