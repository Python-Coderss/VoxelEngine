package com.aetherteam.aether.client;

import com.aetherteam.cumulus.CumulusConfig;

public class AetherMenuUtil {
    /**
     * @return Whether the currently active menu is an Aether menu, as a {@link Boolean}.
     */
    public static boolean isAetherMenu() {
        return CumulusConfig.CLIENT.active_menu.get().equals("aether:the_aether") || CumulusConfig.CLIENT.active_menu.get().equals("aether:the_aether_left");
    }

    /**
     * @return Whether the currently active menu is a Minecraft menu, as a {@link Boolean}.
     */
    public static boolean isMinecraftMenu() {
        return CumulusConfig.CLIENT.active_menu.get().equals("minecraft:minecraft") || CumulusConfig.CLIENT.active_menu.get().equals("aether:minecraft_left");
    }
}
