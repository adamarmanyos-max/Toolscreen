package dev.toolscreen.mobile.mixin;

import dev.toolscreen.mobile.EyeZoom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the EyeZoom overlay on top of the finished HUD.
 *
 * <p>TAIL rather than HEAD so the panel sits above the hotbar and crosshair
 * instead of being painted over by them.
 *
 * <p>{@code require = 0} for the same reason as the centring redirect: this is
 * an optional overlay, and a mixin that cannot find its target aborts startup
 * when required. A missing measuring tool is an annoyance; a game that will not
 * launch is not. {@code EyeZoom} logs once when it actually draws, so a silent
 * failure is still detectable in latest.log.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Shadow
    public abstract TextRenderer getFontRenderer();

    @Inject(
            method = "render(Lnet/minecraft/client/util/math/MatrixStack;F)V",
            at = @At("TAIL"),
            require = 0)
    private void toolscreen$drawEyeZoom(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        EyeZoom.render(matrices, MinecraftClient.getInstance(), getFontRenderer());
    }
}
