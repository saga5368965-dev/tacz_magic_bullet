package saga.tacz_magic_bullet.Event;

import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import io.redspace.ironsspellbooks.api.events.ModifySpellLevelEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.capabilities.magic.RecastInstance;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import io.redspace.ironsspellbooks.network.SyncManaPacket;
import io.redspace.ironsspellbooks.setup.Messages;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
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
    private static Boolean calamityRingExists = null;
    private static Item ringItemInstance = null;

    @SubscribeEvent
    public static void onGunFire(GunFireEvent event) {
        if (event.getLogicalSide().isClient()) return;
        if (!(event.getShooter() instanceof Player player)) return;
        if (!player.getPersistentData().getBoolean("MagicBulletEnabled")) return;

        ItemStack gunStack = event.getGunItemStack();
        CompoundTag rootTag = gunStack.getTag();

        java.util.List<net.minecraft.nbt.CompoundTag> spellsToUse = new java.util.ArrayList<>();

        if (rootTag != null && rootTag.contains("InscribedSpells")) {
            net.minecraft.nbt.ListTag spellsList = rootTag.getList("InscribedSpells", 10);
            if (!spellsList.isEmpty()) {
                for (int i = 0; i < spellsList.size(); i++) {
                    spellsToUse.add(spellsList.getCompound(i));
                }
            }
        } else if (rootTag != null && rootTag.contains("InscribedSpell")) {
            CompoundTag inscribedSpell = rootTag.getCompound("InscribedSpell");
            spellsToUse.add(inscribedSpell);
        } else if (ISpellContainer.isSpellContainer(gunStack)) {
            ISpellContainer container = ISpellContainer.get(gunStack);
            if (!container.isEmpty()) {
                SpellData spellData = container.getSpellAtIndex(0);
                CompoundTag tag = new CompoundTag();
                tag.putString("SpellID", spellData.getSpell().getSpellId());
                tag.putInt("Level", spellData.getLevel());
                spellsToUse.add(tag);
            }
        }

        if (spellsToUse.isEmpty()) return;

        MagicData magicData = MagicData.getPlayerMagicData(player);
        boolean hasCalamityRing = checkForCalamityRing(player);

        // マナコスト合計を計算（修正後レベルで計算するために一時的にレベル調整）
        float totalManaCost = 0;
        for (CompoundTag spellTag : spellsToUse) {
            AbstractSpell spell = SpellRegistry.getSpell(spellTag.getString("SpellID"));
            if (spell != null && spell != SpellRegistry.none()) {
                int level = spellTag.getInt("Level");

                // ModifySpellLevelEvent を発火してレベル調整（邪眼などがブーストを適用）
                ModifySpellLevelEvent levelEvent = new ModifySpellLevelEvent(spell, player, level, level);
                MinecraftForge.EVENT_BUS.post(levelEvent);
                int adjustedLevel = levelEvent.getLevel();

                totalManaCost += spell.getManaCost(adjustedLevel);
            }
        }

        if (!player.isCreative() && !hasCalamityRing) {
            if (magicData.getMana() < totalManaCost) {
                event.setCanceled(true);
                return;
            }
            magicData.setMana(magicData.getMana() - totalManaCost);
            if (player instanceof ServerPlayer serverPlayer) {
                Messages.sendToPlayer(new SyncManaPacket(magicData), serverPlayer);
            }
        }

        // 最終的な呪文データを保存（レベルは既に調整済み）
        net.minecraft.nbt.ListTag pendingList = new net.minecraft.nbt.ListTag();
        for (CompoundTag spellTag : spellsToUse) {
            AbstractSpell spell = SpellRegistry.getSpell(spellTag.getString("SpellID"));
            if (spell != null && spell != SpellRegistry.none()) {
                int level = spellTag.getInt("Level");

                // 邪眼などのレベル調整イベントを発火
                ModifySpellLevelEvent levelEvent = new ModifySpellLevelEvent(spell, player, level, level);
                MinecraftForge.EVENT_BUS.post(levelEvent);
                int finalLevel = levelEvent.getLevel();

                CompoundTag dataTag = new CompoundTag();
                dataTag.putString("SpellID", spell.getSpellId());
                dataTag.putInt("Level", finalLevel);
                pendingList.add(dataTag);
            }
        }

        if (!pendingList.isEmpty()) {
            CompoundTag dataToPass = new CompoundTag();
            dataToPass.put("Spells", pendingList);
            pendingSpells.put(player.getUUID(), dataToPass);
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        if (event.getEntity() instanceof EntityKineticBullet bullet) {
            if (bullet.getOwner() instanceof Player player) {
                CompoundTag reservedData = pendingSpells.get(player.getUUID());
                if (reservedData != null) {
                    bullet.getPersistentData().put("MagicData", reservedData);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            pendingSpells.clear();
        }
    }

    @SubscribeEvent
    public static void onEntityHurtPre(EntityHurtByGunEvent.Pre event) {
        if (!(event.getAttacker() instanceof Player player)) return;
        if (!(event.getHurtEntity() instanceof LivingEntity target)) return;
        processImpact(player, target, event.getBullet().position(), event.getBullet());
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

    private static void processImpact(Player player, LivingEntity target, Vec3 hitPos, Entity bullet) {
        if (player.level().isClientSide) return;
        if (!bullet.getPersistentData().contains("MagicData")) return;
        CompoundTag magicTag = bullet.getPersistentData().getCompound("MagicData");

        net.minecraft.nbt.ListTag spellsList = magicTag.getList("Spells", 10);
        if (spellsList.isEmpty()) {
            AbstractSpell spell = SpellRegistry.getSpell(magicTag.getString("SpellID"));
            int level = magicTag.getInt("Level");
            if (spell == null || spell == SpellRegistry.none()) return;
            processSingleSpell(spell, level, player, target, hitPos, bullet);
        } else {
            for (int i = 0; i < spellsList.size(); i++) {
                CompoundTag spellTag = spellsList.getCompound(i);
                AbstractSpell spell = SpellRegistry.getSpell(spellTag.getString("SpellID"));
                int level = spellTag.getInt("Level");
                if (spell != null && spell != SpellRegistry.none()) {
                    processSingleSpell(spell, level, player, target, hitPos, bullet);
                }
            }
        }
    }

    private static void processSingleSpell(AbstractSpell spell, int level, Player player, LivingEntity target, Vec3 hitPos, Entity bullet) {
        MagicData magicData = MagicData.getPlayerMagicData(player);

        if (spell.getSpellResource().getPath().contains("starfall")) {
            magicData.setAdditionalCastData(new io.redspace.ironsspellbooks.spells.ender.StarfallSpell.StarfallCastData(hitPos));
        } else if (target != null) {
            magicData.setAdditionalCastData(new TargetEntityCastData(target));
        }

        Vec3 originalPos = player.position();
        float originalYRot = player.getYRot();
        float originalXRot = player.getXRot();

        try {
            Vec3 bulletMotion = bullet.getDeltaMovement().normalize();
            double d0 = bulletMotion.x;
            double d1 = bulletMotion.y;
            double d2 = bulletMotion.z;
            double d3 = Math.sqrt(d0 * d0 + d2 * d2);
            float yaw = (float)(Math.atan2(d2, d0) * (180D / Math.PI)) - 90.0F;
            float pitch = (float)(-(Math.atan2(d1, d3) * (180D / Math.PI)));

            double offsetDistance = 3.0;
            Vec3 pseudoSpawnPos = hitPos.subtract(bulletMotion.scale(offsetDistance));

            player.setPos(pseudoSpawnPos.x, pseudoSpawnPos.y, pseudoSpawnPos.z);
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.setYHeadRot(yaw);

            if (spell.getCastType() == CastType.CONTINUOUS) {
                int duration = spell.getCastTime(level);
                if (duration <= 0) duration = 20;
                magicData.initiateCast(spell, level, duration, CastSource.SWORD, spell.getSpellId());
            }

            executeSpell(spell, level, player, magicData);

        } finally {
            player.setPos(originalPos.x, originalPos.y, originalPos.z);
            player.setYRot(originalYRot);
            player.setXRot(originalXRot);
            player.setYHeadRot(originalYRot);

            updateRecastSystems(spell, magicData);

            if (spell.getCastType() != CastType.CONTINUOUS) {
                magicData.resetCastingState();
                magicData.setAdditionalCastData(null);
            }
        }
    }

    private static void updateRecastSystems(AbstractSpell spell, MagicData magicData) {
        String spellIdString = spell.getSpellResource().toString();
        if (magicData.getPlayerRecasts().hasRecastForSpell(spellIdString)) {
            RecastInstance oldInstance = magicData.getPlayerRecasts().getRecastInstance(spellIdString);
            int nextRemaining = oldInstance.getRemainingRecasts() - 1;

            if (nextRemaining >= 0) {
                RecastInstance newInstance = new RecastInstance(
                        oldInstance.getSpellId(),
                        oldInstance.getSpellLevel(),
                        nextRemaining + 1,
                        oldInstance.getTicksToLive(),
                        oldInstance.getCastSource(),
                        oldInstance.getCastData()
                );
                magicData.getPlayerRecasts().addRecast(newInstance, magicData);
            } else {
                magicData.getPlayerRecasts().removeRecast(oldInstance, io.redspace.ironsspellbooks.capabilities.magic.RecastResult.USED_ALL_RECASTS);
            }
        }
    }

    private static void executeSpell(AbstractSpell spell, int level, Player player, MagicData magicData) {
        try {
            spell.onCast(player.level(), level, player, CastSource.SWORD, magicData);
        } catch (Exception e) {
            Tacz_magic_bullet.LOGGER.error("Failed to cast spell: " + spell.getSpellResource().toString(), e);
        }
    }

    private static boolean checkForCalamityRing(Player player) {
        try {
            if (calamityRingExists == null) initializeCalamityRingDetection();
            if (!calamityRingExists || ringItemInstance == null) return false;
            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object curiosHelper = curiosApiClass.getMethod("getCuriosHelper").invoke(null);
            java.lang.reflect.Method findFirstCurio = curiosHelper.getClass().getMethod("findFirstCurio", LivingEntity.class, Item.class);
            Object result = findFirstCurio.invoke(curiosHelper, player, ringItemInstance);
            if (result instanceof java.util.Optional<?> optional) return optional.isPresent();
        } catch (Exception e) { return false; }
        return false;
    }

    private static void initializeCalamityRingDetection() {
        try {
            Class<?> itemRegistryClass = Class.forName("inovation_and_control.inovation_and_control.registry.ItemRegistry");
            java.lang.reflect.Field ringField = itemRegistryClass.getDeclaredField("RING_OF_CALAMITY");
            ringField.setAccessible(true);
            Object registryObject = ringField.get(null);
            java.lang.reflect.Method getMethod = registryObject.getClass().getMethod("get");
            Object item = getMethod.invoke(registryObject);
            if (item instanceof Item castedItem) {
                ringItemInstance = castedItem;
                calamityRingExists = true;
            }
        } catch (Exception e) { calamityRingExists = false; }
    }
}