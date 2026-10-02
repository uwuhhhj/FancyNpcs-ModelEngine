package com.fancyinnovations.fancynpcsmodel.providers.modelengine;

import com.fancyinnovations.fancynpcsmodel.main.FancyNpcsModelPlugin;
import com.ticxo.modelengine.api.events.BaseEntityInteractEvent;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import de.oliver.fancynpcs.api.events.NpcPreInteractEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.IllegalPluginAccessException;

/** ModelEngine clicks retain FancyNpcs' visibility and cancellable pre-interaction hook. */
public final class ModelEngineInteractListener implements Listener {
    private final ModelEngineProvider provider;

    public ModelEngineInteractListener(ModelEngineProvider provider) { this.provider = provider; }

    @EventHandler public void onBaseEntityInteract(BaseEntityInteractEvent event) {
        Npc npc = provider.getNpcForBase(event.getBaseEntity());
        if (npc == null || (event.getAction() != BaseEntityInteractEvent.Action.ATTACK
                && event.getSlot() != EquipmentSlot.HAND)) return;
        ActionTrigger trigger = event.getAction() == BaseEntityInteractEvent.Action.ATTACK
                ? ActionTrigger.LEFT_CLICK : ActionTrigger.RIGHT_CLICK;
        Player player = event.getPlayer();
        Runnable interact = () -> {
            // Discard queued clicks if their model was removed/replaced meanwhile.
            if (provider.getNpcForBase(event.getBaseEntity()) != npc || !provider.mayInteract(npc, player)) return;
            // The addon's NpcPreInteract listener claims one click for both native
            // and ME hitboxes. Do not claim here a second time.
            if (!new NpcPreInteractEvent(npc, player, trigger).callEvent()) return;
            if (provider.getNpcForBase(event.getBaseEntity()) == npc && provider.mayInteract(npc, player)) {
                npc.interact(player, trigger);
            }
        };
        if (Bukkit.isPrimaryThread()) interact.run();
        else if (FancyNpcsModelPlugin.get().isEnabled()) {
            try { Bukkit.getScheduler().runTask(FancyNpcsModelPlugin.get(), interact); }
            catch (IllegalPluginAccessException disabledDuringSchedule) { /* Discard the queued click. */ }
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        provider.forgetInteractions(event.getPlayer().getUniqueId());
    }

}
