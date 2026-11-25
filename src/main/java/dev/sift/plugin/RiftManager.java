package dev.sift.plugin;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Temporary Sift Rifts: paired two-way vortexes (one in each dimension) that last for a configurable time.
 * Anything that touches one - players, mobs, dropped items, projectiles - is thrown out of its twin.
 */
public final class RiftManager {

    private static final class Rift {
        final UUID id = UUID.randomUUID();
        final Location a;
        final Location b;
        final long expiresAt;

        Rift(Location a, Location b, long expiresAt) {
            this.a = a;
            this.b = b;
            this.expiresAt = expiresAt;
        }
    }

    private final SiftPlugin plugin;
    private final List<Rift> rifts = new ArrayList<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private BukkitTask animTask;
    private BukkitTask spawnTask;
    private long tick;

    public RiftManager(SiftPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        animTask = Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 20L, 2L);
        long interval = Math.max(20L, plugin.getConfig().getLong("rifts.natural-spawning.check-interval-seconds", 120) * 20L);
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::naturalSpawn, interval, interval);
    }

    public void shutdown() {
        if (animTask != null) animTask.cancel();
        if (spawnTask != null) spawnTask.cancel();
        rifts.clear();
    }

    public int activeCount() {
        return rifts.size();
    }

    // ------------------------------------------------------------ creation

    /** Creates a rift at {@code origin} paired with a safe spot in the opposite dimension. */
    public CompletableFuture<Boolean> summonAt(Location origin) {
        World from = origin.getWorld();
        World to = plugin.siftWorld().counterpart(from);
        if (to == null) return CompletableFuture.completedFuture(false);

        boolean toSift = plugin.siftWorld().isSift(to);
        double scale = Math.max(0.01, plugin.getConfig().getDouble("portal.sift-scale", 1.0));
        int tx = (int) Math.floor(toSift ? origin.getX() / scale : origin.getX() * scale);
        int tz = (int) Math.floor(toSift ? origin.getZ() / scale : origin.getZ() * scale);

        return findSafeSpot(to, tx, tz, 16, 40).thenApply(dest -> {
            if (dest == null) return false;
            create(origin, dest);
            return true;
        });
    }

    private void create(Location a, Location b) {
        long now = System.currentTimeMillis();
        long dur = plugin.getConfig().getLong("rifts.duration-seconds", 300) * 1000L;
        rifts.add(new Rift(a.clone(), b.clone(), now + dur));

        for (Location l : new Location[]{a, b}) {
            l.getWorld().playSound(l, Sound.BLOCK_END_PORTAL_SPAWN, 1.0f, 1.4f);
            l.getWorld().spawnParticle(Particle.REVERSE_PORTAL, l.clone().add(0, 1, 0), 80, 0.6, 1.0, 0.6, 0.3);
            if (plugin.getConfig().getBoolean("rifts.announce", true)) {
                for (Player p : l.getWorld().getNearbyPlayers(l, 96)) {
                    p.sendActionBar(Component.text("A rift tears open nearby...", NamedTextColor.LIGHT_PURPLE));
                }
            }
        }
    }

    private void naturalSpawn() {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("rifts.natural-spawning.enabled", true)) return;
        if (rifts.size() >= cfg.getInt("rifts.max-active", 6)) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() > cfg.getDouble("rifts.natural-spawning.chance", 0.35)) return;

        List<Player> pool = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.siftWorld().counterpart(p.getWorld()) != null) pool.add(p);
        }
        if (pool.isEmpty()) return;

        Player p = pool.get(r.nextInt(pool.size()));
        double min = cfg.getDouble("rifts.natural-spawning.min-distance", 24);
        double max = Math.max(min + 1, cfg.getDouble("rifts.natural-spawning.max-distance", 80));
        double angle = r.nextDouble() * Math.PI * 2;
        double dist = min + r.nextDouble() * (max - min);
        int x = (int) Math.floor(p.getX() + Math.cos(angle) * dist);
        int z = (int) Math.floor(p.getZ() + Math.sin(angle) * dist);

        findSafeSpot(p.getWorld(), x, z, 8, 12).thenAccept(spot -> {
            if (spot != null && rifts.size() < cfg.getInt("rifts.max-active", 6)) summonAt(spot);
        });
    }

    // ------------------------------------------------------------ safe spot search

    /** A standing spot on solid, dry ground with two free blocks above, searched around (cx, cz). */
    public CompletableFuture<Location> findSafeSpot(World w, int cx, int cz, int attempts, int jitter) {
        if (attempts <= 0) return CompletableFuture.completedFuture(null);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int x = attempts == 1 || jitter == 0 ? cx : cx + r.nextInt(-jitter, jitter + 1);
        int z = attempts == 1 || jitter == 0 ? cz : cz + r.nextInt(-jitter, jitter + 1);
        return w.getChunkAtAsync(x >> 4, z >> 4).thenCompose(chunk -> {
            Location loc = surface(w, x, z);
            if (loc != null) return CompletableFuture.completedFuture(loc);
            return findSafeSpot(w, cx, cz, attempts - 1, Math.max(jitter, 8));
        });
    }

    public static Location surface(World w, int x, int z) {
        Block b = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (b.getType().isAir() || b.isLiquid() || !b.getType().isSolid()) return null;
        Block up1 = b.getRelative(0, 1, 0);
        Block up2 = b.getRelative(0, 2, 0);
        if (!up1.isPassable() || !up2.isPassable() || up1.isLiquid()) return null;
        return b.getLocation().add(0.5, 1.0, 0.5);
    }

    // ------------------------------------------------------------ animation + teleport

    private void animate() {
        tick += 2;
        long now = System.currentTimeMillis();
        if (tick % 200 == 0) cooldowns.values().removeIf(t -> t < now);

        Iterator<Rift> it = rifts.iterator();
        while (it.hasNext()) {
            Rift rift = it.next();
            if (now >= rift.expiresAt) {
                collapse(rift.a);
                collapse(rift.b);
                it.remove();
                continue;
            }
            process(rift.a, rift.b);
            process(rift.b, rift.a);
        }
    }

    private void collapse(Location l) {
        World w = l.getWorld();
        if (w == null) return;
        w.playSound(l, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.5f);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 1, 0), 40, 0.5, 1.0, 0.5, 0.05);
    }

    private void process(Location c, Location twin) {
        World w = c.getWorld();
        if (w == null || !w.isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4)) return;
        if (w.getNearbyPlayers(c, 64).isEmpty()) return;

        vortex(c, 1.0, tick);
        if (tick % 40 == 0) w.playSound(c, Sound.BLOCK_PORTAL_AMBIENT, 0.5f, 1.6f);

        long cd = plugin.getConfig().getLong("rifts.teleport-cooldown-seconds", 3) * 1000L;
        long now = System.currentTimeMillis();
        for (Entity e : w.getNearbyEntities(c.clone().add(0, 1.0, 0), 1.3, 1.8, 1.3)) {
            if (!eligible(e)) continue;
            Long until = cooldowns.get(e.getUniqueId());
            if (until != null && until > now) continue;
            cooldowns.put(e.getUniqueId(), now + cd);

            Location dest = safeAround(twin);
            dest.setYaw(e.getLocation().getYaw());
            dest.setPitch(e.getLocation().getPitch());
            Vector velocity = e.getVelocity();
            Location from = e.getLocation();

            e.teleportAsync(dest).thenAccept(ok -> {
                if (!ok || !e.isValid()) return;
                e.setVelocity(velocity);
                from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.6f);
                dest.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
            });
        }
    }

    private boolean eligible(Entity e) {
        if (!e.isValid() || e.isInsideVehicle() || !e.getPassengers().isEmpty()) return false;
        if (e instanceof Player p) return p.getGameMode() != GameMode.SPECTATOR && p.hasPermission("sift.portal.use");
        if (e instanceof ArmorStand) return false;
        return e instanceof LivingEntity || e instanceof Item || e instanceof Projectile;
    }

    /** Picks a free spot about 2 blocks from the twin so arrivals do not re-trigger it. */
    private Location safeAround(Location twin) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double start = r.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 8; i++) {
            double a = start + i * Math.PI / 4;
            Location l = twin.clone().add(Math.cos(a) * 2.4, 0, Math.sin(a) * 2.4);
            Block feet = l.getBlock();
            Block head = feet.getRelative(0, 1, 0);
            Block below = feet.getRelative(0, -1, 0);
            if (feet.isPassable() && head.isPassable() && !feet.isLiquid() && below.getType().isSolid()) return l;
        }
        return twin.clone();
    }

    // ------------------------------------------------------------ visuals

    /** Swirling funnel of Portal + Reverse Portal + Soul Fire Flame particles. */
    private void vortex(Location c, double scale, long t) {
        World w = c.getWorld();
        double spin = t * 0.35;
        for (int k = 0; k < 14; k++) {
            double y = k * 0.17 * scale;
            double radius = Math.max(0.15, (1.15 - k * 0.07) * scale);
            double angle = spin + k * 0.55;
            double x1 = c.getX() + Math.cos(angle) * radius;
            double z1 = c.getZ() + Math.sin(angle) * radius;
            double x2 = c.getX() + Math.cos(angle + Math.PI) * radius;
            double z2 = c.getZ() + Math.sin(angle + Math.PI) * radius;
            w.spawnParticle(Particle.PORTAL, x1, c.getY() + 0.1 + y, z1, 1, 0, 0, 0, 0.0);
            w.spawnParticle(Particle.REVERSE_PORTAL, x2, c.getY() + 0.1 + y, z2, 1, 0, 0, 0, 0.02);
            if (k % 3 == 0) {
                w.spawnParticle(Particle.SOUL_FIRE_FLAME,
                        c.getX() + Math.cos(angle + Math.PI / 2) * radius, c.getY() + 0.1 + y,
                        c.getZ() + Math.sin(angle + Math.PI / 2) * radius, 1, 0, 0, 0, 0.0);
            }
        }
    }

    /** A small one-way vortex used by death recovery. */
    public void playMiniRift(Location loc, int durationTicks) {
        Location center = loc.clone();
        World w = center.getWorld();
        w.playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, 0.8f, 1.8f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= durationTicks) {
                    collapse(center);
                    cancel();
                    return;
                }
                vortex(center, 0.6, t);
                t += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }
}
