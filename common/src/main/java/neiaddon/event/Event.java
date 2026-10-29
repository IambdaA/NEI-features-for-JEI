package neiaddon.event;

/** Base of the addon's own events (same names and getters as NeoForge's, so the handlers read the same on every loader). */
public abstract class Event {

    private boolean canceled;

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}
