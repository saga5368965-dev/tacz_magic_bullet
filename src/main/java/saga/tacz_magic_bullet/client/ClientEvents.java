package saga.tacz_magic_bullet.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import saga.tacz_magic_bullet.Tacz_magic_bullet;
import saga.tacz_magic_bullet.network.PacketHandler;
import saga.tacz_magic_bullet.network.ToggleMagicPacket;

@Mod.EventBusSubscriber(modid = Tacz_magic_bullet.MODID, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            while (KeyInputHandler.MAGIC_BULLET_TOGGLE.consumeClick()) {
                PacketHandler.CHANNEL.sendToServer(new ToggleMagicPacket());
            }
        }
    }
}