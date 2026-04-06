package saga.tacz_magic_bullet.recipe;

import com.tacz.guns.api.item.IGun;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import saga.tacz_magic_bullet.Tacz_magic_bullet;

public class GunSpellInscribeRecipe extends CustomRecipe {

    public GunSpellInscribeRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        boolean hasGun = false;
        boolean hasScroll = false;

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof IGun) {
                if (hasGun) return false;
                hasGun = true;
            } else if (ISpellContainer.get(stack) != null && !ISpellContainer.get(stack).isEmpty()) {
                if (hasScroll) return false;
                hasScroll = true;
            } else {
                return false;
            }
        }

        return hasGun && hasScroll;
    }

    @Override
    public ItemStack assemble(CraftingContainer container, RegistryAccess registryAccess) {
        ItemStack gun = ItemStack.EMPTY;
        ItemStack scroll = ItemStack.EMPTY;

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof IGun) {
                gun = stack;
            } else if (ISpellContainer.get(stack) != null && !ISpellContainer.get(stack).isEmpty()) {
                scroll = stack;
            }
        }

        if (gun.isEmpty() || scroll.isEmpty()) return ItemStack.EMPTY;

        ItemStack result = gun.copy();
        ISpellContainer scrollContainer = ISpellContainer.get(scroll);

        if (scrollContainer != null && !scrollContainer.isEmpty()) {
            SpellData spell = scrollContainer.getSpellAtIndex(0);
            CompoundTag tag = result.getOrCreateTag();
            CompoundTag spellTag = new CompoundTag();
            spellTag.putString("SpellID", spell.getSpell().getSpellId());
            spellTag.putInt("Level", spell.getLevel());
            tag.put("InscribedSpell", spellTag);
        }

        return result;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return Tacz_magic_bullet.GUN_SPELL_INSCRIBE.get();
    }
}