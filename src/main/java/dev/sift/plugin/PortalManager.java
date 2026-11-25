package dev.sift.plugin;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Awakened Ancient-City-style portals: a 4x5 reinforced deepslate frame (2x3 opening).
 * Portals are persisted in portals.yml, linked lazily to a generated counterpart and
 * teleport players through the opening.
 */
public final class PortalManager {

    /** Interior min corner plus orientation. Opening is 2 wide x 3 tall. */
    public record Frame(String world, int x, int y, int z, boolean xAxis) {

        public BoundingBox box() {
            return new BoundingBox(x, y, z, x + (xAxis ? 2 : 1), y + 3, z + (xAxis ? 1 : 2));
        }

        public Location center(World w) {
            return new Location(w, x + (xAxis ? 1.0 : 0.5), y, z + (xAxis ? 0.5 : 1.0));
        }

        /** Where travellers are placed: two blocks in front of the frame, facing away from it. */
        public Location arrival(World w) {
            return xAxis
                    ? new Location(w, x + 1.0, y, z + 2.0, 0f, 0f)
                    : new Location(w, x + 2.0, y, z + 1.0, -90f, 0f);
        }

        public List<int[]> ring(boolean withCorners) {
            List<int[]> out = new ArrayList<>();
            for (int i = -1; i <= 2; i++) {
                for (int j = -1; j <= 3; j++) {
                    boolean interior = i >= 0 && i <= 1 && j >= 0 && j <= 2;
                    boolean corner = (i == -1 || i == 2) && (j == -1 || j == 3);
                    if (interior || (corner && !withCorners)) continue;
                    out.add(new int[]{x + (xAxis ? i : 0), y + j, z + (xAxis ? 0 : i)});
                }
            }
            return out;
        }

        public List<int[]> interior() {
            List<int[]> out = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < 3; j++) {
                    out.add(new int[]{x + (xAxis ? i : 0), y + j, z + (xAxis ? 0 : i)});
                }
            }
            return out;
        }
    }

    public static final class Portal {
        public final UUID id;
        public final Frame frame;
        public UUID link;

        Portal(UUID id, Frame frame) {
            this.id = id;
            this.frame = frame;
        }
    }

    private final SiftPlugin plugin;
    private final File file;
    private final Map<UUID, Portal> portals = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> building = new HashSet<>();
    private BukkitTask task;
    private long tickCount;

    public PortalManager(SiftPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "portals.yml");
    }

    // ------------------------------------------------------------ lifecycle

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 4L);
    }

    public void load() {
        portals.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("portals");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Portal p = new Portal(UUID.fromString(key),
                        new Frame(s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"), s.getBoolean("xAxis")));
                String link = s.getString("link");
                if (link != null) p.link = UUID.fromString(link);
                portals.put(p.id, p);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping corrupt portal entry " + key);
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Portal p : portals.values()) {
            String base = "portals." + p.id;
            yml.set(base + ".world", p.frame.world());
            yml.set(base + ".x", p.frame.x());
            yml.set(base + ".y", p.frame.y());
            yml.set(base + ".z", p.frame.z());
            yml.set(base + ".xAxis", p.frame.xAxis());
            yml.set(base + ".link", p.link == null ? null : p.link.toString());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save portals.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ frame detection

    public Material frameMaterial() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("portal.frame-material", "REINFORCED_DEEPSLATE"));
        return m != null ? m : Material.REINFORCED_DEEPSLATE;
    }

    /** Frame ring (without corners) intact; optionally also requires a free opening. */
    public boolean isValid(World w, Frame f, boolean requireEmptyOpening) {
        Material fm = frameMaterial();
        for (int[] p : f.ring(false)) {
            if (w.getBlockAt(p[0], p[1], p[2]).getType() != fm) return false;
        }
        if (requireEmptyOpening) {
            for (int[] p : f.interior()) {
                Block b = w.getBlockAt(p[0], p[1], p[2]);
                if (!(b.isEmpty() || b.isPassable()) || b.isLiquid()) return false;
            }
        }
        return true;
    }

    public Optional<Frame> findFrame(Location around, int radius) {
        World w = around.getWorld();
        Material fm = frameMaterial();
        Frame best = null;
        double bestDist = Double.MAX_VALUE;
        int cx = around.getBlockX(), cy = around.getBlockY(), cz = around.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block c = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (c.getType() != fm) continue;
                    // c is the bottom frame block directly under the opening's min corner
                    for (boolean xAxis : new boolean[]{true, false}) {
                        Frame f = new Frame(w.getName(), c.getX(), c.getY() + 1, c.getZ(), xAxis);
                        if (!isValid(w, f, true)) continue;
                        double d = f.center(w).distanceSquared(around);
                        if (d < bestDist) {
                            bestDist = d;
                            best = f;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public boolean isActive(Frame f) {
        for (Portal p : portals.values()) if (p.frame.equals(f)) return true;
        return false;
    }

    // ------------------------------------------------------------ activation

    public Portal activate(Frame f) {
        for (Portal p : portals.values()) if (p.frame.equals(f)) return p;
        Portal p = new Portal(UUID.randomUUID(), f);
        portals.put(p.id, p);
        save();
        World w = Bukkit.getWorld(f.world());
        if (w != null) activationEffects(w, f.center(w));
        return p;
    }

    private void remove(Portal p) {
        portals.remove(p.id);
        for (Portal other : portals.values()) {
            if (p.id.equals(other.link)) other.link = null;
        }
        save();
        World w = Bukkit.getWorld(p.frame.world());
        if (w != null) {
            Location c = p.frame.center(w);
            w.playSound(c, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.6f);
            w.spawnParticle(Particle.SCULK_SOUL, c.clone().add(0, 1.5, 0), 20, 0.5, 1.0, 0.5, 0.02);
        }
    }

    private void activationEffects(World w, Location c) {
        Location mid = c.clone().add(0, 1.5, 0);
        w.playSound(c, Sound.BLOCK_END_PORTAL_SPAWN, 1.5f, 0.6f);
        w.playSound(c, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 1.5f, 0.7f);
        w.spawnParticle(Particle.SONIC_BOOM, mid, 1);
        w.spawnParticle(Particle.SCULK_SOUL, mid, 60, 0.6, 1.2, 0.6, 0.05);
        w.spawnParticle(Particle.END_ROD, mid, 40, 0.6, 1.2, 0.6, 0.1);
        w.strikeLightningEffect(c);
    }

    // ------------------------------------------------------------ build / destination

    /**
     * Builds a frame whose bottom row sits on layer {@code g}. When {@code platformAndClear} is
     * set, a small platform is laid under it and the space around/above it is cleared.
     */
    public Frame buildFrame(World w, int ix, int g, int iz, boolean xAxis, boolean platformAndClear) {
        Frame f = new Frame(w.getName(), ix, g + 1, iz, xAxis);
        if (platformAndClear) {
            for (int x = ix - 3; x <= ix + 4; x++) {
                for (int z = iz - 3; z <= iz + 4; z++) {
                    if (!w.getBlockAt(x, g, z).getType().isSolid()) w.getBlockAt(x, g, z).setType(Material.SMOOTH_BASALT, false);
                    if (w.getBlockAt(x, g - 1, z).getType().isAir()) w.getBlockAt(x, g - 1, z).setType(Material.BASALT, false);
                    for (int y = g + 1; y <= g + 5; y++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (!b.getType().isAir()) b.setType(Material.AIR, false);
                    }
                }
            }
        }
        Material fm = frameMaterial();
        for (int[] p : f.ring(true)) w.getBlockAt(p[0], p[1], p[2]).setType(fm, false);
        for (int[] p : f.interior()) w.getBlockAt(p[0], p[1], p[2]).setType(Material.AIR, false);
        return f;
    }

    private CompletableFuture<Void> loadArea(World w, int x0, int z0, int x1, int z1) {
        List<CompletableFuture<?>> list = new ArrayList<>();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                list.add(w.getChunkAtAsync(cx, cz));
            }
        }
        return CompletableFuture.allOf(list.toArray(new CompletableFuture<?>[0]));
    }

    /** Resolves (building it on first use) where travellers of {@code src} should arrive. */
    private CompletableFuture<Location> destination(Portal src) {
        Portal linked = src.link != null ? portals.get(src.link) : null;
        if (linked != null) {
            World lw = Bukkit.getWorld(linked.frame.world());
            if (lw != null) return CompletableFuture.completedFuture(linked.frame.arrival(lw));
        }

        World from = Bukkit.getWorld(src.frame.world());
        World to = plugin.siftWorld().counterpart(from);
        if (to == null) return CompletableFuture.completedFuture(null);

        building.add(src.id);
        boolean toSift = plugin.siftWorld().isSift(to);
        double scale = Math.max(0.01, plugin.getConfig().getDouble("portal.sift-scale", 1.0));
        int tx = (int) Math.floor(toSift ? src.frame.x() / scale : src.frame.x() * scale);
        int tz = (int) Math.floor(toSift ? src.frame.z() / scale : src.frame.z() * scale);

        return loadArea(to, tx - 4, tz - 4, tx + 5, tz + 5).thenApply(v -> {
            try {
                int g = toSift
                        ? SiftChunkGenerator.BASE_Y
                        : to.getHighestBlockYAt(tx, tz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                g = Math.max(to.getMinHeight() + 2, Math.min(g, to.getMaxHeight() - 12));
                Frame nf = buildFrame(to, tx, g, tz, true, true);
                Portal np = new Portal(UUID.randomUUID(), nf);
                np.link = src.id;
                src.link = np.id;
                portals.put(np.id, np);
                save();
                activationEffects(to, nf.center(to));
                return nf.arrival(to);
            } finally {
                building.remove(src.id);
            }
        }).exceptionally(ex -> {
            building.remove(src.id);
            plugin.getLogger().warning("Could not build portal destination: " + ex);
            return null;
        });
    }

    // ------------------------------------------------------------ ticking

    private boolean eligible(Entity e) {
        if (e instanceof Player p) return p.getGameMode() != GameMode.SPECTATOR && p.hasPermission("sift.portal.use");
        return plugin.getConfig().getBoolean("portal.teleport-mobs", false)
                && e instanceof LivingEntity && !(e instanceof ArmorStand);
    }

    private void tick() {
        tickCount += 4;
        if (tickCount % 400 == 0) {
            long now = System.currentTimeMillis();
            cooldowns.values().removeIf(t -> t < now);
        }
        for (Portal p : new ArrayList<>(portals.values())) {
            World w = Bukkit.getWorld(p.frame.world());
            if (w == null) continue;
            Location c = p.frame.center(w);
            if (!w.isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4)) continue;
            if (tickCount % 40 == 0 && !isValid(w, p.frame, false)) {
                remove(p);
                continue;
            }
            if (w.getNearbyPlayers(c, 48).isEmpty()) continue;
            effects(w, p.frame, c);
            travelCheck(w, p);
        }
    }

    private void effects(World w, Frame f, Location c) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        BoundingBox b = f.box();
        for (int i = 0; i < 6; i++) {
            double x = b.getMinX() + r.nextDouble() * b.getWidthX();
            double y = b.getMinY() + r.nextDouble() * b.getHeight();
            double z = b.getMinZ() + r.nextDouble() * b.getWidthZ();
            w.spawnParticle(Particle.PORTAL, x, y, z, 2, 0.1, 0.1, 0.1, 0.4);
            if (i % 3 == 0) w.spawnParticle(Particle.REVERSE_PORTAL, x, y, z, 1, 0.1, 0.1, 0.1, 0.05);
        }
        if (r.nextInt(6) == 0) {
            w.spawnParticle(Particle.SCULK_SOUL, c.getX(), c.getY() + 1.5, c.getZ(), 1, 0.3, 0.8, 0.3, 0.01);
        }
        if (tickCount % 80 == 0) w.playSound(c, Sound.BLOCK_PORTAL_AMBIENT, 0.6f, 0.5f);
    }

    private void travelCheck(World w, Portal p) {
        BoundingBox box = p.frame.box().expand(0.1);
        Collection<Entity> inside = w.getNearbyEntities(box, this::eligible);
        for (Entity e : inside) travel(p, e);
    }

    private void travel(Portal p, Entity e) {
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(e.getUniqueId());
        if (until != null && until > now) return;
        if (building.contains(p.id)) return;
        long cd = plugin.getConfig().getLong("portal.teleport-cooldown-seconds", 5) * 1000L;
        cooldowns.put(e.getUniqueId(), now + cd);

        destination(p).thenAccept(dest -> {
            if (dest == null || !e.isValid()) return;
            Location from = e.getLocation();
            e.teleportAsync(dest).thenAccept(ok -> {
                if (!ok) return;
                cooldowns.put(e.getUniqueId(), System.currentTimeMillis() + cd);
                from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
                dest.getWorld().playSound(dest, Sound.BLOCK_PORTAL_TRAVEL, 0.4f, 1.2f);
                dest.getWorld().spawnParticle(Particle.REVERSE_PORTAL, dest.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.1);
            });
        });
    }
}
