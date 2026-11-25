package neiaddon.emi;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import neiaddon.Bookmarks;
import neiaddon.Calculator;
import neiaddon.EmiCompat;
import neiaddon.Favorites;
import neiaddon.FluidStacks;
import neiaddon.NeiAddon;
import neiaddon.RecipeData;
import neiaddon.RecipeShape;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * EMI's recipes for the addon (see {@link EmiCompat}). EMI blocks JEI's own plugins, so JEI knows no vanilla recipes and
 * none of the mods that have an EMI plugin; the addon asks here instead. A recipe comes from, in this order: the JEI
 * recipe EMI shows through JEMI, the JEI recipe behind an EMI recipe that has a backing recipe, or else EMI's own data.
 * All state is static: the EMI plugin makes a new bridge on every EMI reload.
 */
public final class EmiBridge implements EmiCompat.Hooks {

    /** The step that made the last shape: "jemi", "backing" or "own". Only the test driver reads it (by reflection). */
    static volatile String lastStep;

    private static final String JEMI_RECIPE = "dev.emi.emi.jemi.JemiRecipe";
    /** Categories that are not recipes: tags, ingredient info and the like. */
    private static final Set<String> SKIPPED = Set.of("emi:tag", "emi:ingredient", "emi:resolution", "emi:info");
    /** EMI names these recipe types differently from JEI 15. */
    private static final Map<String, String> ALIASES = Map.of(
        "minecraft:smelting", "minecraft:furnace",
        "minecraft:campfire_cooking", "minecraft:campfire");

    private static Method isLoaded;
    private static boolean loadedFailed;
    private static Field jemiCategory;
    private static Field jemiRecipe;
    private static boolean warnedRecipe;
    private static boolean iconBroken;

    /** A recipe as the bridge maps it: its shape, and the JEI category and recipe behind it (null for EMI's own data). */
    private record Mapped(RecipeShape shape, IRecipeCategory<?> jeiCategory, Object jeiRecipe) {}

    @Override
    public boolean cheatMode() {
        return EmiApi.isCheatMode();
    }

    // #36: EMI's own give, like its Ctrl + click. Only EMI's api is on our compile classpath, so its
    // packet classes are used by reflection like its other internals.
    private static Constructor<?> createItemPacket;
    private static Method emiSendToServer;
    private static Field emiOnServer;
    private static boolean giveFailed;

    /**
     * Gives through EMI's own packet: mode 0 adds the stack to the inventory, mode 1 puts one stack on the cursor
     * ({@code toCursor}, EMI's CreateItemC2SPacket.apply). Its server takes it for operators and creative
     * players, whatever EMI's own (client side) setting says - the same reach EMI's Ctrl + click has. False when the
     * server has no EMI on it (its ping flag: EMI's own clicks give nothing there either), and for good after one
     * failure (one warning is logged). One packet per stack (1.20.1 writes a stack's count as a byte), at most a
     * full inventory's stacks per give as over JEI's list; on the cursor one stack at most.
     */
    @Override
    public boolean giveEmi(ItemStack stack, long count, boolean toCursor) {
        if (stack.isEmpty() || count <= 0 || giveFailed) return false;
        try {
            if (createItemPacket == null) {
                createItemPacket = Class.forName("dev.emi.emi.network.CreateItemC2SPacket")
                    .getConstructor(int.class, ItemStack.class);
                emiSendToServer = Class.forName("dev.emi.emi.network.EmiNetwork")
                    .getMethod("sendToServer", Class.forName("dev.emi.emi.network.EmiPacket"));
                emiOnServer = Class.forName("dev.emi.emi.platform.EmiClient")
                    .getField("onServer");
            }
            if (!emiOnServer.getBoolean(null)) return false;
            int max = Math.max(1, Math.min(64, stack.getMaxStackSize()));
            int mode = toCursor ? 1 : 0;
            // the cursor holds one stack; otherwise the whole amount, as JeiGive gives it (the server drops what
            // doesn't fit the inventory)
            long left = toCursor ? Math.min(count, max) : neiaddon.JeiGive.ceiling(count, max);
            while (left > 0) {
                int n = (int) Math.min(left, max);
                emiSendToServer.invoke(null, createItemPacket.newInstance(mode, stack.copyWithCount(n)));
                left -= n;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            giveFailed = true;
            NeiAddon.LOGGER.warn("Could not use EMI's own give packet; gives under EMI keep JEI's way", e);
            return false;
        }
    }

    private static boolean sidebarClassChecked;

    /**
     * The item of an EMI sidebar stack (the item list or the favorites) under the point. EMI's sidebar interaction is its
     * internal class, so it is recognised by its name; a stack of one item only (not a fluid, not a tag of several).
     */
    @Override
    public ItemStack sidebarStackAt(int x, int y) {
        if (!sidebarClassChecked) {
            // a renamed internal class would otherwise just stop the clicks without a word: fail once, loudly
            try {
                Class.forName("dev.emi.emi.screen.EmiScreenManager$SidebarEmiStackInteraction");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("EMI's sidebar stack class is not there", e);
            }
            sidebarClassChecked = true;
        }
        EmiStackInteraction hit = EmiApi.getHoveredStack(x, y, false);
        if (hit == null || !"SidebarEmiStackInteraction".equals(hit.getClass().getSimpleName())) return ItemStack.EMPTY;
        List<EmiStack> stacks = hit.getStack().getEmiStacks();
        if (stacks.size() != 1) return ItemStack.EMPTY;
        ItemStack item = stacks.get(0).getItemStack();
        return item == null || item.isEmpty() ? ItemStack.EMPTY : item;
    }

    /** EMI's reload manager is not in its API, so it is asked by reflection; if that fails, EMI counts as loaded. */
    @Override
    public boolean loaded() {
        if (loadedFailed) return true;
        try {
            if (isLoaded == null) {
                isLoaded = Class.forName("dev.emi.emi.runtime.EmiReloadManager").getMethod("isLoaded");
            }
            return (Boolean) isLoaded.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            loadedFailed = true;
            NeiAddon.LOGGER.warn("Could not ask EMI whether its recipes are loaded; the addon takes them as loaded", e);
            return true;
        }
    }

    @Override
    public List<RecipeShape> recipesFor(ItemStack stack) {
        Map<String, RecipeShape> byId = new LinkedHashMap<>();
        if (stack.isEmpty()) return new ArrayList<>();
        for (EmiRecipe r : EmiApi.getRecipeManager()
            .getRecipesByOutput(emiStackOf(stack))) {
            try {
                if (!usable(r)) continue;
                Mapped m = map(r);
                if (m != null) byId.putIfAbsent(m.shape().id, m.shape());
            } catch (RuntimeException | LinkageError e) {
                recipeFailed(e);
            }
        }
        return new ArrayList<>(byId.values());
    }

    @Override
    public EmiCompat.Found find(String recipeId, ItemStack hint) {
        Hit hit = locate(recipeId, hint);
        return hit == null ? null : foundOf(hit.recipe(), hit.mapped(), recipeId);
    }

    /** EMI's recipe drawing, read by reflection (see {@link #picture}), looked up once. */
    private static Method pictureWrap;
    private static Method pictureRender;
    /** Set when drawing a picture failed: EMI pictures are off for good (one warning is logged). */
    private static boolean pictureBroken;

    /**
     * EMI's picture of the recipe, drawn by EMI's own recipe drawing ({@code EmiRenderHelper.renderRecipe} on
     * {@code EmiDrawContext.wrap(g)}, the way EMI's recipe tooltip draws it, without missing-ingredient marks). Its box is the
     * display plus a border of 4 on each side, as {@code renderRecipeBackground} draws it. Null when EMI has no such recipe.
     * A failure to find EMI's drawing throws, so {@link EmiCompat} turns pictures off once.
     */
    @Override
    public EmiCompat.Picture picture(String recipeId, ItemStack hint) {
        if (pictureBroken || recipeId == null) return null;
        Hit hit = locate(recipeId, hint);
        if (hit == null) return null;
        try {
            pictureMethods();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EMI's recipe drawing could not be found", e);
        }
        try {
            return new Picture(hit.recipe(), hit.recipe().getDisplayWidth() + 8, hit.recipe().getDisplayHeight() + 8);
        } catch (RuntimeException | LinkageError e) {
            recipeFailed(e);
            return null;
        }
    }

    private static void pictureMethods() throws ReflectiveOperationException {
        if (pictureRender != null) return;
        Class<?> context = Class.forName("dev.emi.emi.runtime.EmiDrawContext");
        pictureWrap = context.getMethod("wrap", GuiGraphics.class);
        pictureRender = Class.forName("dev.emi.emi.EmiRenderHelper")
            .getMethod("renderRecipe", EmiRecipe.class, context, int.class, int.class, boolean.class, int.class);
    }

    /** One EMI recipe drawn by EMI's own drawing; the box is {@code width} by {@code height} (the border included). */
    private record Picture(EmiRecipe recipe, int width, int height) implements EmiCompat.Picture {

        @Override
        public boolean broken() {
            return pictureBroken;
        }

        @Override
        public void draw(GuiGraphics g, int x, int y) {
            if (pictureBroken) return;
            try {
                // showMissing false and no overlay colour (-1), as EMI's RecipeTooltipComponent draws it by default
                Object context = pictureWrap.invoke(null, g);
                pictureRender.invoke(null, recipe, context, x, y, false, -1);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                pictureBroken = true;
                NeiAddon.LOGGER.warn("Could not draw an EMI recipe in the bookmark tooltip; EMI pictures are off from now on", e);
            } finally {
                // renderRecipe masks colour writes between its passes; a throw in between would leave them masked
                RenderSystem.colorMask(true, true, true, true);
            }
        }
    }

    /** An EMI recipe and its mapping, as {@link #locate} finds them. */
    private record Hit(EmiRecipe recipe, Mapped mapped) {}

    /**
     * The EMI recipe with this addon recipe id, or null when EMI has none. An identifier id is looked up by id; any other
     * id is searched for among the recipes of the hint's item (so a null hint finds only identifier ids).
     */
    private static Hit locate(String recipeId, ItemStack hint) {
        int bar = recipeId.indexOf('|');
        int hash = recipeId.indexOf('#');
        boolean byIdentifier = bar >= 0 && (hash < 0 || bar < hash);
        int sep = byIdentifier ? bar : hash;
        if (sep <= 0) return null;
        String rest = recipeId.substring(sep + 1);
        if (byIdentifier) {
            Hit found = byRecipeId(rest);
            if (found != null) return found;
        }
        if (hint == null || hint.isEmpty()) return null;
        for (EmiRecipe r : EmiApi.getRecipeManager()
            .getRecipesByOutput(emiStackOf(hint))) {
            try {
                if (!usable(r)) continue;
                Mapped m = map(r);
                if (m == null) continue;
                boolean accepted = byIdentifier ? rest.equals(restOf(m.shape().id)) : recipeId.equals(m.shape().id);
                if (accepted) return new Hit(r, m);
            } catch (RuntimeException | LinkageError e) {
                recipeFailed(e);
            }
        }
        return null;
    }

    /** The EMI recipe with this id (a JEI-era category may name it differently), or null when EMI has none. */
    private static Hit byRecipeId(String identifier) {
        ResourceLocation id = ResourceLocation.tryParse(identifier);
        if (id == null) return null;
        try {
            EmiRecipe r = EmiApi.getRecipeManager()
                .getRecipe(id);
            if (r == null || !usable(r)) return null;
            Mapped m = map(r);
            if (m == null || !identifier.equals(restOf(m.shape().id))) return null;
            return new Hit(r, m);
        } catch (RuntimeException | LinkageError e) {
            recipeFailed(e);
            return null;
        }
    }

    // ---------------------------------------------------------------- EMI's player-set default recipes (BoM)

    /**
     * EMI's player-set default recipes are internal (dev.emi.emi.bom.BoM), so they are called by reflection, looked up
     * once: the public static map addedRecipes (ingredient -> recipe; EMI's built-in defaults are not in it), and the
     * static addRecipe and removeRecipe of an ingredient and a recipe. EMI saves its data itself on add and remove.
     */
    private static Method[] bom;
    private static Field addedRecipes;

    private static Method bomMethod(int which) throws ReflectiveOperationException {
        if (bom == null) {
            Class<?> c = Class.forName("dev.emi.emi.bom.BoM");
            Method[] found = new Method[] {
                c.getMethod("addRecipe", EmiIngredient.class, EmiRecipe.class),
                c.getMethod("removeRecipe", EmiIngredient.class, EmiRecipe.class) };
            addedRecipes = c.getField("addedRecipes");
            bom = found;
        }
        return bom[which];
    }

    /** EMI's player-set default recipe of the stack (not a built-in default), or null. */
    private static EmiRecipe playerDefaultOf(EmiStack stack) throws ReflectiveOperationException {
        bomMethod(0);
        Object map = addedRecipes.get(null);
        return map instanceof Map<?, ?> m ? (EmiRecipe) m.get(stack) : null;
    }

    /** The addon's recipe id of an EMI recipe (its {@link #map} shape id), or null; cached per EMI recipe until EMI reloads. */
    private static final Map<EmiRecipe, String> ids = new IdentityHashMap<>();
    /** The EMI recipe and mapping for a recipe id with no item to search by (see {@link #locateCached}), until EMI reloads. */
    private static final Map<String, Optional<Hit>> located = new HashMap<>();
    private static int cacheGeneration = -1;

    /** Drops the caches when EMI has reloaded since they were filled: its recipes are new objects then. */
    private static void checkCache() {
        int now = EmiCompat.generation();
        if (now != cacheGeneration) {
            cacheGeneration = now;
            ids.clear();
            located.clear();
        }
    }

    private static String idOf(EmiRecipe r) {
        checkCache();
        String id = ids.get(r);
        if (id == null) {
            id = "";
            try {
                Mapped m = map(r);
                if (m != null) id = m.shape().id;
            } catch (RuntimeException | LinkageError e) {
                recipeFailed(e);
            }
            ids.put(r, id);
        }
        return id.isEmpty() ? null : id;
    }

    /** {@link #locate} with no item to search by, remembered until EMI reloads. */
    private static Hit locateCached(String recipeId) {
        checkCache();
        Optional<Hit> known = located.get(recipeId);
        if (known == null) {
            known = Optional.ofNullable(locate(recipeId, null));
            located.put(recipeId, known);
        }
        return known.orElse(null);
    }

    /** The item id (see {@link FluidStacks}) of an EMI output: fluid:ns:path for a fluid, else the item's id. */
    private static String itemIdOf(EmiStack s) {
        if (s.getKey() instanceof Fluid f && f != Fluids.EMPTY) return FluidStacks.PREFIX + BuiltInRegistries.FLUID.getKey(f);
        ItemStack is = s.getItemStack();
        return is.isEmpty() ? null : RecipeData.idOf(is);
    }

    @Override
    public String defaultRecipe(ItemStack item) {
        EmiStack stack = emiStackOf(item);
        if (stack.isEmpty()) return null;
        try {
            EmiRecipe r = playerDefaultOf(stack);
            return r == null || !usable(r) ? null : idOf(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EMI's default recipes could not be read", e);
        }
    }

    @Override
    public String defaultItemOf(String recipeId) {
        Hit hit = locateCached(recipeId);
        // shortcut: an EMI recipe with a hash id (uid#hash) cannot be located without an item, so it is not found here;
        // the heart and the keys still find its item through the item's own favorite (Favorites.recipeFor)
        if (hit == null) return null;
        try {
            for (EmiStack out : hit.recipe()
                .getOutputs()) {
                if (out.isEmpty()) continue;
                if (hit.recipe()
                    .equals(playerDefaultOf(out))) return itemIdOf(out);
            }
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EMI's default recipes could not be read", e);
        }
    }

    @Override
    public boolean setDefault(ItemStack item, String recipeId, ItemStack hint) {
        EmiStack stack = emiStackOf(item);
        if (stack.isEmpty()) return false;
        Hit hit = locate(recipeId, hint);
        if (hit == null) return false;
        try {
            bomMethod(0).invoke(null, stack, hit.recipe());
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EMI's default recipes could not be changed", e);
        }
    }

    /**
     * The other items of a recipe lose their player-set default for it (the NEI rule for one favorite per recipe): every
     * output of the recipe except keepItemId whose player-set default is this recipe.
     */
    @Override
    public void clearOtherDefaults(String recipeId, String keepItemId) {
        Hit hit = locateCached(recipeId);
        // shortcut: a recipe with a hash id cannot be located without an item here, so its other items keep their defaults
        if (hit == null) return;
        try {
            for (EmiStack out : hit.recipe()
                .getOutputs()) {
                if (out.isEmpty() || keepItemId.equals(itemIdOf(out))) continue;
                if (hit.recipe()
                    .equals(playerDefaultOf(out))) bomMethod(1).invoke(null, out, hit.recipe());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EMI's default recipes could not be changed", e);
        }
    }

    @Override
    public void clearDefault(ItemStack item, String onlyRecipeId) {
        EmiStack stack = emiStackOf(item);
        if (stack.isEmpty()) return;
        try {
            EmiRecipe current = playerDefaultOf(stack);
            if (current == null) return;
            if (onlyRecipeId != null && !Favorites.sameRecipe(onlyRecipeId, idOf(current))) return;
            bomMethod(1).invoke(null, stack, current);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EMI's default recipes could not be changed", e);
        }
    }

    /** A target for a recipe on EMI's recipe screen, with the JEI recipe it maps to (if any), as {@link #foundOf} makes. */
    static EmiTarget targetOf(EmiRecipe r) {
        Mapped m = null;
        try {
            m = map(r);
        } catch (RuntimeException | LinkageError e) {
            recipeFailed(e);
        }
        return m == null ? new EmiTarget(r, null, null) : new EmiTarget(r, m.jeiCategory(), m.jeiRecipe());
    }

    /** The addon's view of an EMI recipe: its shape under the addon id, and a target that shows and fills it. */
    private static EmiCompat.Found foundOf(EmiRecipe r, Mapped m, String recipeId) {
        return new EmiCompat.Found(m.shape().withId(recipeId), new EmiTarget(r, m.jeiCategory(), m.jeiRecipe()));
    }

    @Override
    public String categoryName(String id) {
        EmiRecipeCategory category = categoryOf(id);
        return category == null ? null : category.getName().getString();
    }

    @Override
    public IDrawable categoryIcon(String id) {
        EmiRecipeCategory category = categoryOf(id);
        return category == null ? null : icon(category);
    }

    private static Method dropMethod;

    /**
     * EMI's drag-and-drop handlers are not in its API as a call, only as the registry that EMI's own item list drops into
     * ({@code EmiDragDropHandlers.dropStack(screen, stack, x, y)}), so it is found by name. A failure disables this for good
     * ({@link EmiCompat#dropStack} logs it once).
     */
    @Override
    public boolean dropStack(Screen screen, ItemStack stack, int x, int y) {
        EmiStack emi = emiStackOf(stack);
        if (emi.isEmpty()) return false;
        try {
            if (dropMethod == null) {
                for (Method m : Class.forName("dev.emi.emi.registry.EmiDragDropHandlers")
                    .getMethods()) {
                    if (m.getName()
                        .equals("dropStack") && m.getParameterCount() == 4 && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        dropMethod = m;
                        break;
                    }
                }
                if (dropMethod == null) throw new NoSuchMethodException("EmiDragDropHandlers.dropStack(screen, stack, x, y)");
            }
            return Boolean.TRUE.equals(dropMethod.invoke(null, screen, emi, x, y));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EMI's drag and drop could not be called", e);
        }
    }

    private static Method dropRenderMethod;

    /**
     * EMI's drop targets are drawn by the static {@code EmiDragDropHandlers.render(screen, stack, g, x, y, delta)}, found by
     * name like {@link #dropStack}. It is drawn at a Z of 400, as EMI draws it when an item is dragged from its own list.
     * A failure throws, so {@link EmiCompat#renderDropTargets} turns this off for good.
     */
    @Override
    public boolean renderDropTargets(Screen screen, ItemStack stack, GuiGraphics g, int mouseX, int mouseY, float delta) {
        EmiStack emi = emiStackOf(stack);
        if (emi.isEmpty()) return false;
        try {
            if (dropRenderMethod == null) {
                for (Method m : Class.forName("dev.emi.emi.registry.EmiDragDropHandlers")
                    .getMethods()) {
                    if (m.getName()
                        .equals("render") && m.getParameterCount() == 6 && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        dropRenderMethod = m;
                        break;
                    }
                }
                if (dropRenderMethod == null) throw new NoSuchMethodException("EmiDragDropHandlers.render(screen, stack, g, x, y, delta)");
            }
            g.pose()
                .pushPose();
            try {
                g.pose()
                    .translate(0, 0, 400);
                dropRenderMethod.invoke(null, screen, emi, g, mouseX, mouseY, delta);
            } finally {
                g.pose()
                    .popPose();
            }
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EMI's drop targets could not be drawn", e);
        }
    }

    // ---------------------------------------------------------------- EMI's favorites as bookmarks

    /**
     * EMI's favorites are internal (dev.emi.emi.runtime.EmiFavorites), so they are read and removed by reflection, looked up
     * once: the public static list favorites, EmiFavorite.getRecipe and getStack, EmiFavorites.removeFavorite(ingredient),
     * and EmiPersistentData.save() (EMI calls it after its own favorite changes; removeFavorite does not).
     */
    private static Field favoritesList;
    private static Method favoriteRecipe;
    private static Method favoriteStack;
    private static Method removeFavorite;
    private static Method saveFavorites;
    /** The favorites seen on the last tick (by identity), and the EMI reload generation they were seen in. */
    private static Set<Object> seenFavorites;
    private static int seenGeneration = -1;

    private static void favoriteMethods() throws ReflectiveOperationException {
        if (favoritesList != null) return;
        Class<?> favorite = Class.forName("dev.emi.emi.runtime.EmiFavorite");
        Class<?> favorites = Class.forName("dev.emi.emi.runtime.EmiFavorites");
        favoriteRecipe = favorite.getMethod("getRecipe");
        favoriteStack = favorite.getMethod("getStack");
        removeFavorite = favorites.getMethod("removeFavorite", EmiIngredient.class);
        saveFavorites = Class.forName("dev.emi.emi.runtime.EmiPersistentData").getMethod("save");
        favoritesList = favorites.getField("favorites");
    }

    /**
     * Every tick: the favorites that are new since the last tick become bookmarks when the bookmark panel is shown
     * (panelShown), and are removed from EMI's favorites; with the panel hidden they stay EMI's. The first tick after EMI
     * (re)loaded its favorites only records them, so the loaded ones are never converted. Favorites with a recipe context
     * (EMI's recipe screen) are left alone. Failures go to {@link EmiCompat}, which turns this off for good.
     */
    @Override
    public void favoritesTick(boolean panelShown) {
        try {
            favoriteMethods();
            List<?> now = (List<?>) favoritesList.get(null);
            int generation = EmiCompat.generation();
            boolean record = seenFavorites == null || generation != seenGeneration;
            seenGeneration = generation;
            if (!record && panelShown) {
                for (Object favorite : new ArrayList<>(now)) {
                    if (seenFavorites.contains(favorite) || favoriteRecipe.invoke(favorite) != null) continue;
                    convertFavorite(favorite);
                }
            }
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            seen.addAll((List<?>) favoritesList.get(null));
            seenFavorites = seen;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EMI's favorites could not be read or changed", e);
        }
    }

    /**
     * One new EMI favorite as a bookmark, then removed from EMI's favorites and saved the way EMI saves. The item is the
     * first stack of the favorite (a fluid as its carrier, one bucket, as the addon's own fluid bookmarks are); it is added
     * only if it is not bookmarked yet, so the key never removes a bookmark.
     */
    private static void convertFavorite(Object favorite) throws ReflectiveOperationException {
        EmiIngredient ingredient = (EmiIngredient) favoriteStack.invoke(favorite);
        List<EmiStack> stacks = ingredient.getEmiStacks()
            .stream()
            .filter(s -> !s.isEmpty())
            .toList();
        if (stacks.isEmpty()) return;
        EmiStack first = stacks.get(0);
        ItemStack item;
        long amount = 1;
        if (first.getKey() instanceof Fluid f && f != Fluids.EMPTY) {
            item = FluidStacks.carrier(BuiltInRegistries.FLUID.getKey(f));
            amount = 1000;
        } else {
            item = first.getItemStack();
        }
        if (item.isEmpty()) return;
        if (!Bookmarks.isBookmarked(item)) Bookmarks.add(item, amount);
        removeFavorite.invoke(null, favorite);
        saveFavorites.invoke(null);
    }

    /** The EMI category with this id, or null. */
    private static EmiRecipeCategory categoryOf(String id) {
        for (EmiRecipeCategory category : EmiApi.getRecipeManager()
            .getCategories()) {
            if (category.getId()
                .toString()
                .equals(id)) return category;
        }
        return null;
    }

    /**
     * EMI's category icon as a JEI drawable, 16 by 16, drawn by the category's own render. If that fails, one warning is
     * logged and nothing is drawn from then on.
     */
    static IDrawable icon(EmiRecipeCategory category) {
        return new IDrawable() {

            @Override
            public int getWidth() {
                return 16;
            }

            @Override
            public int getHeight() {
                return 16;
            }

            @Override
            public void draw(GuiGraphics g, int xOffset, int yOffset) {
                if (iconBroken) return;
                try {
                    category.render(g, xOffset, yOffset, 0f);
                } catch (RuntimeException | LinkageError e) {
                    iconBroken = true;
                    NeiAddon.LOGGER.warn("Could not draw an EMI category icon; the addon draws none from now on", e);
                }
            }
        };
    }

    /** The part of a {@code type|identifier} id after the first bar, or null for any other id. */
    private static String restOf(String id) {
        int bar = id.indexOf('|');
        return bar < 0 ? null : id.substring(bar + 1);
    }

    /**
     * Whether the recipe is a real recipe with ingredients (not a tag, info or resolution page, nor a page that only shows
     * information such as JEI's information or GregTech's diagrams: {@link RecipeShape#infoType}).
     */
    private static boolean usable(EmiRecipe r) {
        String category = r.getCategory()
            .getId()
            .toString();
        return !SKIPPED.contains(category) && !RecipeShape.infoType(category) && !r.getInputs()
            .isEmpty();
    }

    /** The recipe as a shape, or null. Each step falls through to the next when it fails or has no inputs. */
    static Mapped map(EmiRecipe r) {
        Mapped m = fromJemi(r);
        if (m != null && !m.shape().inputs.isEmpty()) {
            lastStep = "jemi";
            return m;
        }
        m = fromBacking(r);
        if (m != null && !m.shape().inputs.isEmpty()) {
            lastStep = "backing";
            return m;
        }
        RecipeShape shape;
        try {
            shape = own(r);
        } catch (RuntimeException | LinkageError e) {
            recipeFailed(e);
            return null;
        }
        if (shape == null) return null;
        lastStep = "own";
        return new Mapped(shape, null, null);
    }

    /** The JEI recipe EMI shows for a JEI recipe (JEMI's recipe objects keep the JEI category and recipe as fields). */
    private static Mapped fromJemi(EmiRecipe r) {
        if (!r.getClass()
            .getName()
            .equals(JEMI_RECIPE)) return null;
        try {
            if (jemiCategory == null) {
                jemiCategory = r.getClass()
                    .getField("category");
                jemiRecipe = r.getClass()
                    .getField("recipe");
            }
            Object category = jemiCategory.get(r);
            Object recipe = jemiRecipe.get(r);
            if (!(category instanceof IRecipeCategory<?> c) || recipe == null) return null;
            RecipeShape shape = jeiShape(c, recipe);
            return shape == null ? null : new Mapped(shape, c, recipe);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.debug("Could not read the JEI recipe of an EMI recipe; EMI's own data is used", e);
            return null;
        }
    }

    /** The JEI recipe behind an EMI recipe that has a backing recipe, looked up in JEI under EMI's category id. */
    private static Mapped fromBacking(EmiRecipe r) {
        try {
            Object backing = r.getBackingRecipe();
            if (backing == null || !Calculator.available()) return null;
            IRecipeManager manager = Calculator.runtime.getRecipeManager();
            ResourceLocation category = r.getCategory()
                .getId();
            Optional<RecipeType<?>> type = manager.getRecipeType(category);
            String alias = ALIASES.get(category.toString());
            if (type.isEmpty() && alias != null) type = manager.getRecipeType(ResourceLocation.parse(alias));
            if (type.isEmpty()) return null;
            return backingShape(manager, type.get(), backing);
        } catch (RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.debug("Could not read the backing recipe of an EMI recipe; EMI's own data is used", e);
            return null;
        }
    }

    private static <T> Mapped backingShape(IRecipeManager manager, RecipeType<T> type, Object backing) {
        // EMI gives the recipe's holder; JEI's vanilla types take the holder, some mods' types the bare recipe
        if (!type.getRecipeClass()
            .isInstance(backing) && backing instanceof RecipeHolder<?> holder) backing = holder.value();
        if (!type.getRecipeClass()
            .isInstance(backing)) return null;
        IRecipeCategory<T> category = manager.getRecipeCategory(type);
        RecipeShape shape = jeiShape(category, backing);
        return shape == null ? null : new Mapped(shape, category, backing);
    }

    /** The JEI layout of a recipe, read as a shape; it has the same id JEI gives the recipe. */
    private static <T> RecipeShape jeiShape(IRecipeCategory<T> category, Object recipe) {
        if (!Calculator.available()) return null;
        IJeiRuntime rt = Calculator.runtime;
        T cast = category.getRecipeType()
            .getRecipeClass()
            .cast(recipe);
        Optional<IRecipeLayoutDrawable<T>> layout = rt.getRecipeManager()
            .createRecipeLayoutDrawable(category, cast, rt.getJeiHelpers()
                .getFocusFactory()
                .getEmptyFocusGroup());
        return layout.isEmpty() ? null : RecipeShape.read(layout.get());
    }

    /** The shape of an EMI recipe, as {@link #find} and {@link #recipesFor} give it, or null when it has none. */
    static RecipeShape shapeFor(EmiRecipe r) {
        try {
            if (!usable(r)) return null;
            Mapped m = map(r);
            return m == null ? null : m.shape();
        } catch (RuntimeException | LinkageError e) {
            recipeFailed(e);
            return null;
        }
    }

    /** Every item and fluid an EMI ingredient accepts, each once (by id), as alternatives; empty for an empty ingredient. */
    static List<RecipeShape.Alt> altsOf(EmiIngredient ing) {
        List<RecipeShape.Alt> alts = new ArrayList<>();
        if (ing.isEmpty()) return alts;
        Set<String> seen = new HashSet<>();
        for (EmiStack s : ing.getEmiStacks()) {
            long amount = s.getAmount() > 0 ? s.getAmount() : ing.getAmount();
            RecipeShape.Alt alt = altFor(s, amount);
            if (alt != null && seen.add(alt.id())) alts.add(alt);
        }
        return alts;
    }

    /** EMI's own data: each input ingredient is a slot, each output a slot; catalysts are not inputs. */
    private static RecipeShape own(EmiRecipe r) {
        List<List<RecipeShape.Alt>> inputs = new ArrayList<>();
        for (EmiIngredient ing : r.getInputs()) {
            List<RecipeShape.Alt> alts = altsOf(ing);
            if (!alts.isEmpty()) inputs.add(alts);
        }
        if (inputs.isEmpty()) return null;
        List<List<RecipeShape.Alt>> outputs = new ArrayList<>();
        for (EmiStack s : r.getOutputs()) {
            RecipeShape.Alt alt = altFor(s, s.getAmount());
            if (alt != null) outputs.add(List.of(alt));
        }
        String identifier = r.getId() == null ? null : r.getId()
            .toString();
        String id = RecipeShape.idFor(r.getCategory()
            .getId()
            .toString(), identifier, inputs, outputs);
        return RecipeShape.of(id, inputs, outputs);
    }

    /** One EMI stack as an alternative; null for an empty item. Fluids are counted in millibuckets. */
    static RecipeShape.Alt altFor(EmiStack s, long amount) {
        if (s.getKey() instanceof Fluid f && f != Fluids.EMPTY) {
            return RecipeShape.fluidAlt(BuiltInRegistries.FLUID.getKey(f), amount / unitsPerMilliBucket());
        }
        ItemStack is = s.getItemStack();
        return is.isEmpty() ? null : RecipeShape.altOf(is, amount);
    }

    /** EMI counts fluids in the loader's fluid unit, the same as JEI: this many units make a millibucket. */
    private static long unitsPerMilliBucket() {
        if (!Calculator.available()) return 1;
        return Math.max(1, Calculator.runtime.getJeiHelpers()
            .getPlatformFluidHelper()
            .bucketVolume() / 1000);
    }

    /** The EMI stack of an addon stack: a fluid for a fluid carrier, else the item. */
    public static EmiStack emiStackOf(ItemStack stack) {
        String fluid = FluidStacks.fluidIdOf(stack);
        if (fluid != null) {
            return BuiltInRegistries.FLUID.getOptional(ResourceLocation.parse(fluid))
                .map(f -> EmiStack.of(f))
                .orElse(EmiStack.EMPTY);
        }
        return EmiStack.of(stack);
    }

    /** EMI failing on one recipe leaves that recipe out: one warning for the first, the rest at debug level. */
    private static void recipeFailed(Throwable e) {
        if (!warnedRecipe) {
            warnedRecipe = true;
            NeiAddon.LOGGER.warn("Could not read an EMI recipe; it is left out (later ones are logged at debug level)", e);
        } else {
            NeiAddon.LOGGER.debug("Could not read an EMI recipe; it is left out", e);
        }
    }
}
