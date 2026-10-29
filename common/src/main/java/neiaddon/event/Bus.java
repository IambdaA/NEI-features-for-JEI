package neiaddon.event;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The addon's own event bus. Handlers are static methods marked {@link SubscribeEvent}; each loader module listens to
 * its loader's events and posts them here, one priority at a time, so the order against other mods' listeners (JEI's)
 * is the loader's own.
 */
public final class Bus {

    private record Handler(Method method) {}

    private static final Map<Class<?>, Map<EventPriority, List<Handler>>> HANDLERS = new HashMap<>();

    private Bus() {}

    /** Registers every {@link SubscribeEvent} method of the class. Done once, before the loader hooks are installed. */
    public static synchronized void register(Class<?> owner) {
        for (Method m : owner.getDeclaredMethods()) {
            SubscribeEvent sub = m.getAnnotation(SubscribeEvent.class);
            if (sub == null) continue;
            if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1
                || !Event.class.isAssignableFrom(m.getParameterTypes()[0])) {
                throw new IllegalArgumentException("Not a static one-event-argument method: " + m);
            }
            m.setAccessible(true);
            HANDLERS.computeIfAbsent(m.getParameterTypes()[0], k -> new EnumMap<>(EventPriority.class))
                .computeIfAbsent(sub.priority(), k -> new ArrayList<>())
                .add(new Handler(m));
        }
    }

    public static synchronized boolean hasHandlers(Class<? extends Event> type, EventPriority priority) {
        Map<EventPriority, List<Handler>> byPriority = HANDLERS.get(type);
        return byPriority != null && byPriority.containsKey(priority);
    }

    /** Whether anything listens to this event at any priority. */
    public static boolean hasHandlers(Class<? extends Event> type) {
        for (EventPriority p : EventPriority.values()) if (hasHandlers(type, p)) return true;
        return false;
    }

    /** Runs the handlers of one priority; a canceled event goes no further. */
    public static void post(Event event, EventPriority priority) {
        List<Handler> list;
        synchronized (Bus.class) {
            Map<EventPriority, List<Handler>> byPriority = HANDLERS.get(event.getClass());
            list = byPriority == null ? null : byPriority.get(priority);
        }
        if (list == null) return;
        for (Handler h : list) {
            if (event.isCanceled()) return;
            try {
                h.method().invoke(null, event);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException r) throw r;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException(cause);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Runs every handler, highest priority first (for hooks that have no ordering against other mods). */
    public static <E extends Event> E post(E event) {
        for (EventPriority p : EventPriority.values()) post(event, p);
        return event;
    }
}
