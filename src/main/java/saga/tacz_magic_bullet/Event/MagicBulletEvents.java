package saga.tacz_magic_bullet.Event;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.EntityKineticBullet;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import saga.tacz_magic_bullet.Tacz_magic_bullet;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Tacz_magic_bullet.MODID)
public class MagicBulletEvents {

    private static final double BULLET_SEARCH_RADIUS = 100.0;
    private static final Map<UUID, SpellData> pendingPlayers = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onGunFire(GunFireEvent event) {
        if (event.isCanceled() || event.getLogicalSide() != LogicalSide.SERVER) return;
        if (!(event.getShooter() instanceof Player player)) return;
        if (!player.getPersistentData().getBoolean("MagicBulletEnabled")) return;

        ItemStack gunItem = event.getGunItemStack();
        if (!(gunItem.getItem() instanceof IGun)) return;

        SpellData inscribedSpell = getInscribedSpell(gunItem);
        if (inscribedSpell == null || inscribedSpell == SpellData.EMPTY) return;

        MagicData magicData = MagicData.getPlayerMagicData(player);
        AbstractSpell spell = inscribedSpell.getSpell();
        int level = inscribedSpell.getLevel();
        float manaCost = spell.getManaCost(level);

        if (magicData.getMana() < manaCost) return;

        magicData.setMana(magicData.getMana() - manaCost);
        pendingPlayers.put(player.getUUID(), inscribedSpell);

        Tacz_magic_bullet.LOGGER.info("[MagicBullet] Spell '{}' queued for player {}",
                spell.getSpellId(), player.getName().getString());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (pendingPlayers.isEmpty()) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (UUID playerId : pendingPlayers.keySet()) {
            SpellData spellData = pendingPlayers.get(playerId);
            if (spellData == null) continue;

            Player player = server.getPlayerList().getPlayer(playerId);
            if (player == null) continue;

            AABB searchBox = new AABB(
                    player.getX() - BULLET_SEARCH_RADIUS, player.getY() - BULLET_SEARCH_RADIUS, player.getZ() - BULLET_SEARCH_RADIUS,
                    player.getX() + BULLET_SEARCH_RADIUS, player.getY() + BULLET_SEARCH_RADIUS, player.getZ() + BULLET_SEARCH_RADIUS
            );

            var bullets = player.level().getEntitiesOfClass(EntityKineticBullet.class, searchBox,
                    bullet -> bullet.getOwner() == player && bullet.tickCount <= 5);

            if (!bullets.isEmpty()) {
                bullets.stream()
                        .min((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)))
                        .ifPresent(bullet -> {
                            bullet.getPersistentData().put("MagicData", createMagicTag(spellData.getSpell(), spellData.getLevel()));
                            Tacz_magic_bullet.LOGGER.info("[MagicBullet] Spell attached to bullet! Bullet ID: {}", bullet.getId());
                        });
                pendingPlayers.remove(playerId);
            }
        }
    }

    // ★ 変更点: LivingHurtEvent → EntityHurtByGunEvent.Pre
    @SubscribeEvent
    public static void onEntityHurtByGun(EntityHurtByGunEvent.Pre event) {
        // 弾丸を取得
        if (!(event.getBullet() instanceof EntityKineticBullet bullet)) return;
        if (!(bullet.getOwner() instanceof Player player)) return;

        // NBTから直接読み取る
        CompoundTag magicTag = bullet.getPersistentData().getCompound("MagicData");
        if (magicTag.isEmpty()) return;

        String spellId = magicTag.getString("SpellID");
        int level = magicTag.getInt("Level");

        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null || spell == SpellRegistry.none()) return;

        LivingEntity target = Objects.requireNonNull(event.getHurtEntity()).getControllingPassenger();
        MagicData magicData = MagicData.getPlayerMagicData(player);

        Tacz_magic_bullet.LOGGER.info("[MagicBullet] Casting '{}' on {}", spellId, Objects.requireNonNull(target).getName().getString());
        spell.onCast(target.level(), level, target, CastSource.SWORD, magicData);
        spell.onServerCastComplete(target.level(), level, target, magicData, true);
    }

    private static SpellData getInscribedSpell(ItemStack gunItem) {
        CompoundTag tag = gunItem.getTag();
        if (tag != null && tag.contains("InscribedSpell")) {
            CompoundTag spellTag = tag.getCompound("InscribedSpell");
            String spellId = spellTag.getString("SpellID");
            int level = spellTag.getInt("Level");
            AbstractSpell spell = SpellRegistry.getSpell(spellId);
            if (spell != null && spell != SpellRegistry.none()) {
                return new SpellData(spell, level);
            }
        }
        return null;
    }

    private static CompoundTag createMagicTag(AbstractSpell spell, int level) {
        CompoundTag tag = new CompoundTag();
        tag.putString("SpellID", spell.getSpellId());
        tag.putInt("Level", level);
        return tag;
    }
}