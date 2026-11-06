package neiaddon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Sends a command as the player, the way typing it in chat would. The server decides whether the player may use it. */
final class UtilityCommands {

    private UtilityCommands() {}

    /** @param command the command without the leading slash */
    static void run(String command) {
        ClientPacketListener connection = Minecraft.getInstance()
            .getConnection();
        if (connection == null) return;
        connection.sendCommand(command);
    }
}
