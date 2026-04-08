package saga.tacz_magic_bullet.Event;

import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.EntityKineticBullet;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import io.redspace.ironsspellbooks.network.SyncManaPacket;
import io.redspace.ironsspellbooks.setup.PacketDistributor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import saga.tacz_magic_bullet.Tacz_magic_bullet;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Tacz_magic_bullet.MODID)
public class MagicBulletEvents {
    private static final Map<UUID, CompoundTag> pendingSpells = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onGunFire(GunFireEvent event) {
        if (event.getLogicalSide().isClient()) return;

        if (!(event.getShooter() instanceof Player player)) return;
        if (!player.getPersistentData().getBoolean("MagicBulletEnabled")) return;

        CompoundTag rootTag = event.getGunItemStack().getTag();
        if (rootTag == null || !rootTag.contains("InscribedSpell")) return;

        CompoundTag inscribedSpell = rootTag.getCompound("InscribedSpell");
        AbstractSpell spell = SpellRegistry.getSpell(inscribedSpell.getString("SpellID"));
        int level = inscribedSpell.getInt("Level");

        if (spell == null || spell == SpellRegistry.none()) return;

        MagicData magicData = MagicData.getPlayerMagicData(player);
        float manaCost = spell.getManaCost(level);
        if (!player.isCreative()) {
            if (magicData.getMana() < manaCost) {
                return;
            }
            magicData.setMana(magicData.getMana() - manaCost);
            if (player instanceof ServerPlayer serverPlayer) {
                PacketDistributor.sendToPlayer(serverPlayer, new SyncManaPacket(magicData));
            }
        }
        pendingSpells.put(player.getUUID(), inscribedSpell.copy());
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;

        if (event.getEntity() instanceof EntityKineticBullet bullet) {
            if (bullet.getOwner() instanceof Player player) {
                CompoundTag reservedData = pendingSpells.remove(player.getUUID());
                if (reservedData != null) {
                    bullet.getPersistentData().put("MagicData", reservedData);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntityHurtPre(EntityHurtByGunEvent.Pre event) {
        if (!(event.getAttacker() instanceof Player player)) return;
        if (!(event.getHurtEntity() instanceof LivingEntity target)) return;

        processImpact(player, target, target.position(), event.getBullet());
    }

    @SubscribeEvent
    public static void onAmmoHitBlock(AmmoHitBlockEvent event) {
        if (!(event.getAmmo().getOwner() instanceof Player player)) return;

        Vec3 hitPos = event.getHitResult().getLocation();
        ArmorStand dummy = new ArmorStand(EntityType.ARMOR_STAND, player.level());
        dummy.setPos(hitPos.x, hitPos.y, hitPos.z);
        dummy.setInvisible(true);
        dummy.setInvulnerable(true);
        dummy.setNoGravity(true);

        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Marker", true);
        tag.putBoolean("Small", true);
        dummy.readAdditionalSaveData(tag);

        player.level().addFreshEntity(dummy);

        processImpact(player, dummy, hitPos, event.getAmmo());

        dummy.discard();
    }

    private static void processImpact(Player player, LivingEntity target, Vec3 hitPos, Object bulletObj) {
        CompoundTag magicTag = new CompoundTag();
        if (bulletObj instanceof EntityKineticBullet bullet) {
            if (bullet.getPersistentData().contains("MagicData")) {
                magicTag = bullet.getPersistentData().getCompound("MagicData");
            }
        }
        if (magicTag.isEmpty()) return;

        String spellId = magicTag.getString("SpellID");
        int level = magicTag.getInt("Level");
        AbstractSpell spell = SpellRegistry.getSpell(spellId);

        if (spell == null || spell == SpellRegistry.none()) return;

        MagicData magicData = MagicData.getPlayerMagicData(player);
        if (target != null) {
            magicData.setAdditionalCastData(new TargetEntityCastData(target));
        }
        if (spell.getCastType() == CastType.CONTINUOUS) {
            if (!magicData.isCasting() || !magicData.getCastingSpellId().equals(spellId)) {
                magicData.initiateCast(spell, level, spell.getCastTime(level), CastSource.SWORD, "mainhand");
            }
        }
        spell.onCast(player.level(), level, player, CastSource.SWORD, magicData);
        if (spell.getCastType() == CastType.INSTANT) {
            magicData.setPlayerCastingItem(ItemStack.EMPTY);
            magicData.setAdditionalCastData(null);
        }
    }
}