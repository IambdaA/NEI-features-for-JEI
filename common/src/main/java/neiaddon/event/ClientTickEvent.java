package neiaddon.event;

public final class ClientTickEvent {

    private ClientTickEvent() {}

    /** End of a client tick. */
    public static final class Post extends Event {}
}
