package neiaddon.event;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A tooltip is about to be drawn; nothing of it is drawn yet (NeoForge's RenderTooltipEvent.Pre). */
public final class RenderTooltipEvent extends Event {

    private final GuiGraphicsExtractor graphics;

    public RenderTooltipEvent(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
    }

    public GuiGraphicsExtractor getGuiGraphics() {
        return graphics;
    }
}
