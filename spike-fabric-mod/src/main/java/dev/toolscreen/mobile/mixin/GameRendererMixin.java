package dev.toolscreen.mobile.mixin;

import dev.toolscreen.mobile.EyeZoom;
import dev.toolscreen.mobile.ToolscreenMobile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws the Toolscreen overlay every frame, whether or not the HUD is visible.
 *
 * <p>This replaces an earlier hook on {@code InGameHud.render}, which never
 * fired: Minecraft skips that call entirely when the HUD is hidden, and hiding
 * the HUD is the natural thing to do while measuring. The same omission is why
 * the crosshair disappeared, so the overlay now draws its own.
 *
 * <p>Drawing at the tail of {@code GameRenderer.render} inherits the GUI's
 * orthographic projection, which is set up for screens as well as the HUD —
 * observable in that the pause menu still renders correctly with the HUD
 * hidden. That is why the 2D drawing here lands in the right place without
 * setting up a projection of its own.
 *
 * <p>This mixin also narrows the field of view while a mode is active, which
 * is what magnifies the game view itself.
 *
 * <p>{@code require = 0} on both: a measuring aid that fails to attach should
 * cost the measurement, not a launchable game. {@code EyeZoom} logs when it
 * first runs so a silent failure is still visible in latest.log.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render(FJZ)V", at = @At("TAIL"), require = 0)
    private void toolscreen$drawOverlay(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        EyeZoom.render(new MatrixStack(), client, client.textRenderer);
    }

    // ---- Field of view -----------------------------------------------------
    //
    // Narrowing the FOV is what actually magnifies the game view, as opposed to
    // the magnifier panel, which only enlarges pixels that have already been
    // rendered. Spreading the same scene over the same pixels means each pixel
    // covers a smaller angle, so the eye's offset resolves more finely - which
    // is the number being fed to Ninjabrain Bot.
    //
    // Minecraft's own slider stops at 30 degrees. Scaling the computed value
    // goes below that without touching the setting, and leaves the player's
    // configured FOV intact for when the override is off.
    //
    // RETURN rather than HEAD: getFov already folds in the player's setting,
    // spyglass scoping, nausea and the sprint/speed multipliers, so scaling its
    // result keeps all of that and simply narrows the outcome.

    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void toolscreen$narrowFov(Camera camera, float tickDelta, boolean changingFov,
                                      CallbackInfoReturnable<Double> cir) {
        if (!ToolscreenMobile.isOverrideActive()) return;
        double original = cir.getReturnValue();
        double scaled = original * ToolscreenMobile.fovScale();
        ToolscreenMobile.noteFovActive(original, scaled);
        cir.setReturnValue(scaled);
    }
}
