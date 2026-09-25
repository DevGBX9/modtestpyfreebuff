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
 *
 * <p>Re-entrancy: the {@code @Invoker} call below re-enters
 * {@code renderPlayerHand}, which would hit this very handler again and
 * recurse forever (StackOverflowError). The {@link #modid$reentering} flag
 * lets that inner call fall straight through to the vanilla body.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsRendererMixin {
	@Unique
	private static final float CHARGE_WINDUP_TIME = 20.0F; // ticks to reach full wind-up

	@Unique
	private boolean modid$reentering = false;

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
		// Our own invoker call below re-enters here: run the vanilla body as-is.
		if (this.modid$reentering) {
			return;
		}
		if (!ChargeTracker.isCharging()) {
			return; // vanilla behaviour for normal clicks and other hands
		}

		float progress = ChargeTracker.getChargeProgress();
		float windup = Math.min(1.0F, progress * (CHARGE_WINDUP_TIME / (float) ChargeTracker.CHARGE_TICKS * 3.0F));
		float age = playerState.avatarRenderState.ageInTicks;
		// Keep the tremble tiny so the arm never leaves the frame.
		float shake = progress >= 1.0F ? Mth.sin(age * 0.6F) * 2.5F : 0.0F;

		boolean right = arm == HumanoidArm.RIGHT;
		float side = right ? 1.0F : -1.0F;

		poseStack.pushPose();
		// Gentle "cocking" motion in the spirit of vanilla eat/charge transforms:
		// small lift and pull, tiny twists. Large rotations swing the arm across
		// the camera near-plane and make it disappear entirely.
		poseStack.translate(0.10F * side * windup, 0.08F * windup, 0.05F * windup);
		poseStack.rotateDegrees(Axis.XP, 25.0F * windup + shake);
		poseStack.rotateDegrees(Axis.YP, -12.0F * side * windup);
		poseStack.rotateDegrees(Axis.ZP, 7.0F * side * windup);

		this.modid$reentering = true;
		try {
			this.modid$invokeRenderPlayerHand(poseStack, collector, renderId, arm, playerState);
		} finally {
			this.modid$reentering = false;
		}
		poseStack.popPose();
		ci.cancel();
	}
}
