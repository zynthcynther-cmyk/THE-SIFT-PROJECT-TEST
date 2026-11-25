package dev.sift.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;

/** Creates/loads world_the_sift and resolves the Overworld/Sift pairing. */
public final class SiftWorld {

    private final SiftPlugin plugin;
    private World world;

    public SiftWorld(SiftPlugin plugin) {
        this.plugin = plugin;
    }

    public World create() {
        String name = plugin.getConfig().getString("world.name", "world_the_sift");
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            world = existing;
            return world;
        }

        World.Environment env;
        try {
            env = World.Environment.valueOf(
                    plugin.getConfig().getString("world.environment", "NORMAL").toUpperCase());
        } catch (IllegalArgumentException ex) {
            env = World.Environment.NORMAL;
        }
        if (env == World.Environment.CUSTOM) env = World.Environment.NORMAL;

        ConfigurationSection gen = plugin.getConfig().getConfigurationSection("world.generation");
        WorldCreator creator = new WorldCreator(name)
                .environment(env)
                .generator(new SiftChunkGenerator(gen))
                .generateStructures(false);
        world = Bukkit.createWorld(creator);
        if (world != null) {
            world.setSpawnLocation(0, SiftChunkGenerator.BASE_Y + 1, 0);
        }
        return world;
    }

    public World get() { return world; }

    public boolean isSift(World w) {
        return w != null && world != null && w.getUID().equals(world.getUID());
    }

    public Location spawn() {
        return new Location(world, 0.5, SiftChunkGenerator.BASE_Y + 1, 0.5);
    }

    public World overworld() {
        String configured = plugin.getConfig().getString("portal.overworld-name", "");
        if (configured != null && !configured.isBlank()) {
            World w = Bukkit.getWorld(configured);
            if (w != null) return w;
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL && !isSift(w)) return w;
        }
        return null;
    }

    /** The world on the other side of a portal/rift, or null if {@code from} is not paired. */
    public World counterpart(World from) {
        if (from == null) return null;
        if (isSift(from)) return overworld();
        if (from.getEnvironment() == World.Environment.NORMAL) return world;
        return null;
    }
}
