package fr.customtools;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class CustomTools
extends JavaPlugin {
    private ToolManager tools;
    private Messages messages;

    public void onEnable() {
        this.saveDefaultConfig();
        this.messages = new Messages(this);
        this.tools = new ToolManager(this);
        this.tools.load();
        this.getServer().getPluginManager().registerEvents((Listener)new ToolListener(this, this.tools), (Plugin)this);
        ToolCommand cmd = new ToolCommand(this, this.tools);
        this.getCommand("ctools").setExecutor((CommandExecutor)cmd);
        this.getCommand("ctools").setTabCompleter((TabCompleter)cmd);
    }

    public Messages messages() {
        return this.messages;
    }

    public void reload() {
        this.reloadConfig();
        this.tools.load();
    }
}

