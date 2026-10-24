package fr.customtools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** Tous les messages viennent de config.yml (section messages). Une valeur vide = message désactivé. */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final CustomTools plugin;

    public Messages(CustomTools plugin) {
        this.plugin = plugin;
    }

    private String raw(String key) {
        return plugin.getConfig().getString("messages." + key);
    }

    /** Message sans préfixe (action bar, etc.). */
    public Component plain(String key, TagResolver... resolvers) {
        String r = raw(key);
        if (r == null) r = "<red>Message manquant : " + key;
        return MM.deserialize(r, resolvers);
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        String r = raw(key);
        if (r != null && r.isBlank()) return;
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        to.sendMessage(MM.deserialize(prefix + (r == null ? "<red>Message manquant : " + key : r), resolvers));
    }
}
