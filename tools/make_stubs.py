#!/usr/bin/env python3
"""
Writes tiny stand-ins for the Minecraft / NeoForge / JEI classes the model code touches, so javac can check the
addon's own logic (generics, typos, missing imports) without the real game jars:

    python3 tools/make_stubs.py /tmp/stubcheck/src
    javac -d /tmp/stubcheck/out $(find /tmp/stubcheck/src -name '*.java') src/main/java/neiaddon/chain/*.java \
        src/main/java/neiaddon/{Bookmarks,RecipeData,RecipeShape,RecipeTree,RecipeLookup,RecipeHearts,Favorites}.java

The signatures mirror the real ones (JEI ones were copied from the JEI 30.42 API sources). This is NOT a substitute for
the real build; it only catches mistakes in our own code.
"""
import os, sys

root = sys.argv[1] if len(sys.argv) > 1 else "/tmp/stubcheck/src"

files = {}

def f(path, body):
    files[path] = body

# ---- minecraft
f("net/minecraft/world/item/Item.java", """package net.minecraft.world.item;
import java.util.HashMap;
import java.util.Map;
public class Item {
  public static final Map<String, Item> REGISTRY = new HashMap<>();
  public final String id;
  public Item(String id) { this.id = id; REGISTRY.put(id, this); }
  public static Item of(String id) { Item i = REGISTRY.get(id); return i != null ? i : new Item(id); }
  public ItemStack getDefaultInstance() { return new ItemStack(this); }
  public interface TooltipContext { static TooltipContext of(Object level) { return null; } }
}
""")
f("net/minecraft/world/item/ItemStack.java", """package net.minecraft.world.item;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
public class ItemStack {
  public static final ItemStack EMPTY = new ItemStack((Item) null);
  private final Item item;
  private final int count;
  public java.util.List<Component> getTooltipLines(Item.TooltipContext c, Object player, TooltipFlag f) { return java.util.List.of(); }
  public int getMaxStackSize() { return 64; }
  public ItemStack(Item i) { this(i, 1); }
  public ItemStack(Item i, int c) { item = i; count = c; }
  public boolean isEmpty() { return item == null; }
  public Item getItem() { return item; }
  public ItemStack copyWithCount(int n) { return new ItemStack(item, n); }
  public int getCount() { return count; }
  public static boolean isSameItemSameComponents(ItemStack a, ItemStack b) { return a.item == b.item; }
  public Component getHoverName() { return Component.literal(""); }
  public DataComponentPatch getComponentsPatch() { return new DataComponentPatch(); }
}
""")
f("net/minecraft/core/component/DataComponentPatch.java", """package net.minecraft.core.component;
public class DataComponentPatch { @Override public boolean equals(Object o) { return o instanceof DataComponentPatch; } @Override public int hashCode() { return 1; } }
""")
f("net/minecraft/network/chat/FormattedText.java", """package net.minecraft.network.chat;
public interface FormattedText {}
""")
f("net/minecraft/network/chat/Component.java", """package net.minecraft.network.chat;
public interface Component extends FormattedText {
  String getString();
  default net.minecraft.util.FormattedCharSequence getVisualOrderText() { return null; }
  static Component literal(String s) { return new Impl(s); }
  static Component empty() { return new Impl(""); }
  default Component append(Component c) { return this; }
  static Component translatable(String s, Object... a) { return new Impl(s); }
  class Impl implements Component { String s; Impl(String s) { this.s = s; } public String getString() { return s; } }
}
""")
f("net/minecraft/resources/Identifier.java", """package net.minecraft.resources;
public final class Identifier {
  private final String s;
  private Identifier(String s) { this.s = s; }
  public String getNamespace() { return s.substring(0, s.indexOf(':')); }
  public String getPath() { return s.substring(s.indexOf(':') + 1); }
  public static Identifier parse(String s) { return new Identifier(s); }
  public static Identifier fromNamespaceAndPath(String a, String b) { return new Identifier(a + ":" + b); }
  @Override public String toString() { return s; }
  @Override public boolean equals(Object o) { return o instanceof Identifier i && i.s.equals(s); }
  @Override public int hashCode() { return s.hashCode(); }
}
""")
f("net/minecraft/core/Registry.java", """package net.minecraft.core;
import java.util.Optional;
import net.minecraft.resources.Identifier;
public interface Registry<T> {
  Identifier getKey(T t);
  Optional<T> getOptional(Identifier id);
  default Holder<T> wrapAsHolder(T t) { return new Holder<>(); }
  class Holder<T> { public java.util.stream.Stream<Tag> tags() { return java.util.stream.Stream.empty(); } }
  class Tag { public Identifier location() { return null; } }
}
""")
f("net/minecraft/core/registries/BuiltInRegistries.java", """package net.minecraft.core.registries;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
public class BuiltInRegistries {
  public static final Registry<Item> ITEM = new Registry<>() {
    public Identifier getKey(Item i) { return Identifier.parse(i.id); }
    public Optional<Item> getOptional(Identifier id) { return Optional.ofNullable(Item.REGISTRY.get(id.toString())); }
  };
}
""")
f("net/minecraft/util/FormattedCharSequence.java", """package net.minecraft.util;
public interface FormattedCharSequence {}
""")
f("org/joml/Matrix3x2fStack.java", """package org.joml;
public class Matrix3x2fStack { public Matrix3x2fStack pushMatrix() { return this; } public Matrix3x2fStack popMatrix() { return this; }
  public Matrix3x2fStack identity() { return this; } public Matrix3x2fStack translate(float x, float y) { return this; }
  public Matrix3x2fStack scale(float x, float y) { return this; } }
""")
f("net/minecraft/client/gui/Font.java", """package net.minecraft.client.gui;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
public class Font { public int width(net.minecraft.network.chat.Component s) { return 0; } public int width(String s) { return 0; } public int width(FormattedCharSequence s) { return 0; } public List<FormattedCharSequence> split(Component c, int w) { return List.of(); } }
""")
f("net/minecraft/world/item/TooltipFlag.java", """package net.minecraft.world.item;
public interface TooltipFlag { enum Default implements TooltipFlag { NORMAL } }
""")
f("net/minecraft/client/gui/screens/inventory/ContainerScreen.java", """package net.minecraft.client.gui.screens.inventory;
public class ContainerScreen extends AbstractContainerScreen<net.minecraft.world.inventory.AbstractContainerMenu> { }
""")
f("net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java", """package net.minecraft.client.gui.screens.inventory;
import net.minecraft.client.gui.screens.Screen;
public class AbstractContainerScreen<T extends net.minecraft.world.inventory.AbstractContainerMenu> extends Screen { public AbstractContainerScreen() { super(null); } public int getLeftPos() { return 0; } public T getMenu() { return null; } }
""")
f("net/minecraft/client/renderer/Rect2i.java", """package net.minecraft.client.renderer;
public class Rect2i { public Rect2i(int a, int b, int c, int d) {} public int getX() { return 0; } public int getY() { return 0; }
  public int getWidth() { return 0; } public int getHeight() { return 0; } }
""")
f("net/minecraft/client/gui/GuiGraphicsExtractor.java", """package net.minecraft.client.gui;
import java.util.List;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;
public class GuiGraphicsExtractor {
  public void fill(int a, int b, int c, int d, int color) {}
  public void text(Font f, String s, int x, int y, int color, boolean shadow) {}
  public void text(Font f, FormattedCharSequence s, int x, int y, int color) {}
  public void fakeItem(ItemStack s, int x, int y) {}
  public void itemDecorations(Font f, ItemStack s, int x, int y, String text) {}
  public void nextStratum() {}
  public void blit(Object pipeline, net.minecraft.resources.Identifier id, int x, int y, float u, float v, int w, int h, int tw, int th) {}
  public void enableScissor(int a, int b, int c, int d) {}
  public void disableScissor() {}
  public Matrix3x2fStack pose() { return new Matrix3x2fStack(); }
  public void setTooltipForNextFrame(Font f, List<FormattedCharSequence> l, int x, int y) {}
}
""")
f("net/minecraft/client/gui/screens/Screen.java", """package net.minecraft.client.gui.screens;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
public class Screen {
  public int width; public int height; protected Font font = new Font();
  protected Screen(Component title) {}
  protected void init() {}
  public void onClose() {}
  public boolean isPauseScreen() { return true; }
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {}
  public boolean mouseClicked(MouseButtonEvent e, boolean dbl) { return false; }
  public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) { return false; }
  public boolean mouseReleased(MouseButtonEvent e) { return false; }
  public boolean mouseScrolled(double x, double y, double sx, double sy) { return false; }
  public boolean keyPressed(KeyEvent e) { return false; }
  public boolean charTyped(CharacterEvent e) { return false; }
}
""")
f("net/minecraft/client/Minecraft.java", """package net.minecraft.client;
import net.minecraft.client.gui.screens.Screen;
public class Minecraft {
  public static class Gui { public Screen screen() { return null; } public void setScreen(Screen s) {} }
  public static class Options { public final KeyMapping keyInventory = new KeyMapping(); }
  public final Options options = new Options();
  public net.minecraft.client.sounds.SoundManager getSoundManager() { return new net.minecraft.client.sounds.SoundManager(); }
  public final Gui gui = new Gui();
  public final net.minecraft.client.gui.Font font = new net.minecraft.client.gui.Font();
  public Object level; public net.minecraft.world.entity.player.Player player; public net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode;
  public static Minecraft getInstance() { return new Minecraft(); }
  public boolean hasShiftDown() { return false; }
  public boolean hasControlDown() { return false; }
  public boolean hasAltDown() { return false; }
}
""")
f("net/minecraft/client/KeyMapping.java", """package net.minecraft.client;
import net.minecraft.network.chat.Component;
public class KeyMapping { public Component getTranslatedKeyMessage() { return Component.literal(""); }
  public boolean isActiveAndMatches(com.mojang.blaze3d.platform.InputConstants.Key k) { return false; } }
""")
# ---- neoforge
f("net/neoforged/fml/loading/FMLPaths.java", """package net.neoforged.fml.loading;
import java.nio.file.Path;
public enum FMLPaths { CONFIGDIR; public Path get() { return Path.of("/tmp/stubcheck/config"); } }
""")
f("net/neoforged/api/distmarker/Dist.java", """package net.neoforged.api.distmarker;
public enum Dist { CLIENT, DEDICATED_SERVER }
""")
f("net/neoforged/bus/api/SubscribeEvent.java", """package net.neoforged.bus.api;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) public @interface SubscribeEvent { EventPriority priority() default EventPriority.NORMAL; }
""")
f("net/neoforged/bus/api/EventPriority.java", """package net.neoforged.bus.api;
public enum EventPriority { HIGHEST, HIGH, NORMAL, LOW, LOWEST }
""")
f("net/neoforged/fml/common/EventBusSubscriber.java", """package net.neoforged.fml.common;
import java.lang.annotation.*;
import net.neoforged.api.distmarker.Dist;
@Retention(RetentionPolicy.RUNTIME) public @interface EventBusSubscriber { String modid() default ""; Dist[] value() default {}; }
""")
f("net/neoforged/neoforge/client/event/ScreenEvent.java", """package net.neoforged.neoforge.client.event;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
public class ScreenEvent {
  public static class Base { public Screen getScreen() { return null; } public void setCanceled(boolean b) {} }
  public static class MouseButtonBase extends Base { public double getMouseX() { return 0; } public double getMouseY() { return 0; } public int getButton() { return 0; } }
  public static class MouseScrolled { public static class Pre extends MouseButtonBase { public double getScrollDeltaY() { return 0; } } }
  public static class MouseButtonPressed { public static class Pre extends MouseButtonBase {} }
  public static class MouseButtonReleased { public static class Pre extends MouseButtonBase {} }
  public static class MouseDragged { public static class Pre extends MouseButtonBase {} }
  public static class Opening extends Base { public Screen getNewScreen() { return null; } }
  public static class CharacterTyped { public static class Pre extends Base { public int getCodePoint() { return 0; } } }
  public static class KeyPressed { public static class Pre extends Base { public int getKeyCode() { return 0; } } }
  public static class Render { public static class Foreground extends Base { public GuiGraphicsExtractor getGuiGraphics() { return null; }
     public int getMouseX() { return 0; } public int getMouseY() { return 0; } } }
}
""")
# ---- addon bits the model code reaches into but that are not under test here
f("neiaddon/NeiAddon.java", """package neiaddon;
public class NeiAddon {
  public interface Log { void warn(String s, Object... o); void info(String s, Object... o); }
  public static final String MODID = "neiaddon";
  public static final Log LOGGER = new Log() { public void warn(String s, Object... o) {} public void info(String s, Object... o) {} };
}
""")
f("neiaddon/Calculator.java", """package neiaddon;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import mezz.jei.api.runtime.IJeiRuntime;
import java.util.Map;
public final class Calculator {
  public static IJeiRuntime runtime;
  public record Plan(Map<StackKey, Long> raw) {}
  public static Plan compute(Map<StackKey, Long> wanted) { return null; }
  public static boolean available() { return runtime != null; }
  public static final class StackKey {
    public final Item item; public final DataComponentPatch patch; public final ItemStack sample;
    public StackKey(ItemStack stack) { item = stack.getItem(); patch = stack.getComponentsPatch(); sample = stack.copyWithCount(1); }
    @Override public boolean equals(Object o) { return o instanceof StackKey k && k.item == item && k.patch.equals(patch); }
    @Override public int hashCode() { return 31 * item.hashCode() + patch.hashCode(); }
  }
}
""")
f("neiaddon/CalculatorScreen.java", """package neiaddon;
public class CalculatorScreen { public static void open(Bookmarks.Group g) {} }
""")
f("neiaddon/ShareClient.java", """package neiaddon;
public class ShareClient { public static boolean send(String payload) { return true; } }
""")
f("neiaddon/CalcKeys.java", """package neiaddon;
import net.minecraft.client.KeyMapping;
public class CalcKeys { public static final KeyMapping ADD = new KeyMapping(); public static final KeyMapping TREE = new KeyMapping(); public static final KeyMapping FAVORITE = new KeyMapping(); public static final KeyMapping CRAFT = new KeyMapping(); public static final KeyMapping CHAT_LINK = new KeyMapping(); public static final KeyMapping PULL = new KeyMapping(); }
""")
# ---- JEI (signatures copied from the JEI 30.42 API sources)
f("mezz/jei/api/recipe/RecipeIngredientRole.java", """package mezz.jei.api.recipe;
public enum RecipeIngredientRole { INPUT, OUTPUT, CATALYST, RENDER_ONLY }
""")
f("mezz/jei/api/ingredients/IIngredientType.java", """package mezz.jei.api.ingredients;
public interface IIngredientType<T> { Class<? extends T> getIngredientClass(); }
""")
f("mezz/jei/api/ingredients/ITypedIngredient.java", """package mezz.jei.api.ingredients;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
public interface ITypedIngredient<T> { IIngredientType<T> getType(); default Optional<ItemStack> getItemStack() { return Optional.empty(); } }
""")
f("mezz/jei/api/constants/VanillaTypes.java", """package mezz.jei.api.constants;
import mezz.jei.api.ingredients.IIngredientType;
import net.minecraft.world.item.ItemStack;
public final class VanillaTypes { public static final IIngredientType<ItemStack> ITEM_STACK = null; }
""")
f("mezz/jei/api/recipe/types/IRecipeType.java", """package mezz.jei.api.recipe.types;
import net.minecraft.resources.Identifier;
public interface IRecipeType<T> { Identifier getUid(); Class<? extends T> getRecipeClass(); }
""")
f("mezz/jei/api/recipe/category/IRecipeCategory.java", """package mezz.jei.api.recipe.category;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.resources.Identifier;
public interface IRecipeCategory<T> { IRecipeType<T> getRecipeType(); mezz.jei.api.gui.drawable.IDrawable getIcon(); net.minecraft.network.chat.Component getTitle(); default Identifier getIdentifier(T recipe) { return null; } }
""")
f("mezz/jei/api/gui/ingredient/IRecipeSlotView.java", """package mezz.jei.api.gui.ingredient;
import java.util.List;
import java.util.stream.Stream;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.world.item.ItemStack;
public interface IRecipeSlotView {
  RecipeIngredientRole getRole();
  List<ITypedIngredient<?>> getAllIngredientsList();
  default Stream<ItemStack> getItemStacks() { return Stream.empty(); }
}
""")
f("mezz/jei/api/gui/ingredient/IRecipeSlotDrawable.java", """package mezz.jei.api.gui.ingredient;
public interface IRecipeSlotDrawable extends IRecipeSlotView {}
""")
f("mezz/jei/api/gui/ingredient/IRecipeSlotsView.java", """package mezz.jei.api.gui.ingredient;
import java.util.List;
public interface IRecipeSlotsView { List<IRecipeSlotView> getSlotViews(); }
""")
f("mezz/jei/api/gui/inputs/RecipeSlotUnderMouse.java", """package mezz.jei.api.gui.inputs;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
public record RecipeSlotUnderMouse(IRecipeSlotDrawable slot, int xOffset) {}
""")
f("mezz/jei/api/gui/inputs/IJeiUserInput.java", """package mezz.jei.api.gui.inputs;
public interface IJeiUserInput { boolean isSimulate(); }
""")
f("mezz/jei/api/gui/IRecipeLayoutDrawable.java", """package mezz.jei.api.gui;
import java.util.Optional;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.recipe.category.IRecipeCategory;
public interface IRecipeLayoutDrawable<R> {
  boolean isMouseOver(double mouseX, double mouseY);
  Optional<RecipeSlotUnderMouse> getSlotUnderMouse(double mouseX, double mouseY);
  IRecipeSlotsView getRecipeSlotsView();
  IRecipeCategory<R> getRecipeCategory();
  R getRecipe();
  void setPosition(int x, int y);
  void drawRecipe(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my);
  net.minecraft.client.renderer.Rect2i getRect();
}
""")
f("mezz/jei/api/gui/builder/ITooltipBuilder.java", """package mezz.jei.api.gui.builder;
import net.minecraft.network.chat.FormattedText;
public interface ITooltipBuilder { void add(FormattedText component); }
""")
f("mezz/jei/api/gui/drawable/IDrawable.java", """package mezz.jei.api.gui.drawable;
import net.minecraft.client.gui.GuiGraphicsExtractor;
public interface IDrawable { int getWidth(); int getHeight(); void draw(GuiGraphicsExtractor g, int xOffset, int yOffset); }
""")
f("mezz/jei/api/gui/buttons/IButtonState.java", """package mezz.jei.api.gui.buttons;
import mezz.jei.api.gui.drawable.IDrawable;
public interface IButtonState { void setIcon(IDrawable icon); void setActive(boolean v); void setVisible(boolean v); void setForcePressed(boolean v); }
""")
f("mezz/jei/api/gui/buttons/IIconButtonController.java", """package mezz.jei.api.gui.buttons;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.Rect2i;
public interface IIconButtonController {
  boolean onPress(IJeiUserInput input);
  default void getTooltips(ITooltipBuilder tooltip) {}
  default void initState(IButtonState state) { updateState(state); }
  default void updateState(IButtonState state) {}
  default void drawExtras(GuiGraphicsExtractor g, Rect2i buttonArea, int mouseX, int mouseY, float partialTicks) {}
}
""")
f("mezz/jei/api/recipe/advanced/IRecipeButtonControllerFactory.java", """package mezz.jei.api.recipe.advanced;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IIconButtonController;
public interface IRecipeButtonControllerFactory { <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout); }
""")
f("mezz/jei/api/recipe/IFocus.java", """package mezz.jei.api.recipe;
public interface IFocus<V> {}
""")
f("mezz/jei/api/recipe/IFocusGroup.java", """package mezz.jei.api.recipe;
public interface IFocusGroup {}
""")
f("mezz/jei/api/recipe/IFocusFactory.java", """package mezz.jei.api.recipe;
import mezz.jei.api.ingredients.IIngredientType;
public interface IFocusFactory {
  <V> IFocus<V> createFocus(RecipeIngredientRole role, IIngredientType<V> type, V ingredient);
  IFocusGroup getEmptyFocusGroup();
}
""")
f("mezz/jei/api/recipe/IRecipeLookup.java", """package mezz.jei.api.recipe;
import java.util.Collection;
import java.util.stream.Stream;
public interface IRecipeLookup<R> { IRecipeLookup<R> limitFocus(Collection<? extends IFocus<?>> f); IRecipeLookup<R> includeHidden(); Stream<R> get(); }
""")
f("mezz/jei/api/recipe/IRecipeManager.java", """package mezz.jei.api.recipe;
import java.util.Optional;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.resources.Identifier;
public interface IRecipeManager {
  <R> IRecipeLookup<R> createRecipeLookup(IRecipeType<R> t);
  <T> IRecipeCategory<T> getRecipeCategory(IRecipeType<T> t);
  <T> Optional<IRecipeLayoutDrawable<T>> createRecipeLayoutDrawable(IRecipeCategory<T> c, T r, IFocusGroup g);
  Optional<IRecipeType<?>> getRecipeType(Identifier uid);
}
""")
f("mezz/jei/api/helpers/IJeiHelpers.java", """package mezz.jei.api.helpers;
import mezz.jei.api.recipe.IFocusFactory;
public interface IJeiHelpers { IFocusFactory getFocusFactory(); }
""")
f("mezz/jei/api/runtime/IJeiRuntime.java", """package mezz.jei.api.runtime;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.IRecipeManager;
public interface IJeiRuntime { IRecipeManager getRecipeManager(); IJeiHelpers getJeiHelpers(); IRecipesGui getRecipesGui(); IIngredientListOverlay getIngredientListOverlay(); Object getBookmarkOverlay(); mezz.jei.api.recipe.transfer.IRecipeTransferManager getRecipeTransferManager(); }
""")

f("mezz/jei/api/runtime/IRecipesGui.java", """package mezz.jei.api.runtime;
import mezz.jei.api.recipe.IFocus;
public interface IRecipesGui { <V> void show(IFocus<V> focus); }
""")
f("mezz/jei/api/runtime/IIngredientListOverlay.java", """package mezz.jei.api.runtime;
public interface IIngredientListOverlay { boolean hasKeyboardFocus(); boolean isListDisplayed(); java.util.Optional<mezz.jei.api.ingredients.ITypedIngredient<?>> getIngredientUnderMouse(); }
""")
f("com/mojang/blaze3d/platform/InputConstants.java", """package com.mojang.blaze3d.platform;
public class InputConstants {
  public static class Key {}
  public enum Type { KEYSYM; public Key getOrCreate(int code) { return new Key(); } }
}
""")


f("net/neoforged/fml/ModList.java", """package net.neoforged.fml;
import java.util.Optional;
public class ModList {
  public static ModList get() { return new ModList(); }
  public static class Info { public String getDisplayName() { return "Name"; } }
  public static class Container { public Info getModInfo() { return new Info(); } }
  public Optional<Container> getModContainerById(String id) { return Optional.of(new Container()); }
}
""")
f("net/neoforged/neoforge/event/entity/player/ItemTooltipEvent.java", """package net.neoforged.neoforge.event.entity.player;
public class ItemTooltipEvent {
  public net.minecraft.world.item.ItemStack getItemStack() { return null; }
  public java.util.List<net.minecraft.network.chat.Component> getToolTip() { return null; }
}
""")

f("org/lwjgl/glfw/GLFW.java", """package org.lwjgl.glfw;
public class GLFW { public static final int GLFW_KEY_ESCAPE = 256, GLFW_KEY_ENTER = 257, GLFW_KEY_KP_ENTER = 335, GLFW_KEY_BACKSPACE = 259, GLFW_KEY_UP = 265, GLFW_KEY_DOWN = 264, GLFW_KEY_R = 82, GLFW_KEY_U = 85; }
""")


f("net/minecraft/world/entity/player/Player.java", """package net.minecraft.world.entity.player;
public class Player { public net.minecraft.world.inventory.AbstractContainerMenu containerMenu; }
""")
f("net/minecraft/world/inventory/AbstractContainerMenu.java", """package net.minecraft.world.inventory;
public class AbstractContainerMenu { public java.util.List<Slot> slots = new java.util.ArrayList<>(); public int containerId; public net.minecraft.world.item.ItemStack getCarried() { return net.minecraft.world.item.ItemStack.EMPTY; } }
""")
f("net/minecraft/world/inventory/Slot.java", """package net.minecraft.world.inventory;
import net.minecraft.world.item.ItemStack;
public class Slot { public int index; public boolean hasItem() { return false; } public ItemStack getItem() { return ItemStack.EMPTY; } public boolean mayPickup(net.minecraft.world.entity.player.Player p) { return true; } public boolean mayPlace(ItemStack s) { return true; } public int getMaxStackSize(ItemStack s) { return 64; } }
""")
f("net/minecraft/world/inventory/ResultSlot.java", """package net.minecraft.world.inventory;
public class ResultSlot extends Slot {}
""")
f("net/minecraft/world/inventory/ContainerInput.java", """package net.minecraft.world.inventory;
public enum ContainerInput { PICKUP, QUICK_MOVE }
""")
f("net/minecraft/client/multiplayer/MultiPlayerGameMode.java", """package net.minecraft.client.multiplayer;
import net.minecraft.world.inventory.ContainerInput;
public class MultiPlayerGameMode { public void handleContainerInput(int containerId, int slotId, int button, ContainerInput type, net.minecraft.world.entity.player.Player p) {} }
""")
f("net/neoforged/neoforge/client/event/ClientTickEvent.java", """package net.neoforged.neoforge.client.event;
public class ClientTickEvent { public static class Post {} }
""")
f("mezz/jei/api/recipe/transfer/IRecipeTransferError.java", """package mezz.jei.api.recipe.transfer;
public interface IRecipeTransferError {}
""")
f("mezz/jei/api/recipe/transfer/IRecipeTransferHandler.java", """package mezz.jei.api.recipe.transfer;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
public interface IRecipeTransferHandler<C extends AbstractContainerMenu, R> {
  @Deprecated(since = "30.30.0", forRemoval = true)
  IRecipeTransferError transferRecipe(C container, R recipe, IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer);
}
""")
f("mezz/jei/api/recipe/transfer/IRecipeTransferManager.java", """package mezz.jei.api.recipe.transfer;
import java.util.Optional;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.inventory.AbstractContainerMenu;
public interface IRecipeTransferManager {
  <C extends AbstractContainerMenu, R> Optional<IRecipeTransferHandler<C, R>> getRecipeTransferHandler(C container, IRecipeCategory<R> recipeCategory);
}
""")

f("net/minecraft/client/sounds/SoundManager.java", """package net.minecraft.client.sounds;
public class SoundManager { public void play(net.minecraft.client.resources.sounds.SimpleSoundInstance s) {} }
""")
f("net/minecraft/client/resources/sounds/SimpleSoundInstance.java", """package net.minecraft.client.resources.sounds;
public class SimpleSoundInstance { public static SimpleSoundInstance forUI(Object e, float p) { return new SimpleSoundInstance(); } }
""")
f("net/minecraft/sounds/SoundEvents.java", """package net.minecraft.sounds;
public class SoundEvents { public static final Object UI_BUTTON_CLICK = new Object(); }
""")
f("net/minecraft/client/renderer/RenderPipelines.java", """package net.minecraft.client.renderer;
public class RenderPipelines { public static final Object GUI_TEXTURED = new Object(); }
""")
f("net/minecraft/client/input/KeyEvent.java", """package net.minecraft.client.input;
public interface KeyEvent { int key(); int modifiers(); }
""")
f("net/minecraft/client/input/CharacterEvent.java", """package net.minecraft.client.input;
public interface CharacterEvent { int codepoint(); }
""")
f("net/minecraft/client/input/MouseButtonEvent.java", """package net.minecraft.client.input;
public interface MouseButtonEvent { double x(); double y(); int button(); }
""")
f("net/minecraft/client/gui/components/EditBox.java", """package net.minecraft.client.gui.components;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
public class EditBox {
  public EditBox(Font f, int x, int y, int w, int h, Component c) {}
  public void setMaxLength(int n) {}
  public void setResponder(java.util.function.Consumer<String> r) {}
  public String getValue() { return ""; } public void setValue(String s) {}
  public void setFocused(boolean b) {} public boolean isFocused() { return false; }
  public int getX() { return 0; } public int getY() { return 0; } public int getWidth() { return 0; } public int getHeight() { return 0; }
  public boolean keyPressed(KeyEvent e) { return false; } public boolean charTyped(CharacterEvent e) { return false; }
  public boolean mouseClicked(MouseButtonEvent e, boolean d) { return false; }
  public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {}
}
""")
f("neiaddon/QuantityField.java", """package neiaddon;
import net.minecraft.world.item.ItemStack;
public class QuantityField { public static long scrollMultiplier(ItemStack s) { return 1; } public static boolean focused() { return false; } }
""")
f("neiaddon/AutoCraft.java", """package neiaddon;
public class AutoCraft { public static boolean available() { return true; } public static boolean start(Object a, boolean b) { return true; } }
""")
f("neiaddon/HotkeyTips.java", """package neiaddon;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
public class HotkeyTips {
  public static String keys(String... p) { return ""; }
  public static List<String> hotkeyLines(Map<String, String> m) { return List.of(); }
  public static Component modName(ItemStack s) { return null; }
  public static void recipeAndCopyKeys(Map<String, String> k) {}
}
""")
f("neiaddon/BookmarkPanel.java", """package neiaddon;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.world.item.ItemStack;
public class BookmarkPanel { static void showRecipes(ItemStack s, RecipeIngredientRole r) {} }
""")
f("neiaddon/AddonConfig.java", """package neiaddon;
public class AddonConfig {
  public static class IntV { int v; public int get(){return v;} public void set(int x){v=x;} public void save(){} }
  public static class BoolV { public boolean get(){return true;} }
  public static final IntV ITEM_QUANTITY = new IntV();
  public static final BoolV TREE_ZOOM_WITHOUT_CTRL = new BoolV();
}
""")
for path, body in files.items():
    full = os.path.join(root, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as fh:
        fh.write(body)
print("wrote", len(files), "stubs to", root)
