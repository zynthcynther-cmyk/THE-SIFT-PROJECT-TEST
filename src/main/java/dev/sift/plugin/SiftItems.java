package dev.sift.plugin;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Factory + helpers for every custom item. Items are identified by a PersistentDataContainer
 * tag; looks are set with Paper's Data Component API. Custom-model-data strings are used so
 * an optional resource pack can restyle them, while vanilla clients simply see the base item.
 */
public final class SiftItems {

    public static final String INGOT = "siftite_ingot";
    public static final String TEMPLATE = "siftite_template";
    public static final String CHAROITE = "charoite";
    public static final String BOTTLED_ICHOR = "bottled_ichor";

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Set<String> UPGRADABLE_SUFFIXES = Set.of(
            "SWORD", "AXE", "PICKAXE", "SHOVEL", "HOE", "SPEAR",
            "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");

    private final SiftPlugin plugin;
    private final Keys keys;
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    public SiftItems(SiftPlugin plugin) {
        this.plugin = plugin;
        this.keys = plugin.keys();
    }

    // ------------------------------------------------------------ text helpers

    private static Component mm(String s) {
        return MM.deserialize("<!italic>" + s);
    }

    private ItemStack create(Material material, String id, String name, List<String> lore, String model, boolean glint) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(keys.itemId, PersistentDataType.STRING, id));
        decorate(item, name, lore, model, glint);
        return item;
    }

    private void decorate(ItemStack item, String name, List<String> lore, String model, boolean glint) {
        item.setData(DataComponentTypes.CUSTOM_NAME, mm(name));
        ItemLore.Builder lb = ItemLore.lore();
        for (String line : lore) lb.addLine(mm(line));
        item.setData(DataComponentTypes.LORE, lb.build());
        if (model != null) {
            item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(model).build());
        }
        if (glint) item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
    }

    // ------------------------------------------------------------ identification

    public String idOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keys.itemId, PersistentDataType.STRING);
    }

    public boolean is(ItemStack item, String id) {
        return id.equals(idOf(item));
    }

    public boolean isCustom(ItemStack item) {
        return idOf(item) != null;
    }

    public boolean isSiftiteGear(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(keys.siftiteGear, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------ resource items

    public ItemStack siftiteIngot() {
        return create(Material.ECHO_SHARD, INGOT, "<gradient:#7b2ff7:#00e5ff>Siftite Ingot</gradient>",
                List.of("<gray>Condensed from the silence between worlds.",
                        "<dark_purple>Combine with a Siftite Upgrade Template",
                        "<dark_purple>and Netherite gear at a Smithing Table."),
                "siftite_ingot", true);
    }

    public ItemStack upgradeTemplate() {
        return create(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, TEMPLATE,
                "<gradient:#7b2ff7:#00e5ff>Siftite Upgrade</gradient>",
                List.of("<gray>Applies to: <blue>Netherite equipment",
                        "<gray>Ingredients: <blue>Siftite Ingot"),
                "siftite_template", false);
    }

    public ItemStack charoite() {
        return create(Material.LAPIS_LAZULI, CHAROITE, "<light_purple>Charoite",
                List.of("<gray>Use in place of Lapis Lazuli in an",
                        "<gray>Enchanting Table to push enchantments",
                        "<gray>one level past their natural limit.",
                        "<dark_purple>Costs extra experience levels."),
                "charoite", true);
    }

    public ItemStack bottledIchor() {
        ItemStack item = new ItemStack(Material.POTION);
        item.editMeta(PotionMeta.class, meta -> {
            meta.setColor(Color.fromRGB(0x8A2BE2));
            meta.getPersistentDataContainer().set(keys.itemId, PersistentDataType.STRING, BOTTLED_ICHOR);
        });
        decorate(item, "<dark_purple>Bottled Ichor",
                List.of("<gray>Thick, glowing, and hungry.",
                        "<red>Harmful to drink...",
                        "<green>...unless you wear Soul Absorption II."),
                "bottled_ichor", true);
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY,
                TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.POTION_CONTENTS).build());
        return item;
    }

    // ------------------------------------------------------------ Soul Absorption

    public static String roman(int level) {
        return level == 1 ? "I" : level == 2 ? "II" : String.valueOf(level);
    }

    public ItemStack soulTome(int level) {
        ItemStack item = new ItemStack(Material.BOOK);
        item.editMeta(meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(keys.itemId, PersistentDataType.STRING, "soul_tome");
            pdc.set(keys.soulTome, PersistentDataType.INTEGER, level);
        });
        decorate(item, "<dark_aqua>Tome of Soul Absorption " + roman(level),
                List.of("<gray>Combine with a chestplate in an Anvil.",
                        level == 1 ? "<gray>Grants immunity to Ichor." : "<gray>Turns Ichor into Regeneration II."),
                "soul_tome_" + level, true);
        return item;
    }

    public int tomeLevel(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return 0;
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(keys.soulTome, PersistentDataType.INTEGER, 0);
    }

    public int soulLevel(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return 0;
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(keys.soulAbsorption, PersistentDataType.INTEGER, 0);
    }

    /** Returns a copy of {@code item} with Soul Absorption {@code level} (0 removes it). */
    public ItemStack applySoul(ItemStack item, int level) {
        ItemStack out = item.clone();
        out.editMeta(meta -> {
            if (level <= 0) meta.getPersistentDataContainer().remove(keys.soulAbsorption);
            else meta.getPersistentDataContainer().set(keys.soulAbsorption, PersistentDataType.INTEGER, level);
        });

        List<Component> lines = new ArrayList<>();
        ItemLore existing = out.getData(DataComponentTypes.LORE);
        if (existing != null) {
            for (Component c : existing.lines()) {
                if (!PlainTextComponentSerializer.plainText().serialize(c).startsWith("Soul Absorption")) lines.add(c);
            }
        }
        if (level > 0) lines.add(0, mm("<dark_aqua>Soul Absorption " + roman(level)));
        ItemLore.Builder lb = ItemLore.lore();
        lines.forEach(lb::addLine);
        out.setData(DataComponentTypes.LORE, lb.build());
        return out;
    }

    // ------------------------------------------------------------ Siftite gear

    public static EquipmentSlotGroup slotGroup(Material m) {
        String n = m.name();
        if (!n.startsWith("NETHERITE_")) return null;
        String suffix = n.substring("NETHERITE_".length());
        if (!UPGRADABLE_SUFFIXES.contains(suffix)) return null;
        return switch (suffix) {
            case "HELMET" -> EquipmentSlotGroup.HEAD;
            case "CHESTPLATE" -> EquipmentSlotGroup.CHEST;
            case "LEGGINGS" -> EquipmentSlotGroup.LEGS;
            case "BOOTS" -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.MAINHAND;
        };
    }

    public boolean isUpgradable(ItemStack item) {
        return item != null && slotGroup(item.getType()) != null && !isSiftiteGear(item);
    }

    private void addBonus(ItemAttributeModifiers.Builder b, Attribute attribute, double amount, EquipmentSlotGroup group) {
        if (amount == 0) return;
        // One bonus per attribute per item, so the attribute name alone makes the key unique.
        NamespacedKey key = new NamespacedKey(plugin, "siftite_" + attribute.getKey().getKey());
        b.addModifier(attribute,
                new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER, group),
                group);
    }

    /**
     * Builds Siftite gear from a Netherite item: keeps enchantments/damage/name, adds bonus
     * attributes, extra durability, a custom model data string and a PDC marker.
     * Returns null if the item cannot be upgraded.
     */
    public ItemStack createSiftiteGear(ItemStack base) {
        if (!isUpgradable(base)) return null;
        EquipmentSlotGroup group = slotGroup(base.getType());
        FileConfiguration cfg = plugin.getConfig();

        ItemStack out = base.clone();
        out.setAmount(1);
        out.editMeta(meta -> meta.getPersistentDataContainer().set(keys.siftiteGear, PersistentDataType.BYTE, (byte) 1));

        // --- attributes: keep the item's current/default modifiers, then add our bonuses
        ItemAttributeModifiers existing = out.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (existing == null) {
            ItemType type = out.getType().asItemType();
            if (type != null) existing = type.getDefaultData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        }
        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.itemAttributes();
        if (existing != null) {
            for (ItemAttributeModifiers.Entry e : existing.modifiers()) {
                b.addModifier(e.attribute(), e.modifier(), e.getGroup());
            }
        }
        if (group == EquipmentSlotGroup.MAINHAND) {
            addBonus(b, Attribute.ATTACK_DAMAGE, cfg.getDouble("siftite.attack-damage", 1.0), group);
            addBonus(b, Attribute.ATTACK_SPEED, cfg.getDouble("siftite.attack-speed", 0.2), group);
        } else {
            addBonus(b, Attribute.ARMOR_TOUGHNESS, cfg.getDouble("siftite.armor-toughness", 1.0), group);
            addBonus(b, Attribute.KNOCKBACK_RESISTANCE, cfg.getDouble("siftite.knockback-resistance", 0.05), group);
        }
        out.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, b.build());

        // --- durability
        int extra = cfg.getInt("siftite.extra-durability", 250);
        Integer maxDamage = out.getData(DataComponentTypes.MAX_DAMAGE);
        if (maxDamage != null && extra > 0) out.setData(DataComponentTypes.MAX_DAMAGE, maxDamage + extra);

        // --- looks
        String suffix = out.getType().name().substring("NETHERITE_".length());
        if (out.getData(DataComponentTypes.CUSTOM_NAME) == null) {
            out.setData(DataComponentTypes.CUSTOM_NAME, mm("<gradient:#7b2ff7:#00e5ff>Siftite "
                    + titleCase(suffix) + "</gradient>"));
        }
        out.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addString("siftite_" + suffix.toLowerCase(Locale.ROOT)).build());

        List<Component> lines = new ArrayList<>();
        ItemLore lore = out.getData(DataComponentTypes.LORE);
        if (lore != null) lines.addAll(lore.lines());
        lines.add(mm("<dark_purple>Forged in The Sift"));
        ItemLore.Builder lb = ItemLore.lore();
        lines.forEach(lb::addLine);
        out.setData(DataComponentTypes.LORE, lb.build());
        return out;
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : s.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ recipes

    /**
     * One real smithing recipe per Netherite item type so the vanilla Smithing Table accepts the
     * custom template/ingot. The result slot is replaced with the real upgraded copy by
     * {@link GearListener} (PrepareSmithingEvent).
     */
    public void registerRecipes() {
        unregisterRecipes();
        for (Material m : Material.values()) {
            if (m.isLegacy() || slotGroup(m) == null) continue;
            NamespacedKey key = new NamespacedKey(plugin, "siftite_upgrade_" + m.name().toLowerCase(Locale.ROOT));
            SmithingTransformRecipe recipe = new SmithingTransformRecipe(
                    key,
                    new ItemStack(m),
                    new RecipeChoice.ExactChoice(upgradeTemplate()),
                    new RecipeChoice.MaterialChoice(m),
                    new RecipeChoice.ExactChoice(siftiteIngot()));
            if (Bukkit.addRecipe(recipe)) recipeKeys.add(key);
        }
    }

    public void unregisterRecipes() {
        for (NamespacedKey key : recipeKeys) Bukkit.removeRecipe(key);
        recipeKeys.clear();
    }
}
