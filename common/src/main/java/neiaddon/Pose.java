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

    /**
     * Runs a recipe picture's drawing (another mod's recipe category can draw anything) in front of everything and
     * keeps what it does from leaking: the pose it leaves unbalanced or moved, and render state like the depth test
     * (a leaked pose or depth state drew the bookmarks after a GregTech recipe over the item on the cursor).
     */
    static void isolated(GuiGraphics g, Runnable draw) {
        PoseStack ps = g.pose();
        PoseStack.Pose top = ps.last();
        org.joml.Matrix4f matrix = new org.joml.Matrix4f(top.pose());
        org.joml.Matrix3f normal = new org.joml.Matrix3f(top.normal());
        g.flush();
        ps.pushPose();
        // above the items (150 to 250) and the tooltip box (400) the picture sits in
        ps.translate(0f, 0f, 150f);
        try {
            draw.run();
        } finally {
            g.flush();
            // pops a push the drawing left open
            for (int i = 0; i < 64 && ps.last() != top && !ps.clear(); i++) ps.popPose();
            top.pose().set(matrix);
            top.normal().set(normal);
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthFunc(515);
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
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
