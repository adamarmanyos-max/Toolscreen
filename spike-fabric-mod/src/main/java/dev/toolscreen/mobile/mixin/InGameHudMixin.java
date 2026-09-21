package dev.toolscreen.mobile.mixin;

import dev.toolscreen.mobile.ToolscreenMobile;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the vanilla crosshair while a mode is active.
 *
 * <p>Not cosmetic: EyeZoom samples the pixels at the centre of the screen, and
 * the crosshair sits exactly there. Left in place it dominates the sample, so
 * the magnifier ends up showing a hugely enlarged crosshair instead of what is
 * being aimed at — which is what the first working build did.
 *
 * <p>The overlay draws its own replacement afterwards, so nothing is lost.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, require = 0)
    private void toolscreen$hideVanillaCrosshair(MatrixStack matrices, CallbackInfo ci) {
        if (ToolscreenMobile.isOverrideActive() && ToolscreenMobile.crosshairEnabled()) {
            ci.cancel();
        }
    }
}
