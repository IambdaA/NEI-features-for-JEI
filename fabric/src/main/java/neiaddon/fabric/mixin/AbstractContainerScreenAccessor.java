package neiaddon.fabric.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The container screen's own area and hovered slot (NeoForge has public getters for them, vanilla does not). */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("leftPos")
    int neiaddon$leftPos();

    @Accessor("topPos")
    int neiaddon$topPos();

    @Accessor("imageWidth")
    int neiaddon$imageWidth();

    @Accessor("imageHeight")
    int neiaddon$imageHeight();

    @Accessor("hoveredSlot")
    Slot neiaddon$hoveredSlot();
}
