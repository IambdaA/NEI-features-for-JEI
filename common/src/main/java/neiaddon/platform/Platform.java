package neiaddon.platform;

import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** What the shared code needs from the loader it runs on (set once by the loader's entry point). */
public interface Platform {

    Holder HOLDER = new Holder();

    static Platform get() {
        Platform p = HOLDER.platform;
        if (p == null) throw new IllegalStateException("The NEI addon's loader entry point has not run yet");
        return p;
    }

    /** The loader's config directory. */
    Path configDir();

    /** The display name of an installed mod, by id. */
    Optional<String> modDisplayName(String modId);

    /** What is left after using the stack once (a bucket after a recipe), or EMPTY. */
    ItemStack craftingRemainder(ItemStack stack);

    /** Server to one client; nothing happens when that client cannot receive it. */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    final class Holder {

        private volatile Platform platform;

        public void set(Platform p) {
            platform = p;
        }
    }
}
