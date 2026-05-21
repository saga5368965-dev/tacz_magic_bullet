package saga.tacz_magic_bullet.Event;

import com.tacz.guns.api.TimelessAPI;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import saga.tacz_magic_bullet.Tacz_magic_bullet;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Tacz_magic_bullet.MODID)
public class MagicBulletEvents {

    private static final Map<UUID, CompoundTag> pendingSpells = new ConcurrentHashMap<>();
    private static final Map<UUID, ContinuousCastTracker> continuousCasts = new ConcurrentHashMap<>();

    private static Boolean calamityRingExists = null;
    private static Item ringItemInstance = null;

    // リフレクション用キャッシュ
    private static Class<?> funnelTurretClass = null;
    private static Method funnelTurretGetOwnerMethod = null;
    private static boolean funnelTurretDetected = false;

    private static class ContinuousCastTracker {
        final UUID fakePlayerId;
        final Vec3 originalPos;
        final float originalYaw;
        final float originalPitch;
        final Vec3 castPos;
        final float castYaw;
        final float castPitch;
        int remainingTicks;
        boolean hasRestored = false;

        ContinuousCastTracker(FakePlayer fakePlayer, Vec3 castPos, float castYaw, float castPitch,
                              Vec3 originalPos, float originalYaw, float originalPitch, int duration) {
            this.fakePlayerId = fakePlayer.getUUID();
            this.castPos = castPos;
            this.castYaw = castYaw;
            this.castPitch = castPitch;
            this.originalPos = originalPos;
            this.originalYaw = originalYaw;
            this.originalPitch = originalPitch;
            this.remainingTicks = duration;
        }
    }

    /**
     * ファンネルタレットクラスの検出とメソッドキャッシュ
     */
    private static void detectFunnelTurret() {
        if (funnelTurretDetected) return;

        try {
            funnelTurretClass = Class.forName("saga.skullheart.entity.FunnelTurretEntity");
            funnelTurretGetOwnerMethod = funnelTurretClass.getMethod("getOwner");
            funnelTurretDetected = true;
            Tacz_magic_bullet.LOGGER.info("[MagicBullet] FunnelTurretEntity detected, integration enabled");
        } catch (ClassNotFoundException e) {
            funnelTurretDetected = true; // クラスが存在しない場合も二度と試行しない
            Tacz_magic_bullet.LOGGER.debug("[MagicBullet] FunnelTurretEntity not found, skipping integration");
        } catch (NoSuchMethodException e) {
            Tacz_magic_bullet.LOGGER.warn("[MagicBullet] FunnelTurretEntity found but getOwner method missing");
            funnelTurretDetected = true;
        }
    }

    /**
     * エンティティがファンネルタレットかどうかを判定
     */
    private static boolean isFunnelTurret(Entity entity) {
        if (funnelTurretClass == null) detectFunnelTurret();
        return funnelTurretClass != null && funnelTurretClass.isInstance(entity);
    }

    /**
     * ファンネルタレットからオーナーを取得
     */
    private static LivingEntity getFunnelTurretOwner(LivingEntity turret) {
        if (!isFunnelTurret(turret) || funnelTurretGetOwnerMethod == null) return null;

        try {
            Object owner = funnelTurretGetOwnerMethod.invoke(turret);
            if (owner instanceof LivingEntity livingOwner && livingOwner.isAlive()) {
                return livingOwner;
            }
        } catch (Exception e) {
            Tacz_magic_bullet.LOGGER.debug("[MagicBullet] Failed to get funnel turret owner: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 魔法の実際の所有者を特定（ファンネルタレットの場合は装備者）
     */
    private static LivingEntity getActualMagicOwner(LivingEntity shooter) {
        if (isFunnelTurret(shooter)) {
            LivingEntity owner = getFunnelTurretOwner(shooter);
            return owner != null ? owner : shooter;
        }
        return shooter;
    }

    @SubscribeEvent
    public static void onGunFire(GunFireEvent event) {
        if (event.getLogicalSide().isClient()) return;

        LivingEntity shooter = event.getShooter();
        if (shooter == null) return;

        // 魔法の所有者を特定
        LivingEntity actualMagicOwner = getActualMagicOwner(shooter);
        boolean isFunnelTurret = isFunnelTurret(shooter);

        // トグル判定（プレイヤーのみ）
        if (actualMagicOwner instanceof Player player) {
            boolean isEnabled = !player.getPersistentData().contains("MagicBulletEnabled")
                    || player.getPersistentData().getBoolean("MagicBulletEnabled");
            if (!isEnabled) return;
        } else if (actualMagicOwner != shooter && !(actualMagicOwner instanceof Player)) {
            // ファンネルタレットのオーナーがプレイヤーでない場合は無効
            return;
        }

        ItemStack gunStack = event.getGunItemStack();
        CompoundTag rootTag = gunStack.getTag();
        java.util.List<CompoundTag> rawSpells = extractSpellsFromGun(gunStack, rootTag);

        if (rawSpells.isEmpty()) return;

        // マナ計算 & 確定させる魔法リストの構築
        ListTag pendingList = new ListTag();
        float totalManaCost = 0;
        java.util.List<CompoundTag> validSpells = new java.util.ArrayList<>();

        for (CompoundTag spellTag : rawSpells) {
            AbstractSpell spell = SpellRegistry.getSpell(spellTag.getString("SpellID"));
            if (spell != null && spell != SpellRegistry.none()) {
                int level = spellTag.contains("Level") ? spellTag.getInt("Level") : 1;

                if (actualMagicOwner instanceof Player player) {
                    ModifySpellLevelEvent levelEvent = new ModifySpellLevelEvent(spell, player, level, level);
                    MinecraftForge.EVENT_BUS.post(levelEvent);
                    level = levelEvent.getLevel();
                }

                totalManaCost += spell.getManaCost(level);

                CompoundTag dataTag = new CompoundTag();
                dataTag.putString("SpellID", spell.getSpellId());
                dataTag.putInt("Level", level);
                validSpells.add(dataTag);
            }
        }

        if (validSpells.isEmpty()) return;

        // マナ消費処理（ファンネルタレットの場合はオーナーのマナを消費）
        if (actualMagicOwner instanceof Player player && !player.isCreative() && !isFunnelTurret) {
            if (!checkForCalamityRing(player)) {
                MagicData magicData = MagicData.getPlayerMagicData(player);
                if (magicData.getMana() < totalManaCost) {
                    event.setCanceled(true);
                    return;
                }
                magicData.setMana(magicData.getMana() - totalManaCost);
            }
        }

        // 保留データへ追加（shooterのUUIDに紐付け）
        for (CompoundTag validSpell : validSpells) {
            pendingList.add(validSpell);
        }

        CompoundTag dataToPass = new CompoundTag();
        dataToPass.put("Spells", pendingList);
        pendingSpells.put(shooter.getUUID(), dataToPass);
    }

    private static java.util.List<CompoundTag> extractSpellsFromGun(ItemStack gunStack, CompoundTag rootTag) {
        java.util.List<CompoundTag> rawSpells = new java.util.ArrayList<>();

        if (rootTag != null && rootTag.contains("InscribedSpells")) {
            ListTag spellsList = rootTag.getList("InscribedSpells", 10);
            for (int i = 0; i < spellsList.size(); i++) {
                rawSpells.add(spellsList.getCompound(i).copy());
            }
        } else if (rootTag != null && rootTag.contains("InscribedSpell")) {
            rawSpells.add(rootTag.getCompound("InscribedSpell").copy());
        } else if (ISpellContainer.isSpellContainer(gunStack)) {
            ISpellContainer container = ISpellContainer.get(gunStack);
            if (!container.isEmpty()) {
                SpellData spellData = container.getSpellAtIndex(0);
                CompoundTag tag = new CompoundTag();
                tag.putString("SpellID", spellData.getSpell().getSpellId());
                tag.putInt("Level", spellData.getLevel());
                rawSpells.add(tag);
            }
        }

        return rawSpells;
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;

        if (event.getEntity() instanceof EntityKineticBullet bullet) {
            Entity shooter = bullet.getOwner();
            if (shooter != null) {
                CompoundTag reservedData = pendingSpells.remove(shooter.getUUID());
                if (reservedData != null) {
                    bullet.getPersistentData().put("MagicData", reservedData);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (!continuousCasts.isEmpty()) {
            // event.getServer() から直接 Server インスタンスを取得
            net.minecraft.server.MinecraftServer server = event.getServer();
            if (server == null) return;

            ServerLevel serverLevel = server.overworld();

            continuousCasts.entrySet().removeIf(entry -> {
                ContinuousCastTracker tracker = entry.getValue();
                tracker.remainingTicks--;

                Entity entity = serverLevel.getEntity(tracker.fakePlayerId);

                if (!(entity instanceof FakePlayer fakePlayer)) {
                    return true;
                }

                MagicData magicData = MagicData.getPlayerMagicData(fakePlayer);

                if (tracker.remainingTicks <= 0) {
                    // 詠唱終了 - 元の位置に復元
                    if (!tracker.hasRestored) {
                        fakePlayer.setPos(tracker.originalPos.x, tracker.originalPos.y, tracker.originalPos.z);
                        fakePlayer.setYRot(tracker.originalYaw);
                        fakePlayer.setXRot(tracker.originalPitch);
                        fakePlayer.setYHeadRot(tracker.originalYaw);
                        tracker.hasRestored = true;
                    }
                    magicData.resetCastingState();
                    return true;
                }

                if (fakePlayer.isAlive() && magicData.isCasting() && magicData.getCastType() == CastType.CONTINUOUS) {
                    fakePlayer.setPos(tracker.castPos.x, tracker.castPos.y, tracker.castPos.z);
                    fakePlayer.setYRot(tracker.castYaw);
                    fakePlayer.setXRot(tracker.castPitch);
                    fakePlayer.setYHeadRot(tracker.castYaw);
                    return false;
                }

                return true;
            });
        }
    }

    @SubscribeEvent
    public static void onEntityHurtPre(EntityHurtByGunEvent.Pre event) {
        LivingEntity attacker = event.getAttacker();
        Entity target = event.getHurtEntity();
        if (attacker == null || target == null) return;

        processImpact(attacker, target instanceof LivingEntity living ? living : null,
                event.getBullet().position(), event.getBullet());
    }

    @SubscribeEvent
    public static void onAmmoHitBlock(AmmoHitBlockEvent event) {
        if (!(event.getAmmo().getOwner() instanceof LivingEntity attacker)) return;

        Vec3 hitPos = event.getHitResult().getLocation();
        processImpact(attacker, null, hitPos, event.getAmmo());
    }

    private static void processImpact(LivingEntity attacker, LivingEntity target, Vec3 hitPos, Entity bullet) {
        if (attacker.level().isClientSide) return;
        if (!bullet.getPersistentData().contains("MagicData")) return;

        CompoundTag magicTag = bullet.getPersistentData().getCompound("MagicData");
        ListTag spellsList = magicTag.getList("Spells", 10);

        if (spellsList.isEmpty()) {
            AbstractSpell spell = SpellRegistry.getSpell(magicTag.getString("SpellID"));
            int level = magicTag.getInt("Level");
            if (spell == null || spell == SpellRegistry.none()) return;
            processSingleSpell(spell, level, attacker, target, hitPos, bullet);
        } else {
            for (int i = 0; i < spellsList.size(); i++) {
                CompoundTag spellTag = spellsList.getCompound(i);
                AbstractSpell spell = SpellRegistry.getSpell(spellTag.getString("SpellID"));
                int level = spellTag.getInt("Level");
                if (spell != null && spell != SpellRegistry.none()) {
                    processSingleSpell(spell, level, attacker, target, hitPos, bullet);
                }
            }
        }
    }

    private static void processSingleSpell(AbstractSpell spell, int level, LivingEntity attacker,
                                           LivingEntity target, Vec3 hitPos, Entity bullet) {
        Player castExecutor;
        boolean isPlayer = attacker instanceof Player;
        boolean isFunnelTurret = isFunnelTurret(attacker);
        ServerLevel serverLevel = (ServerLevel) attacker.level();

        if (isPlayer) {
            castExecutor = (Player) attacker;
        } else {
            castExecutor = FakePlayerFactory.getMinecraft(serverLevel);
            castExecutor.setPos(attacker.getX(), attacker.getY(), attacker.getZ());
            castExecutor.setYRot(attacker.getYRot());
            castExecutor.setXRot(attacker.getXRot());
        }

        MagicData magicData = MagicData.getPlayerMagicData(castExecutor);

        // ファンネルタレットまたは非プレイヤーの場合は仮想マナを設定
        if (!isPlayer) {
            magicData.setMana(10000f);
        }

        // ターゲット設定
        setupCastData(spell, target, hitPos, magicData);

        // 元の状態を保存
        StateSnapshot originalState = new StateSnapshot(castExecutor);

        try {
            // 発射方向を計算
            Vec3 bulletMotion = bullet.getDeltaMovement().normalize();
            double d0 = bulletMotion.x;
            double d1 = bulletMotion.y;
            double d2 = bulletMotion.z;
            double d3 = Math.sqrt(d0 * d0 + d2 * d2);
            float yaw = (float)(Math.atan2(d2, d0) * (180D / Math.PI)) - 90.0F;
            float pitch = (float)(-(Math.atan2(d1, d3) * (180D / Math.PI)));

            // 命中位置より少し手前にキャスターを配置
            double offsetDistance = 0.2;
            Vec3 pseudoSpawnPos = hitPos.subtract(bulletMotion.scale(offsetDistance));

            castExecutor.setPos(pseudoSpawnPos.x, pseudoSpawnPos.y, pseudoSpawnPos.z);
            castExecutor.setYRot(yaw);
            castExecutor.setXRot(pitch);
            castExecutor.setYHeadRot(yaw);

            if (spell.getCastType() == CastType.CONTINUOUS) {
                int duration = spell.getCastTime(level);
                if (duration <= 0) duration = 100;

                magicData.initiateCast(spell, level, duration, CastSource.SWORD, spell.getSpellId());

                // ★ 修正: FakePlayer の場合のみトラッキング
                if (castExecutor instanceof FakePlayer fakePlayer) {
                    continuousCasts.put(castExecutor.getUUID(), new ContinuousCastTracker(
                            fakePlayer, pseudoSpawnPos, yaw, pitch,
                            originalState.pos, originalState.yaw, originalState.pitch, duration
                    ));
                }
            }

            executeSpell(spell, level, castExecutor, magicData);

            if (!isPlayer) {
                castExecutor.removeAllEffects();
            }

        } finally {
            if (spell.getCastType() != CastType.CONTINUOUS) {
                originalState.restore(castExecutor);
                updateRecastSystems(spell, magicData);
                magicData.resetCastingState();
                magicData.setAdditionalCastData(null);
            }
        }
    }

    private static void setupCastData(AbstractSpell spell, LivingEntity target, Vec3 hitPos, MagicData magicData) {
        if (spell.getSpellResource().getPath().contains("starfall")) {
            try {
                Class<?> starfallCastDataClass = Class.forName("io.redspace.ironsspellbooks.spells.ender.StarfallSpell$StarfallCastData");
                Object starfallData = starfallCastDataClass.getConstructor(Vec3.class).newInstance(hitPos);
                magicData.setAdditionalCastData((io.redspace.ironsspellbooks.api.spells.ICastData) starfallData);
            } catch (Exception e) {
                Tacz_magic_bullet.LOGGER.warn("[MagicBullet] Failed to set Starfall cast data: {}", e.getMessage());
            }
        } else if (target != null && spell.getCastType() != CastType.CONTINUOUS) {
            magicData.setAdditionalCastData(new TargetEntityCastData(target));
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
                magicData.getPlayerRecasts().removeRecast(oldInstance,
                        io.redspace.ironsspellbooks.capabilities.magic.RecastResult.USED_ALL_RECASTS);
            }
        }
    }

    private static void executeSpell(AbstractSpell spell, int level, Player player, MagicData magicData) {
        try {
            spell.onCast(player.level(), level, player, CastSource.SWORD, magicData);
        } catch (Exception e) {
            Tacz_magic_bullet.LOGGER.error("[MagicBullet] Failed to cast spell: {}", spell.getSpellResource().toString(), e);
        }
    }

    private static boolean checkForCalamityRing(Player player) {
        try {
            if (calamityRingExists == null) initializeCalamityRingDetection();
            if (!calamityRingExists || ringItemInstance == null) return false;

            Class<?> curiosApiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object curiosHelper = curiosApiClass.getMethod("getCuriosHelper").invoke(null);
            Method findFirstCurio = curiosHelper.getClass().getMethod("findFirstCurio", LivingEntity.class, Item.class);
            Object result = findFirstCurio.invoke(curiosHelper, player, ringItemInstance);

            if (result instanceof java.util.Optional<?> optional) return optional.isPresent();
        } catch (Exception e) {
            Tacz_magic_bullet.LOGGER.debug("[MagicBullet] Curios processing skipped (mod may not be installed)");
            return false;
        }
        return false;
    }

    private static void initializeCalamityRingDetection() {
        try {
            Class<?> itemRegistryClass = Class.forName("inovation_and_control.inovation_and_control.registry.ItemRegistry");
            java.lang.reflect.Field ringField = itemRegistryClass.getDeclaredField("RING_OF_CALAMITY");
            ringField.setAccessible(true);
            Object registryObject = ringField.get(null);
            Method getMethod = registryObject.getClass().getMethod("get");
            Object item = getMethod.invoke(registryObject);
            if (item instanceof Item castedItem) {
                ringItemInstance = castedItem;
                calamityRingExists = true;
            }
        } catch (Exception e) {
            calamityRingExists = false;
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;

        CompoundTag oldData = event.getOriginal().getPersistentData();
        if (oldData.contains("MagicBulletEnabled")) {
            boolean savedState = oldData.getBoolean("MagicBulletEnabled");
            event.getEntity().getPersistentData().putBoolean("MagicBulletEnabled", savedState);
        }
    }

    // 状態保存用のヘルパークラス
    private static class StateSnapshot {
        final Vec3 pos;
        final double xo, yo, zo;
        final double xOld, yOld, zOld;
        final float yaw, pitch, yHeadRot;

        StateSnapshot(Entity entity) {
            this.pos = entity.position();
            this.xo = entity.xo;
            this.yo = entity.yo;
            this.zo = entity.zo;
            this.xOld = entity.xOld;
            this.yOld = entity.yOld;
            this.zOld = entity.zOld;
            this.yaw = entity.getYRot();
            this.pitch = entity.getXRot();
            this.yHeadRot = entity.getYHeadRot();
        }

        void restore(Entity entity) {
            entity.setPos(pos.x, pos.y, pos.z);
            entity.xo = xo;
            entity.yo = yo;
            entity.zo = zo;
            entity.xOld = xOld;
            entity.yOld = yOld;
            entity.zOld = zOld;
            entity.setYRot(yaw);
            entity.setXRot(pitch);
            entity.setYHeadRot(yHeadRot);
        }
    }
}