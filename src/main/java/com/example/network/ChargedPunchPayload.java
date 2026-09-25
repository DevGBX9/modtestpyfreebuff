package com.example.network;

import com.example.ExampleMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Payload sent from the client to the server when the player releases a
 * fully charged punch (3 seconds of holding left click).
 */
public record ChargedPunchPayload() implements CustomPacketPayload {
	public static final ChargedPunchPayload INSTANCE = new ChargedPunchPayload();

	public static final Type<ChargedPunchPayload> TYPE =
			new Type<>(ExampleMod.id("charged_punch"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ChargedPunchPayload> CODEC =
			StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
