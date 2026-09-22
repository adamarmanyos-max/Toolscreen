package dev.toolscreen.mobile.mixin;

import dev.toolscreen.mobile.GlLimits;
import dev.toolscreen.mobile.Mode;
import dev.toolscreen.mobile.ToolscreenMobile;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The core of the spike: makes Minecraft believe the screen is a different
 * shape than it is.
 *
 * <p>On Windows, Toolscreen achieves this by resizing the real OS window. That
 * option does not exist on iOS — Amethyst's {@code glfwSetWindowSize} is a stub
 * that only records numbers ({@code GLFW.java:1023}), and the true render size
 * is pushed from the native side in {@code SurfaceViewController
 * .updateSavedResolution}. So instead of resizing anything, we lie about the
 * framebuffer size at the point Minecraft reads it.
 *
 * <p>Minecraft's {@code onResolutionChanged} sizes the main render target from
 * these getters and then blits that target back to the screen using them again,
 * so a smaller reported size renders the game into a sub-rect of the real
 * surface — the thin window.
 *
 * <p>Note this override is inherently self-healing. Amethyst delivers genuine
 * resizes (rotation, app resume, resolution-slider changes) by queueing a
 * {@code EVENT_TYPE_FRAMEBUFFER_SIZE} event that is drained in
 * {@code pojavPumpEvents} on the next {@code glfwPollEvents}. That path updates
 * the shadowed fields below and calls Minecraft's resize callback — which then
 * re-reads these getters and gets the overridden values again. No re-assert
 * logic is needed, and because those events are only queued on actual change
 * there is no per-frame fight with the launcher.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    /** Real surface dimensions, as maintained by the launcher's resize callbacks. */
    @Shadow private int framebufferWidth;
    @Shadow private int framebufferHeight;

    /** GUI units per framebuffer pixel, as chosen by Minecraft's auto scaling. */
    @Shadow private double scaleFactor;

    // Both getters resolve against ToolscreenMobile's recorded native size
    // rather than the shadowed field. The field can hold a value this override
    // produced - Amethyst's window-size stubs record what they are given and
    // hand it back - and resolving a fraction against an already-reduced width
    // applies the fraction twice. Recording the size separately gives one place
    // to recognise that and refuse it.
    @Inject(method = "getFramebufferWidth", at = @At("HEAD"), cancellable = true)
    private void toolscreen$overrideFramebufferWidth(CallbackInfoReturnable<Integer> cir) {
        ToolscreenMobile.recordNativeSize(this.framebufferWidth, this.framebufferHeight);
        if (!ToolscreenMobile.isOverrideActive()) return;
        Mode mode = ToolscreenMobile.activeMode();
        int width = GlLimits.clampTexture(mode.resolveWidth(ToolscreenMobile.nativeWidth()));
        int height = GlLimits.clampTexture(mode.resolveHeight(ToolscreenMobile.nativeHeight()));
        ToolscreenMobile.recordReportedSize(width, height);
        ToolscreenMobile.recordRenderSize(width, height);
        cir.setReturnValue(width);
    }

    @Inject(method = "getFramebufferHeight", at = @At("HEAD"), cancellable = true)
    private void toolscreen$overrideFramebufferHeight(CallbackInfoReturnable<Integer> cir) {
        ToolscreenMobile.recordNativeSize(this.framebufferWidth, this.framebufferHeight);
        if (!ToolscreenMobile.isOverrideActive()) return;
        Mode mode = ToolscreenMobile.activeMode();
        cir.setReturnValue(GlLimits.clampTexture(mode.resolveHeight(ToolscreenMobile.nativeHeight())));
    }

    // ---- GUI coordinate space ---------------------------------------------
    //
    // Overriding the framebuffer getters alone is not enough. Minecraft caches
    // scaledWidth/scaledHeight, computing them from the *private fields* rather
    // than through the getters above, so the GUI stays laid out for the whole
    // surface while the game renders into a narrow strip. Everything positioned
    // from the centre of that space - the crosshair, the hotbar, this mod's own
    // overlay - then lands outside the strip entirely and is never seen.
    //
    // Reporting the scaled size of the strip instead puts the GUI back inside
    // the rendered area. Where the cached value already matched, this computes
    // the same number, so it is safe either way.

    @Inject(method = "getScaledWidth", at = @At("HEAD"), cancellable = true)
    private void toolscreen$overrideScaledWidth(CallbackInfoReturnable<Integer> cir) {
        if (!ToolscreenMobile.isOverrideActive() || this.scaleFactor <= 0) return;
        int width = GlLimits.clampTexture(ToolscreenMobile.activeMode().resolveWidth(ToolscreenMobile.nativeWidth()));
        cir.setReturnValue((int) Math.ceil(width / this.scaleFactor));
    }

    @Inject(method = "getScaledHeight", at = @At("HEAD"), cancellable = true)
    private void toolscreen$overrideScaledHeight(CallbackInfoReturnable<Integer> cir) {
        if (!ToolscreenMobile.isOverrideActive() || this.scaleFactor <= 0) return;
        int height = GlLimits.clampTexture(ToolscreenMobile.activeMode().resolveHeight(ToolscreenMobile.nativeHeight()));
        cir.setReturnValue((int) Math.ceil(height / this.scaleFactor));
    }
}
