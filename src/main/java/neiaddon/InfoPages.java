package neiaddon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Loads NEI-style information pages. File: {@code config/neiaddon/informationpages.cfg}, one page per line:
 * <pre>filter = description text</pre>
 * Filters: {@code namespace:path} (may contain {@code *}), {@code $namespace:tag} for an item tag (NEI's ore
 * dictionary filter; without a namespace the {@code c:} tags are used), {@code @modid}.
 * {@code \n} in the text starts a new paragraph. The text may be a translation key. As in NEI, empty lines and lines
 * starting with # are comments, and only the first '=' separates filter and text.
 */
public final class InfoPages {

    private static final String DEFAULT_FILE = """
        # NEI-style item information pages (NeoForge port add-on)
        #
        # One page per line:  filter = description
        # Filters:  namespace:path   (use * as a wildcard, e.g. minecraft:*_log)
        #           $namespace:tag   (an item tag, e.g. $c:ingots/iron; $ingots/iron means c:ingots/iron)
        #           @modid           (every item of a mod)
        # Use \\n for a line break. The description may be a translation key.
        # Repeat a filter on several lines for several pages.
        # Comment lines start with '#' followed by a space.
        #
        # Examples (remove the leading '# ' to enable):
        # minecraft:diamond_sword = A powerful melee weapon.
        # minecraft:diamond_sword = Can be enchanted for extra effects.
        # $c:ingots/iron = Iron is a very versatile material.\\nFound below sea level.
        """;

    public record Page(String filter, String text) {}

    private InfoPages() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get()
            .resolve("neiaddon")
            .resolve("informationpages.cfg");
    }

    public static List<Page> load() {
        Path file = file();
        List<Page> pages = new ArrayList<>();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_FILE);
            }
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String filter = line.substring(0, eq).strip();
                String text = line.substring(eq + 1).strip();
                if (!filter.isEmpty() && !text.isEmpty()) pages.add(new Page(filter, text));
            }
        } catch (IOException e) {
            NeiAddon.LOGGER.error("Could not read {}", file, e);
        }
        return pages;
    }

    /** Builds an item predicate from a filter, or null if the filter is malformed. */
    public static Predicate<Item> compile(String filter) {
        try {
            if (filter.startsWith("@")) {
                String ns = filter.substring(1);
                return item -> BuiltInRegistries.ITEM.getKey(item)
                    .getNamespace()
                    .equals(ns);
            }
            if (filter.startsWith("$")) {
                String name = filter.substring(1);
                TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(name.contains(":") ? name : "c:" + name));
                return item -> new ItemStack(item).is(tag);
            }
            if (filter.contains("*")) {
                Pattern p = Pattern.compile("\\Q" + filter.replace("*", "\\E.*\\Q") + "\\E");
                return item -> p.matcher(BuiltInRegistries.ITEM.getKey(item).toString()).matches();
            }
            ResourceLocation id = ResourceLocation.parse(filter);
            return item -> BuiltInRegistries.ITEM.getKey(item).equals(id);
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Ignoring bad info page filter '{}': {}", filter, e.getMessage());
            return null;
        }
    }

    /** Splits description text into paragraphs, translating when the text is a translation key. */
    public static Component[] components(String text) {
        String[] parts = text.split("\\\\n");
        List<Component> out = new ArrayList<>();
        for (String part : parts) {
            String p = part.strip();
            out.add(p.isEmpty() ? Component.literal(" ") : Component.translatableWithFallback(p, p));
        }
        return out.toArray(new Component[0]);
    }
}
