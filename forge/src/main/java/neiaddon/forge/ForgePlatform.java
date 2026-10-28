package neiaddon.forge;

import java.nio.file.Path;
import java.util.Optional;
import neiaddon.platform.Platform;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.PacketDistributor;

/** Forge's answers to the shared code's questions (both sides). */
final class ForgePlatform implements Platform {

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Optional<String> modDisplayName(String modId) {
        return ModList.get()
            .getModContainerById(modId)
            .map(ModContainer::getModInfo)
            .map(info -> info.getDisplayName());
    }

    @Override
    public ItemStack craftingRemainder(ItemStack stack) {
        return stack.hasCraftingRemainingItem() ? stack.getCraftingRemainingItem() : ItemStack.EMPTY;
    }

    @Override
    public void sendToPlayer(ServerPlayer player, Object message) {
        ForgeNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
