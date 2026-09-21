package dev.toolscreen.mobile.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.toolscreen.mobile.EyeZoom;
import dev.toolscreen.mobile.ToolscreenMobile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Centres the rendered strip on screen.
 *
 * <p>Minecraft finishes a frame by blitting its main render target to the
 * screen, setting up that blit with a {@code viewport(0, 0, width, height)}
 * call. GL's viewport origin is the <em>bottom-left</em> corner, so once
 * {@code WindowMixin} reports a width narrower than the real surface, the strip
 * is drawn hard against the left edge with the remainder left black.
 *
 * <p>Redirecting that call and shifting its origin puts the strip wherever
 * {@code align} asks for. Width and height pass through untouched, so the
 * projection set up just above still matches the quad being drawn — only its
 * position on the real surface moves.
 *
 * <h2>Why this exact target</h2>
 *
 * Verified by disassembling the remapped 1.16.1 jar rather than assumed, after
 * a first attempt crashed on load with "Scanned 0 target(s)":
 *
 * <ul>
 *   <li>The call lives in {@code drawInternal}, not {@code draw} — {@code draw}
 *       only defers to a render-call lambda.</li>
 *   <li>It is {@code GlStateManager.viewport}, not {@code RenderSystem.viewport}.</li>
 *   <li>{@code Framebuffer.bind} calls {@code viewport} too, and that one must
 *       <em>not</em> be touched: it sets up rendering <em>into</em> the
 *       framebuffer at texture size. Targeting {@code drawInternal} specifically
 *       leaves it alone.</li>
 * </ul>
 */
@Mixin(Framebuffer.class)
public abstract class FramebufferMixin {

    /**
     * {@code require = 0} deliberately: centring is cosmetic, and a mismatched
     * target here previously crashed the game on load. Mixin aborts startup
     * when a required injector finds no target, which is the right behaviour
     * for the core size override but far too harsh for positioning. If this
     * ever stops matching, the game launches and renders left-aligned instead.
     */
    @Redirect(
            method = "drawInternal(IIZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;viewport(IIII)V"),
            require = 0)
    private void toolscreen$offsetBlitViewport(int x, int y, int width, int height) {
        ToolscreenMobile.noteCenteringActive();
        lastBlitX = x + ToolscreenMobile.offsetX();
        lastBlitY = y + ToolscreenMobile.offsetY();
        lastBlitW = width;
        lastBlitH = height;
        GlStateManager.viewport(lastBlitX, lastBlitY, lastBlitW, lastBlitH);
    }

    /**
     * Draws the EyeZoom panel into the letterboxed area, once the strip has
     * been blitted to the screen.
     *
     * <p>This is the only point in the frame where the full surface can be
     * drawn to. Everything before it goes into Minecraft's own framebuffer,
     * which is the strip itself, so it can never reach the black.
     *
     * <p>Guarded on the blit matching the window's reported framebuffer size,
     * to pick out the main pass rather than any other framebuffer that happens
     * to be drawn - shader effects use this same method.
     */
    @Inject(method = "drawInternal(IIZ)V", at = @At("TAIL"), require = 0)
    private void toolscreen$drawSidePanel(int width, int height, boolean bl, CallbackInfo ci) {
        if (!ToolscreenMobile.isOverrideActive()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) return;
        if (width != client.getWindow().getFramebufferWidth()) return;
        EyeZoom.renderBackground(lastBlitX, lastBlitY, lastBlitW, lastBlitH);
        EyeZoom.renderSide(client.textRenderer, lastBlitX, lastBlitY, lastBlitW, lastBlitH);
    }

    @Unique private int lastBlitX;
    @Unique private int lastBlitY;
    @Unique private int lastBlitW;
    @Unique private int lastBlitH;
}
