package dev.toolscreen.mobile.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.toolscreen.mobile.ToolscreenMobile;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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

    @Redirect(
            method = "drawInternal(IIZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;viewport(IIII)V"))
    private void toolscreen$offsetBlitViewport(int x, int y, int width, int height) {
        GlStateManager.viewport(x + ToolscreenMobile.offsetX(), y + ToolscreenMobile.offsetY(), width, height);
    }
}
