package neiaddon.autocraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import neiaddon.FluidStacks;
import neiaddon.NeiAddon;
import neiaddon.RecipeLookup;
import neiaddon.StackIds;
import neiaddon.Tools;
import neiaddon.chain.ChainAutocraft;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainMath;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;

import static neiaddon.autocraft.Ae2Path.*;
import static neiaddon.autocraft.Cleanup.*;
import static neiaddon.autocraft.Run.*;
import static neiaddon.autocraft.Runner.*;

/**
 * One recipe visit as the chain sees it: which recipes can be crafted, which are deferred, skipped or given up,
 * and what the player is told.
 */
final class Visits {

    /**
     * The vanilla crafting recipe behind a chain recipe id: the id is
     * {@code <recipe type>|<vanilla id>} for a recipe known by name (see {@link RecipeShape#idOf}), and JEI only
     * fills a crafting grid from vanilla crafting recipes (GregTech's bridged ones included). Null for anything
     * else (a {@code #hash} id, the chain's root, no client level, no such recipe).
     */
    static CraftingRecipe vanillaCrafting(Minecraft mc, String recipeId) {
        int bar = recipeId.indexOf('|');
        if (bar <= 0 || mc.level == null) return null;
        Identifier vanilla = Identifier.tryParse(recipeId.substring(bar + 1));
        if (vanilla == null) return null;
        // 26.x: the client holds no recipes by id (its RecipeAccess has no lookup), so the recipe is read from the
        // integrated server's manager; on a server there is none, and JEI's own crafting recipes are read instead
        net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return jeiCrafting(vanilla);
        Recipe<?> recipe;
        try {
            recipe = server.getRecipeManager()
                .byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, vanilla))
                .map(net.minecraft.world.item.crafting.RecipeHolder::value)
                .orElse(null);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return recipe instanceof CraftingRecipe crafting ? crafting : null;
    }

    private static Map<Identifier, CraftingRecipe> jeiCrafting;
    private static int jeiCraftingGeneration = -1;
    private static boolean jeiCraftingWarned;

    /**
     * 26.x, on a server: the crafting recipe with this id among the ones JEI holds (the recipes the server sent it;
     * JEI's crafting recipes are vanilla recipe holders). Read once per JEI reload. Null when JEI has none by that id;
     * the visit then goes by clicks, as it would without the server path.
     */
    private static CraftingRecipe jeiCrafting(Identifier id) {
        mezz.jei.api.runtime.IJeiRuntime rt = neiaddon.Calculator.runtime;
        if (rt == null) return null;
        if (jeiCrafting == null || jeiCraftingGeneration != RecipeLookup.generation()) {
            Map<Identifier, CraftingRecipe> found = new HashMap<>();
            try {
                rt.getRecipeManager()
                    .createRecipeLookup(mezz.jei.api.constants.RecipeTypes.CRAFTING)
                    .includeHidden()
                    .get()
                    .forEach(holder -> found.putIfAbsent(holder.id().identifier(), holder.value()));
            } catch (RuntimeException | LinkageError e) {
                if (!jeiCraftingWarned) {
                    jeiCraftingWarned = true;
                    NeiAddon.LOGGER.warn("Could not read JEI's crafting recipes, autocrafting goes by clicks", e);
                }
            }
            jeiCrafting = found;
            jeiCraftingGeneration = RecipeLookup.generation();
        }
        return jeiCrafting.get(id);
    }

    /**
     * The id of the vanilla recipe behind a chain recipe id (a recipe carries no id of its own, its holder does), or
     * null. Read from the id the same way as {@link #vanillaCrafting}, so it names the recipe found there.
     */
    static Identifier vanillaId(String recipeId) {
        int bar = recipeId.indexOf('|');
        return bar <= 0 ? null : Identifier.tryParse(recipeId.substring(bar + 1));
    }

    /** AE2: the ids an ingredient accepts: its options (tags) and its own item, each once. */
    static java.util.Set<String> candidates(ChainItem ingredient) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(ingredient.options);
        ids.add(ingredient.item);
        return ids;
    }

    /** AE2: a fluid ingredient (the chain marks it by its id, see {@link FluidStacks}); the grid never holds one. */
    static boolean isFluid(ChainItem ingredient) {
        ItemStack stack = FluidStacks.stackOf(ingredient.item);
        return FluidStacks.isFluidId(ingredient.item) || FluidStacks.isFluid(stack) || FluidStacks.isMilli(stack);
    }

    /** AE2: an ingredient the item check leaves to the transfer: a fluid, or a tool (a worn tool's id is not its option's). */
    static boolean leftToTransfer(ChainItem ingredient) {
        return isFluid(ingredient) || Tools.isTool(FluidStacks.stackOf(ingredient.item));
    }

    /** An id without its subtype mark: the registry item a (possibly worn, subtyped) id stands for. */
    static String plainId(String id) {
        int at = id.indexOf(StackIds.MARK);
        return at < 0 ? id : id.substring(0, at);
    }

    /**
     * For the log, which of the chain's ingredients for this recipe the open screen's
     * inventory is short of for one craft (the fill check's scale): each ingredient line of the recipe is compared
     * with what the inventory holds of its candidates, without the subtype mark so a worn tool stands for its plain
     * item; fluids are left out, they are not items. Says so when nothing is short, which means JEI's own check
     * refused a fill this side believes the screen can do.
     */
    static String missingFor(String recipeId) {
        if (math == null) return "no chain";
        Map<String, Long> have = new HashMap<>();
        for (Map.Entry<String, Long> e : inventory().entrySet()) {
            have.merge(plainId(e.getKey()), e.getValue(), Long::sum);
        }
        StringBuilder shortOf = new StringBuilder();
        for (ChainItem ingredient : math.recipeIngredients) {
            if (!recipeId.equals(ingredient.recipeId) || ingredient.emptyFactor() || isFluid(ingredient)) continue;
            java.util.Set<String> options = new java.util.LinkedHashSet<>();
            options.add(plainId(ingredient.item));
            for (String option : candidates(ingredient)) options.add(plainId(option));
            long got = 0;
            for (String option : options) got += have.getOrDefault(option, 0L);
            if (got >= ingredient.factor) continue;
            if (shortOf.length() > 0) shortOf.append(", ");
            shortOf.append("short ").append(ingredient.factor - got).append('x').append(plainId(ingredient.item)).append(" (have ").append(got).append(')');
        }
        return shortOf.length() == 0 ? "nothing short here, the viewer refused the fill itself" : shortOf.toString();
    }

    /**
     * A visit that was refused, could not be re-filled, or left no result gets its turn back later in this same run
     * instead of giving up on the recipe, when what it waits for is an input this run's own plan still has to craft
     * (see {@link #waitsForPlannedInput}). {@link ChainAutocraft#defer()} re-adds the recipe so a later pass hands it
     * again once the input has been crafted, and keeps the passes alive while that is owed. Each recipe gets at most
     * {@link Run#DEFERRED_TURNS} deferred turns per run (counted here, separate from AE2's {@code ae2Retried}); after that
     * the visit is given up. AE2 visits keep their own retry handling and never defer. The caller abandons when this
     * returns false.
     */
    static boolean deferVisit(String recipeId, String why) {
        if (ae2 || math == null || "recipe not found".equals(why)) return false;
        if (!waitsForPlannedInput(recipeId)) return false;
        if (deferred.merge(recipeId, 1, Integer::sum) > DEFERRED_TURNS) return false;
        NeiAddon.LOGGER.info("Autocrafting: {} is waiting for input it does not have yet, it gets another turn this run", recipeId);
        walker.defer();
        return true;
    }

    /**
     * Does this visit wait for an input the chain's plan crafts? {@link #missingFor} names what is short by item id
     * ("short 2xgtceu:titanium_ingot (have 0)"), and that counts when it names the output of a recipe of the plan (an
     * ingredient of another planned step: not the run's wanted item, which hangs on the chain's root recipe and is
     * never crafted as an input of a step). JEI's own refusal note is not matched by display name: a substring test
     * took "Iron Ingot" inside "Sterling Iron Ingot" and deferred visits waiting for something else. The ids of
     * {@link #missingFor} are what the refusals actually turn on.
     */
    static boolean waitsForPlannedInput(String recipeId) {
        for (String part : missingFor(recipeId).split(", ")) {
            int at = part.indexOf('x');
            int space = part.indexOf(" (", at);
            if (!part.startsWith("short ") || at < 0 || space < 0) continue;
            if (plannedToCraft(part.substring(at + 1, space))) return true;
        }
        return false;
    }

    /** Is this item (plain id, worn subtypes stripped) an output of a planned recipe? */
    static boolean plannedToCraft(String itemId) {
        String plain = plainId(itemId);
        for (ChainItem result : math.recipeResults) {
            if (result.recipeId != null && !ChainMath.ROOT_RECIPE_ID.equals(result.recipeId)
                && plainId(result.item).equals(plain)) return true;
        }
        return false;
    }

    static void fail(String why) {
        sayStopped(why);
        startCleanup();
    }

    static void sayStopped(String why) {
        NeiAddon.LOGGER.info("Autocrafting stopped while crafting {}: {}", current, why);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6[NEI Features] §eAutocrafting stopped: " + why + "."));
    }

    /**
     * The chain is done as far as it goes: every recipe that got fewer crafts than the chain wanted of it is named in chat
     * with its count and why it fell short (what the target lacks now, else the reason the run gave up on it, else that it
     * ran out of ingredients). Recipes the tool line or the give-up line already named are left out; nothing is said when
     * every recipe got what it wanted.
     */
    static void reportUnfinished(Minecraft mc) {
        List<String> parts = new ArrayList<>();
        int more = 0;
        for (Map.Entry<String, Long> e : walker.stillWanted().entrySet()) {
            // in the order the chain first handed them out; the value is what the last pass still wanted, so the
            // recipe's whole want is what it crafted plus that
            String recipeId = e.getKey();
            long crafted = craftedBy.getOrDefault(recipeId, 0L);
            long want = crafted + e.getValue();
            if (skipped.containsKey(recipeId) || givenUp.contains(recipeId) || toolNamed.contains(recipeId)) continue;
            if (parts.size() >= 3) {
                more++;
                continue;
            }
            RecipeLookup.Target<?> target = target(recipeId);
            String missing = ae2 ? ae2Missing(recipeId) : null;
            if (ae2 && missing == null) missing = ae2MissingTool(recipeId);
            String why = missing != null ? missing + " is not in the inventory, the network or the grid"
                : target == null ? "recipe not found" : target.whyNot(menu, mc.player);
            if (why == null) why = giveUpWhy.getOrDefault(recipeId, "ran out of ingredients");
            parts.add(recipeName(recipeId) + ": crafted " + crafted + " of " + want + " (" + why + ")");
        }
        String rest = more > 0 ? " | and " + more + " more" : "";
        NeiAddon.LOGGER.info("Autocrafting finished{}", parts.isEmpty() ? "" : "; came up short: " + String.join(" | ", parts) + rest);
        if (!parts.isEmpty() && mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component
            .literal("§6[NEI Features] §eAutocrafting finished, but these recipes came up short: " + String.join(" | ", parts) + rest + "."));
        saySkipped(mc);
    }

    /**
     * AE2: the chat line naming the recipes the run skipped, sent once per run: at the normal end, or when the run stops
     * (see {@link Runner#stop}), whichever comes first.
     */
    static void saySkipped(Minecraft mc) {
        if (skippedSaid || skipped.isEmpty()) return;
        skippedSaid = true;
        if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component
            .literal("§6[NEI Features] §eAutocrafting skipped these recipes: " + String.join(" | ", skipped.values()) + "."));
    }

    /** Gives up on the visit of a recipe in the walker, and keeps the short reason for the end-of-run line (see {@link #reportUnfinished}). */
    static void abandonVisit(String recipeId, String why) {
        giveUpWhy.put(recipeId, why);
        walker.abandon();
    }

    static String recipeName(String recipeId) {
        for (ChainItem item : math.recipeResults) {
            if (recipeId.equals(item.recipeId)) return FluidStacks.name(FluidStacks.stackOf(item.item))
                .getString();
        }
        return recipeId;
    }
}
