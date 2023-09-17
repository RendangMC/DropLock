package org.clytage.droplock;

import me.minebuilders.clearlag.events.EntityRemoveEvent;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ClearLagHandler implements Listener {
    private final DropLock plugin;

    public ClearLagHandler(DropLock pl) {
        this.plugin = pl;
    }

    @EventHandler
    public void onClearLagRemoveEntity(EntityRemoveEvent event) {
        if (!this.plugin.config.getBoolean("record_clearlag_removal", false)) return;

        for (Entity ent : event.getEntityList()) {
            if (!(ent instanceof Item)) continue;

            this.plugin.db.addDropRem((Item) ent, "$CLEARLAG");
        }
    }
}
