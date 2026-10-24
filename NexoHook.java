package fr.customtools;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Accès à l'API Nexo par réflexion : aucune dépendance de compilation, Nexo reste optionnel. */
public final class NexoHook {
    private final boolean enabled;
    private Class<?> cls;
    private Object target; // null si méthodes statiques, sinon INSTANCE (objet Kotlin)
    private Method itemFromId, idFromItem;

    public NexoHook(CustomTools plugin) {
        boolean ok = false;
        if (Bukkit.getPluginManager().getPlugin("Nexo") != null) {
            try {
                cls = Class.forName("com.nexomc.nexo.api.NexoItems");
                itemFromId = cls.getMethod("itemFromId", String.class);
                idFromItem = cls.getMethod("idFromItem", ItemStack.class);
                if (!Modifier.isStatic(itemFromId.getModifiers())) {
                    target = cls.getField("INSTANCE").get(null);
                }
                ok = true;
                plugin.getLogger().info("Nexo détecté : support des textures custom activé.");
            } catch (Throwable t) {
                plugin.getLogger().warning("Nexo détecté mais API introuvable : " + t);
            }
        }
        this.enabled = ok;
    }

    public boolean enabled() {
        return enabled;
    }

    public ItemStack build(String nexoId) {
        if (!enabled || nexoId == null || nexoId.isBlank()) return null;
        try {
            Object builder = itemFromId.invoke(target, nexoId);
            if (builder == null) return null;
            return (ItemStack) builder.getClass().getMethod("build").invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }

    public String idOf(ItemStack item) {
        if (!enabled) return null;
        try {
            return (String) idFromItem.invoke(target, item);
        } catch (Throwable t) {
            return null;
        }
    }
}
