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
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side logic for the charged punch. The attack sequence is:
 *
 * <ol>
 *   <li>massive knockback away from the player,</li>
 *   <li>the target flies backwards until it has travelled
 *       {@link #EXPLODE_TRAVEL_DISTANCE} blocks (tick-count safety cap applies),</li>
 *   <li>it detonates in mid-flight,</li>
 *   <li>a ground shockwave pushes nearby entities (never the attacker),</li>
 *   <li>slowness and weakness are applied to the victim only.</li>
 * </ol>
 *
 * <p>All names verified against the unobfuscated 26.3 jars.
 */
public final class ChargedPunchHandler {
	/** Knockback multiplier applied before the explosion (compared to a vanilla hit). */
	private static final double KNOCKBACK_STRENGTH = 3.2D;
	/** Extra upwards boost so the target visibly launches into the air. */
	private static final double KNOCKBACK_LIFT = 0.55D;
	/** How far (blocks) the target must fly before it detonates. */
	private static final double EXPLODE_TRAVEL_DISTANCE = 3.5D;
	/** Safety cap: detonate anyway after this many ticks. */
	private static final int EXPLOSION_MAX_DELAY_TICKS = 40;
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
		ServerLevel level = player.level(); // ServerPlayer.level() returns ServerLevel in 26.3
		LivingEntity target = findTarget(player);

		// Swing the arm so the release feels responsive.
		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);

		if (target == null) {
			// Missed: a smaller "air punch" burst so it still feels powerful.
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
		target.addDeltaMovement(launch);
		target.hurtServer(level, level.damageSources().playerAttack(player), 8.0F);

		level.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.4F, 0.6F);
		level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.5D), target.getZ(), 20, 0.4D, 0.4D, 0.4D, 0.15D);

		// 2) The explosion follows the target: it detonates once it has flown
		// EXPLODE_TRAVEL_DISTANCE blocks away (with a tick-count safety cap).
		Vec3 startPos = target.position();
		ServerTickScheduler.schedule(level, 1, () ->
				trackAndExplode(level, target, startPos, 0, player));
	}

	/**
	 * Tracks the flying target and detonates once it has travelled far enough
	 * from its launch point (or when the tick cap is reached).
	 */
	private static void trackAndExplode(ServerLevel level, LivingEntity target, Vec3 startPos, int ticksWaited,
			ServerPlayer attacker) {
		if (!level.getServer().isRunning()) {
			return;
		}
		boolean targetGone = target.isRemoved() || !target.isAlive();
		double travelled = targetGone ? Double.MAX_VALUE : target.position().distanceTo(startPos);

		if (!targetGone && travelled < EXPLODE_TRAVEL_DISTANCE && ticksWaited < EXPLOSION_MAX_DELAY_TICKS) {
			ServerTickScheduler.schedule(level, 1, () -> trackAndExplode(level, target, startPos, ticksWaited + 1, attacker));
			return;
		}

		Vec3 detonationPos = targetGone ? startPos : target.position();
		explode(level, target, detonationPos, attacker);
	}

	private static void explode(ServerLevel level, LivingEntity target, Vec3 pos, ServerPlayer attacker) {
		double x = target.isRemoved() ? pos.x : target.getX();
		double y = target.isRemoved() ? pos.y : target.getY(0.5D);
		double z = target.isRemoved() ? pos.z : target.getZ();

		// 3) Explosion at the target's position (TNT interaction, no fire).
		level.explode(null, x, y, z, EXPLOSION_POWER, Level.ExplosionInteraction.TNT);

		// GENERIC_EXPLODE is a Holder<SoundEvent> in 26.3, so use the Holder overload.
		level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 3.0F, 1.0F);

		// 4) Ground shockwave: dust ring around the impact + radial push.
		applyShockwave(level, x, y, z, attacker);

		// 5) Debuffs: slowness and weakness on the victim only - never the player
		// and never bystanders.
		applyDebuffs(level, target, x, y, z);
	}

	private static void applyShockwave(ServerLevel level, double x, double y, double z, ServerPlayer attacker) {
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
		// The attacking player is never pushed by their own shockwave.
		AABB box = new AABB(x - SHOCKWAVE_RADIUS, y - 2.0D, z - SHOCKWAVE_RADIUS,
				x + SHOCKWAVE_RADIUS, y + 2.0D, z + SHOCKWAVE_RADIUS);
		for (LivingEntity nearby : level.getEntitiesOfClass(LivingEntity.class, box,
				e -> e.isAlive() && e != attacker && !e.isSpectator())) {
			Vec3 away = nearby.position().subtract(x, 0.0D, z);
			double distance = away.length();
			if (distance * distance < 1.0E-4D) {
				away = new Vec3(1.0D, 0.0D, 0.0D);
				distance = 1.0D;
			}
			away = away.normalize();
			double falloff = Math.max(0.25D, 1.0D - distance / SHOCKWAVE_RADIUS);
			nearby.addDeltaMovement(new Vec3(away.x * falloff, 0.35D * falloff, away.z * falloff));
		}
	}

	private static void applyDebuffs(ServerLevel level, LivingEntity victim, double x, double y, double z) {
		if (victim.isRemoved() || !victim.isAlive()) {
			return;
		}
		victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DEBUFF_TICKS, 2));
		victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DEBUFF_TICKS, 1));
	}

	private static LivingEntity findTarget(ServerPlayer player) {
		ServerLevel level = player.level();
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
