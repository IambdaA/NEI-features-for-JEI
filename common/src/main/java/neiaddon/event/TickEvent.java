package neiaddon.event;

/** Client tick and frame events (Forge 1.20.1's TickEvent: handlers look at {@link #phase}). */
public final class TickEvent {

    private TickEvent() {}

    public enum Phase {
        START,
        END
    }

    /** A client tick. */
    public static final class ClientTickEvent extends Event {

        public final Phase phase;

        public ClientTickEvent(Phase phase) {
            this.phase = phase;
        }
    }

    /** A rendered frame. */
    public static final class RenderTickEvent extends Event {

        public final Phase phase;

        public RenderTickEvent(Phase phase) {
            this.phase = phase;
        }
    }
}
