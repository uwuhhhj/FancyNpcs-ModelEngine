package com.fancyinnovations.fancynpcsmodel.listeners;

import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.events.NpcRemoveEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class NpcRemoveListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNpcRemove(NpcRemoveEvent event) {
        Npc npc = event.getNpc();
        // The event is cancellable and fires before deletion. Confirm the
        // manager no longer owns this object before cancelling pending renders.
        Bukkit.getScheduler().runTask(FancyNpcsModelPlugin.get(), () -> {
            FancyNpcsPlugin fancyNpcs = FancyNpcsPlugin.get();
            if (fancyNpcs != null && fancyNpcs.getNpcManager().getNpcById(npc.getData().getId()) != npc)
                CustomModelAttribute.removeModels(npc);
        });
    }

}
