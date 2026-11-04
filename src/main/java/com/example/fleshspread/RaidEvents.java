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

    /** Вогнемет у руках гравця поруч зі зараженням (раз на секунду): тривога, як від ТНТ. */
    @SubscribeEvent
    public static void onFlamethrower(net.minecraftforge.event.entity.living.LivingEntityUseItemEvent.Tick event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
                || !(player.level() instanceof ServerLevel level) || event.getDuration() % 20 != 0) return;
        if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(event.getItem().getItem()).toString()
                .equals("fromanotherworld:flamethrower_mk_1")) return;
        AirDefense.onSuspicious(level, player.blockPosition(), false);
    }

    @SubscribeEvent
    public static void onPlace(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var b = event.getPlacedBlock().getBlock();
        if (b == net.minecraft.world.level.block.Blocks.TNT || b == net.minecraft.world.level.block.Blocks.SLIME_BLOCK
                || b == net.minecraft.world.level.block.Blocks.DISPENSER || b == net.minecraft.world.level.block.Blocks.DROPPER) {
            AirDefense.onSuspicious(level, event.getPos(), false); // з землі теж: охорона зʼявляється одразу
        }
    }

    /**
     * Стартова фора: кожне гніздо (малий/великий інкубатор) і спавнер SCP-610 при першому завантаженні один раз
     * випускає по starterPerSpawner істот SCP-610-3 (Pincher) поруч.
     */
    @SubscribeEvent
    public static void onNestTick(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
        var e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || e.tickCount < 40 || e.tickCount % 20 != 0) return;
        if (!Nests.isIncubator(e) && !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())
                .toString().equals("flesh_that_hates:scp_610_spawner")) return;
        var d = e.getPersistentData();
        if (d.getBoolean("fs_starter")) return;
        d.putBoolean("fs_starter", true);
        SpreadConfig cfg = SpreadConfig.get();
        if (!cfg.enabled || cfg.starterPerSpawner <= 0) return;
        int made = AirDefense.spawnGuards(level, e.blockPosition(), cfg.starterPerSpawner, "scp_6103");
        FleshSpread.LOGGER.info("Стартова фора: {} істот SCP-610-3 біля {} ({})", made, e.blockPosition().toShortString(),
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile fire
                && fire.getOwner() instanceof net.minecraft.server.level.ServerPlayer
                && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(fire.getType()).toString()
                        .equals("fromanotherworld:flamethrower_fire")) {
            AirDefense.onSuspicious(level, fire.blockPosition(), false); // вогнемет поруч: охорона вірусу
            return;
        }
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
