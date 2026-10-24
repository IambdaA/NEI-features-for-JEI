package neiaddon;

import java.util.List;
import java.util.function.Predicate;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** JEI entry point. Registers NEI-style information pages as JEI ingredient info. */
@JeiPlugin
public class NeiAddonJeiPlugin implements IModPlugin {

    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath(NeiAddon.MODID, "main");
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        List<InfoPages.Page> pages = InfoPages.load();
        int count = 0;
        for (InfoPages.Page page : pages) {
            Predicate<Item> filter = InfoPages.compile(page.filter());
            if (filter == null) continue;
            List<ItemStack> matches = BuiltInRegistries.ITEM.stream()
                .filter(filter)
                .map(Item::getDefaultInstance)
                .filter(s -> !s.isEmpty())
                .toList();
            if (matches.isEmpty()) continue;
            registration.addItemStackInfo(matches, InfoPages.components(page.text()));
            count++;
        }
        NeiAddon.LOGGER.info("Registered {} NEI-style info pages with JEI", count);
    }

    /** NEI's favorite (heart) button on every recipe. */
    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        registration.addRecipeButtonFactory(new RecipeHearts.Factory());
        registration.addRecipeButtonFactory(new TreeRecipes.Factory());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        Calculator.onRuntime(jeiRuntime);
    }

    @Override
    public void onRuntimeUnavailable() {
        Calculator.onRuntime(null);
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // keep JEI's own overlays out of the bookmark panel
        registration.addGlobalGuiHandler(new IGlobalGuiHandler() {

            @Override
            public Collection<Rect2i> getGuiExtraAreas() {
                List<Rect2i> areas = new ArrayList<>(BookmarkPanel.extraAreas());
                areas.addAll(QuantityField.extraAreas());
                return areas;
            }
        });
        // drag items from JEI's list onto a group (or anywhere on the panel = the active group)
        registration.addGhostIngredientHandler((Class) AbstractContainerScreen.class, new BookmarkDropHandler());
    }

    private static final class BookmarkDropHandler implements IGhostIngredientHandler<AbstractContainerScreen<?>> {

        @Override
        public <I> java.util.List<Target<I>> getTargetsTyped(AbstractContainerScreen<?> gui, ITypedIngredient<I> ingredient,
            boolean doStart) {
            java.util.List<Target<I>> targets = new ArrayList<>();
            if (ingredient.getItemStack().isEmpty()) return targets;
            Bookmarks.ensureLoaded();
            for (BookmarkPanel.GroupArea area : BookmarkPanel.groupAreas()) {
                targets.add(target(area.rect(), area.groupId()));
            }
            // whole panel as a fallback, listed last so group headers win
            targets.add(target(BookmarkPanel.panelRect(), Bookmarks.NO_GROUP));
            return targets;
        }

        private static <I> Target<I> target(Rect2i rect, int groupId) {
            return new Target<I>() {

                @Override
                public Rect2i getArea() {
                    return rect;
                }

                @Override
                public void accept(I ingredient) {
                    if (ingredient instanceof net.minecraft.world.item.ItemStack stack) {
                        Bookmarks.add(stack, 1, groupId);
                    }
                }
            };
        }

        /** JEI would paint the whole panel green whenever an item is merely hovered; only show targets while dragging. */
        @Override
        public boolean shouldHighlightTargets() {
            return false;
        }

        @Override
        public void onComplete() {}
    }
}
