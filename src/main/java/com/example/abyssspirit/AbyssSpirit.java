package com.example.abyssspirit;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class AbyssSpirit extends JavaPlugin {

    private AbyssManager abyss;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        abyss = new AbyssManager(this);
        getServer().getPluginManager().registerEvents(abyss, this);

        AbyssCommand command = new AbyssCommand(this);
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("abyss"));
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        abyss.start();
    }

    @Override
    public void onDisable() {
        if (abyss != null) abyss.shutdown();
    }

    public AbyssManager abyss() {
        return abyss;
    }
}
