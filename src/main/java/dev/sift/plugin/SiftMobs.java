package dev.sift.plugin;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Light custom spawner so The Sift has hostile mobs (custom world generators get little natural spawning
 * on floating islands). Spawns a few hostile mobs on solid ground near each player, up to a per-player cap.
 */
public final class SiftMobs {

    private final SiftPlugin plugin;

    public SiftMobs(SiftPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("mobs.enabled", true)) return;
        long interval = Math.max(20L, plugin.getConfig().getLong("mobs.interval-ticks", 100));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    private List<EntityType> types() {
        List<EntityType> out = new ArrayList<>();
        for (String name : plugin.getConfig().getStringList("mobs.types")) {
            try {
                out.add(EntityType.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Unknown mob type in config: " + name);
            }
        }
        return out;
    }

    private void tick() {
        World world = plugin.siftWorld().get();
        if (world == null) return;
        List<EntityType> types = types();
        if (types.isEmpty()) return;
        int cap = plugin.getConfig().getInt("mobs.cap-per-player", 6);
        ThreadLocalRandom r = ThreadLocalRandom.current();

        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            long nearby = p.getNearbyEntities(40, 40, 40).stream().filter(e -> e instanceof Monster).count();
            if (nearby >= cap) continue;

            for (int attempt = 0; attempt < 6; attempt++) {
                double angle = r.nextDouble() * Math.PI * 2;
                double dist = 14 + r.nextDouble() * 14;
                int x = (int) Math.floor(p.getX() + Math.cos(angle) * dist);
                int z = (int) Math.floor(p.getZ() + Math.sin(angle) * dist);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                Location spot = RiftManager.surface(world, x, z);
                if (spot == null) continue;
                world.spawnEntity(spot, types.get(r.nextInt(types.size())), CreatureSpawnEvent.SpawnReason.CUSTOM);
                break;
            }
        }
    }
}
