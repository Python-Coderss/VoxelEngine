package com.aetherteam.aether.mixin.mixins.client;

import com.aetherteam.aether.mixin.AetherMixinHooks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin {
    /**
     * Sets the cape texture to use when a cape accessory is equipped and visible. This is also used by Elytra.
     *
     * @param original The original {@link PlayerSkin} data.
     * @return {@link PlayerSkin} data with the texture of a cape if equipped and visible, otherwise uses the original cape texture.
     */
    @WrapMethod(method = "getSkin()Lnet/minecraft/client/resources/PlayerSkin;")
    private PlayerSkin getSkin(Operation<PlayerSkin> original) {
        AbstractClientPlayer abstractClientPlayer = (AbstractClientPlayer) (Object) this;
        PlayerSkin skin = original.call();
        ItemStack stack = AetherMixinHooks.isCapeVisible(abstractClientPlayer);
        if (!stack.isEmpty()) {
            ResourceLocation texture = AetherMixinHooks.getCapeTexture(stack);
            if (texture != null) {
                return new PlayerSkin(skin.texture(), skin.textureUrl(), texture, skin.elytraTexture(), skin.model(), skin.secure());
            }
        }
        return skin;
    }
}
