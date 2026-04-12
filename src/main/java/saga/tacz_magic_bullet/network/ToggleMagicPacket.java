package saga.tacz_magic_bullet.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ToggleMagicPacket {
    public ToggleMagicPacket() {}
    public static void encode(ToggleMagicPacket msg, FriendlyByteBuf buf) {}
    public static ToggleMagicPacket decode(FriendlyByteBuf buf) { return new ToggleMagicPacket(); }

    public static void handle(ToggleMagicPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                boolean currentState = player.getPersistentData().getBoolean("MagicBulletEnabled");
                boolean newState = !currentState;
                player.getPersistentData().putBoolean("MagicBulletEnabled", newState);

                // langファイルから翻訳を呼び出す
                String key = newState ? "msg.tacz_magic_bullet.mode.on" : "msg.tacz_magic_bullet.mode.off";
                player.displayClientMessage(Component.translatable(key), true);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
