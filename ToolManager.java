package fr.customtools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class ToolManager {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String DEFAULT_LORE = "<gray>Durabilit\u00e9 : <white><current><gray>/<white><max>";
    private final CustomTools plugin;
    private final NexoHook nexo;
    private final NamespacedKey idKey;
    private final NamespacedKey durKey;
    private final NamespacedKey maxKey;
    private final NamespacedKey loreKey;
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<String, ToolDefinition>();
    private final Map<String, ToolDefinition> byNexoId = new HashMap<String, ToolDefinition>();
    private final Map<Material, Material> crops = new HashMap<Material, Material>();
    private final Set<Material> hammerBlacklist = new HashSet<Material>();
    private final Set<Material> smeltBlacklist = new HashSet<Material>();
    private final Set<EntityType> killBlacklist = new HashSet<EntityType>();
    private Map<Material, FurnaceRecipe> smelting;

    public ToolManager(CustomTools plugin) {
        this.plugin = plugin;
        this.nexo = new NexoHook(plugin);
        this.idKey = new NamespacedKey((Plugin)plugin, "tool_id");
        this.durKey = new NamespacedKey((Plugin)plugin, "durability");
        this.maxKey = new NamespacedKey((Plugin)plugin, "max_durability");
        this.loreKey = new NamespacedKey((Plugin)plugin, "lore_line");
    }

    public void load() {
        this.tools.clear();
        this.byNexoId.clear();
        this.crops.clear();
        this.hammerBlacklist.clear();
        this.smeltBlacklist.clear();
        this.killBlacklist.clear();
        this.smelting = null;
        FileConfiguration cfg = this.plugin.getConfig();
        ConfigurationSection cs = cfg.getConfigurationSection("crops");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                Material crop = Material.matchMaterial((String)k);
                if (crop == null) {
                    this.plugin.getLogger().warning("Culture inconnue : " + k);
                    continue;
                }
                Material seed = Material.matchMaterial((String)cs.getString(k, "AIR"));
                this.crops.put(crop, seed == null ? Material.AIR : seed);
            }
        }
        this.readMaterials(cfg.getStringList("hammer-blacklist"), this.hammerBlacklist);
        this.readMaterials(cfg.getStringList("smelt-blacklist"), this.smeltBlacklist);
        for (String n : cfg.getStringList("multi-kill-blacklist")) {
            try {
                this.killBlacklist.add(EntityType.valueOf(n.toUpperCase(Locale.ROOT)));
            }
            catch (IllegalArgumentException ex) {
                this.plugin.getLogger().warning("Entit\u00e9 inconnue dans multi-kill-blacklist : " + n);
            }
        }
        ConfigurationSection sec = cfg.getConfigurationSection("tools");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection t = sec.getConfigurationSection(key);
            if (t == null) continue;
            Material mat = Material.matchMaterial((String)t.getString("material", "DIAMOND_PICKAXE"));
            if (mat == null) {
                this.plugin.getLogger().warning("Outil '" + key + "' : material invalide.");
                continue;
            }
            EnumSet<ToolFeature> features = EnumSet.noneOf(ToolFeature.class);
            for (String f : t.getStringList("features")) {
                try {
                    features.add(ToolFeature.valueOf(f.toUpperCase(Locale.ROOT)));
                }
                catch (IllegalArgumentException e) {
                    this.plugin.getLogger().warning("Outil '" + key + "' : feature inconnue '" + f + "'.");
                }
            }
            LinkedHashMap<Enchantment, Integer> enchants = new LinkedHashMap<Enchantment, Integer>();
            ConfigurationSection es = t.getConfigurationSection("enchantments");
            if (es != null) {
                for (String en : es.getKeys(false)) {
                    NamespacedKey nk = NamespacedKey.fromString(en.contains(":") ? en.toLowerCase(Locale.ROOT) : "minecraft:" + en.toLowerCase(Locale.ROOT));
                    Enchantment e = nk == null ? null : Registry.ENCHANTMENT.get(nk);
                    if (e == null) {
                        this.plugin.getLogger().warning("Outil '" + key + "' : enchantement inconnu '" + en + "'.");
                        continue;
                    }
                    enchants.put(e, Math.max(1, es.getInt(en, 1)));
                }
            }
            ToolDefinition def = new ToolDefinition(key.toLowerCase(Locale.ROOT), mat, t.getString("nexo-id", ""), t.getString("name", ""), t.getStringList("lore"), t.getInt("durability", 0), features, Math.max(1, t.getInt("radius", 1)), enchants, t);
            this.tools.put(def.id(), def);
            if (def.nexoId().isBlank()) continue;
            this.byNexoId.put(def.nexoId(), def);
        }
        this.plugin.getLogger().info(this.tools.size() + " outil(s) charg\u00e9(s).");
    }

    private void readMaterials(List<String> names, Set<Material> out) {
        for (String n : names) {
            Material m = Material.matchMaterial((String)n);
            if (m != null) {
                out.add(m);
                continue;
            }
            this.plugin.getLogger().warning("Material inconnu : " + n);
        }
    }

    public boolean flag(ToolDefinition d, String key, boolean def) {
        ConfigurationSection o = d.options();
        if (o != null && o.isSet(key)) {
            return o.getBoolean(key);
        }
        return this.plugin.getConfig().getBoolean(key, def);
    }

    public int number(ToolDefinition d, String key, int def) {
        ConfigurationSection o = d.options();
        if (o != null && o.isSet(key)) {
            return o.getInt(key);
        }
        return this.plugin.getConfig().getInt(key, def);
    }

    public double decimal(ToolDefinition d, String key, double def) {
        ConfigurationSection o = d.options();
        if (o != null && o.isSet(key)) {
            return o.getDouble(key);
        }
        return this.plugin.getConfig().getDouble(key, def);
    }

    public boolean killBlacklisted(EntityType t) {
        return this.killBlacklist.contains(t);
    }

    public String text(ToolDefinition d, String key, String def) {
        ConfigurationSection o = d.options();
        if (o != null && o.isSet(key)) {
            return o.getString(key, def);
        }
        return this.plugin.getConfig().getString(key, def);
    }

    public boolean cfgBool(String path, boolean def) {
        return this.plugin.getConfig().getBoolean(path, def);
    }

    public Collection<ToolDefinition> all() {
        return this.tools.values();
    }

    public ToolDefinition get(String id) {
        return this.tools.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean hammerBlacklisted(Material m) {
        return this.hammerBlacklist.contains(m);
    }

    public boolean smeltBlacklisted(Material m) {
        return this.smeltBlacklist.contains(m);
    }

    public boolean isCrop(Block b) {
        return this.crops.containsKey(b.getType()) && b.getBlockData() instanceof Ageable;
    }

    public Material seedFor(Material crop) {
        return this.crops.getOrDefault(crop, Material.AIR);
    }

    public boolean isMature(Block b) {
        Ageable a;
        BlockData blockData = b.getBlockData();
        return blockData instanceof Ageable && (a = (Ageable)blockData).getAge() >= a.getMaximumAge();
    }

    public FurnaceRecipe smelt(Material m) {
        if (this.smelting == null) {
            this.smelting = new HashMap<Material, FurnaceRecipe>();
            Iterator it = this.plugin.getServer().recipeIterator();
            while (it.hasNext()) {
                Object e = it.next();
                if (!(e instanceof FurnaceRecipe)) continue;
                FurnaceRecipe fr = (FurnaceRecipe)e;
                RecipeChoice c = fr.getInputChoice();
                if (c instanceof RecipeChoice.MaterialChoice) {
                    RecipeChoice.MaterialChoice mc = (RecipeChoice.MaterialChoice)c;
                    for (Material in : mc.getChoices()) {
                        this.smelting.putIfAbsent(in, fr);
                    }
                    continue;
                }
                if (!(c instanceof RecipeChoice.ExactChoice)) continue;
                RecipeChoice.ExactChoice ec = (RecipeChoice.ExactChoice)c;
                for (ItemStack s : ec.getChoices()) {
                    this.smelting.putIfAbsent(s.getType(), fr);
                }
            }
        }
        return this.smelting.get(m);
    }

    public ToolDefinition fromItem(ItemStack item) {
        String nid;
        ToolDefinition def;
        String id;
        if (item == null || item.getType().isAir()) {
            return null;
        }
        if (item.hasItemMeta() && (id = (String)item.getItemMeta().getPersistentDataContainer().get(this.idKey, PersistentDataType.STRING)) != null && (def = this.tools.get(id)) != null) {
            return def;
        }
        if (this.nexo.enabled() && !this.byNexoId.isEmpty() && (nid = this.nexo.idOf(item)) != null) {
            return this.byNexoId.get(nid);
        }
        return null;
    }

    public ItemStack create(ToolDefinition def, int durability) {
        ItemStack item = this.nexo.build(def.nexoId());
        if (item == null) {
            item = new ItemStack(def.material());
        }
        this.initialize(item, def, durability);
        return item;
    }

    private void initialize(ItemStack item, ToolDefinition def, int durability) {
        ItemMeta meta = item.getItemMeta();
        if (!def.name().isBlank()) {
            meta.displayName(Text.parse(def.name(), new TagResolver[0]).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        boolean ignoreLimit = this.plugin.getConfig().getBoolean("ignore-level-limit", true);
        def.enchantments().forEach((e, lvl) -> meta.addEnchant(e, lvl.intValue(), ignoreLimit));
        if (this.flag(def, "hide-attributes", true)) {
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
        }
        if (this.flag(def, "hide-enchantments", true)) {
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
        }
        if (!def.lore().isEmpty()) {
            ArrayList<Component> lore = meta.lore() == null ? new ArrayList<Component>() : new ArrayList(meta.lore());
            for (String l : def.lore()) {
                lore.add(Text.parse(l, new TagResolver[0]).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        if (def.durability() > 0) {
            this.applyDurability(item, def, durability, durability);
        } else {
            ItemMeta m = item.getItemMeta();
            m.getPersistentDataContainer().set(this.idKey, PersistentDataType.STRING, def.id());
            item.setItemMeta(m);
        }
    }

    /**
     * V\u00e9rifie / r\u00e9pare un outil (init, durabilit\u00e9, flags de masquage).
     * Modifie l'item en place. Retourne true si quelque chose a chang\u00e9.
     */
    public boolean ensure(ItemStack item, ToolDefinition def) {
        boolean changed = false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(this.idKey, PersistentDataType.STRING)) {
            this.initialize(item, def, def.durability());
            changed = true;
        } else if (def.durability() > 0 && !pdc.has(this.durKey, PersistentDataType.INTEGER)) {
            this.applyDurability(item, def, def.durability(), def.durability());
            changed = true;
        }
        ItemMeta m = item.getItemMeta();
        boolean metaChanged = false;
        if (this.flag(def, "hide-attributes", true) && !m.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES)) {
            m.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
            metaChanged = true;
        }
        if (this.flag(def, "hide-enchantments", true) && !m.hasItemFlag(ItemFlag.HIDE_ENCHANTS)) {
            m.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
            metaChanged = true;
        }
        if (metaChanged) {
            item.setItemMeta(m);
            changed = true;
        }
        return changed;
    }

    /** Re-applique les flags de masquage sur tous les outils custom de l'inventaire du joueur. */
    public void refresh(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; ++i) {
            ItemStack it = inv.getItem(i);
            ToolDefinition d = this.fromItem(it);
            if (d != null && this.ensure(it, d)) {
                inv.setItem(i, it);
            }
        }
        ItemStack off = inv.getItemInOffHand();
        ToolDefinition d = this.fromItem(off);
        if (d != null && this.ensure(off, d)) {
            inv.setItemInOffHand(off);
        }
    }

    private int storedDurability(ItemStack item) {
        Integer v = (Integer)item.getItemMeta().getPersistentDataContainer().get(this.durKey, PersistentDataType.INTEGER);
        return v == null ? -1 : v;
    }

    private int storedMax(ItemStack item, ToolDefinition def) {
        Integer v = (Integer)item.getItemMeta().getPersistentDataContainer().get(this.maxKey, PersistentDataType.INTEGER);
        return v == null ? def.durability() : v.intValue();
    }

    public int remaining(ItemStack item, ToolDefinition def) {
        if (def.durability() <= 0) {
            return Integer.MAX_VALUE;
        }
        int cur = this.storedDurability(item);
        return cur < 0 ? def.durability() : cur;
    }

    public void repair(ItemStack item, ToolDefinition def) {
        if (def.durability() <= 0) {
            return;
        }
        int max = this.storedMax(item, def);
        this.applyDurability(item, def, max, max);
    }

    private void applyDurability(ItemStack item, ToolDefinition def, int cur, int max) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(this.idKey, PersistentDataType.STRING, def.id());
        pdc.set(this.durKey, PersistentDataType.INTEGER, cur);
        pdc.set(this.maxKey, PersistentDataType.INTEGER, max);
        this.updateLore(meta, def, cur, max);
        if (meta instanceof Damageable) {
            int vmax;
            Damageable d = (Damageable)meta;
            int n = vmax = d.hasMaxDamage() ? d.getMaxDamage() : (int)item.getType().getMaxDurability();
            if (vmax > 0) {
                int dmg = (int)Math.round((1.0 - (double)cur / (double)max) * (double)vmax);
                d.setDamage(Math.max(0, Math.min(vmax - 1, dmg)));
            }
        }
        item.setItemMeta(meta);
    }

    private void updateLore(ItemMeta meta, ToolDefinition def, int cur, int max) {
        String format = this.text(def, "durability-lore", DEFAULT_LORE);
        String probe = PLAIN.serialize(Text.parse(format, new TagResolver[]{Placeholder.unparsed((String)"current", (String)"\u0001"), Placeholder.unparsed((String)"max", (String)"\u0001")}));
        int cut = probe.indexOf(1);
        String prefix = cut >= 0 ? probe.substring(0, cut) : probe;
        ArrayList<Component> lore = meta.lore() == null ? new ArrayList<Component>() : new ArrayList(meta.lore());
        Component line = Text.parse(format, new TagResolver[]{Placeholder.unparsed((String)"current", (String)String.valueOf(cur)), Placeholder.unparsed((String)"max", (String)String.valueOf(max))}).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int idx = (Integer)pdc.getOrDefault(this.loreKey, PersistentDataType.INTEGER, -1);
        if (idx >= 0 && idx < lore.size() && PLAIN.serialize((Component)lore.get(idx)).startsWith(prefix)) {
            lore.set(idx, line);
        } else {
            lore.add(line);
            pdc.set(this.loreKey, PersistentDataType.INTEGER, (lore.size() - 1));
        }
        meta.lore(lore);
    }

    public void consume(Player p, ToolDefinition def, int amount) {
        double threshold;
        int lvl;
        if (def.durability() <= 0 || p.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        ToolDefinition now = this.fromItem(hand);
        if (now == null || !now.id().equals(def.id())) {
            return;
        }
        int cur = this.storedDurability(hand);
        if (cur < 0) {
            cur = def.durability();
        }
        int max = this.storedMax(hand, def);
        int cost = amount;
        if (this.plugin.getConfig().getBoolean("respect-unbreaking", true) && (lvl = hand.getEnchantmentLevel(Enchantment.UNBREAKING)) > 0) {
            cost = 0;
            for (int i = 0; i < amount; ++i) {
                if (ThreadLocalRandom.current().nextInt(lvl + 1) != 0) continue;
                ++cost;
            }
        }
        if (cost <= 0) {
            return;
        }
        ItemMeta m = hand.getItemMeta();
        Component name = m.hasDisplayName() ? m.displayName() : Component.text((String)def.id());
        int next = Math.max(0, cur - cost);
        if (next == 0) {
            p.getInventory().setItemInMainHand(null);
            String sound = this.plugin.getConfig().getString("sounds.tool-break", "");
            if (sound != null && !sound.isBlank()) {
                p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
            }
            this.plugin.messages().send((CommandSender)p, "tool-broken", new TagResolver[]{Placeholder.component((String)"tool", (ComponentLike)name)});
            return;
        }
        this.applyDurability(hand, def, next, max);
        p.getInventory().setItemInMainHand(hand);
        int pct = this.plugin.getConfig().getInt("low-durability-warning-percent", 10);
        if (pct > 0 && (double)cur > (threshold = (double)(max * pct) / 100.0) && (double)next <= threshold) {
            p.sendActionBar(this.plugin.messages().plain("low-durability", new TagResolver[]{Placeholder.unparsed((String)"current", (String)String.valueOf(next)), Placeholder.unparsed((String)"max", (String)String.valueOf(max)), Placeholder.component((String)"tool", (ComponentLike)name)}));
        }
    }
}

