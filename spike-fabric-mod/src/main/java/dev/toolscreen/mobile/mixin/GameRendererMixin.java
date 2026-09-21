package dev.toolscreen.mobile.mixin;

import dev.toolscreen.mobile.EyeZoom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * <p>{@code require = 0}: an overlay that fails to attach should cost a
 * measuring tool, not a launchable game. {@code EyeZoom} logs when it first
 * runs so a silent failure is still visible in latest.log.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render(FJZ)V", at = @At("TAIL"), require = 0)
    private void toolscreen$drawOverlay(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        EyeZoom.render(new MatrixStack(), client, client.textRenderer);
    }
}
