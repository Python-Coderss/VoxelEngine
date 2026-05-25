package com.aetherteam.aether.api;

import com.aetherteam.aether.Aether;
import com.aetherteam.aether.client.gui.screen.menu.AetherTitleScreen;
import com.aetherteam.aether.client.gui.screen.menu.VanillaLeftTitleScreen;
import com.aetherteam.cumulus.api.*;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

@CumulusEntrypoint
public class AetherMenus implements MenuInitializer {
    // Icons
    private static final ResourceLocation THE_AETHER_ICON = ResourceLocation.fromNamespaceAndPath(Aether.MODID, "textures/gui/menu_api/menu_icon_aether.png");

    // Names
    private static final Component MINECRAFT_LEFT_NAME = Component.translatable("aether.menu_title.minecraft_left");
    private static final Component THE_AETHER_NAME = Component.translatable("aether.menu_title.the_aether");
    private static final Component THE_AETHER_LEFT_NAME = Component.translatable("aether.menu_title.the_aether_left");

    // Menus
    public static final Menu MINECRAFT_LEFT = new Menu(Menus.MINECRAFT_ICON, MINECRAFT_LEFT_NAME, new VanillaLeftTitleScreen());
    public static final Menu THE_AETHER = new Menu(THE_AETHER_ICON, THE_AETHER_NAME, new AetherTitleScreen(), new Menu.Properties().music(AetherTitleScreen.MENU).panorama(new CubeMap(ResourceLocation.fromNamespaceAndPath(Aether.MODID, "textures/gui/title/panorama/panorama"))));
    public static final Menu THE_AETHER_LEFT = new Menu(THE_AETHER_ICON, THE_AETHER_LEFT_NAME, new AetherTitleScreen(true), new Menu.Properties().music(AetherTitleScreen.MENU).panorama(new CubeMap(ResourceLocation.fromNamespaceAndPath(Aether.MODID, "textures/gui/title/panorama/panorama"))));

    @Override
    public void registerMenus(MenuRegisterCallback menuRegisterCallback) {
        menuRegisterCallback.registerMenu(ResourceLocation.fromNamespaceAndPath(Aether.MODID, "minecraft_left"), MINECRAFT_LEFT);
        menuRegisterCallback.registerMenu(ResourceLocation.fromNamespaceAndPath(Aether.MODID, "the_aether"), THE_AETHER);
        menuRegisterCallback.registerMenu(ResourceLocation.fromNamespaceAndPath(Aether.MODID, "the_aether_left"), THE_AETHER_LEFT);
    }
}
