package neiaddon.emi;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import neiaddon.EmiCompat;
import neiaddon.NeiAddon;
import neiaddon.NeiAddonJeiPlugin;
import neiaddon.Untranslator;

/**
 * The addon's EMI plugin. Only EMI loads it: on NeoForge through the {@link EmiEntrypoint} annotation, on Fabric through
 * the "emi" entrypoint in fabric.mod.json. It registers the addon's panels (the bookmark panel, the quantity field and
 * the craftables) as EMI exclusion areas, so EMI's sidebars are placed around them, and it hands EMI's cheat mode to
 * the addon. It also hands EMI's recipes to the addon (see {@link EmiBridge}). It adds a generic drag-and-drop handler, so an
 * item dragged from EMI's list onto the bookmark panel is bookmarked (the group under the mouse, else the active group; the
 * panel itself draws nothing for EMI's drag). The favorite heart next to each recipe on
 * EMI's recipe screen is drawn by the addon's own screen events, not by an EMI recipe decorator (EMI
 * draws decorators only in its developer mode): the plugin only sets the hooks (see {@link EmiRecipeButtons}). The recipe
 * search field on EMI's recipe screen is set the same way (see {@link EmiRecipeSearch}). It also adds the Item
 * Untranslator's English names to EMI's search (see {@link Untranslator}). Only the EMI API members that exist in EMI
 * 1.1.22 are used.
 */
@EmiEntrypoint
public final class NeiAddonEmiPlugin implements EmiPlugin {

    private static boolean areasFailed;
    private static boolean dropFailed;

    @Override
    public void register(EmiRegistry registry) {
        try {
            registry.addGenericExclusionArea((screen, consumer) -> {
                if (areasFailed) return;
                try {
                    for (Rect2i r : EmiCompat.areas(screen)) {
                        consumer.accept(new Bounds(r.getX(), r.getY(), r.getWidth(), r.getHeight()));
                    }
                } catch (RuntimeException | LinkageError e) {
                    areasFailed = true;
                    NeiAddon.LOGGER.warn("Could not hand the addon's screen areas to EMI; EMI may cover the addon's panels", e);
                }
            });
            EmiCompat.hooks = new EmiBridge();
            EmiCompat.reloaded();
        } catch (RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("EMI support is off: the addon could not register with EMI", e);
        }
        try {
            registry.addGenericDragDropHandler(new EmiDragDropHandler<Screen>() {

                /** An item dragged from EMI's list onto the bookmark panel is bookmarked, as a drop from JEI's list is. */
                @Override
                public boolean dropStack(Screen screen, EmiIngredient ingredient, int x, int y) {
                    if (dropFailed) return false;
                    try {
                        if (!(screen instanceof AbstractContainerScreen<?>)) return false;
                        if (!(ingredient instanceof EmiStack stack)) return false;
                        ItemStack item = stack.getItemStack();
                        if (item.isEmpty()) return false;
                        return NeiAddonJeiPlugin.dropOnPanel(item, x, y);
                    } catch (RuntimeException | LinkageError e) {
                        dropFailed = true;
                        EmiCompat.panelDropLive = false;
                        NeiAddon.LOGGER.warn("Could not bookmark an item dropped from EMI's list on the bookmark panel; drops from EMI's list are off", e);
                        return false;
                    }
                }

                /** Draws nothing: EMI's own targets are the drop targets, and the bookmark panel adds none to them. */
                @Override
                public void render(Screen screen, EmiIngredient ingredient, GuiGraphics g, int mouseX, int mouseY, float delta) {}
            });
            EmiCompat.panelDropLive = true;
        } catch (RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("Items dragged from EMI's list cannot be bookmarked: the addon could not add its drag-and-drop handler to EMI", e);
        }
        try {
            EmiCompat.recipeScreen = new EmiRecipeButtons();
        } catch (RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("The favorite hearts are off on EMI's recipe screen: the addon could not set up its buttons there", e);
        }
        try {
            EmiCompat.recipeSearch = new EmiRecipeSearch();
        } catch (RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("The recipe search is off on EMI's recipe screen: the addon could not set it up there", e);
        }
        try {
            EmiCompat.layout = new EmiLayout();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("Could not find EMI's item list and buttons; the quantity field and the N/S buttons keep their own places", e);
        }
        try {
            int added = 0;
            for (Map.Entry<Item, String> entry : Untranslator.englishNames().entrySet()) {
                registry.addAlias(EmiStack.of(entry.getKey()), Component.literal(entry.getValue()));
                added++;
            }
            if (added > 0) NeiAddon.LOGGER.info("Registered {} English names with EMI's search", added);
        } catch (RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("Item Untranslator is off in EMI's search: the addon could not add the English names", e);
        }
    }
}
