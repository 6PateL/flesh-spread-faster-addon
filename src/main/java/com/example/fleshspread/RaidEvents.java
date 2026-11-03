package com.example.fleshspread;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Підключає цілі рейдерам і ППО після завантаження та реагує на запалений ТНТ. */
@Mod.EventBusSubscriber(modid = FleshSpread.MOD_ID)
public final class RaidEvents {
    private RaidEvents() {}

    @SubscribeEvent
    public static void onPlace(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var b = event.getPlacedBlock().getBlock();
        if (b == net.minecraft.world.level.block.Blocks.TNT || b == net.minecraft.world.level.block.Blocks.SLIME_BLOCK
                || b == net.minecraft.world.level.block.Blocks.DISPENSER || b == net.minecraft.world.level.block.Blocks.DROPPER) {
            AirDefense.onSuspicious(level, event.getPos(), true);
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof PrimedTnt tnt) {
            AirDefense.onPrimedTnt(level, tnt);
            return;
        }
        if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile pr
                && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(pr.getType()).toString()
                        .equals("hostile_civilization:molotov_projectile")) {
            AirDefense.onSuspicious(level, pr.blockPosition(), false); // молотов: кидають - значить, ціляться
            return;
        }
        if (event.getEntity() instanceof Mob mob) {
            var d = mob.getPersistentData();
            if (d.contains(InfectedRaids.END)) InfectedRaids.attachGoal(mob);
            if (d.getBoolean(AirDefense.AA)) AirDefense.attachGoal(mob);
        }
    }
}
