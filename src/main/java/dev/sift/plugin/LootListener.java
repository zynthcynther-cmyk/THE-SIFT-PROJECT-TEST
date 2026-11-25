package dev.sift.plugin;

import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/** Siftite / Charoite / template / tome drops from Sift mobs and Sift chests. */
public final class LootListener implements Listener {

    private final SiftPlugin plugin;

    public LootListener(SiftPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob)) return;
        if (!plugin.siftWorld().isSift(event.getEntity().getWorld())) return;

        FileConfiguration cfg = plugin.getConfig();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() < cfg.getDouble("loot.mob-drop.siftite-ingot", 0.06)) event.getDrops().add(plugin.items().siftiteIngot());
        if (r.nextDouble() < cfg.getDouble("loot.mob-drop.charoite", 0.10)) event.getDrops().add(plugin.items().charoite());
        if (r.nextDouble() < cfg.getDouble("loot.mob-drop.siftite-template", 0.008)) event.getDrops().add(plugin.items().upgradeTemplate());
    }

    @EventHandler
    public void onChestLoot(LootGenerateEvent event) {
        World w = event.getWorld();
        if (!plugin.siftWorld().isSift(w)) return;

        FileConfiguration cfg = plugin.getConfig();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        SiftItems items = plugin.items();

        if (r.nextDouble() < cfg.getDouble("loot.chest.siftite-ingot", 0.35)) {
            ItemStack s = items.siftiteIngot();
            s.setAmount(1 + r.nextInt(3));
            event.getLoot().add(s);
        }
        if (r.nextDouble() < cfg.getDouble("loot.chest.charoite", 0.50)) {
            ItemStack s = items.charoite();
            s.setAmount(1 + r.nextInt(4));
            event.getLoot().add(s);
        }
        if (r.nextDouble() < cfg.getDouble("loot.chest.siftite-template", 0.15)) {
            event.getLoot().add(items.upgradeTemplate());
        }
        if (r.nextDouble() < cfg.getDouble("loot.chest.soul-tome", 0.12)) {
            event.getLoot().add(items.soulTome(r.nextDouble() < 0.35 ? 2 : 1));
        }
        if (r.nextDouble() < 0.25) event.getLoot().add(items.bottledIchor());
    }
}
