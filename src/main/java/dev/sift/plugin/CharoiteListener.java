package dev.sift.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.EnchantingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Map;

/**
 * Charoite is a Lapis-based item. Using it in the Enchanting Table's lapis slot lifts each eligible
 * enchantment by one level, above the vanilla cap, for extra XP levels.
 */
public final class CharoiteListener implements Listener {

    private final SiftPlugin plugin;

    public CharoiteListener(SiftPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!(event.getInventory() instanceof EnchantingInventory table)) return;
        ItemStack lapis = table.getSecondary();
        if (!plugin.items().is(lapis, SiftItems.CHAROITE)) return;

        Player player = event.getEnchanter();
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        int extra = plugin.getConfig().getInt("charoite.extra-level-cost", 5);
        int over = plugin.getConfig().getInt("charoite.max-over-cap", 1);

        if (!creative && player.getLevel() < event.getExpLevelCost() + extra) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Charoite needs " + extra + " more experience levels.", NamedTextColor.RED));
            return;
        }

        Map<Enchantment, Integer> toAdd = event.getEnchantsToAdd();
        boolean boosted = false;
        for (Map.Entry<Enchantment, Integer> entry : new ArrayList<>(toAdd.entrySet())) {
            Enchantment ench = entry.getKey();
            int level = entry.getValue();
            if (ench.getMaxLevel() <= 1) continue;                                     // Mending, Silk Touch...
            if (ench.equals(Enchantment.BINDING_CURSE) || ench.equals(Enchantment.VANISHING_CURSE)) continue;
            int boostedLevel = Math.min(level + 1, ench.getMaxLevel() + over);
            if (boostedLevel > level) {
                toAdd.put(ench, boostedLevel);
                boosted = true;
            }
        }

        if (boosted) {
            player.sendActionBar(Component.text("The Charoite resonates - your enchantments surge past their limits.",
                    NamedTextColor.LIGHT_PURPLE));
            if (!creative && extra > 0) {
                // Vanilla deducts its own cost after this event; take our surcharge on the next tick.
                Bukkit.getScheduler().runTask(plugin, () -> player.setLevel(Math.max(0, player.getLevel() - extra)));
            }
        }
    }
}
