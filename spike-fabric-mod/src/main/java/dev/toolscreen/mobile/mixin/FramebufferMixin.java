package dev.toolscreen.mobile.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.toolscreen.mobile.ToolscreenMobile;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Centres the rendered strip on screen.
 *
 * <p>Minecraft finishes a frame by blitting its main render target to the
 * screen, and sets up that blit with {@code RenderSystem.viewport(0, 0, width,
 * height)}. GL's viewport origin is the <em>bottom-left</em> corner, so once
 * {@code WindowMixin} reports a width narrower than the real surface, the strip
 * is drawn hard against the left edge with the remainder left black.
 *
 * <p>Redirecting that single call and shifting its origin puts the strip
 * wherever {@code align} asks for. The width and height passed through are
 * untouched, so the projection set up just above still matches the quad being
 * drawn — only its position on the real surface moves.
 */
@Mixin(Framebuffer.class)
public abstract class FramebufferMixin {

    @Redirect(
            method = "draw(IIZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;viewport(IIII)V"))
    private void toolscreen$offsetBlitViewport(int x, int y, int width, int height) {
        RenderSystem.viewport(x + ToolscreenMobile.offsetX(), y + ToolscreenMobile.offsetY(), width, height);
    }
}
