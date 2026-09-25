package com.example.client.mixin;

import com.example.client.ChargeTracker;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the punch is charging, the player's main arm winds up backwards like a
 * vanilla "drawing a bow" wind-up, then trembles slightly when fully charged.
 * The pose is applied after vanilla pose calculation, so it layers cleanly on
 * top of any existing animation.
 */
@Mixin(HumanoidModel.class)
public class HumanoidModelMixin {
	@Unique
	private static final float CHARGE_WINDUP_TIME = 20.0F; // ticks to reach full wind-up

	@Inject(method = "setupAnim", at = @At("TAIL"))
	private <T extends LivingEntity> void modid$applyChargePose(T entity, float limbSwing, float limbSwingAmount,
			float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
		if (!ChargeTracker.isCharging()) {
			return;
		}

		float progress = ChargeTracker.getChargeProgress();

		@SuppressWarnings("unchecked")
		HumanoidModel<T> model = (HumanoidModel<T>) (Object) this;
		ModelPart arm = entity.getMainArm() == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;

		// Wind-up: pull the arm back and up, with a slight body-side tilt.
		float windup = Math.min(1.0F, progress * (CHARGE_WINDUP_TIME / (float) ChargeTracker.CHARGE_TICKS * 3.0F));
		float shake = progress >= 1.0F ? Mth.sin(ageInTicks * 0.6F) * 0.05F : 0.0F;

		arm.xRot = -2.4F * windup + shake; // raise the arm back over the shoulder
		arm.yRot = 0.35F * windup;
		arm.zRot = 0.2F * windup;
	}
}
