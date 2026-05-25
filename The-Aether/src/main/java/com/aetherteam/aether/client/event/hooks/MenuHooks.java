package com.aetherteam.aether.client.event.hooks;

import com.aetherteam.cumulus.client.CumulusClient;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Calendar;
import java.util.function.Predicate;

public class MenuHooks {
    /**
     * If the current date is July 22nd, displays the Aether's anniversary splash text.
     *
     * @see com.aetherteam.aether.client.event.listeners.MenuListener#onGuiInitialize(ScreenEvent.Init.Post)
     */
    public static void setCustomSplashText(TitleScreen screen) {
        Predicate<Calendar> condition = (calendar) -> calendar.get(Calendar.MONTH) + 1 == 7 && calendar.get(Calendar.DATE) == 22;
        CumulusClient.MENU_HELPER.setCustomSplash(screen, condition, "Happy anniversary to the Aether!");
    }
}
