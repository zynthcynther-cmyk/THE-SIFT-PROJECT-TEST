package dev.sift.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ichor hazard. Any configured liquid inside The Sift drains health unless the player wears a
 * chestplate with Soul Absorption (I: immune, II: Regeneration II instead). Also handles Bottled Ichor.
 */
public final class IchorManager implements Listener {

    private final SiftPlugin plugin;
    private final Set<Material> ichorMaterials = EnumSet.noneOf(Material.class);
    private BukkitTask task;

    public IchorManager(SiftPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        reloadSettings();
    }

    /** (Re)reads the material list and restarts the check task with the configured interval. */
    public void reloadSettings() {
        if (task != null) task.cancel();
        FileConfiguration cfg = plugin.getConfig();

        ichorMaterials.clear();
        for (String name : cfg.getStringList("ichor.materials")) {
            Material m = Material.matchMaterial(name);
            if (m != null) ichorMaterials.add(m);
        }
        if (ichorMaterials.isEmpty()) ichorMaterials.add(Material.WATER);

        if (!cfg.getBoolean("ichor.enabled", true)) return;
        long interval = Math.max(1L, cfg.getLong("ichor.tick-interval", 10));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
    }

    public boolean isIchor(Block block) {
        if (ichorMaterials.contains(block.getType())) return true;
        return ichorMaterials.contains(Material.WATER)
                && block.getBlockData() instanceof Waterlogged w && w.isWaterlogged();
    }

    public boolean isInIchor(Player player) {
        return isIchor(player.getLocation().getBlock()) || isIchor(player.getEyeLocation().getBlock());
    }

    private void tick() {
        World sift = plugin.siftWorld().get();
        if (sift == null) return;
        FileConfiguration cfg = plugin.getConfig();

        double damage = cfg.getDouble("ichor.damage", 2.0);
        boolean wither = "WITHER".equalsIgnoreCase(cfg.getString("ichor.damage-type", "MAGIC"));
        boolean witherEffect = cfg.getBoolean("ichor.apply-wither-effect", false);
        int regenTicks = cfg.getInt("ichor.regeneration-duration-ticks", 60);

        for (Player p : sift.getPlayers()) {
            ambient(p);
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (!isInIchor(p)) continue;

            int level = plugin.items().soulLevel(p.getInventory().getChestplate());
            Location at = p.getLocation().add(0, 1, 0);
            if (level >= 2) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, regenTicks, 1, true, true, true));
                p.getWorld().spawnParticle(Particle.HEART, at, 1, 0.3, 0.4, 0.3, 0.0);
            } else if (level == 1) {
                p.getWorld().spawnParticle(Particle.SCULK_SOUL, at, 1, 0.3, 0.4, 0.3, 0.01);
            } else {
                DamageSource src = DamageSource.builder(wither ? DamageType.WITHER : DamageType.MAGIC).build();
                p.damage(damage, src);
                if (witherEffect) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40, 0, true, true, true));
                }
                p.getWorld().spawnParticle(Particle.DUST, at, 6, 0.3, 0.5, 0.3, 0.0,
                        new Particle.DustOptions(Color.fromRGB(0x6A0DAD), 1.4f));
            }
        }
    }

    /** Glowing motes over nearby Ichor surfaces so the pools read as hazardous. */
    private void ambient(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location base = p.getLocation();
        World w = p.getWorld();
        for (int i = 0; i < 20; i++) {
            int x = base.getBlockX() + r.nextInt(-14, 15);
            int y = base.getBlockY() + r.nextInt(-8, 5);
            int z = base.getBlockZ() + r.nextInt(-14, 15);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = w.getBlockAt(x, y, z);
            if (!ichorMaterials.contains(b.getType()) || !b.getRelative(0, 1, 0).getType().isAir()) continue;
            w.spawnParticle(Particle.DUST, x + r.nextDouble(), y + 1.05, z + r.nextDouble(), 2, 0.2, 0.05, 0.2, 0.0,
                    new Particle.DustOptions(Color.fromRGB(0x9B30FF), 1.2f));
            if (r.nextInt(4) == 0) w.spawnParticle(Particle.SCULK_SOUL, x + 0.5, y + 1.2, z + 0.5, 1, 0.1, 0.2, 0.1, 0.01);
        }
    }

    // ------------------------------------------------------------ Bottled Ichor

    @EventHandler
    public void onDrink(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (!plugin.items().is(item, SiftItems.BOTTLED_ICHOR)) return;

        Player p = event.getPlayer();
        FileConfiguration cfg = plugin.getConfig();
        int level = plugin.items().soulLevel(p.getInventory().getChestplate());

        if (level >= 2) {
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            double cap = max != null ? max.getValue() : 20.0;
            p.setHealth(Math.min(cap, p.getHealth() + cfg.getDouble("ichor.bottled.heal", 8.0)));
            p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.5, 0), 8, 0.4, 0.4, 0.4, 0.0);
        } else if (level == 1 && cfg.getBoolean("ichor.bottled.soul-absorption-1-negates", true)) {
            p.getWorld().spawnParticle(Particle.SCULK_SOUL, p.getLocation().add(0, 1.5, 0), 8, 0.4, 0.4, 0.4, 0.02);
        } else {
            DamageSource src = DamageSource.builder(DamageType.MAGIC).build();
            p.damage(cfg.getDouble("ichor.bottled.damage", 8.0), src);
            int secs = cfg.getInt("ichor.bottled.wither-seconds", 6);
            if (secs > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, secs * 20, 0));
        }
    }
}
