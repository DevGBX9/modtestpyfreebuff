package com.example.client.mixin;

import com.example.client.ChargeTracker;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the punch is charging, the player's main arm winds up backwards like a
 * vanilla "drawing a bow" wind-up, then trembles slightly at full charge.
 *
 * <p>In 26.3 models are posed from {@code HumanoidRenderState} instead of
 * entity instances, so we drive the animation from client-global charge state
 * and assume the rendered avatar is the local player's (the only renderer that
 * plays the charge animation).
 */
@Mixin(HumanoidModel.class)
public class HumanoidModelMixin {
	@Unique
	private static final float CHARGE_WINDUP_TIME = 20.0F; // ticks to reach full wind-up

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
	private void modid$applyChargePose(HumanoidRenderState state, CallbackInfo ci) {
		if (!ChargeTracker.isCharging()) {
			return;
		}

		float progress = ChargeTracker.getChargeProgress();

		// Tremble once fully charged.
		float shake = progress >= 1.0F ? Mth.sin(state.ageInTicks * 0.6F) * 0.05F : 0.0F;

		// Wind-up: pull the main arm back and up, with a slight tilt.
		float windup = Math.min(1.0F, progress * (CHARGE_WINDUP_TIME / (float) ChargeTracker.CHARGE_TICKS * 3.0F));

		@SuppressWarnings("unchecked")
		HumanoidModel<HumanoidRenderState> model = (HumanoidModel<HumanoidRenderState>) (Object) this;
		ModelPart arm = model.getArm(HumanoidArm.RIGHT);

		arm.xRot = -2.4F * windup + shake; // raise the arm back over the shoulder
		arm.yRot = 0.35F * windup;
		arm.zRot = 0.2F * windup;
	}
}
