package fr.customtools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class ToolCommand
implements CommandExecutor,
TabCompleter {
    private static final String PERM = "customtools.admin";
    private final CustomTools plugin;
    private final ToolManager tools;

    public ToolCommand(CustomTools plugin, ToolManager tools) {
        this.plugin = plugin;
        this.tools = tools;
    }

    private void msg(CommandSender s, String key, TagResolver ... r) {
        this.plugin.messages().send(s, key, r);
    }

    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(PERM)) {
            this.msg(s, "no-permission", new TagResolver[0]);
            return true;
        }
        if (a.length == 0) {
            this.msg(s, "usage", new TagResolver[0]);
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload": {
                this.plugin.reload();
                this.msg(s, "reloaded", new TagResolver[0]);
                break;
            }
            case "list": {
                this.msg(s, "list", new TagResolver[]{Placeholder.unparsed((String)"tools", (String)String.join((CharSequence)", ", this.tools.all().stream().map(ToolDefinition::id).toList()))});
                break;
            }
            case "give": {
                ItemStack item;
                if (a.length < 3) {
                    this.msg(s, "usage-give", new TagResolver[0]);
                    return true;
                }
                Player target = Bukkit.getPlayerExact((String)a[1]);
                ToolDefinition def = this.tools.get(a[2]);
                if (target == null) {
                    this.msg(s, "unknown-player", new TagResolver[0]);
                    return true;
                }
                if (def == null) {
                    this.msg(s, "unknown-tool", new TagResolver[0]);
                    return true;
                }
                int dur = def.durability();
                if (a.length >= 4) {
                    try {
                        dur = Math.max(1, Integer.parseInt(a[3]));
                    }
                    catch (NumberFormatException e) {
                        this.msg(s, "invalid-durability", new TagResolver[0]);
                        return true;
                    }
                }
                Component name = (item = this.tools.create(def, dur)).getItemMeta().hasDisplayName() ? item.getItemMeta().displayName() : Component.text((String)def.id());
                target.getInventory().addItem(new ItemStack[]{item}).values().forEach(i -> target.getWorld().dropItemNaturally(target.getLocation(), i));
                this.msg(s, "given", new TagResolver[]{Placeholder.component((String)"tool", (ComponentLike)name), Placeholder.unparsed((String)"player", (String)target.getName())});
                if (target.equals(s)) break;
                this.msg((CommandSender)target, "received", new TagResolver[]{Placeholder.component((String)"tool", (ComponentLike)name)});
                break;
            }
            case "repair": {
                if (!(s instanceof Player)) {
                    this.msg(s, "player-only", new TagResolver[0]);
                    return true;
                }
                Player p = (Player)s;
                ItemStack hand = p.getInventory().getItemInMainHand();
                ToolDefinition def = this.tools.fromItem(hand);
                if (def == null) {
                    this.msg(s, "hold-tool", new TagResolver[0]);
                    return true;
                }
                this.tools.repair(hand, def);
                p.getInventory().setItemInMainHand(hand);
                this.msg(s, "repaired", new TagResolver[0]);
                break;
            }
            default: {
                this.msg(s, "unknown-subcommand", new TagResolver[0]);
            }
        }
        return true;
    }

    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        ArrayList<String> out = new ArrayList<String>();
        if (!s.hasPermission(PERM)) {
            return out;
        }
        if (a.length == 1) {
            for (String x : List.of("give", "list", "repair", "reload")) {
                if (!x.startsWith(a[0].toLowerCase())) continue;
                out.add(x);
            }
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(p -> {
                if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) {
                    out.add(p.getName());
                }
            });
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            this.tools.all().forEach(t -> {
                if (t.id().startsWith(a[2].toLowerCase())) {
                    out.add(t.id());
                }
            });
        }
        return out;
    }
}

