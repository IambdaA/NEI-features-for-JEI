package neiaddon.event;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** An item's tooltip is being built; the list may be changed. */
public final class ItemTooltipEvent extends Event {

    private final ItemStack stack;
    private final List<Component> toolTip;

    public ItemTooltipEvent(ItemStack stack, List<Component> toolTip) {
        this.stack = stack;
        this.toolTip = toolTip;
    }

    public ItemStack getItemStack() {
        return stack;
    }

    public List<Component> getToolTip() {
        return toolTip;
    }
}
