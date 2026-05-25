package com.aetherteam.aether.network.packet.clientbound;

import com.aetherteam.aether.Aether;
import com.aetherteam.aether.perk.types.MoaSkins;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RegisterMoaSkinsPacket() implements CustomPacketPayload {
    public static final Type<RegisterMoaSkinsPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Aether.MODID, "register_moa_skin"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RegisterMoaSkinsPacket> STREAM_CODEC = StreamCodec.unit(new RegisterMoaSkinsPacket());

    @Override
    public Type<RegisterMoaSkinsPacket> type() {
        return TYPE;
    }

    public static void execute(RegisterMoaSkinsPacket payload, IPayloadContext context) {
        MoaSkins.registerClient();
    }
}
