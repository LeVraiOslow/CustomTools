package fr.customtools;

import org.bukkit.event.player.PlayerItemMendEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
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
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class ToolListener implements Listener {
    private final CustomTools plugin;
    private final ToolManager tools;
    /** Joueurs dont on est en train de casser les blocs en chaîne (anti-récursion). */
    private final Set<UUID> processing = new HashSet<>();

    public ToolListener(CustomTools plugin, ToolManager tools) {
        this.plugin = plugin;
        this.tools = tools;
    }

    // ═════════════ Houe : protège les jeunes pousses ═════════════
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakPrecheck(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (processing.contains(p.getUniqueId())) return;
        ToolDefinition def = tools.fromItem(p.getInventory().getItemInMainHand());
        if (def == null || !def.has(ToolFeature.HARVEST)) return;
        if (p.isSneaking() && tools.flag(def, "sneak-disables", true)) return;
        Block b = e.getBlock();
        if (tools.flag(def, "require-mature", true) && tools.isCrop(b) && !tools.isMature(b)) e.setCancelled(true);
    }

    // ═════════════ Casse en chaîne (3x3, houe, arbre) ═════════════
    // MONITOR + ignoreCancelled : on n'agit que si le bloc d'origine est réellement cassé
    // (protections WorldGuard/Towny/... déjà passées).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (processing.contains(id)) return;

        ItemStack hand = p.getInventory().getItemInMainHand();
        ToolDefinition def = tools.fromItem(hand);
        if (def == null) return;
        hand = tools.ensure(hand, def);
        p.getInventory().setItemInMainHand(hand);

        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        boolean spawnerBroken = false;
        if (def.has(ToolFeature.SPAWNER) && e.getBlock().getType() == Material.SPAWNER && !creative) {
            dropSpawner(e, def);
            spawnerBroken = true;
        }
        boolean limited = def.durability() > 0 && !creative;
        int per = Math.max(1, tools.number(def, "durability-per-block", 1));
        // budget = nombre de blocs supplémentaires que la durabilité restante permet (-1 = bloc d'origine)
        int budget = limited ? (tools.remaining(hand, def) / per) - 1 : Integer.MAX_VALUE;

        Block origin = e.getBlock();
        List<Block> extra = new ArrayList<>();
        Map<Block, BlockData> replant = new LinkedHashMap<>();
        boolean harvest = def.has(ToolFeature.HARVEST);
        boolean replantOn = harvest && tools.flag(def, "replant", true);

        if (!(p.isSneaking() && tools.flag(def, "sneak-disables", true))) {
            if (def.has(ToolFeature.HAMMER) && canHammer(origin)) {
                extra.addAll(hammerBlocks(p, origin, def.radius()));
            }
            if (harvest && tools.isCrop(origin)) {
                if (replantOn) rememberReplant(origin, origin.getBlockData(), replant);
                extra.addAll(harvestBlocks(origin, def.radius()));
            }
            if (def.has(ToolFeature.TREE_CHOPPER) && Tag.LOGS.isTagged(origin.getType())) {
                extra.addAll(treeBlocks(origin, def));
            }
        }
        if (extra.size() > budget) extra = new ArrayList<>(extra.subList(0, Math.max(0, budget)));

        int broken = 0;
        processing.add(id);
        try {
            for (Block b : extra) {
                if (b.getType().isAir()) continue;
                BlockData before = b.getBlockData();
                // Player#breakBlock déclenche un vrai BlockBreakEvent -> protections + ExcellentJobs (XP par bloc)
                if (p.breakBlock(b)) {
                    broken++;
                    if (replantOn) rememberReplant(b, before, replant);
                }
            }
        } finally {
            processing.remove(id);
        }

        if (!replant.isEmpty()) {
            boolean consume = tools.flag(def, "consume-seeds", true) && !creative;
            Bukkit.getScheduler().runTask(plugin, () -> replantAll(p, replant, consume));
        }
        if (limited) {
            int cost = spawnerBroken ? Math.max(1, tools.number(def, "spawner-durability-cost", 10)) : per * (1 + broken);
            // Différé : si l'outil casse sur ce coup, le bloc d'origine garde ses drops "outil en main"
            Bukkit.getScheduler().runTask(plugin, () -> tools.consume(p, def, cost));
        }
    }

    private void rememberReplant(Block b, BlockData data, Map<Block, BlockData> map) {
        if (!(data.clone() instanceof Ageable a)) return;
        a.setAge(0);
        map.put(b, a);
    }

    /** Replante ; si consume=true, une graine est prélevée dans l'inventaire (sinon on ne replante pas). */
    private void replantAll(Player p, Map<Block, BlockData> replant, boolean consume) {
        Set<Material> missing = new LinkedHashSet<>();
        replant.forEach((b, d) -> {
            if (!b.getType().isAir() || !d.isSupported(b)) return;
            if (consume) {
                Material seed = tools.seedFor(d.getMaterial());
                if (seed != Material.AIR && !p.getInventory().removeItem(new ItemStack(seed, 1)).isEmpty()) {
                    missing.add(seed);
                    return;
                }
            }
            b.setBlockData(d);
        });
        if (!missing.isEmpty()) {
            String names = String.join(", ", missing.stream().map(m -> m.name().toLowerCase(Locale.ROOT).replace('_', ' ')).toList());
            plugin.messages().send(p, "no-seed", Placeholder.unparsed("seed", names));
        }
    }

    // ═════════════ Pioche à spawners ═════════════
    /** Drop le spawner avec son type de mob (le bloc est encore en place à MONITOR). */
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

            var mobTag = Placeholder.unparsed("mob", mob);
            String name = tools.text(def, "spawner-item-name", "&eSpawner &7(&f<mob>&7)");
            bsm.displayName(Text.parse(name).decorationIfAbsent(
                    net.kyori.adventure.text.format.TextDecoration.ITALIC,
                    net.kyori.adventure.text.format.TextDecoration.State.FALSE));
            var lore = new ArrayList<net.kyori.adventure.text.Component>();
            for (String line : plugin.getConfig().getStringList("spawner-item-lore")) {
                lore.add(Text.parse(line, mobTag).decorationIfAbsent(
                        net.kyori.adventure.text.format.TextDecoration.ITALIC,
                        net.kyori.adventure.text.format.TextDecoration.State.FALSE));
            }
            if (!lore.isEmpty()) bsm.lore(lore);
            item.setItemMeta(bsm);
        }
        if (!tools.flag(def, "spawner-drop-xp", false)) e.setExpToDrop(0);
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), item);
    }

    // ═════════════ Sélection des blocs ═════════════
    private boolean canHammer(Block b) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid() || m.getHardness() < 0) return false;
        if (!Tag.MINEABLE_PICKAXE.isTagged(m) || tools.hammerBlacklisted(m)) return false;
        return !(b.getState(false) instanceof Container);
    }

    private List<Block> hammerBlocks(Player p, Block origin, int r) {
        BlockFace face = faceOf(p, origin);
        List<Block> list = new ArrayList<>();
        for (int a = -r; a <= r; a++) {
            for (int c = -r; c <= r; c++) {
                if (a == 0 && c == 0) continue;
                Block t = switch (face) {
                    case UP, DOWN -> origin.getRelative(a, 0, c);
                    case NORTH, SOUTH -> origin.getRelative(a, c, 0);
                    default -> origin.getRelative(0, c, a);
                };
                if (canHammer(t)) list.add(t);
            }
        }
        return list;
    }

    private BlockFace faceOf(Player p, Block origin) {
        RayTraceResult r = p.rayTraceBlocks(8, FluidCollisionMode.NEVER);
        if (r != null && origin.equals(r.getHitBlock()) && r.getHitBlockFace() != null) {
            return r.getHitBlockFace();
        }
        Vector d = p.getEyeLocation().getDirection();
        double ax = Math.abs(d.getX()), ay = Math.abs(d.getY()), az = Math.abs(d.getZ());
        if (ay >= ax && ay >= az) return d.getY() > 0 ? BlockFace.DOWN : BlockFace.UP;
        if (ax >= az) return d.getX() > 0 ? BlockFace.WEST : BlockFace.EAST;
        return d.getZ() > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    private List<Block> harvestBlocks(Block origin, int r) {
        List<Block> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) continue;
                Block t = origin.getRelative(dx, 0, dz);
                if (tools.isCrop(t) && tools.isMature(t)) list.add(t);
            }
        }
        return list;
    }

    private List<Block> treeBlocks(Block origin, ToolDefinition def) {
        int max = Math.max(1, tools.number(def, "max-logs", 256));
        Set<Block> visited = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        List<Block> found = new ArrayList<>();
        boolean leaves = false;
        visited.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && found.size() < max) {
            Block c = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = c.getRelative(dx, dy, dz);
                        if (!visited.add(n)) continue;
                        if (Tag.LOGS.isTagged(n.getType())) {
                            found.add(n);
                            queue.add(n);
                        } else if (n.getBlockData() instanceof Leaves l && !l.isPersistent()) {
                            leaves = true;
                        }
                    }
                }
            }
        }
        if (tools.flag(def, "require-leaves", true) && !leaves) return List.of();
        found.sort(Comparator.comparingInt(Block::getY)); // le bas du tronc d'abord
        return found;
    }

    // ═════════════ Pioche de fonte ═════════════
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        ToolDefinition def = tools.fromItem(p.getInventory().getItemInMainHand());
        if (def == null || !def.has(ToolFeature.SMELT)) return;

        double xp = 0;
        for (Item item : e.getItems()) {
            ItemStack stack = item.getItemStack();
            if (tools.smeltBlacklisted(stack.getType())) continue;
            FurnaceRecipe r = tools.smelt(stack.getType());
            if (r == null) continue;
            ItemStack res = r.getResult().clone();
            res.setAmount(Math.min(res.getMaxStackSize(), res.getAmount() * stack.getAmount()));
            item.setItemStack(res);
            xp += r.getExperience() * stack.getAmount();
        }
        if (tools.flag(def, "smelt-give-xp", true) && xp > 0) {
            int whole = (int) xp;
            if (ThreadLocalRandom.current().nextDouble() < xp - whole) whole++;
            final int amount = whole;
            if (amount > 0) {
                e.getBlock().getWorld().spawn(e.getBlock().getLocation().add(0.5, 0.5, 0.5),
                        ExperienceOrb.class, o -> o.setExperience(amount));
            }
        }
    }

    // ═════════════ Durabilité custom ═════════════
    /** La durabilité vanilla est ignorée : seule la durabilité custom compte. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemDamage(PlayerItemDamageEvent e) {
        ToolDefinition def = tools.fromItem(e.getItem());
        if (def != null && def.durability() > 0) e.setCancelled(true);
    }

    /** Mending désactivé sur les outils à durabilité custom (la barre vanilla se désynchroniserait). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMend(PlayerItemMendEvent e) {
        ToolDefinition def = tools.fromItem(e.getItem());
        if (def != null && def.durability() > 0) e.setCancelled(true);
    }

    /** Initialise lore/enchants/durabilité des items Nexo reçus sans passer par /ctools give. */
    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack it = p.getInventory().getItemInMainHand();
            ToolDefinition d = tools.fromItem(it);
            if (d != null) p.getInventory().setItemInMainHand(tools.ensure(it, d));
        });
    }

    // ═════════════ Enchantements autorisés, réparation bloquée ═════════════
    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack first = e.getInventory().getFirstItem();
        ItemStack second = e.getInventory().getSecondItem();
        ToolDefinition d1 = tools.fromItem(first);
        ToolDefinition d2 = tools.fromItem(second);
        if ((d1 == null && d2 == null) || second == null) return; // pas d'outil, ou simple renommage

        if (second.getType() == Material.ENCHANTED_BOOK) {
            if (!tools.cfgBool("anvil-allow-enchanted-books", true)) e.setResult(null);
            return;
        }
        if (tools.cfgBool("block-repair.anvil", true)) {
            e.setResult(null);
        } else if (d1 != null && e.getResult() != null) {
            // Réparation autorisée par la config : on garde la durabilité custom cohérente
            ItemStack r = e.getResult().clone();
            tools.repair(r, d1);
            e.setResult(r);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGrindstone(PrepareGrindstoneEvent e) {
        if (!tools.cfgBool("block-repair.grindstone", true)) return;
        ItemStack up = e.getInventory().getUpperItem();
        ItemStack low = e.getInventory().getLowerItem();
        if (up == null || low == null) return;
        if (tools.fromItem(up) != null || tools.fromItem(low) != null) e.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraftRepair(PrepareItemCraftEvent e) {
        if (!e.isRepair() || !tools.cfgBool("block-repair.crafting", true)) return;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (it != null && tools.fromItem(it) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }
}
