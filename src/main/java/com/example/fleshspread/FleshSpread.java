package com.example.fleshspread;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(FleshSpread.MOD_ID)
public class FleshSpread {
    public static final String MOD_ID = "flesh_spread";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FleshSpread() {
        SpreadConfig.load();
        ProbeItem.ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        LOGGER.info("Flesh Spread loaded");
    }
}
