package com.aetherteam.aether.event.listeners;

import com.aetherteam.aether.Aether;
import com.aetherteam.aether.event.hooks.PerkHooks;
import com.aetherteam.aether.network.packet.clientbound.RegisterMoaSkinsPacket;
import com.aetherteam.aether.perk.types.MoaSkins;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public class PerkListener {
    /**
     * @see Aether#eventSetup(IEventBus)
     */
    public static void listen(IEventBus bus) {
        bus.addListener(PerkListener::playerLoggedIn);
    }

    /**
     * @see PerkHooks#refreshPerks(Player)
     */
    public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        PerkHooks.refreshPerks(player);
        MoaSkins.registerMoaSkins(player.level());
        if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, new RegisterMoaSkinsPacket());
        }
    }
}
