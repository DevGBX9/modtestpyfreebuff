package com.example.client;

import com.example.network.ChargedPunchPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks the "hold left click to charge" mechanic on the client.
 *
 * <p>While the attack key is held the tracker counts up; at
 * {@link #CHARGE_TICKS} (3 seconds) the punch is fully charged: a chime plays
 * and enchantment particles start swirling around the player. When the button
 * is released while fully charged, a release burst plays and a
 * {@link ChargedPunchPayload} is sent to the server which performs the
 * knockback + explosion sequence.
 *
 * <p>Normal (short) clicks are completely unaffected.
 *
 * <p>All API names verified against the unobfuscated 26.3 client jar.
 */
public final class ChargeTracker {
	/** Full charge duration: 3 seconds = 60 ticks. */
	public static final int CHARGE_TICKS = 60;

	private static int chargeTicks = -1; // -1 = not charging
	private static boolean wasAttackDown = false;

	private ChargeTracker() {
	}

	public static boolean isCharging() {
		return chargeTicks >= 0;
	}

	/** 0..1 charge progress, used by the arm animation mixin. */
	public static float getChargeProgress() {
		if (chargeTicks < 0) {
			return 0.0F;
		}
		return Math.min(1.0F, chargeTicks / (float) CHARGE_TICKS);
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ChargeTracker::onEndTick);
	}

	private static void onEndTick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.options == null) {
			reset();
			return;
		}

		// A released mouse = a screen is open (menu/inventory/chat): cancel the charge.
		// Also cancel on death or spectator mode.
		if (!client.mouseHandler.isMouseGrabbed() || player.isDeadOrDying() || player.isSpectator()) {
			if (chargeTicks >= 0) {
				reset();
			}
			wasAttackDown = false;
			return;
		}

		boolean down = client.options.keyAttack.isDown();
		boolean justPressed = down && !wasAttackDown;
		boolean justReleased = !down && wasAttackDown;
		wasAttackDown = down;

		if (justPressed) {
			chargeTicks = 0;
		} else if (down) {
			chargeTicks++;
		}

		if (justReleased) {
			if (chargeTicks >= CHARGE_TICKS) {
				fireChargedPunch(client, player);
			}
			reset();
			return;
		}

		if (chargeTicks < 0) {
			return;
		}

		// Full-charge moment: chime + burst.
		if (chargeTicks == CHARGE_TICKS) {
			playReadyEffects(player);
		}

		// While charged: swirling particles every 5 ticks.
		if (chargeTicks > CHARGE_TICKS && chargeTicks % 5 == 0) {
			spawnChargeParticles(player);
		}
	}

	private static void fireChargedPunch(Minecraft client, LocalPlayer player) {
		// Release burst around the player.
		Vec3 pos = player.position().add(0.0D, player.getBbHeight() * 0.6D, 0.0D);
		player.level().addParticle(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 0.0D, 0.0D, 0.0D);
		for (int i = 0; i < 12; i++) {
			double angle = player.level().getRandom().nextDouble() * Math.PI * 2;
			player.level().addParticle(ParticleTypes.CRIT,
					pos.x, pos.y, pos.z,
					Math.cos(angle) * 0.3D, 0.1D, Math.sin(angle) * 0.3D);
		}
		player.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.2F, 0.5F);

		ClientPlayNetworking.send(ChargedPunchPayload.INSTANCE);
	}

	private static void playReadyEffects(LocalPlayer player) {
		Vec3 pos = player.position().add(0.0D, player.getBbHeight() * 0.6D, 0.0D);
		for (int i = 0; i < 16; i++) {
			double angle = (Math.PI * 2 * i) / 16;
			player.level().addParticle(ParticleTypes.ENCHANTED_HIT,
					pos.x + Math.cos(angle) * 0.7D, pos.y + 0.2D, pos.z + Math.sin(angle) * 0.7D,
					-Math.cos(angle) * 0.15D, 0.1D, -Math.sin(angle) * 0.15D);
		}
		player.playSound(SoundEvents.PLAYER_LEVELUP, 0.8F, 1.7F);
	}

	private static void spawnChargeParticles(LocalPlayer player) {
		Vec3 pos = player.position().add(0.0D, player.getBbHeight() * 0.6D, 0.0D);
		player.level().addParticle(ParticleTypes.ENCHANTED_HIT,
				pos.x + (player.level().getRandom().nextDouble() - 0.5D) * 0.9D,
				pos.y + (player.level().getRandom().nextDouble() - 0.5D) * 0.7D,
				pos.z + (player.level().getRandom().nextDouble() - 0.5D) * 0.9D,
				0.0D, 0.08D, 0.0D);
		player.level().addParticle(ParticleTypes.CRIT,
				pos.x + (player.level().getRandom().nextDouble() - 0.5D) * 0.8D,
				pos.y + (player.level().getRandom().nextDouble() - 0.5D) * 0.6D,
				pos.z + (player.level().getRandom().nextDouble() - 0.5D) * 0.8D,
				0.0D, 0.05D, 0.0D);
	}

	private static void reset() {
		chargeTicks = -1;
	}
}
