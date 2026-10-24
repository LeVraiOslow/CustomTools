package fr.customtools;

import org.bukkit.plugin.java.JavaPlugin;

public final class CustomTools extends JavaPlugin {
    private ToolManager tools;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        tools = new ToolManager(this);
        tools.load();
        getServer().getPluginManager().registerEvents(new ToolListener(this, tools), this);
        ToolCommand cmd = new ToolCommand(this, tools);
        getCommand("ctools").setExecutor(cmd);
        getCommand("ctools").setTabCompleter(cmd);
    }

    public Messages messages() {
        return messages;
    }

    public void reload() {
        reloadConfig();
        tools.load();
    }
}
