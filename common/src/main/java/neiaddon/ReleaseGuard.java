package neiaddon;

import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;

/**
 * When the addon uses up a mouse press (a cheat-mode give, a click on a bookmark, ...), the release of that button is kept
 * from the screen too, as JEI does with its own clicks. Otherwise the screen gets a release without its press, and a
 * container screen then handles the item JEI just put on the cursor as if it were being dragged (it had to be held down
 * to stay on the cursor). Handlers of the addon that need the release (dragging in the bookmark panel) still get it: this
 * one runs last and only when nothing else took the release.
 */
public final class ReleaseGuard {

    private static int pending = -1;

    private ReleaseGuard() {}

    public static void pressUsed(int button) {
        pending = button;
    }

    /** A new press starts over (the release of an earlier one may have been taken by another handler of the addon). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        pending = -1;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (pending != event.getButton()) return;
        pending = -1;
        event.setCanceled(true);
    }
}
