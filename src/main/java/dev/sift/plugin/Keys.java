package dev.sift.plugin;

import org.bukkit.NamespacedKey;

/** All PersistentDataContainer keys used by the plugin. */
public final class Keys {
    public final NamespacedKey itemId;
    public final NamespacedKey siftiteGear;
    public final NamespacedKey soulAbsorption;
    public final NamespacedKey soulTome;

    public Keys(SiftPlugin plugin) {
        this.itemId = new NamespacedKey(plugin, "item_id");
        this.siftiteGear = new NamespacedKey(plugin, "siftite_gear");
        this.soulAbsorption = new NamespacedKey(plugin, "soul_absorption");
        this.soulTome = new NamespacedKey(plugin, "soul_tome");
    }
}
