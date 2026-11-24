package neiaddon.event;

/**
 * Raw input, before the open screen or any other mod's mouse handling sees it. EMI takes the presses on its item list
 * before the screen events, so the addon reads them here (see {@link MouseButtonPre}).
 */
public final class InputEvent {

    private InputEvent() {}

    /**
     * A mouse button pressed (never a release), whatever screen is open (Forge and NeoForge also post it with no screen open, Fabric only with one; the handlers look at it).
     * Canceling it keeps the press from the screen and from every other mod. Forge and NeoForge post it from
     * their own raw mouse event before the screen calls; Fabric from the head of the same method.
     */
    public static final class MouseButtonPre extends Event {

        private final int button;

        public MouseButtonPre(int button) {
            this.button = button;
        }

        public int getButton() {
            return button;
        }
    }
}
