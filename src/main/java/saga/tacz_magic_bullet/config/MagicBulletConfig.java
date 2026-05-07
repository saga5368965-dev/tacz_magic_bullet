package saga.tacz_magic_bullet.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import saga.tacz_magic_bullet.Tacz_magic_bullet;

public class MagicBulletConfig {
    public static ForgeConfigSpec.IntValue MAX_INSCRIBED_SPELLS;
    public static ForgeConfigSpec.BooleanValue ALLOW_DUPLICATE_SPELLS;
    public static ForgeConfigSpec.BooleanValue SYNERGY_MODE; // 全呪文同時発動 or 順次発動

    public static void register() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Magic Bullet Addon Configuration").push("magic_bullet");

        MAX_INSCRIBED_SPELLS = builder
                .comment("Maximum number of spells that can be inscribed on a gun (1-100)")
                .worldRestart()
                .defineInRange("maxInscribedSpells", 6, 1, 100);

        ALLOW_DUPLICATE_SPELLS = builder
                .comment("Allow duplicate spells on the same gun")
                .define("allowDuplicateSpells", false);

        SYNERGY_MODE = builder
                .comment("true = all spells cast simultaneously, false = spells cast sequentially per shot")
                .define("synergyMode", true);

        builder.pop();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, builder.build(),
                Tacz_magic_bullet.MODID + "-common.toml");
    }
}
