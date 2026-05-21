package saga.tacz_magic_bullet.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ToggleMagicPacket {
    public ToggleMagicPacket() {}

    public static void encode(ToggleMagicPacket msg, FriendlyByteBuf buf) {
        // 送信データなし
    }

    public static ToggleMagicPacket decode(FriendlyByteBuf buf) {
        return new ToggleMagicPacket();
    }

    public static void handle(ToggleMagicPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isServer()) {
                // サーバー側の処理
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    // 現在の状態を取得（デフォルトはfalse）
                    boolean currentState = player.getPersistentData().getBoolean("MagicBulletEnabled");
                    boolean newState = !currentState;

                    // 状態を切り替え
                    player.getPersistentData().putBoolean("MagicBulletEnabled", newState);

                    // アクションバーにメッセージを表示
                    String key = newState ? "msg.tacz_magic_bullet.mode.on" : "msg.tacz_magic_bullet.mode.off";
                    player.displayClientMessage(Component.translatable(key), true);

                    // クライアントに同期するためのパケットを送信
                    // NetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new SyncMagicStatePacket(newState));
                    // 上記はNetworkHandlerが必要な場合の例。簡易的な方法として、直接NBT同期に任せることも可能
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}