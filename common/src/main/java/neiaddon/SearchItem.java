package neiaddon;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import neiaddon.platform.Platform;
import neiaddon.search.SearchQuery;

/**
 * An item as the NEI search language sees it: the name for plain terms, {@code @} the mod (id or display name),
 * {@code #} the tooltip text, {@code $} the item tags and {@code &} the registry id. The texts are worked out the first
 * time they are asked for and kept per item.
 */
public final class SearchItem implements SearchQuery.Item {

    private static final Map<String, SearchItem> cache = new HashMap<>();

    private final ItemStack stack;
    private final String id;
    private String name, mod, tooltip, tags;

    private SearchItem(ItemStack stack, String id) {
        this.stack = McCompat.copyWithCount(stack, 1);
        this.id = id;
    }

    public static SearchItem of(ItemStack stack) {
        String id = RecipeData.idOf(stack);
        return cache.computeIfAbsent(id, k -> new SearchItem(stack, id));
    }

    public static void reset() {
        cache.clear();
    }

    public ItemStack stack() {
        return stack;
    }

    @Override
    public boolean test(char prefix, String text, boolean regex) {
        return SearchQuery.textMatches(switch (prefix) {
            case '@' -> mod();
            case '#' -> tooltip();
            case '$' -> tags();
            case '&' -> id;
            default -> name();
        }, text, regex);
    }

    private String name() {
        if (name == null) name = stack.getHoverName()
            .getString();
        return name;
    }

    private String mod() {
        if (mod == null) {
            String ns = BuiltInRegistries.ITEM.getKey(stack.getItem())
                .getNamespace();
            String display = ns;
            try {
                display = Platform.get().modDisplayName(ns).orElse(ns);
            } catch (RuntimeException ignored) {}
            mod = ns + " " + display;
        }
        return mod;
    }

    private String tooltip() {
        if (tooltip == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return "";
            HotkeyTips.building = true;
            try {
                tooltip = stack.getTooltipLines(mc.player, TooltipFlag.Default.NORMAL)
                    .stream()
                    .map(c -> c.getString())
                    .collect(Collectors.joining("\n"));
            } catch (RuntimeException e) {
                tooltip = "";
            } finally {
                HotkeyTips.building = false;
            }
        }
        return tooltip;
    }

    private String tags() {
        if (tags == null) tags = BuiltInRegistries.ITEM.wrapAsHolder(stack.getItem())
            .tags()
            .map(
                t -> t.location()
                    .toString())
            .collect(Collectors.joining(" "));
        return tags;
    }
}
