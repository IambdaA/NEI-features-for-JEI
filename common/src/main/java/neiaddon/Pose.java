package neiaddon;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A 2D-style view of GuiGraphics' PoseStack (26.x has a flat Matrix3x2fStack; this keeps the same method names so the
 * drawing code reads the same on both versions). 1.21.1 draws with a depth test: things drawn later must sit nearer
 * (higher z) than what is under them, so the layer helpers below move drawing forward.
 */
final class Pose {

    /** Items are drawn 150 above the pose; counts and decorations at 200. This is "in front of an item". */
    static final float OVER = 250f;
    /** Vanilla tooltips are drawn at z 400. */
    static final float TOOLTIP = 400f;

    private final PoseStack stack;

    private Pose(PoseStack stack) {
        this.stack = stack;
    }

    static Pose of(GuiGraphics g) {
        return new Pose(g.pose());
    }

    void pushMatrix() {
        stack.pushPose();
    }

    void popMatrix() {
        stack.popPose();
    }

    /** Resets to no translation or scale. */
    void identity() {
        stack.last()
            .pose()
            .identity();
        stack.last()
            .normal()
            .identity();
    }

    void translate(float x, float y) {
        stack.translate(x, y, 0f);
    }

    void translateZ(float z) {
        stack.translate(0f, 0f, z);
    }

    void scale(float x, float y) {
        stack.scale(x, y, 1f);
    }

    /** Moves everything drawn from now on {@code z} forward, until {@link #end}. */
    static void begin(GuiGraphics g, float z) {
        Pose pose = of(g);
        pose.pushMatrix();
        pose.translateZ(z);
    }

    static void end(GuiGraphics g) {
        of(g).popMatrix();
    }

    /** Runs {@code draw} in front of items (counts, highlights, badges). */
    static void over(GuiGraphics g, Runnable draw) {
        Pose pose = of(g);
        pose.pushMatrix();
        pose.translateZ(OVER);
        try {
            draw.run();
        } finally {
            pose.popMatrix();
        }
    }
}
