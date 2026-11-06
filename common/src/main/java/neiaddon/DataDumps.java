package neiaddon;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;

/**
 * NEI's data dumps: a table of everything the game registers (items, blocks, potions, enchantments, biomes, ...) as a
 * CSV file under {@code <game folder>/neiaddon/dumps}, started from the Data Dumps page of the settings.
 *
 * <p>
 * Ported from GTNH NotEnoughItems (LGPL-3.0-or-later).
 */
public final class DataDumps {

    private DataDumps() {}

    private static File folder() {
        File dir = new File(Minecraft.getInstance().gameDirectory, "neiaddon/dumps");
        dir.mkdirs();
        return dir;
    }

    private static String csv(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    private static void say(String text) {
        NeiAddon.LOGGER.info(text);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
    }

    private static void write(String name, String header, List<String> rows) {
        File file = new File(folder(), name + ".csv");
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add(header);
        lines.addAll(rows);
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            say("Dumped " + rows.size() + " rows to " + file.getPath());
        } catch (IOException e) {
            NeiAddon.LOGGER.warn("Could not write the {} dump", name, e);
            say("Could not write " + file.getPath() + ": " + e.getMessage());
        }
    }

    private static String line(String... cells) {
        List<String> out = new ArrayList<>();
        for (String c : cells) out.add(csv(c));
        return String.join(",", out);
    }

    /** One row per entry of a registry: id, mod and whatever {@code extra} adds. */
    private static <T> List<String> rows(Registry<T> registry, Function<T, String[]> extra) {
        List<String> rows = new ArrayList<>();
        for (T value : registry) {
            ResourceLocation id = registry.getKey(value);
            if (id == null) continue;
            List<String> cells = new ArrayList<>();
            cells.add(id.toString());
            cells.add(id.getNamespace());
            for (String c : extra.apply(value)) cells.add(c);
            rows.add(line(cells.toArray(new String[0])));
        }
        rows.sort(String::compareTo);
        return rows;
    }

    private static String translated(String prefix, ResourceLocation id) {
        return Component.translatable(prefix + "." + id.getNamespace() + "." + id.getPath())
            .getString();
    }

    // ---------------------------------------------------------------- the dumps

    public static void items() {
        write("items", "id,mod,name,max_stack,max_damage", rows(BuiltInRegistries.ITEM, (Item item) -> {
            ItemStack stack = new ItemStack(item);
            return new String[] { stack.getHoverName()
                .getString(), String.valueOf(stack.getMaxStackSize()), String.valueOf(stack.getMaxDamage()) };
        }));
    }

    public static void blocks() {
        write("blocks", "id,mod,name,hardness", rows(BuiltInRegistries.BLOCK, (Block block) -> new String[] { block.getName()
            .getString(),
            String.valueOf(
                block.defaultBlockState()
                    .getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) }));
    }

    public static void effects() {
        write("potion_effects", "id,mod,name", rows(BuiltInRegistries.MOB_EFFECT, effect -> new String[] { translated("effect", BuiltInRegistries.MOB_EFFECT.getKey(effect)) }));
    }

    public static void potions() {
        write("potions", "id,mod", rows(BuiltInRegistries.POTION, potion -> new String[0]));
    }

    public static void entities() {
        write("entities", "id,mod,name", rows(BuiltInRegistries.ENTITY_TYPE, (EntityType<?> type) -> new String[] { type.getDescription()
            .getString() }));
    }

    public static void enchantments() {
        Registry<net.minecraft.world.item.enchantment.Enchantment> registry = dynamic(Registries.ENCHANTMENT);
        if (registry == null) return;
        write("enchantments", "id,mod,name", rows(registry, e -> new String[] { translated("enchantment", registry.getKey(e)) }));
    }

    public static void biomes() {
        Registry<net.minecraft.world.level.biome.Biome> registry = dynamic(Registries.BIOME);
        if (registry == null) return;
        write("biomes", "id,mod,name", rows(registry, b -> new String[] { translated("biome", registry.getKey(b)) }));
    }

    /** Everything JEI lists as an item (its item panel), one row per stack: id with its data, name and mod. */
    public static void jeiItems() {
        if (!Calculator.available()) {
            say("JEI is not ready yet: join a world first.");
            return;
        }
        List<String> rows = new ArrayList<>();
        for (ItemStack stack : Calculator.runtime.getIngredientManager()
            .getAllItemStacks()) {
            rows.add(line(RecipeData.idOf(stack), FluidStacks.namespaceOf(stack), FluidStacks.name(stack)
                .getString()));
        }
        rows.sort(String::compareTo);
        write("jei_item_list", "id,mod,name", rows);
    }

    /** A registry the world's data packs fill; only there once a world is loaded. */
    private static <T> Registry<T> dynamic(ResourceKey<? extends Registry<T>> key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            say("Join a world first: this registry comes with the world.");
            return null;
        }
        return mc.level.registryAccess()
            .registryOrThrow(key);
    }
}
