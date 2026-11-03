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

    /** Стартова фора: кожен спавнер SCP-610 при першому завантаженні випускає по 2 початкові істоти (один раз). */
    @SubscribeEvent
    public static void onSpawnerTick(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
        var e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || e.tickCount < 20 || e.tickCount > 400 || e.tickCount % 20 != 0) return;
        if (!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()
                .equals("flesh_that_hates:scp_610_spawner")) return;
        var d = e.getPersistentData();
        if (d.getBoolean("fs_starter")) return;
        d.putBoolean("fs_starter", true);
        SpreadConfig cfg = SpreadConfig.get();
        if (!cfg.enabled || cfg.starterPerSpawner <= 0) return;
        String[] ids = {"scp_6101", "scp_6103"};
        for (int i = 0; i < cfg.starterPerSpawner; i++) {
            var id = new net.minecraft.resources.ResourceLocation("flesh_that_hates", ids[i % ids.length]);
            if (!net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(id)) continue;
            var type = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(id);
            for (int a = 0; a < 8; a++) {
                var p = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        e.blockPosition().offset(level.random.nextInt(7) - 3, 0, level.random.nextInt(7) - 3));
                if (type.spawn(level, p, net.minecraft.world.entity.MobSpawnType.EVENT) != null) break;
            }
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
