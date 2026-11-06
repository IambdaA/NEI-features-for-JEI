package neiaddon;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
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
final class Untranslator {

    private Untranslator() {}

    private static Map<String, String> english() {
        Map<String, String> names = new HashMap<>();
        ResourceManager manager = Minecraft.getInstance()
            .getResourceManager();
        for (String namespace : manager.getNamespaces()) {
            ResourceLocation file = ResourceLocation.fromNamespaceAndPath(namespace, "lang/en_us.json");
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

    static void register(IIngredientAliasRegistration registration) {
        if (!AddonConfig.UNTRANSLATE.get()) return;
        Map<String, String> english = english();
        if (english.isEmpty()) return;
        int added = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            try {
                String name = english.get(item.getDescriptionId());
                if (name == null || name.isBlank()) continue;
                String shown = item.getDefaultInstance()
                    .getHoverName()
                    .getString();
                if (name.equalsIgnoreCase(shown)) continue;
                registration.addAlias(item, name);
                added++;
            } catch (RuntimeException e) {
                NeiAddon.LOGGER.debug("Could not add an English name for {}", item, e);
            }
        }
        NeiAddon.LOGGER.info("Registered {} English names with JEI's search", added);
    }
}
