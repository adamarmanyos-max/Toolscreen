package dev.toolscreen.mobile.mixin;

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

    @Inject(method = "getFramebufferWidth", at = @At("HEAD"), cancellable = true)
    private void toolscreen$overrideFramebufferWidth(CallbackInfoReturnable<Integer> cir) {
        // Recorded unconditionally: once the getters start lying, this is the
        // only place the real surface size is still visible, and FramebufferMixin
        // needs it to work out the centring offset.
        ToolscreenMobile.recordNativeSize(this.framebufferWidth, this.framebufferHeight);
        if (!ToolscreenMobile.isOverrideActive()) return;
        Mode mode = ToolscreenMobile.activeMode();
        cir.setReturnValue(mode.resolveWidth(this.framebufferWidth));
    }

    @Inject(method = "getFramebufferHeight", at = @At("HEAD"), cancellable = true)
    private void toolscreen$overrideFramebufferHeight(CallbackInfoReturnable<Integer> cir) {
        ToolscreenMobile.recordNativeSize(this.framebufferWidth, this.framebufferHeight);
        if (!ToolscreenMobile.isOverrideActive()) return;
        Mode mode = ToolscreenMobile.activeMode();
        cir.setReturnValue(mode.resolveHeight(this.framebufferHeight));
    }
}
