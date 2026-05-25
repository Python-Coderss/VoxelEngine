package com.aetherteam.aether.client.event.listeners;

import com.aetherteam.aether.client.AetherClient;
import com.aetherteam.aether.client.event.hooks.MenuHooks;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ScreenEvent;

public class MenuListener {
    /**
     * @see AetherClient#eventSetup(IEventBus)
     */
    public static void listen(IEventBus bus) {
        bus.addListener(MenuListener::onGuiInitialize);
    }

    /**
     * @see MenuHooks#setCustomSplashText(TitleScreen)
     */
    public static void onGuiInitialize(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof TitleScreen titleScreen) {
            MenuHooks.setCustomSplashText(titleScreen);
        }
    }
}
