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
 * Puts the rendered strip on screen: where {@code align} asks, and in Eye
 * Measure as a 1:1 crop of a framebuffer far taller than the screen.
 *
 * <h2>How the crop is made</h2>
 *
 * Minecraft finishes a frame with {@code drawInternal}, which sets an
 * orthographic projection the size of the framebuffer, a viewport the same size,
 * and draws one textured quad over it. Showing only a slice used to be done by
 * leaving the quad alone and handing GL a viewport as tall as the framebuffer -
 * sixteen thousand rows - positioned so its middle landed on the screen.
 *
 * <p>The driver clamped that viewport's height. The quad was squashed into the
 * clamped height, which read as a horizontal stretch of about 1.52, and the row
 * that landed at screen centre moved, which needed a crop of 0.327 to undo.
 * Those two numbers are the same fault: 0.5 / 1.52 = 0.328. The stretch had been
 * measured off a screenshot and the crop found by hand, independently.
 *
 * <p>Now every viewport is screen-sized. The slice is chosen with the
 * projection instead: its vertical range is set to just the rows that should
 * show, so the rest of the quad falls outside clip space and is cut there, by
 * arithmetic rather than by a limit. There is nothing oversized left to clamp.
 *
 * <h2>Why these exact targets</h2>
 *
 * Verified from the remapped 1.16.1 jar's bytecode: {@code drawInternal} makes
 * one {@code GlStateManager.ortho(DDDDDD)V} call and then one
 * {@code GlStateManager.viewport(IIII)V}. {@code Framebuffer.bind} also calls
 * {@code viewport} and must not be touched - it sets up rendering into the
 * framebuffer - which targeting {@code drawInternal} leaves alone.
 */
@Mixin(Framebuffer.class)
public abstract class FramebufferMixin {

    /**
     * The projection for the final blit. In Eye Measure its vertical range is
     * narrowed to the rows that should be visible; everything else passes
     * through.
     *
     * <p>In {@code drawInternal}'s projection a vertex at y = height is the
     * framebuffer's bottom row and y = 0 its top, so framebuffer row r (counted
     * from the bottom) sits at y = height - r. Setting the bottom and top of the
     * projection to the y of the lowest and highest visible rows maps exactly
     * those rows onto the viewport, one to one.
     */
    @Redirect(
            method = "drawInternal(IIZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;ortho(DDDDDD)V"),
            require = 0)
    private void toolscreen$cropBlitProjection(double left, double right, double bottom, double top,
                                              double near, double far) {
        int height = (int) Math.round(bottom);
        if (!toolscreen$croppingMainPass((int) Math.round(right), height)) {
            GlStateManager.ortho(left, right, bottom, top, near, far);
            return;
        }
        int visible = toolscreen$visibleRows(height);
        int firstRow = toolscreen$firstVisibleRow(height, visible);
        GlStateManager.ortho(left, right, height - firstRow, height - firstRow - visible, near, far);
    }

    /**
     * The viewport for the final blit: offset for {@code align}, and in Eye
     * Measure screen-sized and scaled across by the main-screen stretch.
     *
     * <p>{@code require = 0}: positioning is cosmetic, and a mismatched target
     * here once crashed the game on load. If this ever stops matching, the game
     * launches and draws left-aligned instead.
     */
    @Redirect(
            method = "drawInternal(IIZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;viewport(IIII)V"),
            require = 0)
    private void toolscreen$offsetBlitViewport(int x, int y, int width, int height) {
        ToolscreenMobile.noteCenteringActive();
        int stripX = x + ToolscreenMobile.offsetX();

        if (!toolscreen$croppingMainPass(width, height)) {
            lastBlitX = stripX;
            lastBlitY = y + ToolscreenMobile.offsetY();
            lastBlitW = width;
            lastBlitH = height;
            GlStateManager.viewport(lastBlitX, lastBlitY, lastBlitW, lastBlitH);
            return;
        }

        int screenH = ToolscreenMobile.nativeHeight();
        int visible = toolscreen$visibleRows(height);

        // The stretch scales the drawn width about the strip's own centre, so
        // the crosshair stays where it was; the recorded rectangle follows, so
        // the background and the clone panel's overlap guard see the width the
        // window is actually drawn at.
        int drawnW = (int) Math.round(width * ToolscreenMobile.mainStretch());
        if (drawnW < 2) drawnW = width;
        int drawnX = stripX + (width - drawnW) / 2;
        int drawnY = Math.max(0, (screenH - visible) / 2);

        lastBlitX = drawnX;
        lastBlitY = drawnY;
        lastBlitW = drawnW;
        lastBlitH = visible;

        ToolscreenMobile.noteCrop(screenH, height,
                toolscreen$firstVisibleRow(height, visible) + visible / 2);
        GlStateManager.viewport(drawnX, drawnY, drawnW, visible);
    }

    /**
     * True for the main pass in Eye Measure, the only blit that is cropped.
     *
     * <p>Picked out by size: shader effects blit through this same method with
     * their own framebuffers and must be left alone.
     */
    @Unique
    private static boolean toolscreen$croppingMainPass(int width, int height) {
        if (!ToolscreenMobile.isOverrideActive() || !ToolscreenMobile.eyeZoomActive()) return false;
        if (ToolscreenMobile.nativeHeight() < 2) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) return false;
        return width == client.getWindow().getFramebufferWidth()
                && height == client.getWindow().getFramebufferHeight();
    }

    /** Framebuffer rows that fit on screen: all of them, or one per screen row. */
    @Unique
    private static int toolscreen$visibleRows(int height) {
        return Math.max(1, Math.min(height, ToolscreenMobile.nativeHeight()));
    }

    /**
     * Lowest visible framebuffer row, counted from the bottom: the crop centre
     * row minus half a screen, kept inside the framebuffer.
     */
    @Unique
    private static int toolscreen$firstVisibleRow(int height, int visible) {
        int centreRow = (int) Math.round(height * ToolscreenMobile.cropCentre());
        int first = centreRow - visible / 2;
        return Math.max(0, Math.min(height - visible, first));
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
        EyeZoom.renderSide(lastBlitX, lastBlitY, lastBlitW, lastBlitH);
    }

    /**
     * Paints the background across the surface, before the strip is blitted
     * over it.
     *
     * <p>HEAD rather than TAIL, deliberately. Filling afterwards means the fill
     * must agree exactly with where the strip landed, and a wrong number hides
     * the game. Filling underneath cannot: whatever this paints, the blit
     * covers the middle of it a moment later.
     */
    @Inject(method = "drawInternal(IIZ)V", at = @At("HEAD"), require = 0)
    private void toolscreen$drawBackground(int width, int height, boolean bl, CallbackInfo ci) {
        if (!ToolscreenMobile.isOverrideActive()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) return;
        if (width != client.getWindow().getFramebufferWidth()) return;
        EyeZoom.renderBackground(lastBlitX, lastBlitY, lastBlitW, lastBlitH);
    }

    @Unique private int lastBlitX;
    @Unique private int lastBlitY;
    @Unique private int lastBlitW;
    @Unique private int lastBlitH;
}
