package dev.sift.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/** /sift rift | tp | give | soul | buildframe | reload */
public final class SiftCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("rift", "tp", "give", "soul", "buildframe", "reload");
    private static final List<String> ITEMS = List.of(
            "siftite_ingot", "siftite_template", "charoite", "bottled_ichor", "soul_tome_1", "soul_tome_2");

    private final SiftPlugin plugin;

    public SiftCommand(SiftPlugin plugin) {
        this.plugin = plugin;
    }

    private static void msg(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            msg(sender, "Usage: /sift <" + String.join("|", SUBS) + ">", NamedTextColor.GRAY);
            return true;
        }
        if (!sender.hasPermission("sift.admin")) {
            msg(sender, "You do not have permission.", NamedTextColor.RED);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "rift" -> rift(sender);
            case "tp" -> tp(sender);
            case "give" -> give(sender, args);
            case "soul" -> soul(sender, args);
            case "buildframe" -> buildFrame(sender);
            case "reload" -> {
                plugin.reload();
                msg(sender, "Config reloaded (world-generation settings need a restart).", NamedTextColor.GREEN);
            }
            default -> msg(sender, "Unknown subcommand.", NamedTextColor.RED);
        }
        return true;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) return p;
        msg(sender, "This subcommand must be run by a player.", NamedTextColor.RED);
        return null;
    }

    private void rift(CommandSender sender) {
        Player p = player(sender);
        if (p == null) return;
        if (plugin.siftWorld().counterpart(p.getWorld()) == null) {
            msg(p, "Rifts only open in the Overworld and The Sift.", NamedTextColor.RED);
            return;
        }
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        Location ahead = p.getLocation().add(dir.normalize().multiply(3));
        Location origin = RiftManager.surface(p.getWorld(), ahead.getBlockX(), ahead.getBlockZ());
        if (origin == null) origin = p.getLocation();

        msg(p, "Tearing open a rift...", NamedTextColor.LIGHT_PURPLE);
        plugin.rifts().summonAt(origin).thenAccept(ok -> {
            if (!ok) msg(p, "Could not find safe ground on the other side. Try again.", NamedTextColor.RED);
        });
    }

    private void tp(CommandSender sender) {
        Player p = player(sender);
        if (p == null) return;
        World w = plugin.siftWorld().get();
        w.getChunkAtAsync(0, 0).thenAccept(c -> p.teleportAsync(plugin.siftWorld().spawn()));
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /sift give <" + String.join("|", ITEMS) + "> [amount] [player]", NamedTextColor.GRAY);
            return;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException ex) {
                msg(sender, "Amount must be a number.", NamedTextColor.RED);
                return;
            }
        }
        Player target = args.length >= 4 ? Bukkit.getPlayerExact(args[3]) : (sender instanceof Player p ? p : null);
        if (target == null) {
            msg(sender, "Player not found.", NamedTextColor.RED);
            return;
        }
        SiftItems items = plugin.items();
        ItemStack stack = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "siftite_ingot" -> items.siftiteIngot();
            case "siftite_template" -> items.upgradeTemplate();
            case "charoite" -> items.charoite();
            case "bottled_ichor" -> items.bottledIchor();
            case "soul_tome_1" -> items.soulTome(1);
            case "soul_tome_2" -> items.soulTome(2);
            default -> null;
        };
        if (stack == null) {
            msg(sender, "Unknown item.", NamedTextColor.RED);
            return;
        }
        stack.setAmount(amount);
        target.getInventory().addItem(stack).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        msg(sender, "Gave " + amount + "x " + args[1] + " to " + target.getName() + ".", NamedTextColor.GREEN);
    }

    private void soul(CommandSender sender, String[] args) {
        Player p = player(sender);
        if (p == null) return;
        if (args.length < 2) {
            msg(p, "Usage: /sift soul <0|1|2> (applies to the chestplate in your main hand)", NamedTextColor.GRAY);
            return;
        }
        int level;
        try {
            level = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            msg(p, "Level must be 0, 1 or 2.", NamedTextColor.RED);
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (level < 0 || level > 2 || !hand.getType().name().endsWith("_CHESTPLATE")) {
            msg(p, "Hold a chestplate and use a level from 0 to 2.", NamedTextColor.RED);
            return;
        }
        p.getInventory().setItemInMainHand(plugin.items().applySoul(hand, level));
        msg(p, "Soul Absorption set to " + level + ".", NamedTextColor.GREEN);
    }

    /** Builds an (inactive) frame in front of the player for testing "Wake the Singer". */
    private void buildFrame(CommandSender sender) {
        Player p = player(sender);
        if (p == null) return;
        if (plugin.siftWorld().counterpart(p.getWorld()) == null) {
            msg(p, "Frames only work in the Overworld and The Sift.", NamedTextColor.RED);
            return;
        }
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        boolean xAxis = Math.abs(dir.getZ()) >= Math.abs(dir.getX()); // frame faces the player
        Location at = p.getLocation().add(dir.clone().multiply(4));
        int ix = at.getBlockX() - (xAxis ? 1 : 0);
        int iz = at.getBlockZ() - (xAxis ? 0 : 1);
        int g = p.getLocation().getBlockY() - 1;

        plugin.portals().buildFrame(p.getWorld(), ix, g, iz, xAxis, false);
        msg(p, "Frame built. Play 3+ note blocks as a chord nearby, then blow a goat horn.", NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("sift.admin")) return List.of();
        if (args.length == 1) return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return ITEMS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("soul")) return List.of("0", "1", "2");
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[3].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
