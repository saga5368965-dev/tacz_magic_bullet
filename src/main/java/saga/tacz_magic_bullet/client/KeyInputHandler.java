package saga.tacz_magic_bullet.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import saga.tacz_magic_bullet.Tacz_magic_bullet;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = Tacz_magic_bullet.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class KeyInputHandler {
    public static final KeyMapping MAGIC_BULLET_TOGGLE = new KeyMapping(
            "key.tacz_magic_bullet.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "category.tacz_magic_bullet"
    );

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(MAGIC_BULLET_TOGGLE);
    }
}