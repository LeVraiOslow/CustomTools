package fr.customtools;

import java.util.HashMap;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTables;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public final class ToolListener
implements Listener {
    private final CustomTools plugin;
    private final ToolManager tools;
    private final Set<UUID> processing = new HashSet<UUID>();
    private final Map<UUID, Integer> lastBreakTick = new HashMap<UUID, Integer>();

    public ToolListener(CustomTools plugin, ToolManager tools) {
        this.plugin = plugin;
        this.tools = tools;
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onBreakPrecheck(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (this.processing.contains(p.getUniqueId())) {
            return;
        }
        ToolDefinition def = this.tools.fromItem(p.getInventory().getItemInMainHand());
        if (def == null || !def.has(ToolFeature.HARVEST)) {
            return;
        }
        if (p.isSneaking() && this.tools.flag(def, "sneak-disables", true)) {
            return;
        }
        Block b = e.getBlock();
        if (this.tools.flag(def, "require-mature", true) && this.tools.isCrop(b) && !this.tools.isMature(b)) {
            e.setCancelled(true);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onBreak(BlockBreakEvent e) {
        boolean replantOn;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (this.processing.contains(id)) {
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        ToolDefinition def = this.tools.fromItem(hand);
        if (def == null) {
            return;
        }
        this.lastBreakTick.put(id, Bukkit.getCurrentTick());
        if (this.tools.ensure(hand, def)) {
            p.getInventory().setItemInMainHand(hand);
        }
        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        boolean spawnerBroken = false;
        if (def.has(ToolFeature.SPAWNER) && e.getBlock().getType() == Material.SPAWNER && !creative) {
            this.dropSpawner(e, def);
            spawnerBroken = true;
        }
        boolean limited = def.durability() > 0 && !creative;
        int per = Math.max(1, this.tools.number(def, "durability-per-block", 1));
        int budget = limited ? this.tools.remaining(hand, def) / per - 1 : Integer.MAX_VALUE;
        Block origin = e.getBlock();
        ArrayList<Block> extra = new ArrayList<Block>();
        LinkedHashMap<Block, BlockData> replant = new LinkedHashMap<Block, BlockData>();
        boolean harvest = def.has(ToolFeature.HARVEST);
        boolean bl = replantOn = harvest && this.tools.flag(def, "replant", true);
        if (!p.isSneaking() || !this.tools.flag(def, "sneak-disables", true)) {
            if (def.has(ToolFeature.HAMMER) && this.canHammer(origin, def)) {
                extra.addAll(this.hammerBlocks(p, origin, def));
            }
            if (harvest && this.tools.isCrop(origin)) {
                if (replantOn) {
                    this.rememberReplant(origin, origin.getBlockData(), replant);
                }
                extra.addAll(this.harvestBlocks(origin, def.radius()));
            }
            if (def.has(ToolFeature.TREE_CHOPPER) && Tag.LOGS.isTagged(origin.getType())) {
                extra.addAll(this.treeBlocks(origin, def));
            }
        }
        if (extra.size() > budget) {
            extra = new ArrayList(extra.subList(0, Math.max(0, budget)));
        }
        int broken = 0;
        this.processing.add(id);
        try {
            for (Block b : extra) {
                if (b.getType().isAir()) continue;
                BlockData before = b.getBlockData();
                if (!p.breakBlock(b)) continue;
                ++broken;
                if (!replantOn) continue;
                this.rememberReplant(b, before, replant);
            }
        }
        finally {
            this.processing.remove(id);
        }
        if (!replant.isEmpty()) {
            boolean consume = this.tools.flag(def, "consume-seeds", true) && !creative;
            Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> this.replantAll(p, replant, consume));
        }
        if (limited) {
            int cost = spawnerBroken ? Math.max(1, this.tools.number(def, "spawner-durability-cost", 10)) : per * (1 + broken);
            Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> this.tools.consume(p, def, cost));
        }
    }

    private void rememberReplant(Block b, BlockData data, Map<Block, BlockData> map) {
        BlockData blockData = data.clone();
        if (!(blockData instanceof Ageable)) {
            return;
        }
        Ageable a = (Ageable)blockData;
        a.setAge(0);
        map.put(b, (BlockData)a);
    }

    private void replantAll(Player p, Map<Block, BlockData> replant, boolean consume) {
        LinkedHashSet<Material> missing = new LinkedHashSet<Material>();
        replant.forEach((b, d) -> {
            Material seed;
            if (!b.getType().isAir() || !d.isSupported(b)) {
                return;
            }
            if (consume && (seed = this.tools.seedFor(d.getMaterial())) != Material.AIR && !p.getInventory().removeItem(new ItemStack[]{new ItemStack(seed, 1)}).isEmpty()) {
                missing.add(seed);
                return;
            }
            b.setBlockData(d);
        });
        if (!missing.isEmpty()) {
            String names = String.join((CharSequence)", ", missing.stream().map(m -> m.name().toLowerCase(Locale.ROOT).replace('_', ' ')).toList());
            this.plugin.messages().send((CommandSender)p, "no-seed", new TagResolver[]{Placeholder.unparsed((String)"seed", (String)names)});
        }
    }

    private void dropSpawner(BlockBreakEvent e, ToolDefinition def) {
        Block block = e.getBlock();
        ItemStack item = new ItemStack(Material.SPAWNER);
        String mob = "inconnu";
        if (item.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.getBlockState() instanceof CreatureSpawner target
                && block.getState() instanceof CreatureSpawner src) {
            EntityType type = src.getSpawnedType();
            if (type != null) {
                target.setSpawnedType(type);
                mob = type.getKey().getKey().replace('_', ' ');
            }
            target.setSpawnCount(src.getSpawnCount());
            target.setMaxNearbyEntities(src.getMaxNearbyEntities());
            target.setRequiredPlayerRange(src.getRequiredPlayerRange());
            target.setSpawnRange(src.getSpawnRange());
            bsm.setBlockState(target);
            TagResolver.Single mobTag = Placeholder.unparsed("mob", mob);
            String name = this.tools.text(def, "spawner-item-name", "&eSpawner &7(&f<mob>&7)");
            bsm.displayName(Text.parse(name, new TagResolver[0]).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            ArrayList<Component> lore = new ArrayList<Component>();
            for (String line : this.plugin.getConfig().getStringList("spawner-item-lore")) {
                lore.add(Text.parse(line, new TagResolver[]{mobTag}).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            if (!lore.isEmpty()) {
                bsm.lore(lore);
            }
            item.setItemMeta(bsm);
        }
        if (!this.tools.flag(def, "spawner-drop-xp", false)) {
            e.setExpToDrop(0);
        }
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), item);
    }

    private Tag<Material> mineableTag(ToolDefinition def) {
        String mode = this.tools.text(def, "mineable", "").toUpperCase(Locale.ROOT);
        if (mode.isBlank()) {
            String n = def.material().name();
            mode = n.endsWith("_SHOVEL") ? "SHOVEL" : (n.endsWith("_AXE") ? "AXE" : (n.endsWith("_HOE") ? "HOE" : "PICKAXE"));
        }
        return switch (mode) {
            case "SHOVEL" -> Tag.MINEABLE_SHOVEL;
            case "AXE" -> Tag.MINEABLE_AXE;
            case "HOE" -> Tag.MINEABLE_HOE;
            default -> Tag.MINEABLE_PICKAXE;
        };
    }

    private boolean canHammer(Block b, ToolDefinition def) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid() || m.getHardness() < 0.0f) {
            return false;
        }
        if (!this.mineableTag(def).isTagged(m) || this.tools.hammerBlacklisted(m)) {
            return false;
        }
        return !(b.getState(false) instanceof Container);
    }

    private List<Block> hammerBlocks(Player p, Block origin, ToolDefinition def) {
        int r = def.radius();
        BlockFace face = this.faceOf(p, origin);
        ArrayList<Block> list = new ArrayList<Block>();
        for (int a = -r; a <= r; ++a) {
            for (int c = -r; c <= r; ++c) {
                if (a == 0 && c == 0) continue;
                Block t = switch (face) {
                    case UP, DOWN -> origin.getRelative(a, 0, c);
                    case NORTH, SOUTH -> origin.getRelative(a, c, 0);
                    default -> origin.getRelative(0, c, a);
                };
                if (!this.canHammer(t, def)) continue;
                list.add(t);
            }
        }
        return list;
    }

    private BlockFace faceOf(Player p, Block origin) {
        RayTraceResult r = p.rayTraceBlocks(8.0, FluidCollisionMode.NEVER);
        if (r != null && origin.equals(r.getHitBlock()) && r.getHitBlockFace() != null) {
            return r.getHitBlockFace();
        }
        Vector d = p.getEyeLocation().getDirection();
        double ax = Math.abs(d.getX());
        double ay = Math.abs(d.getY());
        double az = Math.abs(d.getZ());
        if (ay >= ax && ay >= az) {
            return d.getY() > 0.0 ? BlockFace.DOWN : BlockFace.UP;
        }
        if (ax >= az) {
            return d.getX() > 0.0 ? BlockFace.WEST : BlockFace.EAST;
        }
        return d.getZ() > 0.0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    private List<Block> harvestBlocks(Block origin, int r) {
        ArrayList<Block> list = new ArrayList<Block>();
        for (int dx = -r; dx <= r; ++dx) {
            for (int dz = -r; dz <= r; ++dz) {
                Block t;
                if (dx == 0 && dz == 0 || !this.tools.isCrop(t = origin.getRelative(dx, 0, dz)) || !this.tools.isMature(t)) continue;
                list.add(t);
            }
        }
        return list;
    }

    private List<Block> treeBlocks(Block origin, ToolDefinition def) {
        int max = Math.max(1, this.tools.number(def, "max-logs", 256));
        HashSet<Block> visited = new HashSet<Block>();
        ArrayDeque<Block> queue = new ArrayDeque<Block>();
        ArrayList<Block> found = new ArrayList<Block>();
        boolean leaves = false;
        visited.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && found.size() < max) {
            Block c = (Block)queue.poll();
            for (int dx = -1; dx <= 1; ++dx) {
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dz = -1; dz <= 1; ++dz) {
                        Leaves l;
                        Block n;
                        if (dx == 0 && dy == 0 && dz == 0 || !visited.add(n = c.getRelative(dx, dy, dz))) continue;
                        if (Tag.LOGS.isTagged(n.getType())) {
                            found.add(n);
                            queue.add(n);
                            continue;
                        }
                        BlockData blockData = n.getBlockData();
                        if (!(blockData instanceof Leaves) || (l = (Leaves)blockData).isPersistent()) continue;
                        leaves = true;
                    }
                }
            }
        }
        if (this.tools.flag(def, "require-leaves", true) && !leaves) {
            return List.of();
        }
        found.sort(Comparator.comparingInt(Block::getY));
        return found;
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onDrops(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        ToolDefinition def = this.tools.fromItem(p.getInventory().getItemInMainHand());
        if (def == null || !def.has(ToolFeature.SMELT)) {
            return;
        }
        double xp = 0.0;
        for (Item item : e.getItems()) {
            FurnaceRecipe r;
            ItemStack stack = item.getItemStack();
            if (this.tools.smeltBlacklisted(stack.getType()) || (r = this.tools.smelt(stack.getType())) == null) continue;
            ItemStack res = r.getResult().clone();
            res.setAmount(Math.min(res.getMaxStackSize(), res.getAmount() * stack.getAmount()));
            item.setItemStack(res);
            xp += (double)(r.getExperience() * (float)stack.getAmount());
        }
        if (this.tools.flag(def, "smelt-give-xp", true) && xp > 0.0) {
            int amount;
            int whole = (int)xp;
            if (ThreadLocalRandom.current().nextDouble() < xp - (double)whole) {
                ++whole;
            }
            if ((amount = whole) > 0) {
                e.getBlock().getWorld().spawn(e.getBlock().getLocation().add(0.5, 0.5, 0.5), ExperienceOrb.class, o -> o.setExperience(amount));
            }
        }
    }

    /**
     * La durabilit\u00e9 vanilla est annul\u00e9e pour les outils custom. Les casses de blocs sont d\u00e9j\u00e0
     * factur\u00e9es dans onBreak ; tout le reste (coups d'\u00e9p\u00e9e, p\u00eache, labour, \u00e9corce, chemin...)
     * est factur\u00e9 ici, avec le d\u00e9g\u00e2t vanilla.
     */
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onItemDamage(PlayerItemDamageEvent e) {
        ToolDefinition def = this.tools.fromItem(e.getItem());
        if (def == null || def.durability() <= 0) {
            return;
        }
        e.setCancelled(true);
        if (e.getItem().getType() == Material.FISHING_ROD) {
            return; // facturée dans onFishDurability
        }
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Integer last = this.lastBreakTick.get(id);
        if (this.processing.contains(id) || last != null && last == Bukkit.getCurrentTick()) {
            return;
        }
        int dmg = Math.max(1, e.getDamage());
        Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> this.tools.consume(p, def, dmg));
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onMend(PlayerItemMendEvent e) {
        ToolDefinition def = this.tools.fromItem(e.getItem());
        if (def != null && def.durability() > 0) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> {
            ItemStack it = p.getInventory().getItemInMainHand();
            ToolDefinition d = this.tools.fromItem(it);
            if (d != null && this.tools.ensure(it, d)) {
                p.getInventory().setItemInMainHand(it);
            }
        });
    }

    // ───────────── Masquage des attributs / enchantements : r\u00e9application ─────────────

    private void scheduleRefresh(Player p, long... delays) {
        for (long delay : delays) {
            Bukkit.getScheduler().runTaskLater((Plugin)this.plugin, () -> {
                if (p.isOnline()) {
                    this.tools.refresh(p);
                }
            }, delay);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        this.scheduleRefresh(e.getPlayer(), 1L, 20L, 100L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        this.scheduleRefresh(e.getPlayer(), 1L, 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        this.scheduleRefresh(e.getPlayer(), 2L, 20L);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            this.scheduleRefresh(p, 1L);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) {
            this.scheduleRefresh(p, 1L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.lastBreakTick.remove(e.getPlayer().getUniqueId());
    }

    // ───────────── \u00c9p\u00e9e : MULTI_KILL ─────────────

    @EventHandler(priority=EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof Mob victim)) {
            return;
        }
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || this.processing.contains(p.getUniqueId())) {
            return;
        }
        ToolDefinition def = this.tools.fromItem(p.getInventory().getItemInMainHand());
        if (def == null || !def.has(ToolFeature.MULTI_KILL)) {
            return;
        }
        if (p.isSneaking() && this.tools.flag(def, "sneak-disables", false)) {
            return;
        }
        if (this.tools.killBlacklisted(victim.getType()) || victim instanceof Tameable t && t.isTamed()) {
            return;
        }
        int extra = Math.max(0, this.tools.number(def, "kill-count", 9) - 1);
        if (extra == 0) {
            return;
        }
        double radius = Math.max(0.5, this.tools.decimal(def, "stack-radius", 1.5));
        // Un tick plus tard : si la victime est morte (mobs empil\u00e9s en entit\u00e9s s\u00e9par\u00e9es) on tape ses voisines,
        // si elle est encore vivante (plugin de stack \u00e0 compteur) on la retape elle-m\u00eame.
        Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> this.killStack(p, def, victim, extra, radius));
    }

    private void killStack(Player p, ToolDefinition def, Mob victim, int extra, double radius) {
        UUID id = p.getUniqueId();
        if (!p.isOnline() || this.processing.contains(id)) {
            return;
        }
        Location center = victim.getLocation();
        int killed = 0;
        this.processing.add(id);
        try {
            for (int i = 0; i < extra; ++i) {
                Mob target = this.nextStackTarget(p, victim, center, radius);
                if (target == null) break;
                target.setNoDamageTicks(0);
                target.damage(1000000.0, (Entity)p);
                ++killed;
            }
        }
        finally {
            this.processing.remove(id);
        }
        if (killed > 0 && def.durability() > 0) {
            int n = killed;
            Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> this.tools.consume(p, def, n));
        }
    }

    private Mob nextStackTarget(Player p, Mob victim, Location center, double radius) {
        Mob best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity en : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(en instanceof Mob m) || m.getType() != victim.getType() || m.isDead() || !m.isValid()) continue;
            if (m instanceof Tameable t && t.isTamed()) continue;
            double d = m.getLocation().distanceSquared(center);
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    // ───────────── Canne : MULTI_FISH ─────────────

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        // 1) Durabilité (cannes custom classiques ou mythiques), coûts identiques à vanilla
        int cost = switch (e.getState()) {
            case CAUGHT_FISH -> 1;
            case CAUGHT_ENTITY -> e.getCaught() instanceof Item ? 3 : 5;
            case IN_GROUND -> 2;
            default -> 0;
        };
        ToolDefinition mainDef = this.tools.fromItem(p.getInventory().getItemInMainHand());
        if (this.plugin.getConfig().getBoolean("debug", false)) {
            this.plugin.getLogger().info("[debug] pêche state=" + e.getState() + " coût=" + cost + " outil=" + (mainDef == null ? "aucun" : mainDef.id() + " dur=" + mainDef.durability()) + " gamemode=" + p.getGameMode());
        }
        if (cost > 0 && mainDef != null && mainDef.durability() > 0) {
            Bukkit.getScheduler().runTask((Plugin)this.plugin, () -> this.tools.consume(p, mainDef, cost));
        }
        // 2) MULTI_FISH
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item caught)) {
            return;
        }
        ItemStack rod = p.getInventory().getItemInMainHand();
        ToolDefinition def = mainDef;
        if (def == null || !def.has(ToolFeature.MULTI_FISH)) {
            rod = p.getInventory().getItemInOffHand();
            def = this.tools.fromItem(rod);
            if (def == null || !def.has(ToolFeature.MULTI_FISH)) {
                return;
            }
        }
        int count = Math.max(1, this.tools.number(def, "fish-count", 3));
        if (count == 1) {
            return;
        }
        Location at = caught.getLocation();
        Location to = p.getLocation();
        double dx = to.getX() - at.getX();
        double dy = to.getY() - at.getY();
        double dz = to.getZ() - at.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        for (int i = 1; i < count; ++i) {
            ItemStack stack = this.rollFishingLoot(p, rod, at, caught.getItemStack());
            Item it = at.getWorld().dropItem(at, stack);
            it.setVelocity(new Vector(dx * 0.1, dy * 0.1 + Math.sqrt(dist) * 0.08, dz * 0.1));
        }
        e.setExpToDrop(e.getExpToDrop() * count);
    }

    private ItemStack rollFishingLoot(Player p, ItemStack rod, Location at, ItemStack fallback) {
        try {
            float luck = (float)rod.getEnchantmentLevel(Enchantment.LUCK_OF_THE_SEA);
            AttributeInstance attr = p.getAttribute(Attribute.LUCK);
            if (attr != null) {
                luck += (float)attr.getValue();
            }
            LootContext ctx = new LootContext.Builder(at).luck(luck).killer((org.bukkit.entity.HumanEntity)p).build();
            for (ItemStack loot : LootTables.FISHING.getLootTable().populateLoot(new Random(), ctx)) {
                if (loot != null && !loot.getType().isAir()) {
                    return loot;
                }
            }
        }
        catch (Throwable t) {
            // table de loot indisponible : on duplique la prise
        }
        return fallback.clone();
    }

    @EventHandler(priority=EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack first = e.getInventory().getFirstItem();
        ItemStack second = e.getInventory().getSecondItem();
        ToolDefinition d1 = this.tools.fromItem(first);
        ToolDefinition d2 = this.tools.fromItem(second);
        if (d1 == null && d2 == null || second == null) {
            return;
        }
        if (second.getType() == Material.ENCHANTED_BOOK) {
            if (!this.tools.cfgBool("anvil-allow-enchanted-books", true)) {
                e.setResult(null);
            }
            return;
        }
        if (this.tools.cfgBool("block-repair.anvil", true)) {
            e.setResult(null);
        } else if (d1 != null && e.getResult() != null) {
            ItemStack r = e.getResult().clone();
            this.tools.repair(r, d1);
            e.setResult(r);
        }
    }

    @EventHandler(priority=EventPriority.HIGH)
    public void onGrindstone(PrepareGrindstoneEvent e) {
        if (!this.tools.cfgBool("block-repair.grindstone", true)) {
            return;
        }
        ItemStack up = e.getInventory().getUpperItem();
        ItemStack low = e.getInventory().getLowerItem();
        if (up == null || low == null) {
            return;
        }
        if (this.tools.fromItem(up) != null || this.tools.fromItem(low) != null) {
            e.setResult(null);
        }
    }

    @EventHandler(priority=EventPriority.HIGH)
    public void onCraftRepair(PrepareItemCraftEvent e) {
        if (!e.isRepair() || !this.tools.cfgBool("block-repair.crafting", true)) {
            return;
        }
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (it == null || this.tools.fromItem(it) == null) continue;
            e.getInventory().setResult(null);
            return;
        }
    }
}

