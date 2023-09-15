package org.clytage.droplock;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.ChatColor;

import java.util.logging.Level;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.io.File;

public class MessagesManager {
    private DropLock plugin;
    private Map<String, String> messages = new HashMap<>();
    private Map<String, String> defaults = new HashMap<>();

    public MessagesManager(DropLock pl) {
        this.plugin = pl;

        InputStream defStream = this.plugin.getResource("dropmessage.yml");
        YamlConfiguration conf = new YamlConfiguration();
        try {
            conf.loadFromString(new String(defStream.readAllBytes()));

            for (String key : conf.getKeys(false)) {
                this.defaults.put(key, conf.getString(key));
            }
        } catch (IOException | InvalidConfigurationException err) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to load default plugin messages (Error: " + err.getMessage() +"). Turning off plugin...");
            this.plugin.getPluginLoader().disablePlugin(this.plugin);
        }
    }

    public void loadMessages() {
        this.messages.clear();

        File messageFile = new File(this.plugin.getDataFolder(), "dropmessage.yml");
        if (!messageFile.exists())
            this.plugin.saveResource("dropmessage.yml", false);
        YamlConfiguration conf = YamlConfiguration.loadConfiguration(messageFile);

        for (String key : conf.getKeys(false)) {
            this.messages.put(key, conf.getString(key));
        }
    }

    public String getMessage(String key) {
        String msg = null;

        if (messages.containsKey(key)) {
            msg = colorize(messages.get(key));
        } else if (defaults.containsKey(key)) {
            msg = colorize(defaults.get(key));
        }

        return msg;
    }

    private String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
