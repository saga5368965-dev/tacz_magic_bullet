package saga.tacz_magic_bullet.item;

import com.tacz.guns.api.item.IGun;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import saga.tacz_magic_bullet.Tacz_magic_bullet;

import java.util.List;

@Mod.EventBusSubscriber(modid = Tacz_magic_bullet.MODID)
public class GunTooltipHandler {

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof IGun)) return;

        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("InscribedSpell")) {
            CompoundTag spellTag = tag.getCompound("InscribedSpell");
            String spellId = spellTag.getString("SpellID");
            int level = spellTag.getInt("Level");

            AbstractSpell spell = SpellRegistry.getSpell(spellId);
            if (spell != null && spell != SpellRegistry.none()) {
                List<Component> tooltip = event.getToolTip();
                tooltip.add(Component.literal(""));
                tooltip.add(Component.literal("§7┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈"));
                tooltip.add(Component.literal("§d§l✦ 刻印された魔法 ✦"));
                tooltip.add(Component.literal("§7 呪文: §b" + spell.getSpellName()));
                tooltip.add(Component.literal("§7 レベル: §a" + level));
                tooltip.add(Component.literal("§7┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈"));
            }
        }
    }
}

