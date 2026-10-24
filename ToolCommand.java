package fr.customtools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ToolCommand implements CommandExecutor, TabCompleter {
    private static final String PERM = "customtools.admin";
    private final CustomTools plugin;
    private final ToolManager tools;

    public ToolCommand(CustomTools plugin, ToolManager tools) {
        this.plugin = plugin;
        this.tools = tools;
    }

    private void msg(CommandSender s, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        plugin.messages().send(s, key, r);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(PERM)) { msg(s, "no-permission"); return true; }
        if (a.length == 0) { msg(s, "usage"); return true; }

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                msg(s, "reloaded");
            }
            case "list" -> msg(s, "list", Placeholder.unparsed("tools",
                    String.join(", ", tools.all().stream().map(ToolDefinition::id).toList())));
            case "give" -> {
                if (a.length < 3) { msg(s, "usage-give"); return true; }
                Player target = Bukkit.getPlayerExact(a[1]);
                ToolDefinition def = tools.get(a[2]);
                if (target == null) { msg(s, "unknown-player"); return true; }
                if (def == null) { msg(s, "unknown-tool"); return true; }
                int dur = def.durability();
                if (a.length >= 4) {
                    try { dur = Math.max(1, Integer.parseInt(a[3])); }
                    catch (NumberFormatException e) { msg(s, "invalid-durability"); return true; }
                }
                ItemStack item = tools.create(def, dur);
                Component name = item.getItemMeta().hasDisplayName()
                        ? item.getItemMeta().displayName() : Component.text(def.id());
                target.getInventory().addItem(item)
                        .values().forEach(i -> target.getWorld().dropItemNaturally(target.getLocation(), i));
                msg(s, "given", Placeholder.component("tool", name), Placeholder.unparsed("player", target.getName()));
                if (!target.equals(s)) msg(target, "received", Placeholder.component("tool", name));
            }
            case "repair" -> {
                if (!(s instanceof Player p)) { msg(s, "player-only"); return true; }
                ItemStack hand = p.getInventory().getItemInMainHand();
                ToolDefinition def = tools.fromItem(hand);
                if (def == null) { msg(s, "hold-tool"); return true; }
                tools.repair(hand, def);
                p.getInventory().setItemInMainHand(hand);
                msg(s, "repaired");
            }
            default -> msg(s, "unknown-subcommand");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission(PERM)) return out;
        if (a.length == 1) {
            for (String x : List.of("give", "list", "repair", "reload")) if (x.startsWith(a[0].toLowerCase())) out.add(x);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(p -> { if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName()); });
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            tools.all().forEach(t -> { if (t.id().startsWith(a[2].toLowerCase())) out.add(t.id()); });
        }
        return out;
    }
}
