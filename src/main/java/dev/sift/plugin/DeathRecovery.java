package dev.sift.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Siftite gear never scatters on death: it is held back, and three seconds after the player respawns a
 * Mini Rift opens at their respawn point and releases the items (invulnerable, pickup-locked to the owner).
 * Pending items are persisted in recovery.yml so a restart cannot lose them.
 */
public final class DeathRecovery implements Listener {

    private final SiftPlugin plugin;
    private final File file;
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();
    private final Set<UUID> scheduled = new HashSet<>();

    public DeathRecovery(SiftPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "recovery.yml");
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        pending.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("pending");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                List<ItemStack> items = new ArrayList<>();
                for (String encoded : sec.getStringList(key)) {
                    items.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded)));
                }
                if (!items.isEmpty()) pending.put(id, items);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping corrupt recovery entry " + key);
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, List<ItemStack>> e : pending.entrySet()) {
            List<String> encoded = new ArrayList<>();
            for (ItemStack stack : e.getValue()) {
                encoded.add(Base64.getEncoder().encodeToString(stack.serializeAsBytes()));
            }
            yml.set("pending." + e.getKey(), encoded);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save recovery.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("recovery.enabled", true) || event.getKeepInventory()) return;

        List<ItemStack> saved = new ArrayList<>();
        Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (plugin.items().isSiftiteGear(stack)) {
                saved.add(stack.clone());
                it.remove();
            }
        }
        if (saved.isEmpty()) return;

        pending.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new ArrayList<>()).addAll(saved);
        save();
        event.getPlayer().sendMessage(Component.text(
                "Your Siftite gear is held in the rift. It will return when you respawn.", NamedTextColor.DARK_PURPLE));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        schedule(event.getPlayer().getUniqueId(), event.getRespawnLocation().clone());
    }

    /** Covers the server restarting between death and respawn. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (pending.containsKey(p.getUniqueId()) && !p.isDead()) schedule(p.getUniqueId(), p.getLocation().clone());
    }

    // ------------------------------------------------------------ release

    private void schedule(UUID id, Location where) {
        if (!pending.containsKey(id) || !scheduled.add(id)) return;
        long delay = plugin.getConfig().getLong("recovery.delay-ticks", 60);
        Bukkit.getScheduler().runTaskLater(plugin, () -> release(id, where), delay);
    }

    private void release(UUID id, Location where) {
        scheduled.remove(id);
        List<ItemStack> items = pending.remove(id);
        if (items == null || items.isEmpty()) return;
        save();

        World world = where.getWorld();
        if (world == null) {
            pending.put(id, items); // world unavailable - keep them safe
            save();
            return;
        }

        plugin.rifts().playMiniRift(where, plugin.getConfig().getInt("recovery.mini-rift-ticks", 100));
        Location spot = where.clone().add(0, 1.0, 0);
        for (ItemStack stack : items) {
            world.dropItem(spot, stack, item -> {
                item.setOwner(id);
                item.setInvulnerable(true);
                item.setGlowing(true);
                item.setPickupDelay(10);
                item.setVelocity(new Vector(0, 0.2, 0));
            });
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.sendActionBar(Component.text("A mini rift returns your Siftite gear.", NamedTextColor.LIGHT_PURPLE));
        }
    }
}
