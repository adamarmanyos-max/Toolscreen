package dev.toolscreen.mobile.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.toolscreen.mobile.EyeZoom;
import dev.toolscreen.mobile.Layout;
import dev.toolscreen.mobile.ToolscreenMobile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the tall framebuffer on screen at 1:1, and draws everything around it.
 *
 * <h2>Why the blit is a crop, not a fit</h2>
 *
 * The framebuffer is 16384 rows tall and the screen is under two thousand.
 * Scaling that down to fit would shrink everything on it by the same factor,
 * which is exactly what the extra render height was bought to avoid: the whole
 * point is that a given angle now covers more pixels, and a fit throws every one
 * of them away again. It also makes the view look eight times smaller than the
 * original's, which is what a screenshot showed.
 *
 * <p>So the viewport is set to the framebuffer's full size, positioned so its
 * centre lands on the centre of the game window. GL clips what falls outside the
 * screen, and what remains is the middle slice at one framebuffer pixel per
 * screen pixel - the same thing Toolscreen gets on Windows by making the OS
 * window taller than the monitor.
 *
 * <h2>Why this exact target</h2>
 *
 * Verified by disassembling the remapped 1.16.1 jar, after a first attempt
 * crashed on load with "Scanned 0 target(s)": the call is in
 * {@code drawInternal}, not {@code draw}, and it is {@code GlStateManager
 * .viewport}, not {@code RenderSystem.viewport}. {@code Framebuffer.bind} calls
 * {@code viewport} too and must not be touched - that one sets up rendering
 * <em>into</em> the framebuffer at texture size.
 */
@Mixin(Framebuffer.class)
public abstract class FramebufferMixin {

    @Redirect(
            method = "drawInternal(IIZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;viewport(IIII)V"),
            require = 0)
    private void toolscreen$cropBlitViewport(int x, int y, int width, int height) {
        Layout layout = toolscreen$layout(width);
        if (layout == null) {
            GlStateManager.viewport(x, y, width, height);
            return;
        }
        ToolscreenMobile.noteCenteringActive();

        // The framebuffer drawn at its own size, centred on the game window.
        // Both offsets are usually negative for the tall axis; that is the
        // intent, and GL clips the overhang.
        int vx = layout.gameX + (layout.gameWidth - width) / 2;
        int vy = (layout.screenHeight - height) / 2;
        GlStateManager.viewport(vx, vy, width, height);
    }

    @Inject(method = "drawInternal(IIZ)V", at = @At("HEAD"), require = 0)
    private void toolscreen$drawBackground(int width, int height, boolean bl, CallbackInfo ci) {
        Layout layout = toolscreen$layout(width);
        if (layout == null) return;
        EyeZoom.renderBackground(new MatrixStack(), layout);
    }

    @Inject(method = "drawInternal(IIZ)V", at = @At("TAIL"), require = 0)
    private void toolscreen$drawPanel(int width, int height, boolean bl, CallbackInfo ci) {
        Layout layout = toolscreen$layout(width);
        if (layout == null) return;
        ToolscreenMobile.notePanelGeometry(layout);
        EyeZoom.render(new MatrixStack(), layout);
    }

    /**
     * The layout for this blit, or null if this is not the main pass.
     *
     * <p>Guarded on the width matching the window's reported framebuffer width,
     * because shader effects blit through this same method and must be left
     * alone.
     */
    @Unique
    private Layout toolscreen$layout(int width) {
        if (!ToolscreenMobile.eyeZoomActive()) return null;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) return null;
        if (width != client.getWindow().getFramebufferWidth()) return null;
        return ToolscreenMobile.layout();
    }

}
