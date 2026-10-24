package fr.customtools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class ToolManager {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String DEFAULT_LORE = "<gray>Durabilité : <white><current><gray>/<white><max>";

    private final CustomTools plugin;
    private final NexoHook nexo;
    private final NamespacedKey idKey, durKey, maxKey, loreKey;

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    private final Map<String, ToolDefinition> byNexoId = new HashMap<>();
    private final Map<Material, Material> crops = new HashMap<>(); // culture -> graine (AIR = gratuit)
    private final Set<Material> hammerBlacklist = new HashSet<>();
    private final Set<Material> smeltBlacklist = new HashSet<>();
    private Map<Material, FurnaceRecipe> smelting;

    public ToolManager(CustomTools plugin) {
        this.plugin = plugin;
        this.nexo = new NexoHook(plugin);
        this.idKey = new NamespacedKey(plugin, "tool_id");
        this.durKey = new NamespacedKey(plugin, "durability");
        this.maxKey = new NamespacedKey(plugin, "max_durability");
        this.loreKey = new NamespacedKey(plugin, "lore_line");
    }

    // ───────────────────────── Chargement ─────────────────────────
    public void load() {
        tools.clear();
        byNexoId.clear();
        crops.clear();
        hammerBlacklist.clear();
        smeltBlacklist.clear();
        smelting = null;

        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection cs = cfg.getConfigurationSection("crops");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                Material crop = Material.matchMaterial(k);
                if (crop == null) { plugin.getLogger().warning("Culture inconnue : " + k); continue; }
                Material seed = Material.matchMaterial(cs.getString(k, "AIR"));
                crops.put(crop, seed == null ? Material.AIR : seed);
            }
        }
        readMaterials(cfg.getStringList("hammer-blacklist"), hammerBlacklist);
        readMaterials(cfg.getStringList("smelt-blacklist"), smeltBlacklist);

        ConfigurationSection sec = cfg.getConfigurationSection("tools");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection t = sec.getConfigurationSection(key);
            if (t == null) continue;
            Material mat = Material.matchMaterial(t.getString("material", "DIAMOND_PICKAXE"));
            if (mat == null) {
                plugin.getLogger().warning("Outil '" + key + "' : material invalide.");
                continue;
            }
            Set<ToolFeature> features = EnumSet.noneOf(ToolFeature.class);
            for (String f : t.getStringList("features")) {
                try {
                    features.add(ToolFeature.valueOf(f.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Outil '" + key + "' : feature inconnue '" + f + "'.");
                }
            }
            Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
            ConfigurationSection es = t.getConfigurationSection("enchantments");
            if (es != null) {
                for (String en : es.getKeys(false)) {
                    NamespacedKey nk = NamespacedKey.fromString(en.contains(":") ? en.toLowerCase(Locale.ROOT)
                            : "minecraft:" + en.toLowerCase(Locale.ROOT));
                    Enchantment e = nk == null ? null : Registry.ENCHANTMENT.get(nk);
                    if (e == null) {
                        plugin.getLogger().warning("Outil '" + key + "' : enchantement inconnu '" + en + "'.");
                        continue;
                    }
                    enchants.put(e, Math.max(1, es.getInt(en, 1)));
                }
            }
            ToolDefinition def = new ToolDefinition(key.toLowerCase(Locale.ROOT), mat,
                    t.getString("nexo-id", ""), t.getString("name", ""), t.getStringList("lore"),
                    t.getInt("durability", 0), features, Math.max(1, t.getInt("radius", 1)), enchants, t);
            tools.put(def.id(), def);
            if (!def.nexoId().isBlank()) byNexoId.put(def.nexoId(), def);
        }
        plugin.getLogger().info(tools.size() + " outil(s) chargé(s).");
    }

    private void readMaterials(List<String> names, Set<Material> out) {
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) out.add(m);
            else plugin.getLogger().warning("Material inconnu : " + n);
        }
    }

    // ───────────────────────── Réglages (global + surcharge par outil) ─────────────────────────
    public boolean flag(ToolDefinition d, String key, boolean def) {
        ConfigurationSection o = d.options();
        if (o != null && o.isSet(key)) return o.getBoolean(key);
        return plugin.getConfig().getBoolean(key, def);
    }

    public int number(ToolDefinition d, String key, int def) {
        ConfigurationSection o = d.options();
        if (o != null && o.isSet(key)) return o.getInt(key);
        return plugin.getConfig().getInt(key, def);
    }

    public String text(ToolDefinition d, String key, String def) {
        ConfigurationSection o = d.options();
        if (o != null && o.isSet(key)) return o.getString(key, def);
        return plugin.getConfig().getString(key, def);
    }

    public boolean cfgBool(String path, boolean def) {
        return plugin.getConfig().getBoolean(path, def);
    }

    // ───────────────────────── Accès ─────────────────────────
    public Collection<ToolDefinition> all() { return tools.values(); }
    public ToolDefinition get(String id) { return tools.get(id.toLowerCase(Locale.ROOT)); }
    public boolean hammerBlacklisted(Material m) { return hammerBlacklist.contains(m); }
    public boolean smeltBlacklisted(Material m) { return smeltBlacklist.contains(m); }

    public boolean isCrop(Block b) {
        return crops.containsKey(b.getType()) && b.getBlockData() instanceof Ageable;
    }

    /** Item consommé pour replanter cette culture (AIR = gratuit). */
    public Material seedFor(Material crop) {
        return crops.getOrDefault(crop, Material.AIR);
    }

    public boolean isMature(Block b) {
        return b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge();
    }

    public FurnaceRecipe smelt(Material m) {
        if (smelting == null) {
            smelting = new HashMap<>();
            for (Iterator<Recipe> it = plugin.getServer().recipeIterator(); it.hasNext(); ) {
                if (!(it.next() instanceof FurnaceRecipe fr)) continue;
                RecipeChoice c = fr.getInputChoice();
                if (c instanceof RecipeChoice.MaterialChoice mc) {
                    for (Material in : mc.getChoices()) smelting.putIfAbsent(in, fr);
                } else if (c instanceof RecipeChoice.ExactChoice ec) {
                    for (ItemStack s : ec.getChoices()) smelting.putIfAbsent(s.getType(), fr);
                }
            }
        }
        return smelting.get(m);
    }

    // ───────────────────────── Identification d'un item ─────────────────────────
    /** Reconnaît un outil par son tag PDC, ou par son id Nexo si configuré. */
    public ToolDefinition fromItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        if (item.hasItemMeta()) {
            String id = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            if (id != null) {
                ToolDefinition def = tools.get(id);
                if (def != null) return def;
            }
        }
        if (nexo.enabled() && !byNexoId.isEmpty()) {
            String nid = nexo.idOf(item);
            if (nid != null) return byNexoId.get(nid);
        }
        return null;
    }

    // ───────────────────────── Création / initialisation ─────────────────────────
    public ItemStack create(ToolDefinition def, int durability) {
        ItemStack item = nexo.build(def.nexoId());
        if (item == null) item = new ItemStack(def.material());
        initialize(item, def, durability);
        return item;
    }

    /** Nom, enchantements, lore et durabilité (une seule fois par item). */
    private void initialize(ItemStack item, ToolDefinition def, int durability) {
        ItemMeta meta = item.getItemMeta();
        if (!def.name().isBlank()) { // nom vide = on garde le nom de l'item (ex : Nexo)
            meta.displayName(Text.parse(def.name()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        boolean ignoreLimit = plugin.getConfig().getBoolean("ignore-level-limit", true);
        def.enchantments().forEach((e, lvl) -> meta.addEnchant(e, lvl, ignoreLimit));
        if (flag(def, "hide-attributes", true)) meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (!def.lore().isEmpty()) {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            for (String l : def.lore()) {
                lore.add(Text.parse(l).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(lore);
        }
        item.setItemMeta(meta);

        if (def.durability() > 0) {
            applyDurability(item, def, durability, durability);
        } else {
            ItemMeta m = item.getItemMeta();
            m.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id());
            item.setItemMeta(m);
        }
    }

    /** Initialise les items reconnus via Nexo (donnés par /nexo give) ou dont la config a changé. */
    public ItemStack ensure(ItemStack item, ToolDefinition def) {
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(idKey, PersistentDataType.STRING)) {
            initialize(item, def, def.durability());
        } else if (def.durability() > 0 && !pdc.has(durKey, PersistentDataType.INTEGER)) {
            applyDurability(item, def, def.durability(), def.durability());
        }
        if (flag(def, "hide-attributes", true)) {
            ItemMeta m = item.getItemMeta();
            if (!m.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES)) {
                m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                item.setItemMeta(m);
            }
        }
        return item;
    }

    // ───────────────────────── Durabilité ─────────────────────────
    private int storedDurability(ItemStack item) {
        Integer v = item.getItemMeta().getPersistentDataContainer().get(durKey, PersistentDataType.INTEGER);
        return v == null ? -1 : v;
    }

    private int storedMax(ItemStack item, ToolDefinition def) {
        Integer v = item.getItemMeta().getPersistentDataContainer().get(maxKey, PersistentDataType.INTEGER);
        return v == null ? def.durability() : v;
    }

    /** Durabilité restante (Integer.MAX_VALUE si l'outil n'a pas de durabilité custom). */
    public int remaining(ItemStack item, ToolDefinition def) {
        if (def.durability() <= 0) return Integer.MAX_VALUE;
        int cur = storedDurability(item);
        return cur < 0 ? def.durability() : cur;
    }

    public void repair(ItemStack item, ToolDefinition def) {
        if (def.durability() <= 0) return;
        int max = storedMax(item, def);
        applyDurability(item, def, max, max);
    }

    private void applyDurability(ItemStack item, ToolDefinition def, int cur, int max) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, def.id());
        pdc.set(durKey, PersistentDataType.INTEGER, cur);
        pdc.set(maxKey, PersistentDataType.INTEGER, max);
        updateLore(meta, def, cur, max);

        // Barre de durabilité vanilla synchronisée avec la durabilité custom
        if (meta instanceof Damageable d) {
            int vmax = d.hasMaxDamage() ? d.getMaxDamage() : item.getType().getMaxDurability();
            if (vmax > 0) {
                int dmg = (int) Math.round((1.0 - (double) cur / max) * vmax);
                d.setDamage(Math.max(0, Math.min(vmax - 1, dmg)));
            }
        }
        item.setItemMeta(meta);
    }

    private void updateLore(ItemMeta meta, ToolDefinition def, int cur, int max) {
        String format = text(def, "durability-lore", DEFAULT_LORE);
        String probe = PLAIN.serialize(Text.parse(format,
                Placeholder.unparsed("current", "\u0001"), Placeholder.unparsed("max", "\u0001")));
        int cut = probe.indexOf('\u0001');
        String prefix = cut >= 0 ? probe.substring(0, cut) : probe;

        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        Component line = Text.parse(format,
                        Placeholder.unparsed("current", String.valueOf(cur)),
                        Placeholder.unparsed("max", String.valueOf(max)))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int idx = pdc.getOrDefault(loreKey, PersistentDataType.INTEGER, -1);
        if (idx >= 0 && idx < lore.size() && PLAIN.serialize(lore.get(idx)).startsWith(prefix)) {
            lore.set(idx, line);
        } else {
            lore.add(line);
            pdc.set(loreKey, PersistentDataType.INTEGER, lore.size() - 1);
        }
        meta.lore(lore);
    }

    /** Retire de la durabilité à l'outil tenu en main (Unbreaking pris en compte) ; le détruit à 0. */
    public void consume(Player p, ToolDefinition def, int amount) {
        if (def.durability() <= 0 || p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        ToolDefinition now = fromItem(hand);
        if (now == null || !now.id().equals(def.id())) return;

        int cur = storedDurability(hand);
        if (cur < 0) cur = def.durability();
        int max = storedMax(hand, def);

        int cost = amount;
        if (plugin.getConfig().getBoolean("respect-unbreaking", true)) {
            int lvl = hand.getEnchantmentLevel(Enchantment.UNBREAKING);
            if (lvl > 0) {
                cost = 0;
                for (int i = 0; i < amount; i++) {
                    if (ThreadLocalRandom.current().nextInt(lvl + 1) == 0) cost++;
                }
            }
        }
        if (cost <= 0) return;

        ItemMeta m = hand.getItemMeta();
        Component name = m.hasDisplayName() ? m.displayName() : Component.text(def.id());
        int next = Math.max(0, cur - cost);

        if (next == 0) {
            p.getInventory().setItemInMainHand(null);
            String sound = plugin.getConfig().getString("sounds.tool-break", "");
            if (sound != null && !sound.isBlank()) p.playSound(p.getLocation(), sound, 1f, 1f);
            plugin.messages().send(p, "tool-broken", Placeholder.component("tool", name));
            return;
        }
        applyDurability(hand, def, next, max);
        p.getInventory().setItemInMainHand(hand);

        int pct = plugin.getConfig().getInt("low-durability-warning-percent", 10);
        if (pct > 0) {
            double threshold = max * pct / 100.0;
            if (cur > threshold && next <= threshold) {
                p.sendActionBar(plugin.messages().plain("low-durability",
                        Placeholder.unparsed("current", String.valueOf(next)),
                        Placeholder.unparsed("max", String.valueOf(max)),
                        Placeholder.component("tool", name)));
            }
        }
    }
}
