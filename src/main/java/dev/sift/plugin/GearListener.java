package dev.sift.plugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;

/**
 * Smithing Table (Siftite upgrade), Anvil (Soul Absorption tomes) and crafting guards that stop the
 * custom items from being used as their vanilla base items.
 */
public final class GearListener implements Listener {

    private final SiftPlugin plugin;
    private final SiftItems items;

    public GearListener(SiftPlugin plugin) {
        this.plugin = plugin;
        this.items = plugin.items();
    }

    @EventHandler
    public void onSmithing(PrepareSmithingEvent event) {
        SmithingInventory inv = event.getInventory();
        ItemStack template = inv.getInputTemplate();
        ItemStack base = inv.getInputEquipment();
        ItemStack addition = inv.getInputMineral();

        boolean hasTemplate = items.is(template, SiftItems.TEMPLATE);
        boolean hasIngot = items.is(addition, SiftItems.INGOT);
        if (!hasTemplate && !hasIngot) return;

        // Our template/ingot are vanilla item types underneath; make sure they never fall into a vanilla recipe.
        if (hasTemplate && hasIngot && base != null) {
            event.setResult(items.createSiftiteGear(base)); // null (cleared) if base is not eligible
        } else {
            event.setResult(null);
        }
    }

    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getFirstItem();
        ItemStack right = inv.getSecondItem();
        if (left == null || right == null) return;

        int tomeLevel = items.tomeLevel(right);
        if (tomeLevel <= 0) return;

        if (!left.getType().name().endsWith("_CHESTPLATE") || items.soulLevel(left) >= tomeLevel) {
            event.setResult(null);
            return;
        }
        event.setResult(items.applySoul(left, tomeLevel));
        int cost = plugin.getConfig().getInt(tomeLevel >= 2 ? "soul-absorption.anvil-cost-2" : "soul-absorption.anvil-cost-1",
                tomeLevel >= 2 ? 20 : 10);
        event.getView().setRepairCost(cost);
    }

    /** Custom items must not work as their base items (echo shard, lapis, book...) in crafting grids. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack stack : event.getInventory().getMatrix()) {
            if (stack != null && items.isCustom(stack)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }
}
