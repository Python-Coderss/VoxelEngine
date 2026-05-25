package com.aetherteam.aether.mixin.mixins.client;

import com.aetherteam.aether.AetherConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public class PauseScreenMixin {
    /**
     * Sends the player straight back to the title screen if the server button is enabled.
     *
     * @param ci The {@link CallbackInfo} for the void method return.
     * @see WorldDisplayHelper#setActive()
     * @see WorldDisplayHelper#setupLevelForDisplay()
     */
    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen;<init>(Lnet/minecraft/client/gui/screens/Screen;)V"), method = "onDisconnect()V", cancellable = true)
    public void onDisconnectServerButton(CallbackInfo ci) {
        if (AetherConfig.CLIENT.enable_server_button.get()) {
            Minecraft.getInstance().setScreen(new TitleScreen());
            ci.cancel();
        }
    }
}
