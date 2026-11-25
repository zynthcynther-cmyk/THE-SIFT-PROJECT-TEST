package dev.sift.plugin;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class SiftPlugin extends JavaPlugin {

    private Keys keys;
    private SiftItems items;
    private SiftWorld siftWorld;
    private PortalManager portals;
    private RiftManager rifts;
    private IchorManager ichor;
    private DeathRecovery recovery;
    private SiftMobs mobs;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        keys = new Keys(this);
        items = new SiftItems(this);
        items.registerRecipes();

        siftWorld = new SiftWorld(this);
        if (siftWorld.create() == null) {
            getLogger().severe("Could not create the Sift world - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        portals = new PortalManager(this);
        portals.load();
        portals.start();

        rifts = new RiftManager(this);
        rifts.start();

        ichor = new IchorManager(this);
        ichor.start();

        recovery = new DeathRecovery(this);
        recovery.load();

        mobs = new SiftMobs(this);
        mobs.start();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PortalListener(this), this);
        pm.registerEvents(ichor, this);
        pm.registerEvents(new GearListener(this), this);
        pm.registerEvents(new CharoiteListener(this), this);
        pm.registerEvents(recovery, this);
        pm.registerEvents(new LootListener(this), this);

        SiftCommand command = new SiftCommand(this);
        PluginCommand pc = getCommand("sift");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }
        getLogger().info("The Sift is awake.");
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        if (portals != null) portals.save();
        if (rifts != null) rifts.shutdown();
        if (recovery != null) recovery.save();
        if (items != null) items.unregisterRecipes();
    }

    /** Re-reads config.yml and restarts the config-driven tasks. */
    public void reload() {
        reloadConfig();
        ichor.reloadSettings();
    }

    public Keys keys() { return keys; }
    public SiftItems items() { return items; }
    public SiftWorld siftWorld() { return siftWorld; }
    public PortalManager portals() { return portals; }
    public RiftManager rifts() { return rifts; }
    public IchorManager ichor() { return ichor; }
    public DeathRecovery recovery() { return recovery; }
}
