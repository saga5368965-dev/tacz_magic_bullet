package saga.tacz_magic_bullet.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import saga.tacz_magic_bullet.Tacz_magic_bullet;

public class PacketHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(

            new ResourceLocation(Tacz_magic_bullet.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, ToggleMagicPacket.class, ToggleMagicPacket::encode, ToggleMagicPacket::decode, ToggleMagicPacket::handle);
    }
}
