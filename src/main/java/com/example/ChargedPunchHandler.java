package com.example;

import com.example.network.ChargedPunchPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side logic for the charged punch. The attack sequence is:
 *
 * <ol>
 *   <li>massive knockback away from the player,</li>
 *   <li>a short delay while the target is flying backwards,</li>
 *   <li>an explosion at the target,</li>
 *   <li>a ground shockwave (dust/sweep particles around the impact),</li>
 *   <li>slowness and weakness on the victim and nearby entities.</li>
 * </ol>
 */
public final class ChargedPunchHandler {
	/** Knockback multiplier applied before the explosion (compared to a vanilla hit). */
	private static final double KNOCKBACK_STRENGTH = 3.2D;
	/** Extra upwards boost so the target visibly launches into the air. */
	private static final double KNOCKBACK_LIFT = 0.55D;
	/** Ticks between the knockback and the explosion. */
	private static final int EXPLOSION_DELAY_TICKS = 8;
	/** Explosion power (TNT is 4). */
	private static final float EXPLOSION_POWER = 3.0F;
	/** Radius of the ground shockwave. */
	private static final double SHOCKWAVE_RADIUS = 4.5D;
	/** Duration of the follow-up slowness/weakness effects. */
	private static final int DEBUFF_TICKS = 100;

	private ChargedPunchHandler() {
	}

	public static void register() {
		// Must be registered on both sides before any receiver.
		PayloadTypeRegistry.serverboundPlay().register(ChargedPunchPayload.TYPE, ChargedPunchPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ChargedPunchPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			executePunch(player);
		});
	}

	private static void executePunch(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		LivingEntity target = findTarget(player);

		// Swing the arm so the release feels responsive.
		player.swing(InteractionHand.MAIN_HAND, true);

		if (target == null) {
			// Missed: a weaker "air punch" burst so it still feels powerful.
			Vec3 look = player.getLookAngle();
			Vec3 pos = player.getEyePosition().add(look.scale(2.0D));
			level.sendParticles(ParticleTypes.SWEEP_ATTACK, pos.x, pos.y, pos.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.7F);
			return;
		}

		Vec3 direction = target.position().subtract(player.position());
		direction = new Vec3(direction.x, 0.0D, direction.z);
		if (direction.lengthSqr() < 1.0E-4D) {
			Vec3 look = player.getLookAngle();
			direction = new Vec3(look.x, 0.0D, look.z);
		}
		direction = direction.normalize();

		// 1) Knockback: send the target flying away from the player.
		Vec3 launch = direction.scale(KNOCKBACK_STRENGTH).add(0.0D, KNOCKBACK_LIFT, 0.0D);
		target.push(launch.x, launch.y, launch.z);
		target.hurtMarked = true; // force velocity sync to clients

		// Direct impact damage (for the death message attribution).
		target.hurt(player.damageSources().playerAttack(player), 8.0F);

		level.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.4F, 0.6F);
		level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.5D), target.getZ(), 20, 0.4D, 0.4D, 0.4D, 0.15D);

		// 2) Explosion shortly after the target has been launched.
		Vec3 targetPos = target.position();
		ServerTickScheduler.schedule(level, EXPLOSION_DELAY_TICKS, () -> explode(level, target, targetPos));
	}

	private static void explode(ServerLevel level, LivingEntity target, Vec3 pos) {
		double x = target.isRemoved() ? pos.x : target.getX();
		double y = target.isRemoved() ? pos.y : target.getY(0.5D);
		double z = target.isRemoved() ? pos.z : target.getZ();

		// 3) Explosion at the target's position.
		level.explode(
				null,
				x, y, z,
				EXPLOSION_POWER,
				Level.ExplosionInteraction.TNT
		);

		level.playSound(null, target.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 3.0F, 1.0F);

		// 4) Ground shockwave: dust ring around the impact + radial push.
		applyShockwave(level, x, y, z);

		// 5) Debuffs: slowness and weakness on the victim and everything nearby.
		applyDebuffs(level, x, y, z);
	}

	private static void applyShockwave(ServerLevel level, double x, double y, double z) {
		// Dust ring at ground level.
		int steps = 24;
		for (int i = 0; i < steps; i++) {
			double angle = (Math.PI * 2 * i) / steps;
			double px = x + Math.cos(angle) * 2.5D;
			double pz = z + Math.sin(angle) * 2.5D;
			level.sendParticles(ParticleTypes.CLOUD, px, y + 0.2D, pz, 3, 0.1D, 0.05D, 0.1D, 0.02D);
			level.sendParticles(ParticleTypes.POOF, px, y + 0.4D, pz, 2, 0.05D, 0.02D, 0.05D, 0.01D);
		}
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);

		// Extra knockback wave for nearby entities (dirt-dash effect).
		AABB box = new AABB(x - SHOCKWAVE_RADIUS, y - 2.0D, z - SHOCKWAVE_RADIUS,
				x + SHOCKWAVE_RADIUS, y + 2.0D, z + SHOCKWAVE_RADIUS);
		for (LivingEntity nearby : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
			Vec3 away = nearby.position().subtract(x, 0.0D, z);
			double distance = away.length();
			if (distance * distance < 1.0E-4D) {
				away = new Vec3(1.0D, 0.0D, 0.0D);
				distance = 1.0D;
			}
			away = away.normalize();
			double falloff = Math.max(0.25D, 1.0D - distance / SHOCKWAVE_RADIUS);
			nearby.push(away.x * falloff, 0.35D * falloff, away.z * falloff);
			nearby.hurtMarked = true;
		}
	}

	private static void applyDebuffs(ServerLevel level, double x, double y, double z) {
		AABB box = new AABB(x - SHOCKWAVE_RADIUS, y - 2.0D, z - SHOCKWAVE_RADIUS,
				x + SHOCKWAVE_RADIUS, y + 2.0D, z + SHOCKWAVE_RADIUS);
		for (LivingEntity nearby : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
			nearby.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DEBUFF_TICKS, 2));
			nearby.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DEBUFF_TICKS, 1));
		}
	}

	private static LivingEntity findTarget(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();

		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		double reach = 3.5D;

		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(reach), e -> e.isAlive() && e != player)) {
			// Score by distance from the look ray.
			Vec3 toEntity = entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D).subtract(eye);
			double alongRay = toEntity.dot(look);
			if (alongRay < 0.0D || alongRay > reach) {
				continue;
			}
			double perpendicular = toEntity.subtract(look.scale(alongRay)).length();
			double score = perpendicular + alongRay * 0.1D;
			if (perpendicular < 1.25D && score < bestScore) {
				bestScore = score;
				best = entity;
			}
		}
		return best;
	}
}
