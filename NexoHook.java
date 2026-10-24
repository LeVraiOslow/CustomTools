package fr.customtools;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

public final class NexoHook {
    private final boolean enabled;
    private Class<?> cls;
    private Object target;
    private Method itemFromId;
    private Method idFromItem;

    public NexoHook(CustomTools plugin) {
        boolean ok = false;
        if (Bukkit.getPluginManager().getPlugin("Nexo") != null) {
            try {
                this.cls = Class.forName("com.nexomc.nexo.api.NexoItems");
                this.itemFromId = this.cls.getMethod("itemFromId", String.class);
                this.idFromItem = this.cls.getMethod("idFromItem", ItemStack.class);
                if (!Modifier.isStatic(this.itemFromId.getModifiers())) {
                    this.target = this.cls.getField("INSTANCE").get(null);
                }
                ok = true;
                plugin.getLogger().info("Nexo d\u00e9tect\u00e9 : support des textures custom activ\u00e9.");
            }
            catch (Throwable t) {
                plugin.getLogger().warning("Nexo d\u00e9tect\u00e9 mais API introuvable : " + String.valueOf(t));
            }
        }
        this.enabled = ok;
    }

    public boolean enabled() {
        return this.enabled;
    }

    public ItemStack build(String nexoId) {
        if (!this.enabled || nexoId == null || nexoId.isBlank()) {
            return null;
        }
        try {
            Object builder = this.itemFromId.invoke(this.target, nexoId);
            if (builder == null) {
                return null;
            }
            return (ItemStack)builder.getClass().getMethod("build", new Class[0]).invoke(builder, new Object[0]);
        }
        catch (Throwable t) {
            return null;
        }
    }

    public String idOf(ItemStack item) {
        if (!this.enabled) {
            return null;
        }
        try {
            return (String)this.idFromItem.invoke(this.target, item);
        }
        catch (Throwable t) {
            return null;
        }
    }
}

