package dev.sift.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.loot.Lootable;
import org.bukkit.loot.LootTables;
import org.bukkit.util.noise.SimplexOctaveGenerator;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Random;

/**
 * Void-adjacent wasteland: floating islands of basalt / end stone capped with sculk,
 * moss clearings and smooth basalt, split by chasms, with amethyst veins, Ichor pools
 * (water pockets lined with sculk), pale oak trees and loot chests.
 */
public final class SiftChunkGenerator extends ChunkGenerator {

    /** Island surface level at the spawn platform. */
    public static final int BASE_Y = 64;

    private final double threshold;
    private final double chasmWidth;
    private final double poolThreshold;
    private final int heightVar;
    private final int spawnRadius;
    private final int trees;
    private final double chestChance;

    private volatile Noise noise;

    public SiftChunkGenerator(ConfigurationSection s) {
        this.threshold = s == null ? 0.12 : s.getDouble("island-threshold", 0.12);
        this.chasmWidth = s == null ? 0.07 : s.getDouble("chasm-width", 0.07);
        this.poolThreshold = s == null ? 0.55 : s.getDouble("pool-threshold", 0.55);
        this.heightVar = s == null ? 18 : s.getInt("height-variance", 18);
        this.spawnRadius = s == null ? 24 : s.getInt("spawn-platform-radius", 24);
        this.trees = s == null ? 2 : s.getInt("trees-per-chunk", 2);
        this.chestChance = s == null ? 0.04 : s.getDouble("chest-chance", 0.04);
    }

    // ---------------------------------------------------------------- noise

    private static final class Noise {
        final long seed;
        final SimplexOctaveGenerator island, height, chasm, biome, pool, under;

        Noise(long seed) {
            this.seed = seed;
            island = gen(seed, 4, 0.010);
            height = gen(seed + 1, 3, 0.006);
            chasm = gen(seed + 2, 3, 0.012);
            biome = gen(seed + 3, 2, 0.015);
            pool = gen(seed + 4, 2, 0.030);
            under = gen(seed + 5, 2, 0.040);
        }

        private static SimplexOctaveGenerator gen(long seed, int octaves, double scale) {
            SimplexOctaveGenerator g = new SimplexOctaveGenerator(seed, octaves);
            g.setScale(scale);
            return g;
        }
    }

    private Noise noise(long seed) {
        Noise n = noise;
        if (n == null) {
            synchronized (this) {
                if (noise == null) noise = new Noise(seed);
                n = noise;
            }
        }
        return n;
    }

    private static double hash(long seed, int x, int y, int z) {
        long h = seed ^ (x * 341873128712L) ^ (z * 132897987541L) ^ (y * 42317861L);
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }

    // ------------------------------------------------------------ generation

    @Override
    public void generateNoise(@NotNull WorldInfo info, @NotNull Random random, int cx, int cz, @NotNull ChunkData data) {
        Noise n = noise(info.getSeed());
        int minY = data.getMinHeight();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                column(n, data, lx, lz, (cx << 4) + lx, (cz << 4) + lz, minY);
            }
        }
    }

    private void column(Noise n, ChunkData data, int lx, int lz, int x, int z, int minY) {
        double d = Math.sqrt((double) x * x + (double) z * z);

        double m = n.island.noise(x, z, 2.0, 0.5, true);
        if (d < spawnRadius) m += (1.0 - d / spawnRadius) * 1.6;           // guaranteed spawn island
        double c = Math.abs(n.chasm.noise(x, z, 2.0, 0.5, true));
        if (c < chasmWidth && d > spawnRadius) m -= (1.0 - c / chasmWidth) * 1.5; // chasms
        if (m < threshold) return;                                           // void

        double w = Math.min(1.0, d / Math.max(1, spawnRadius));
        double hn = n.height.noise(x, z, 2.0, 0.5, true);
        int top = BASE_Y + (int) Math.round(hn * heightVar * w);
        double under = (n.under.noise(x, z, 2.0, 0.5, true) + 1.0) / 2.0;
        int thickness = Math.min(70, 6 + (int) ((m - threshold) * 40.0 * (0.5 + under)));
        int bottom = Math.max(minY + 1, top - thickness);

        double bio = n.biome.noise(x, z, 2.0, 0.5, true);
        Material cover, sub;
        if (bio > 0.2) {            // overgrown clearing
            cover = Material.MOSS_BLOCK;
            sub = Material.DIRT;
        } else if (bio < -0.2) {    // sculk blight
            cover = Material.SCULK;
            sub = Material.DEEPSLATE;
        } else {                    // barren basalt
            cover = Material.SMOOTH_BASALT;
            sub = Material.BASALT;
        }

        for (int y = bottom; y <= top; y++) {
            int depth = top - y;
            Material mat;
            if (depth == 0) mat = cover;
            else if (depth <= 2) mat = sub;
            else if (y > bottom + thickness * 0.45) mat = Material.END_STONE;
            else mat = Material.BASALT;

            if (depth > 3) {
                double r = hash(n.seed, x, y, z);
                if (r < 0.002) mat = Material.BUDDING_AMETHYST;
                else if (r < 0.014) mat = Material.AMETHYST_BLOCK;
            }
            data.setBlock(lx, y, lz, mat);
        }

        // Ichor pool: a water pocket lined with sculk
        if (d > spawnRadius + 4 && m > threshold + 0.1
                && n.pool.noise(x, z, 2.0, 0.5, true) > poolThreshold) {
            int floor = top - 4;
            data.setBlock(lx, floor, lz, Material.SCULK);
            for (int y = floor + 1; y < top; y++) data.setBlock(lx, y, lz, Material.WATER);
            data.setBlock(lx, top, lz, Material.AIR);
        }
    }

    // --------------------------------------------------------------- misc

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0.5, BASE_Y + 1, 0.5);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public @NotNull Biome getBiome(@NotNull WorldInfo info, int x, int y, int z) {
                return Biome.PALE_GARDEN;
            }

            @Override
            public @NotNull List<Biome> getBiomes(@NotNull WorldInfo info) {
                return List.of(Biome.PALE_GARDEN);
            }
        };
    }

    @Override
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of(new Populator());
    }

    // ------------------------------------------------------------ populator

    private final class Populator extends BlockPopulator {

        @Override
        public void populate(@NotNull WorldInfo info, @NotNull Random random, int cx, int cz, @NotNull LimitedRegion region) {
            int scanTop = BASE_Y + heightVar + 2;
            int scanBottom = BASE_Y - 70;

            for (int i = 0; i < trees + 3; i++) {
                int x = (cx << 4) + 3 + random.nextInt(10);
                int z = (cz << 4) + 3 + random.nextInt(10);
                int y = surface(region, x, z, scanTop, scanBottom);
                if (y == Integer.MIN_VALUE) continue;
                Material top = region.getType(x, y, z);

                if (top == Material.MOSS_BLOCK && i < trees) {
                    tree(region, random, x, y + 1, z);
                } else if (top == Material.SCULK && random.nextDouble() < 0.25) {
                    region.setType(x, y + 1, z, random.nextDouble() < 0.2 ? Material.SCULK_SHRIEKER : Material.SCULK_SENSOR);
                } else if ((top == Material.SMOOTH_BASALT || top == Material.END_STONE) && random.nextDouble() < 0.5) {
                    region.setType(x, y + 1, z, Material.AMETHYST_CLUSTER);
                }
            }

            if (random.nextDouble() < chestChance) {
                int x = (cx << 4) + 3 + random.nextInt(10);
                int z = (cz << 4) + 3 + random.nextInt(10);
                int y = surface(region, x, z, scanTop, scanBottom);
                if (y != Integer.MIN_VALUE && region.getType(x, y, z) != Material.WATER) {
                    chest(region, random, x, y + 1, z);
                }
            }
        }

        private int surface(LimitedRegion region, int x, int z, int from, int to) {
            for (int y = from; y >= to; y--) {
                if (!region.isInRegion(x, y, z)) continue;
                Material t = region.getType(x, y, z);
                if (t == Material.WATER) return Integer.MIN_VALUE;
                if (!t.isAir()) return y;
            }
            return Integer.MIN_VALUE;
        }

        private void tree(LimitedRegion region, Random random, int x, int y, int z) {
            int h = 4 + random.nextInt(3);
            for (int dy = 0; dy < h + 3; dy++) {
                if (!region.isInRegion(x, y + dy, z) || !region.getType(x, y + dy, z).isAir()) return;
            }
            for (int dy = 0; dy < h; dy++) region.setType(x, y + dy, z, Material.PALE_OAK_LOG);

            Leaves leaves = (Leaves) Material.PALE_OAK_LEAVES.createBlockData();
            leaves.setPersistent(true);
            for (int dy = h - 2; dy <= h + 1; dy++) {
                int r = dy >= h ? 1 : 2;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                        int lx = x + dx, ly = y + dy, lz = z + dz;
                        if (region.isInRegion(lx, ly, lz) && region.getType(lx, ly, lz).isAir()) {
                            region.setBlockData(lx, ly, lz, leaves);
                        }
                    }
                }
            }
        }

        private void chest(LimitedRegion region, Random random, int x, int y, int z) {
            if (!region.isInRegion(x, y, z)) return;
            try {
                Directional data = (Directional) Material.CHEST.createBlockData();
                BlockFace[] faces = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
                data.setFacing(faces[random.nextInt(4)]);
                region.setBlockData(x, y, z, data);
                BlockState state = region.getBlockState(x, y, z);
                if (state instanceof Lootable lootable) {
                    lootable.setLootTable(LootTables.ANCIENT_CITY.getLootTable());
                    state.update(true, false);
                }
            } catch (RuntimeException ignored) {
                // never let a decoration failure break chunk generation
            }
        }
    }
}
