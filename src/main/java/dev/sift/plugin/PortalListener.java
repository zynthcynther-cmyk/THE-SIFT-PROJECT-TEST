package dev.sift.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Wake the Singer": play note blocks in harmony near a reinforced deepslate frame, then blow a goat horn.
 */
public final class PortalListener implements Listener {

    private record NoteRecord(UUID world, int x, int y, int z, int pitchClass, long time) {}

    private final SiftPlugin plugin;
    private final Deque<NoteRecord> notes = new ArrayDeque<>();
    private final Map<UUID, Long> hornCooldown = new HashMap<>();

    public PortalListener(SiftPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onNote(NotePlayEvent event) {
        long now = System.currentTimeMillis();
        long keep = plugin.getConfig().getLong("portal.note-window-seconds", 12) * 2000L;
        while (!notes.isEmpty() && now - notes.peekFirst().time() > keep) notes.pollFirst();

        var b = event.getBlock();
        notes.addLast(new NoteRecord(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ(),
                event.getNote().getId() % 12, now));
    }

    @EventHandler
    public void onHorn(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getItem() == null || event.getItem().getType() != Material.GOAT_HORN) return;

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        long cdMs = plugin.getConfig().getLong("portal.horn-cooldown-ticks", 40) * 50L;
        Long last = hornCooldown.get(player.getUniqueId());
        if (last != null && now - last < cdMs) return;
        hornCooldown.put(player.getUniqueId(), now);

        // Let the horn play normally, evaluate on the next tick.
        Bukkit.getScheduler().runTask(plugin, () -> attempt(player));
    }

    private void attempt(Player player) {
        if (!player.isOnline()) return;
        World world = player.getWorld();
        if (plugin.siftWorld().counterpart(world) == null) return; // only Overworld <-> Sift

        int hornRadius = plugin.getConfig().getInt("portal.horn-search-radius", 10);
        Optional<PortalManager.Frame> found = plugin.portals().findFrame(player.getLocation(), hornRadius);
        if (found.isEmpty()) return;
        PortalManager.Frame frame = found.get();

        if (plugin.portals().isActive(frame)) {
            player.sendActionBar(Component.text("The Singer is already awake.", NamedTextColor.GRAY));
            return;
        }

        Location center = frame.center(world);
        double radius = plugin.getConfig().getDouble("portal.note-search-radius", 8);
        long window = plugin.getConfig().getLong("portal.note-window-seconds", 12) * 1000L;
        long now = System.currentTimeMillis();

        Set<Long> blocks = new HashSet<>();
        Set<Integer> pitchClasses = new HashSet<>();
        for (NoteRecord n : notes) {
            if (!n.world().equals(world.getUID()) || now - n.time() > window) continue;
            double dx = n.x() + 0.5 - center.getX();
            double dy = n.y() + 0.5 - center.getY();
            double dz = n.z() + 0.5 - center.getZ();
            if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
            blocks.add(((long) n.x() & 0x3FFFFFFL) << 38 | ((long) n.z() & 0x3FFFFFFL) << 12 | ((long) n.y() & 0xFFFL));
            pitchClasses.add(n.pitchClass());
        }

        int needed = plugin.getConfig().getInt("portal.minimum-notes", 3);
        if (blocks.size() < needed) {
            player.sendActionBar(Component.text("The frame hums, but the song is too thin (" + blocks.size() + "/" + needed
                    + " note blocks played nearby).", NamedTextColor.DARK_PURPLE));
            return;
        }
        if (plugin.getConfig().getBoolean("portal.require-harmony", true) && !hasTriad(pitchClasses)) {
            player.sendActionBar(Component.text("The notes clash. The Singer will not wake to discord.", NamedTextColor.DARK_RED));
            world.playSound(center, org.bukkit.Sound.BLOCK_SCULK_SENSOR_CLICKING_STOP, 1.0f, 0.5f);
            return;
        }

        plugin.portals().activate(frame);
        player.sendActionBar(Component.text("You have woken the Singer.", NamedTextColor.LIGHT_PURPLE));
    }

    /** True when the pitch classes contain a major (0-4-7) or minor (0-3-7) triad on any root. */
    static boolean hasTriad(Set<Integer> pcs) {
        for (int root : pcs) {
            boolean fifth = pcs.contains((root + 7) % 12);
            if (fifth && (pcs.contains((root + 4) % 12) || pcs.contains((root + 3) % 12))) return true;
        }
        return false;
    }
}
