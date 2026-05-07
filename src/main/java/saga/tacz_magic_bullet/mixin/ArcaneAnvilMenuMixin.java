package saga.tacz_magic_bullet.mixin;

import com.tacz.guns.api.item.IGun;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.gui.arcane_anvil.ArcaneAnvilMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import saga.tacz_magic_bullet.config.MagicBulletConfig;

@Mixin(ArcaneAnvilMenu.class)
public abstract class ArcaneAnvilMenuMixin extends ItemCombinerMenu {

    public ArcaneAnvilMenuMixin(@Nullable MenuType<?> pType, int pContainerId,
                                net.minecraft.world.entity.player.Inventory pPlayerInventory,
                                net.minecraft.world.inventory.ContainerLevelAccess pAccess) {
        super(pType, pContainerId, pPlayerInventory, pAccess);
    }

    @Inject(method = "createResult", at = @At("TAIL"))
    private void onCreateResult(CallbackInfo ci) {
        ItemStack baseStack = this.inputSlots.getItem(0);
        ItemStack modifierStack = this.inputSlots.getItem(1);

        if (!this.resultSlots.getItem(0).isEmpty()) return;

        if (baseStack.getItem() instanceof IGun && ISpellContainer.isSpellContainer(modifierStack)) {
            ISpellContainer spellContainer = ISpellContainer.get(modifierStack);
            
            ItemStack result = baseStack.copy();
            CompoundTag resultTag = result.getOrCreateTag();
            ListTag existingSpells = resultTag.getList("InscribedSpells", 10);
            if (!spellContainer.isEmpty()) {
                for (int i = 0; i < 100; i++) {
                    try {
                        SpellData spellData = spellContainer.getSpellAtIndex(i);
                        if (spellData == null || spellData.getSpell() == null) break;
                        
                        String spellId = spellData.getSpell().getSpellId();
                        if (!spellId.isEmpty()) {
                            boolean allowDuplicates = MagicBulletConfig.ALLOW_DUPLICATE_SPELLS != null && MagicBulletConfig.ALLOW_DUPLICATE_SPELLS.get();
                            if (!allowDuplicates) {
                                boolean duplicate = false;
                                for (int j = 0; j < existingSpells.size(); j++) {
                                    if (existingSpells.getCompound(j).getString("SpellID").equals(spellId)) {
                                        duplicate = true;
                                        break;
                                    }
                                }
                                if (duplicate) continue;
                            }

                            CompoundTag spellTag = new CompoundTag();
                            spellTag.putString("SpellID", spellId);
                            spellTag.putInt("Level", spellData.getLevel());
                            existingSpells.add(spellTag);
                        }
                    } catch (Exception e) {
                        break;
                    }
                }
            }
            int maxSpells = 6;
            if (MagicBulletConfig.MAX_INSCRIBED_SPELLS != null) {
                maxSpells = MagicBulletConfig.MAX_INSCRIBED_SPELLS.get();
            }
            while (existingSpells.size() > maxSpells) {
                existingSpells.remove(0);
            }

            resultTag.put("InscribedSpells", existingSpells);
            this.resultSlots.setItem(0, result);
        }
    }
}