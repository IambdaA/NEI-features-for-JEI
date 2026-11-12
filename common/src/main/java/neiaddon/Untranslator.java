package neiaddon;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import mezz.jei.api.registration.IIngredientAliasRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;

/**
 * NEI's item untranslator: while the game is not in English, JEI's search also finds an item by its English name. The
 * English names come from the {@code en_us.json} of every resource pack, and each one that differs from the name shown
 * is registered with JEI as an alias of the item.
 *
 * <p>
 * Ported from GTNH NotEnoughItems (LGPL-3.0-or-later).
 */
public final class Untranslator {

    private Untranslator() {}

    private static Map<String, String> english() {
        Map<String, String> names = new HashMap<>();
        ResourceManager manager = Minecraft.getInstance()
            .getResourceManager();
        for (String namespace : manager.getNamespaces()) {
            ResourceLocation file = new ResourceLocation(namespace, "lang/en_us.json");
            for (Resource resource : manager.getResourceStack(file)) {
                try (Reader reader = resource.openAsReader()) {
                    JsonObject json = JsonParser.parseReader(reader)
                        .getAsJsonObject();
                    for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                        if (entry.getValue()
                            .isJsonPrimitive()) {
                            names.put(entry.getKey(), entry.getValue()
                                .getAsString());
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    NeiAddon.LOGGER.debug("Could not read {} of a resource pack", file, e);
                }
            }
        }
        return names;
    }

    /**
     * The English name of each item whose English name differs from the name shown. Empty when the untranslator is off
     * or no English names were found. JEI's search and EMI's search both take their names from here.
     */
    public static Map<Item, String> englishNames() {
        Map<Item, String> names = new LinkedHashMap<>();
        if (!AddonConfig.UNTRANSLATE.get()) return names;
        Map<String, String> english = english();
        if (english.isEmpty()) return names;
        for (Item item : BuiltInRegistries.ITEM) {
            try {
                String name = english.get(item.getDescriptionId());
                if (name == null || name.isBlank()) continue;
                String shown = item.getDefaultInstance()
                    .getHoverName()
                    .getString();
                if (name.equalsIgnoreCase(shown)) continue;
                names.put(item, name);
            } catch (RuntimeException e) {
                NeiAddon.LOGGER.debug("Could not add an English name for {}", item, e);
            }
        }
        return names;
    }

    static void register(IIngredientAliasRegistration registration) {
        Map<Item, String> names = englishNames();
        if (names.isEmpty()) return;
        int added = 0;
        for (Map.Entry<Item, String> entry : names.entrySet()) {
            try {
                registration.addAlias(entry.getKey(), entry.getValue());
                added++;
            } catch (RuntimeException e) {
                NeiAddon.LOGGER.debug("Could not add an English name for {}", entry.getKey(), e);
            } catch (LinkageError e) {
                // older JEI (19.25) has no addAlias(Item, String): the untranslator needs a newer JEI
                NeiAddon.LOGGER.warn("Item Untranslator needs a newer JEI (no ingredient aliases): {}", e.toString());
                return;
            }
        }
        NeiAddon.LOGGER.info("Registered {} English names with JEI's search", added);
    }
}
