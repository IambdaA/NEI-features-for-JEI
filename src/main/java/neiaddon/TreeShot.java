package neiaddon;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import neiaddon.chain.TreeCamera;

/**
 * NEI's "Save tree as image": the whole crafting tree as one PNG in the screenshots folder, even the part that does
 * not fit on screen. The tree screen draws the tree alone (no buttons, no stats) one screenful at a time, the frame
 * is read back after it was rendered, and the pieces are stitched together. One tree unit is one GUI unit, so the
 * picture is as sharp as the GUI scale is large.
 */
@Mod.EventBusSubscriber(modid = NeiAddon.MODID, value = Dist.CLIENT)
public final class TreeShot {

    private static final int MARGIN = 8;
    private static final long MAX_PIXELS = 100_000_000L;
    private static final long TIMEOUT_MS = 15_000;

    private static final class Job {

        Screen screen;
        TreeCamera cam;
        int scale;
        int stepX;
        int stepY;
        int columns;
        int rows;
        int index;
        int pending;
        int outWidth;
        int outHeight;
        NativeImage dest;
        boolean drawn;
        long last;
        float savedScrollX;
        float savedScrollY;
        float savedZoom;
        int savedX;
        int savedY;
        int savedW;
        int savedH;
    }

    private static Job job;

    private TreeShot() {}

    public static boolean running(Screen s) {
        return job != null && job.screen == s;
    }

    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        // chat needs the render thread (it bakes font glyphs); the picture is written on a thread of its own
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(text));
        });
    }

    /** Starts saving the tree of {@code screen}; its camera is put back when the picture is done. */
    public static void start(Screen screen, TreeCamera cam, int treeWidth, int treeHeight) {
        if (job != null) return;
        Minecraft mc = Minecraft.getInstance();
        int scale = Math.max(1, (int) Math.round(mc.getWindow().getGuiScale()));
        int stepX = mc.getWindow().getWidth() / scale;
        int stepY = mc.getWindow().getHeight() / scale;
        int unitsX = treeWidth + MARGIN * 2;
        int unitsY = treeHeight + MARGIN * 2;
        long pixels = (long) unitsX * scale * unitsY * scale;
        if (stepX < 16 || stepY < 16 || pixels > MAX_PIXELS || (long) unitsX * scale > 32_000 || (long) unitsY * scale > 32_000) {
            say("The crafting tree is too large to save as one image (" + unitsX * scale + " x " + unitsY * scale + " pixels). Lower the GUI scale and try again.");
            return;
        }
        Job j = new Job();
        j.screen = screen;
        j.cam = cam;
        j.scale = scale;
        j.stepX = stepX;
        j.stepY = stepY;
        j.columns = (unitsX + stepX - 1) / stepX;
        j.rows = (unitsY + stepY - 1) / stepY;
        j.outWidth = unitsX * scale;
        j.outHeight = unitsY * scale;
        j.last = System.currentTimeMillis();
        j.savedScrollX = cam.scrollX;
        j.savedScrollY = cam.scrollY;
        j.savedZoom = cam.zoomLevel;
        j.savedX = cam.widgetX;
        j.savedY = cam.widgetY;
        j.savedW = cam.widgetW;
        j.savedH = cam.widgetH;
        job = j;
    }

    /** Called by the tree screen each frame while saving: puts the camera on the tile this frame has to show. */
    public static void prepare(Screen screen) {
        Job j = job;
        if (j == null || j.screen != screen || j.index >= j.columns * j.rows) return;
        int tx = j.index % j.columns;
        int ty = j.index / j.columns;
        j.cam.setViewport(0, 0, screen.width, screen.height);
        j.cam.zoomLevel = 1.0f;
        j.cam.scrollX = -MARGIN + tx * j.stepX;
        j.cam.scrollY = -MARGIN + ty * j.stepY;
        j.drawn = true;
    }

    // ---------------------------------------------------------------- reading the frame back

    private static RenderTarget cachedTarget;

    /** Higher is more likely the window's own target; the game keeps other targets (entity outlines, weather...) too. */
    private static int score(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        int score = 0;
        if (p.contains("main")) score += 10;
        for (String bad : new String[] { "outline", "weather", "cloud", "particle", "translucent", "itementity", "entity", "temp", "bloom", "blur" }) {
            if (p.contains(bad)) score -= 10;
        }
        return score;
    }

    /**
     * The window's main render target. Its accessor moved between versions, so every RenderTarget reachable from the
     * Minecraft instance through fields of the game's own classes (three levels deep) is collected and the one whose
     * name says "main" (and not "outline", "weather"...) is used. All of them are written to the log once.
     */
    private static RenderTarget mainTarget(Minecraft mc) throws ReflectiveOperationException {
        if (cachedTarget != null) return cachedTarget;
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Map<RenderTarget, String> found = new java.util.LinkedHashMap<>();
        java.util.List<Object[]> level = new java.util.ArrayList<>();
        level.add(new Object[] { mc, "Minecraft" });
        seen.add(mc);
        for (int depth = 0; depth < 3 && !level.isEmpty() && seen.size() < 5000; depth++) {
            java.util.List<Object[]> next = new java.util.ArrayList<>();
            for (Object[] entry : level) {
                Object holder = entry[0];
                for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType()
                            .isPrimitive()) continue;
                        Object value;
                        try {
                            f.setAccessible(true);
                            value = f.get(holder);
                        } catch (RuntimeException | IllegalAccessException e) {
                            continue;
                        }
                        if (value == null) continue;
                        String path = entry[1] + "." + f.getName();
                        if (value instanceof RenderTarget target) {
                            found.putIfAbsent(target, path);
                            continue;
                        }
                        String type = value.getClass()
                            .getName();
                        if ((type.startsWith("net.minecraft.client") || type.startsWith("com.mojang.blaze3d")) && seen.add(value)) {
                            next.add(new Object[] { value, path });
                        }
                    }
                }
            }
            level = next;
        }
        RenderTarget best = null;
        int bestScore = Integer.MIN_VALUE;
        StringBuilder all = new StringBuilder();
        for (java.util.Map.Entry<RenderTarget, String> e : found.entrySet()) {
            all.append(e.getValue())
                .append(" (")
                .append(e.getKey()
                    .getClass()
                    .getSimpleName())
                .append("); ");
            int score = score(e.getValue());
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        NeiAddon.LOGGER.info("Crafting tree picture: render targets found: {}", all);
        if (best == null) throw new NoSuchFieldException("the main render target (looked at " + seen.size() + " objects)");
        cachedTarget = best;
        return best;
    }

    /** The frame in the main render target; older versions return it, newer ones hand it to a callback. */
    private static void grab(Minecraft mc, Consumer<NativeImage> callback) throws ReflectiveOperationException {
        StringBuilder seen = new StringBuilder();
        for (Method m : Screenshot.class.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            seen.append(m)
                .append("; ");
            if (!m.getName()
                .equals("takeScreenshot")) continue;
            Object[] args = new Object[m.getParameterCount()];
            boolean wantsCallback = false;
            boolean ok = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> type = m.getParameterTypes()[i];
                if (type.isAssignableFrom(RenderTarget.class) && type != Object.class) args[i] = mainTarget(mc);
                else if (type == int.class) args[i] = 1;
                else if (type == Consumer.class) {
                    args[i] = callback;
                    wantsCallback = true;
                } else ok = false;
            }
            if (!ok) continue;
            Object result = m.invoke(null, args);
            if (!wantsCallback && result instanceof NativeImage image) callback.accept(image);
            return;
        }
        throw new NoSuchMethodException("Screenshot.takeScreenshot among " + seen);
    }

    @SubscribeEvent
    static void onFrameDone(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Job j = job;
        if (j == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (McCompat.screen() != j.screen) {
            cancel(j, "The crafting tree picture was cancelled (screen closed).");
            return;
        }
        if (System.currentTimeMillis() - j.last > TIMEOUT_MS) {
            cancel(j, "The crafting tree picture timed out.");
            return;
        }
        if (!j.drawn || j.index >= j.columns * j.rows) return;
        j.drawn = false;
        int tile = j.index++;
        j.pending++;
        try {
            grab(mc, image -> merge(j, tile, image));
        } catch (ReflectiveOperationException | RuntimeException e) {
            NeiAddon.LOGGER.warn("Crafting tree picture: could not read the frame", e);
            j.pending--;
            cancel(j, "The crafting tree picture failed (see the log).");
        }
    }

    private static void merge(Job j, int tile, NativeImage image) {
        try {
            if (job != j) return;
            if (j.dest == null) j.dest = new NativeImage(image.format(), j.outWidth, j.outHeight, false);
            int tx = tile % j.columns;
            int ty = tile / j.columns;
            int x0 = tx * j.stepX * j.scale;
            int y0 = ty * j.stepY * j.scale;
            int w = Math.min(Math.min(j.stepX * j.scale, j.outWidth - x0), image.getWidth());
            int h = Math.min(Math.min(j.stepY * j.scale, j.outHeight - y0), image.getHeight());
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    j.dest.setPixelRGBA(x0 + x, y0 + y, image.getPixelRGBA(x, y) | 0xFF000000);
                }
            }
            j.last = System.currentTimeMillis();
        } finally {
            image.close();
            j.pending--;
            if (job == j && j.index >= j.columns * j.rows && j.pending == 0) finish(j);
        }
    }

    private static void restore(Job j) {
        j.cam.scrollX = j.savedScrollX;
        j.cam.scrollY = j.savedScrollY;
        j.cam.zoomLevel = j.savedZoom;
        j.cam.setViewport(j.savedX, j.savedY, j.savedW, j.savedH);
    }

    private static void cancel(Job j, String why) {
        if (job != j) return;
        job = null;
        restore(j);
        if (j.dest != null) j.dest.close();
        say(why);
    }

    private static void finish(Job j) {
        job = null;
        restore(j);
        NativeImage image = j.dest;
        if (image == null) return;
        File dir = new File(Minecraft.getInstance().gameDirectory, "screenshots");
        dir.mkdirs();
        String name = "crafting_tree_" + LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".png";
        Path file = new File(dir, name).toPath();
        Thread writer = new Thread(() -> {
            try {
                image.writeToFile(file);
                say("Saved the crafting tree as screenshots/" + name);
            } catch (java.io.IOException | RuntimeException e) {
                NeiAddon.LOGGER.warn("Crafting tree picture: could not write the file", e);
                say("Could not save the crafting tree picture (see the log).");
            } finally {
                image.close();
            }
        }, "neiaddon-tree-picture");
        writer.setDaemon(true);
        writer.start();
    }
}
