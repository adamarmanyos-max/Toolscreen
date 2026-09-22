package dev.toolscreen.mobile.mixin;

import dev.toolscreen.mobile.EyeZoom;
import dev.toolscreen.mobile.Layout;
import dev.toolscreen.mobile.ToolscreenMobile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes the magnifier's sample, at the end of the frame's rendering and before
 * the framebuffer is blitted.
 *
 * <p>This is the last moment the framebuffer holds the frame and nothing of
 * ours has been drawn over it. The panel itself is drawn later, after the blit,
 * because only then is the area beside the game window addressable.
 *
 * <h2>The field of view is deliberately untouched</h2>
 *
 * An earlier version scaled {@code getFov} to magnify the view. That was wrong
 * for a measuring tool and has been removed. Angle per pixel is the vertical
 * field of view divided by the render height, and Ninjabrain Bot recomputes it
 * from the player's own FOV setting and the tall resolution it is told about -
 * so quietly altering the FOV makes every reading wrong by that factor while
 * everything still looks plausible. Magnification comes from render height and
 * from the panel, both of which leave the camera alone.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render(FJZ)V", at = @At("TAIL"), require = 0)
    private void toolscreen$sample(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (!ToolscreenMobile.eyeZoomActive()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        Layout layout = ToolscreenMobile.layout();
        if (layout == null) return;
        EyeZoom.sample(client, layout);
    }
}
