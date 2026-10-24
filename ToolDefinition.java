package fr.customtools;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** options = section YAML de l'outil (permet de surcharger les réglages globaux par outil). */
public record ToolDefinition(String id, Material material, String nexoId, String name,
                             List<String> lore, int durability, Set<ToolFeature> features, int radius,
                             Map<Enchantment, Integer> enchantments, ConfigurationSection options) {
    public boolean has(ToolFeature f) {
        return features.contains(f);
    }
}
