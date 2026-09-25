package com.example.client.mixin;

import com.example.client.ChargeTracker;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person arm animation for the charged punch.
 *
 * <p>The vanilla method {@code renderPlayerHand} draws the empty hand in
 * first person. While the punch is charging we rotate the arm backwards
 * into a wind-up pose (trembling at full charge) around the vanilla
 * rendering, and skip the vanilla swing transform. Every other case is
 * delegated to vanilla untouched.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsRendererMixin {
	@Unique
	private static final float CHARGE_WINDUP_TIME = 20.0F; // ticks to reach full wind-up

	@Invoker("renderPlayerHand")
	protected abstract void modid$invokeRenderPlayerHand(PoseStack poseStack, SubmitNodeCollector collector,
			int renderId, HumanoidArm arm, PlayerRenderState playerState);

	@Inject(
			method = "renderPlayerHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void modid$chargeHandPose(PoseStack poseStack, SubmitNodeCollector collector, int renderId,
			HumanoidArm arm, PlayerRenderState playerState, CallbackInfo ci) {
		if (!ChargeTracker.isCharging()) {
			return; // vanilla behaviour for normal clicks and other hands
		}

		float progress = ChargeTracker.getChargeProgress();
		float windup = Math.min(1.0F, progress * (CHARGE_WINDUP_TIME / (float) ChargeTracker.CHARGE_TICKS * 3.0F));
		float age = playerState.avatarRenderState.ageInTicks;
		float shake = progress >= 1.0F ? Mth.sin(age * 0.6F) * 0.04F : 0.0F;

		boolean right = arm == HumanoidArm.RIGHT;
		float side = right ? 1.0F : -1.0F;

		poseStack.pushPose();
		// Move the arm towards the center-back and rotate it into a wind-up.
		poseStack.translate(0.35F * side, -0.45F, -0.32F);
		poseStack.rotateDegrees(Axis.XP, 65.0F * windup + shake * 60.0F); // pull back
		poseStack.rotateDegrees(Axis.YP, -25.0F * side * windup); // angle inward
		poseStack.rotateDegrees(Axis.ZP, 15.0F * side * windup); // elbow-out tilt

		modid$invokeRenderPlayerHand(poseStack, collector, renderId, arm, playerState);
		poseStack.popPose();
		ci.cancel();
	}
}
