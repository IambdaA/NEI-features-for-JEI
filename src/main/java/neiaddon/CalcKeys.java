package neiaddon;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Optional;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/** Key bindings with NEI's defaults: A add bookmark, T crafting tree (calculator), B hide the panel. */
@EventBusSubscriber(modid = NeiAddon.MODID, value = Dist.CLIENT)
public final class CalcKeys {

    static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath(NeiAddon.MODID, "main"));

    // NEI's defaults: bookmark.add = A, bookmark.open_crafting_tree = T, bookmark.hide = B
    public static final KeyMapping ADD = new KeyMapping("key.neiaddon.bookmark_add", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_A, CATEGORY);
    public static final KeyMapping TREE = new KeyMapping("key.neiaddon.open_crafting_tree", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_T, CATEGORY);
    // NEI: bookmark.favorite = F (Shift+F over an item of the list saves its favorite recipe tree)
    public static final KeyMapping FAVORITE = new KeyMapping("key.neiaddon.bookmark_favorite", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, CATEGORY);
    /** NEI's gui.craft_items: with Shift, autocraft (Ctrl: only what is missing). */
    public static final KeyMapping CRAFT = new KeyMapping("key.neiaddon.craft_items", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);
    /** NEI's bookmark.chat_link: with Ctrl, send a link to chat (Ctrl + Shift for a recipe). */
    public static final KeyMapping CHAT_LINK = new KeyMapping("key.neiaddon.chat_link", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, CATEGORY);
    /** NEI's bookmark.pull_items: pull the hovered item (or group) out of the open chest; Shift: only what is missing. */
    public static final KeyMapping PULL = new KeyMapping("key.neiaddon.pull_items", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
    public static final KeyMapping HIDE = new KeyMapping("key.neiaddon.hide_bookmarks", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY);

    // NEI: world.highlight_tips = Numpad 0 (toggle). The position screen has no NEI key; it is unbound by default.
    public static final KeyMapping HUD_TOGGLE = new KeyMapping("key.neiaddon.highlight_tips", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_0, CATEGORY);
    public static final KeyMapping HUD_POSITION = new KeyMapping("key.neiaddon.highlight_tips_position", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    private static java.lang.reflect.Method setBookmarkEnabled;
    private static java.lang.reflect.Method isBookmarkEnabled;
    private static Object toggleState;
    private static boolean toggleFailed;

    private CalcKeys() {}

    @SubscribeEvent
    static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(ADD);
        event.register(TREE);
        event.register(FAVORITE);
        event.register(CRAFT);
        event.register(CHAT_LINK);
        event.register(PULL);
        event.register(HIDE);
        event.register(HUD_TOGGLE);
        event.register(HUD_POSITION);
        event.register(ItemZoom.TOGGLE);
        event.register(ItemZoom.HOLD);
        event.register(ItemZoom.ZOOM_IN);
        event.register(ItemZoom.ZOOM_OUT);
    }

    /**
     * JEI has its own bookmark overlay (and switches it back on whenever its list is non-empty). This addon replaces
     * it, so keep it off. Uses JEI internals by reflection; if that fails, JEI's overlay just stays.
     */
    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        while (HUD_TOGGLE.consumeClick()) {
            AddonConfig.HUD_ENABLED.set(!AddonConfig.HUD_ENABLED.get());
            AddonConfig.HUD_ENABLED.save();
        }
        while (HUD_POSITION.consumeClick()) {
            HudPositionScreen.open();
        }
        if (toggleFailed) return;
        try {
            if (toggleState == null) {
                Class<?> internal = Class.forName("mezz.jei.common.Internal");
                toggleState = internal.getMethod("getClientToggleState")
                    .invoke(null);
                isBookmarkEnabled = toggleState.getClass()
                    .getMethod("isBookmarkEnabled");
                setBookmarkEnabled = toggleState.getClass()
                    .getMethod("setBookmarkEnabled", boolean.class);
            }
            if ((boolean) isBookmarkEnabled.invoke(toggleState)) {
                setBookmarkEnabled.invoke(toggleState, false);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            toggleFailed = true;
            NeiAddon.LOGGER.warn("Could not switch off JEI's bookmark overlay; both panels will show", e);
        }
    }

    static boolean typing() {
        return QuantityField.focused() || Calculator.runtime != null && Calculator.runtime.getIngredientListOverlay()
            .hasKeyboardFocus();
    }

    /** While a screen (inventory, JEI recipes...) is open. */
    @SubscribeEvent
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();
        if (screen instanceof CalculatorScreen || typing()) return;
        if (screen instanceof CraftingTreeScreen tree && tree.searchFocused()) return;
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(event.getKeyCode());
        boolean shift = Minecraft.getInstance().hasShiftDown();
        int code = event.getKeyCode();
        if (!shift && Minecraft.getInstance().hasControlDown() && (code == GLFW.GLFW_KEY_C || code == GLFW.GLFW_KEY_X || code == GLFW.GLFW_KEY_D)) {
            // NEI: Ctrl+C copies the item name, Ctrl+X the item id, Ctrl+D the ore dictionary names (here: tags)
            ItemStack copy = hoveredStack(screen);
            if (!copy.isEmpty()) {
                String text = code == GLFW.GLFW_KEY_C ? copy.getHoverName()
                    .getString()
                    : code == GLFW.GLFW_KEY_D ? tagsOf(copy)
                        : BuiltInRegistries.ITEM.getKey(copy.getItem())
                            .toString();
                Minecraft.getInstance().keyboardHandler.setClipboard(text);
                event.setCanceled(true);
                return;
            }
        }
        Optional<ItemStack> listed = HotkeyTips.hoveredInList();
        if (Minecraft.getInstance().hasControlDown() && CHAT_LINK.isActiveAndMatches(key)) {
            // NEI sendRecipeInChatLink: Ctrl+Shift sends the hovered (or favorite) recipe, otherwise the item
            ItemStack stack = hoveredStack(screen);
            if (!stack.isEmpty()) {
                String payload = null;
                if (shift) {
                    RecipeData recipe = RecipeHearts.sharedRecipe(stack, listed.isPresent());
                    if (recipe != null) payload = Bookmarks.shareRecipe(recipe, RecipeData.idOf(stack));
                }
                if (payload == null) {
                    payload = Bookmarks.shareItem(stack, listed.isPresent() ? QuantityField.scrollMultiplier(stack) : Math.max(1, stack.getCount()));
                }
                if (ShareClient.send(payload)) {
                    event.setCanceled(true);
                    return;
                }
            }
        }
        if (shift && listed.isPresent() && FAVORITE.isActiveAndMatches(key)) {
            // NEI saveFavoriteTree: only over the item list; the key is used up even when the item has no favorite
            RecipeHearts.onShiftFavoriteKey(listed.get());
            event.setCanceled(true);
            return;
        }
        if (shift && CRAFT.isActiveAndMatches(key)) {
            // NEI runAutoCrafting over an item that has a favorite recipe; Ctrl keeps what the inventory already holds
            ItemStack stack = hoveredStack(screen);
            if (!stack.isEmpty() && (CraftablesPanel.autocraft(stack, !Minecraft.getInstance().hasControlDown())
                || RecipeHearts.autocraftFavorite(stack, !Minecraft.getInstance().hasControlDown()))) {
                event.setCanceled(true);
                return;
            }
        }
        if (PULL.isActiveAndMatches(key) && BookmarkPanel.hoveredStack().isEmpty()) {
            // NEI pullRecipeItems over anything but a bookmark: the quantity field's amount of the item
            ItemStack stack = hoveredStack(screen);
            if (!stack.isEmpty() && Pull.pullItem(stack, QuantityField.scrollMultiplier(stack), shift)) {
                event.setCanceled(true);
                return;
            }
        }
        if (shift && TREE.isActiveAndMatches(key) && RecipeHearts.onShiftTreeKey()) {
            // NEI: Shift + the tree key over a heart saves the recipe tree and opens it
            event.setCanceled(true);
        } else if (ADD.isActiveAndMatches(key)) {
            if (shift && RecipeHearts.onShiftBookmarkKey()) {
                // NEI: Shift + the bookmark key over a heart saves a recipe tree, over a recipe saves just that recipe
                event.setCanceled(true);
                return;
            }
            if (shift && listed.isPresent() && RecipeHearts.onShiftListItem(listed.get())) {
                // NEI saveRecipeInBookmark with saveIngredients: the item's favorite recipe
                event.setCanceled(true);
                return;
            }
            ItemStack stack = hoveredStack(screen);
            if (!stack.isEmpty()) {
                // NEI bookmark.add toggles: an item that is already bookmarked is removed. Ctrl keeps the stack size.
                long amount = !Minecraft.getInstance().hasControlDown() ? 1
                    : listed.isPresent() ? QuantityField.scrollMultiplier(stack) : Math.max(1, stack.getCount());
                Bookmarks.toggle(stack, amount);
                event.setCanceled(true);
            }
        } else if (HIDE.isActiveAndMatches(key) && BookmarkPanel.supports(screen)) {
            BookmarkPanel.toggleHidden();
            event.setCanceled(true);
        }
    }

    /** NEI's copy.oredict, with item tags in place of the ore dictionary: comma separated, sorted. */
    private static String tagsOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.wrapAsHolder(stack.getItem())
            .tags()
            .map(
                t -> t.location()
                    .toString())
            .sorted()
            .collect(java.util.stream.Collectors.joining(","));
    }

    /** What the zoom may show: the bookmark panel, the craftables panel and the JEI item list (no inventory slots, no recipes). */
    static ItemStack hoveredStack(Screen screen, boolean overlaysOnly) {
        if (!overlaysOnly) return hoveredStack(screen);
        ItemStack fromPanel = BookmarkPanel.hoveredStack();
        if (!fromPanel.isEmpty()) return fromPanel;
        ItemStack fromCraftables = CraftablesPanel.hoveredStack();
        if (!fromCraftables.isEmpty()) return fromCraftables;
        return HotkeyTips.hoveredInList()
            .orElse(ItemStack.EMPTY);
    }

    private static ItemStack hoveredStack(Screen screen) {
        ItemStack fromPanel = BookmarkPanel.hoveredStack();
        if (!fromPanel.isEmpty()) return fromPanel;
        ItemStack fromCraftables = CraftablesPanel.hoveredStack();
        if (!fromCraftables.isEmpty()) return fromCraftables;
        if (screen instanceof CraftingTreeScreen tree) return tree.hoveredStack();
        if (Calculator.runtime != null) {
            Optional<ItemStack> fromList = Calculator.runtime.getIngredientListOverlay()
                .getIngredientUnderMouse()
                .flatMap(ITypedIngredient::getItemStack);
            if (fromList.isPresent()) return fromList.get();
            Optional<ItemStack> fromRecipes = Calculator.runtime.getRecipesGui()
                .getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
            if (fromRecipes.isPresent()) return fromRecipes.get();
        }
        if (screen instanceof AbstractContainerScreen<?> container) {
            Slot slot = container.getHoveredSlot();
            if (slot != null && slot.hasItem()) return slot.getItem();
        }
        return ItemStack.EMPTY;
    }
}
