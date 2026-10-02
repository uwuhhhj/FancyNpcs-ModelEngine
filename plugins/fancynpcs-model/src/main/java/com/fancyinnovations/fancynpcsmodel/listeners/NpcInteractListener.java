package com.fancyinnovations.fancynpcsmodel.listeners;

import com.fancyinnovations.fancynpcsmodel.fancynpcshook.CustomModelAttribute;
import com.fancyinnovations.fancynpcsmodel.providers.ModelProvider;
import com.fancyinnovations.fancynpcsmodel.providers.ModelProviderRegistry;
import com.fancyinnovations.fancynpcsmodel.providers.modelengine.ModelEngineProvider;
import de.oliver.fancynpcs.api.events.NpcPreInteractEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class NpcInteractListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onNpcInteract(NpcPreInteractEvent event) {
        if (!CustomModelAttribute.hasAttribute(event.getNpc())) {
            return;
        }

        ModelProvider provider = ModelProviderRegistry.getActiveProvider(event.getNpc());
        if (provider instanceof ModelEngineProvider modelEngine) {
            // Both the packet NPC and ModelEngine hitbox enter this event. Claim
            // once here so permissions, visibility, reach and duplicate clicks
            // are checked identically before FancyNpcs executes its actions.
            if (!modelEngine.claimInteraction(event.getNpc(), event.getPlayer(), event.getInteractionType()))
                event.setCancelled(true);
        } else if (provider != null && provider.shouldCancelNativeInteraction()) {
            event.setCancelled(true);
        }
    }

}
