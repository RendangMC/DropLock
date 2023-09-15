package org.clytage.droplock;

import me.minebuilders.clearlag.events.EntityRemoveEvent;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.command.*;
import org.bukkit.ChatColor;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.io.File;

public class DropLock extends JavaPlugin implements CommandExecutor, Listener, TabCompleter {
    public final MessagesManager msg = new MessagesManager(this);

    private final DatabaseManager db = new DatabaseManager(this);

    private final Set<UUID> lockedPlayers = new HashSet<>();

    private FileConfiguration config;

    public boolean inventoryLockedByDefault = true;

    private boolean purge = true;

    private long interval = 15 * 60 * 100;

    public long maxAlive = 3 * 60 * 60 * 1000;

    private Thread purgeThread;

    public String prefix = ChatColor.DARK_GRAY + "[" + ChatColor.GREEN + "DropLock" + ChatColor.DARK_GRAY + "] ";

    private String pluginInfoMessage = ChatColor.GRAY + "DropLock plugin created by Clytage with " + ChatColor.RED + "♥";

    @Override
    public void onEnable() {
        this.msg.loadMessages();
        this.db.prepareDb();
        this.loadConfig();

        getCommand("dlreload").setExecutor(this);
        getCommand("lockdrop").setExecutor(this);
        getCommand("unlockdrop").setExecutor(this);
        getCommand("drop").setExecutor(this);

        PluginCommand drh = getCommand("dropremhistory");
        drh.setExecutor(this);
        drh.setTabCompleter(this);

        PluginCommand drhbr = getCommand("drhbr");
        drhbr.setExecutor(this);
        drhbr.setTabCompleter(this);

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> possible = new ArrayList<>();

        if (args.length > 1) return possible;

        String name = command.getName();
        if (name.equals("dropremhistory")) {
            if (sender.hasPermission("droplock.droprem.history.checkall")) possible.add("all");
            if (sender.hasPermission("droplock.droprem.history.checkplayer")) possible.addAll(getServer().getOnlinePlayers().stream().map(player -> player.getName()).toList());
        } else if (name.equals("drhbr")) {
            if (sender.hasPermission("droplock.droprem.history.checkclearlag")) possible.add("clearlag");
            if (sender.hasPermission("droplock.droprem.history.checkdespawn")) possible.add("despawn");
            if (sender.hasPermission("droplock.droprem.history.checkbyplayer")) possible.addAll(getServer().getOnlinePlayers().stream().map(player -> player.getName()).toList());
        }

        return possible.stream().filter(str -> str.startsWith(args.length == 1 ? args[0] : "")).toList();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (this.lockedPlayers.contains(event.getPlayer().getUniqueId()) || this.db.isInventoryLocked(event.getPlayer())) {
            this.lockedPlayers.add(event.getPlayer().getUniqueId());
            this.reloadInvMeta(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (this.lockedPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(this.prefix + this.msg.getMessage("inventory_locked"));
        }
    }

    @EventHandler
    public void onClearLagRemoveEntity(EntityRemoveEvent event) {
        if (!this.config.getBoolean("record_clearlag_removal", false)) return;

        for (Entity ent : event.getEntityList()) {
            if (!(ent instanceof Item)) continue;

            this.db.addDropRem((Item) ent, "$CLEARLAG");
        }
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent event) {
        if (!this.config.getBoolean("record_despawn_removal", false)) return;

        this.db.addDropRem(event.getEntity(), "$DESPAWN");
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!this.config.getBoolean("record_player_removal", false) || !(event.getEntity() instanceof Player)) return;

        this.db.addDropRem(event.getItem(), event.getEntity().getUniqueId().toString());
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (
                !this.config.getBoolean("record_void_removal", false) ||
                        !(event.getEntity() instanceof Item) ||
                        event.getCause() != EntityDamageEvent.DamageCause.VOID
        ) return;

        this.db.addDropRem((Item) event.getEntity(), "$VOID");
        event.getEntity().remove();
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("dropremhistory")) {
            handleDropHistoryCall(sender, args);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("drhbr")) {
            handleDRHBRCall(sender, args);
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        Player player = (Player)sender;

        if (cmd.getName().equalsIgnoreCase("lockdrop")) {
            if (this.lockedPlayers.contains(player.getUniqueId())) {
                player.sendMessage(this.prefix + this.msg.getMessage("already_locked"));
            } else {
                this.lockedPlayers.add(player.getUniqueId());
                this.db.setLock(player, true);

                player.sendMessage(this.prefix + this.msg.getMessage("lock_inventory"));
                reloadInvMeta(player);
            }
        } else if (cmd.getName().equalsIgnoreCase("unlockdrop")) {
            if (this.lockedPlayers.contains(player.getUniqueId())) {
                this.lockedPlayers.remove(player.getUniqueId());
                this.db.setLock(player, false);

                player.sendMessage(this.prefix + this.msg.getMessage("unlock_inventory"));
                reloadInvMeta(player);
            } else {
                player.sendMessage(this.prefix + this.msg.getMessage("not_locked"));
            }
        } else if (cmd.getName().equalsIgnoreCase("drop")) {
            player.sendMessage(this.prefix + this.pluginInfoMessage);
        } else if (cmd.getName().equalsIgnoreCase("dlreload")) {
            this.msg.loadMessages();
            this.loadConfig();

            player.sendMessage(this.prefix + this.msg.getMessage("reloaded"));
        }
        return true;
    }

    private void handleDRHBRCall(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(this.prefix + this.msg.getMessage("specify_reason"));
            return;
        }

        String reason = null;
        int page = 1;

        if (args[0].matches("clearlag|despawn")) {
            reason = args[0].equals("clearlag") ? "$CLEARLAG" : "$DESPAWN";
        } else {
            Player target = null;

            try {
                target = getServer().getPlayer(UUID.fromString(args[0]));
            } catch (IllegalArgumentException e) {
                target = getServer().getPlayer(args[0]);
            }

            if (target == null) {
                sender.sendMessage(this.prefix + this.msg.getMessage("cannot_find_player"));
                return;
            }

            reason = target.getUniqueId().toString();
        }

        if (args.length >= 2) {
            try {
                int num = Integer.parseInt(args[1]);
                if (num > 0) page = num;
            } finally {}
        }

        this.db.dropRemHistory(sender, null, reason, page);
    }

    private void handleDropHistoryCall(CommandSender sender, String[] args) {
        if (!(sender instanceof Player) && args.length < 1) {
            sender.sendMessage(this.prefix + this.msg.getMessage("specify_player_check"));
            return;
        }

        if (args.length >= 1 && args[0].equals("all")) {
            if (!sender.hasPermission("droplock.droprem.history.checkall")) {
                sender.sendMessage(this.prefix + this.msg.getMessage("no_perm_check_all"));
                return;
            }

            int page = 1;

            if (args.length >= 2) {
                try {
                    int num = Integer.parseInt(args[1]);
                    if (num > 0) page = num;
                } finally {}
            }

            this.db.dropRemHistory(sender, null, null, page);
            return;
        }

        Player target = null;
        int page = 1;

        if (args.length >= 1) {
            if (args[0].matches("^\\d+$")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(this.prefix + this.msg.getMessage("specify_player_check"));
                    return;
                }

                target = (Player) sender;
                page = Integer.parseInt(args[0]);
            } else if (!sender.hasPermission("droplock.droprem.history.checkother")) {
                sender.sendMessage(this.prefix + this.msg.getMessage("no_perm_check_of_player"));
                return;
            } else {
                target = getServer().getPlayer(args[0]);
                page = args.length > 1 && args[1].matches("^\\d+$") ? Integer.parseInt(args[1]) : page;
            }
        } else {
            target = (Player) sender;
        }

        if (target == null) {
            sender.sendMessage(this.prefix + this.msg.getMessage("cannot_find_player"));
            return;
        }

        this.db.dropRemHistory(sender, target, null, page);
    }

    private void loadConfig() {
        saveDefaultConfig();

        this.inventoryLockedByDefault = true;
        this.purge = true;
        this.interval = 15 * 60 * 1000;
        this.maxAlive = 3 * 60 * 60 * 1000;
        this.prefix = ChatColor.DARK_GRAY + "[" + ChatColor.GREEN + "DropLock" + ChatColor.DARK_GRAY + "] ";
        this.pluginInfoMessage = ChatColor.GRAY + "DropLock plugin created by Clytage with " + ChatColor.RED + "♥";
        if (this.purgeThread != null) {
            this.purgeThread.interrupt();
            this.purgeThread = null;
        }

        this.config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));

        if (config.contains("inventory_locked_by_default"))
            this.inventoryLockedByDefault = config.getBoolean("inventory_locked_by_default");

        if (config.contains("prefix"))
            this.prefix = colorize(config.getString("prefix"));

        if (config.contains("plugin_info_message"))
            this.pluginInfoMessage = colorize(config.getString("plugin_info_message"));

        if (config.contains("drop_removal_history_purge")) {
            ConfigurationSection section = config.getConfigurationSection("drop_removal_history_purge");

            if (section.contains("enabled"))
                this.purge = section.getBoolean("enabled");
            if (section.contains("minutes_interval")) {
                int num = section.getInt("minutes_interval");
                if (num >= 1) this.interval = (long) num * 60 * 1000;
            }
            if (section.contains("minutes_alive")) {
                int num = section.getInt("minutes_alive");
                if (num >= 1) this.maxAlive = (long) num * 60 * 1000;
            }
        }

        if (this.purge) {
            DatabaseManager db = this.db;
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        db.deleteOldDropHistory();

                        try {
                            Thread.sleep(interval);
                        } catch (InterruptedException e) {}
                    }
                }
            };

            this.purgeThread = new Thread(runnable);
            this.purgeThread.start();
        } else if (this.purgeThread != null) {
            this.purgeThread.interrupt();
            this.purgeThread = null;
        }
    }

    private void reloadInvMeta(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack itemStack = player.getInventory().getItem(slot);
            if (itemStack != null) {
                ItemMeta meta = itemStack.getItemMeta();
                if (meta != null)
                    itemStack.setItemMeta(meta);
            }
        }
    }

    private String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
