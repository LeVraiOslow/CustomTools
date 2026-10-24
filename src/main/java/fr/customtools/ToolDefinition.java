package fr.customtools;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;

public record ToolDefinition(String id, Material material, String nexoId, String name, List<String> lore, int durability, Set<ToolFeature> features, int radius, Map<Enchantment, Integer> enchantments, ConfigurationSection options) {
    public boolean has(ToolFeature f) {
        return this.features.contains(f);
    }
}

