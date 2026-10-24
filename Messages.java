package fr.customtools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

public final class Messages {
    private final CustomTools plugin;

    public Messages(CustomTools plugin) {
        this.plugin = plugin;
    }

    private String raw(String key) {
        return this.plugin.getConfig().getString("messages." + key);
    }

    public Component plain(String key, TagResolver ... resolvers) {
        Object r = this.raw(key);
        if (r == null) {
            r = "<red>Message manquant : " + key;
        }
        return Text.parse((String)r, resolvers);
    }

    public void send(CommandSender to, String key, TagResolver ... resolvers) {
        String r = this.raw(key);
        if (r != null && r.isBlank()) {
            return;
        }
        String prefix = this.plugin.getConfig().getString("messages.prefix", "");
        to.sendMessage(Text.parse(prefix + (String)(r == null ? "<red>Message manquant : " + key : r), resolvers));
    }
}

