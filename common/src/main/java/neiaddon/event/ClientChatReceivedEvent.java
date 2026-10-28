package neiaddon.event;

import net.minecraft.network.chat.Component;

public final class ClientChatReceivedEvent extends Event {

    private final Component message;

    public ClientChatReceivedEvent(Component message) {
        this.message = message;
    }

    public Component getMessage() {
        return message;
    }
}
