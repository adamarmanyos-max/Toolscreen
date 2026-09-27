package dev.toolscreen.mobile;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.LiteralText;

/**
 * The angle-per-pixel arithmetic, and a report that checks it against reality.
 *
 * <h2>The relation being checked</h2>
 *
 * Minecraft's field-of-view setting is <em>vertical</em>, so the focal length in
 * pixels is fixed by the render height alone:
 *
 * <pre>  f = (renderHeight / 2) / tan(fovVertical / 2)</pre>
 *
 * A horizontal rotation of {@code theta} moves a distant point by
 * {@code f * tan(theta)} pixels across the framebuffer, and one ruler cell is
 * one of those pixels. Nothing in it refers to the screen, the window, the panel
 * or the zoom - which is the point of the check. If the observed count tracks
 * the prediction, the render really is as tall as the config claims and the
 * ruler really is counting framebuffer pixels; if it is out by a constant
 * factor, that factor is the ratio between the real render height and the
 * configured one.
 */
public final class Measurement {

    /** Yaw nudges the report predicts for, in degrees. */
    private static final double[] PROBES = {0.01, 0.02, 0.05, 0.1, 0.25};

    private Measurement() {
    }

    /** Focal length in framebuffer pixels. */
    public static double focalLength(int renderHeight, double fovVerticalDegrees) {
        double half = Math.toRadians(fovVerticalDegrees) / 2.0;
        if (half <= 0.0 || half >= Math.PI / 2) return 0.0;
        return (renderHeight / 2.0) / Math.tan(half);
    }

    /** Framebuffer pixels a distant point moves for a yaw change, in degrees. */
    public static double pixelsForYaw(int renderHeight, double fovVerticalDegrees, double yawDegrees) {
        double f = focalLength(renderHeight, fovVerticalDegrees);
        return f * Math.tan(Math.toRadians(yawDegrees));
    }

    /**
     * Prints the prediction table into chat and the log.
     *
     * <p>Only the prediction: the observed half has to be read off the ruler by
     * eye, because nothing in the game knows where the eye's sprite lands. The
     * report therefore also prints the exact {@code /tp} needed to set up the
     * test, so the two halves are comparable rather than approximately aligned.
     */
    public static void report(MinecraftClient client) {
        int height = ToolscreenMobile.renderHeight();
        int width = ToolscreenMobile.renderWidth();
        double fov = client.options.fov;

        StringBuilder out = new StringBuilder();
        out.append("Toolscreen measurement check\n");
        out.append(String.format("  render %dx%d, vertical FOV %.1f deg\n", width, height, fov));

        if (height < 2 || fov <= 0.0) {
            out.append("  no render resolution recorded yet - enter a mode first");
            send(client, out.toString());
            return;
        }

        double f = focalLength(height, fov);
        out.append(String.format("  f = (%d/2) / tan(%.1f/2) = %.1f px\n", height, fov, f));

        if (client.player != null) {
            double yaw = client.player.getYaw(1.0F);
            out.append(String.format("  current yaw %.4f - reset with:\n", yaw));
            out.append(String.format("    /tp @s ~ ~ ~ %.4f %.4f\n", yaw, client.player.getPitch(1.0F)));
        }

        out.append("  yaw nudge -> predicted cells (1 cell = 1 framebuffer pixel):\n");
        for (double probe : PROBES) {
            double pixels = pixelsForYaw(height, fov, probe);
            out.append(String.format("    %+.3f deg -> %.2f px\n", probe, pixels));
        }
        out.append("  Stand still, note the eye's cell, re-run /tp with yaw + nudge,\n");
        out.append("  then read the new cell. The difference should match the table.");

        send(client, out.toString());
    }

    private static void send(MinecraftClient client, String text) {
        for (String line : text.split("\n")) {
            ToolscreenMobile.LOGGER.info("[{}] {}", ToolscreenMobile.MOD_ID, line);
            if (client.player != null) {
                client.player.sendMessage(new LiteralText(line), false);
            }
        }
    }
}
