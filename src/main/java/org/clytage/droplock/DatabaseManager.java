package org.clytage.droplock;

import com.loohp.interactivechat.libs.net.kyori.adventure.text.Component;
import com.loohp.interactivechat.api.InteractiveChatAPI;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;

import java.text.SimpleDateFormat;
import java.text.DateFormat;
import java.nio.file.Paths;
import java.sql.*;
import java.util.UUID;
public class DatabaseManager {
    private DropLock plugin;
    private Connection conn;

    public DatabaseManager(DropLock pl) { this.plugin = pl; }

    public void prepareDb() {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + Paths.get(this.plugin.getDataFolder().getPath(), "data.db"));

            Statement stmt = conn.createStatement();
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `userlock` (" +
                            "`uuid` VARCHAR NOT NULL," +
                            "`invlock` INT NULL," +
                            "PRIMARY KEY (`uuid`)" +
                            ")"
            );

            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `droprem` (" +
                            "`uuid` VARCHAR," +
                            "`id`INTEGER PRIMARY KEY," +
                            "`item` VARCHAR NOT NULL," +
                            "`timestamp` INT NOT NULL," +
                            "`pickup` VARCHAR NOT NULL" +
                            ")"
            );

            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
            this.plugin.getServer().shutdown();
        }
    }

    public void deleteOldDropHistory() {
        try {
            Statement stmt = conn.createStatement();

            stmt.executeUpdate("DELETE FROM `drops` WHERE (" + System.currentTimeMillis() + " - `timestamp`) >= " + this.plugin.maxAlive);
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void dropRemHistory(CommandSender sender, Player player, String reason, int page) {
        try {
            Statement stmt = conn.createStatement();
            String sql = "SELECT `id`, `item`, `timestamp`, `pickup`, `uuid` FROM `droprem` " + (player == null && reason == null ? "" : "WHERE " + (player != null ? "`uuid` = \"" + player.getUniqueId() + "\"" : "") + (player != null && reason != null ? " AND " : " ") + (reason != null ? "`pickup` = \"" + reason + "\"" : "") + " ") +"ORDER BY `timestamp` DESC LIMIT 10 OFFSET " + (page - 1) * 10;
            ResultSet res = stmt.executeQuery(sql);

            if (!res.next()) {
                sender.sendMessage(this.plugin.prefix + ChatColor.RED + "Cannot find drop data at that page");
                return;
            }

            if (reason == null) sender.sendMessage(this.plugin.prefix + this.plugin.msg.getMessage("drop_rem_of_player").replace("{PLAYER}", player != null ? player.getName() : this.plugin.msg.getMessage("all_players")));

            DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
            do {
                YamlConfiguration conf = new YamlConfiguration();
                conf.loadFromString(res.getString("item"));
                ItemStack stack = conf.getItemStack("item");

                Component compo = Component.text("ID: " + res.getInt("id") + " - ").append(InteractiveChatAPI.createItemDisplayComponent((Player) sender, stack));
                Date date = new Date(res.getLong("timestamp"));
                compo = compo.append(Component.text(" - " + this.plugin.msg.getMessage("removal_at").replace("{TIMESTAMP}", formatter.format(date))));

                if (player == null) {
                    String uuid = res.getString("uuid");
                    if (uuid != null) {
                        Player thrower = this.plugin.getServer().getPlayer(UUID.fromString(uuid));
                        if (thrower != null) {
                            compo = compo.append(Component.text(" - " + this.plugin.msg.getMessage("thrown_by").replace("{PLAYER}", thrower.getName())));
                        }
                    }
                }

                String pickup = res.getString("pickup");
                if (pickup.equals("$CLEARLAG")) {
                    compo = compo.append(Component.text(" - " + this.plugin.msg.getMessage("del_clearlag")));
                } else if (pickup.equals("$DESPAWN")) {
                    compo = compo.append(Component.text(" - " + this.plugin.msg.getMessage("del_despawn")));
                } else if (pickup.equals("$VOID")) {
                    compo = compo.append(Component.text(" - " + this.plugin.msg.getMessage("del_void")));
                } else {
                    Player pick = this.plugin.getServer().getPlayer(UUID.fromString(pickup));
                    if (pick != null) {
                        compo = compo.append(Component.text(" - " + this.plugin.msg.getMessage("picked_up_by").replace("{PLAYER}", pick.getName())));
                    }
                }

                InteractiveChatAPI.sendMessage(sender, compo);
            } while (res.next());
        } catch (SQLException e) {
            e.printStackTrace();
            sender.sendMessage(this.plugin.prefix + ChatColor.RED + "Something occured while trying to get player drop history");
        } catch (InvalidConfigurationException e) {
            e.printStackTrace();
            sender.sendMessage(this.plugin.prefix + ChatColor.RED + "Something occured while trying to get player drop history");
        } catch (Exception e) {
            e.printStackTrace();
            sender.sendMessage(this.plugin.prefix + ChatColor.RED + "Something occured while trying to get player drop history");
        }
    }

    public boolean isInventoryLocked(Player player) {
        try {
            Statement stmt = conn.createStatement();
            ResultSet set = stmt.executeQuery("SELECT `invlock` FROM `userlock` WHERE `uuid` = \"" + player.getUniqueId().toString() + "\"");
            stmt.close();
            boolean isLock = this.plugin.inventoryLockedByDefault;

            if (set.next())
                isLock = set.getInt("invlock") == 1;

            return isLock;
        } catch (SQLException e) {
            return false;
        }
    }

    public void setLock(Player player, boolean lock) {
        try {
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO `userlock` (`uuid`, `invlock`) VALUES(?, ?) ON CONFLICT(`uuid`) DO UPDATE SET `invlock` = excluded.invlock");

            stmt.setString(1, player.getUniqueId().toString());
            stmt.setInt(2, lock ? 1 : 0);

            stmt.executeUpdate();
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void addDropRem(Item item, String val) {
        try {
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO `droprem` (`uuid`, `timestamp`, `pickup`, `item`) VALUES(?, ?, ?, ?)");

            stmt.setString(1, item.getThrower() != null ? item.getThrower().toString() : null);
            stmt.setLong(2, System.currentTimeMillis());
            stmt.setString(3, val);

            YamlConfiguration conf = new YamlConfiguration();
            conf.set("item", item.getItemStack());
            String itemStr = conf.saveToString();

            stmt.setString(4, itemStr);

            stmt.executeUpdate();
            stmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
