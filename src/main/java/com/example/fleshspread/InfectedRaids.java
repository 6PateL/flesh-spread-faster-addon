package com.example.fleshspread;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Рейди зараження: раз на кілька днів вірус нападає на ОДНУ з цілей (гравець / найближчі поселення / поселення варварів /
 * пасовище / промисловий об'єкт Create). Голови рейду немає: це просто хвиля мобів Flesh That Hates.
 */
public final class InfectedRaids {
    public static final String END = "fs_raid_end";
    public static final String TYPE = "fs_raid_type";
    public static final String TX = "fs_raid_x";
    public static final String TY = "fs_raid_y";
    public static final String TZ = "fs_raid_z";
    public static final String BUDGET = "fs_raid_budget";
    public static final String FLANK = "fs_raid_flank";

    private static final long DAY = 24000L;

    private record Raider(String id, double minDay, double weight) {}

    private static final Raider[] RAIDERS = {
            new Raider("scp_6101", 0, 5), new Raider("scp_6103", 0, 4), new Raider("infected_pillager", 0, 3),
            new Raider("scp_6102", 1, 3), new Raider("scp_6109", 4, 2), new Raider("scp_6108", 8, 1),
            new Raider("scp_61010", 6, 1), new Raider("scp_61011", 6, 1.5), new Raider("scp_6104", 12, 0.7)
    };

    private InfectedRaids() {}

    // ------------------------------------------------------------------ розклад

    /** Раз на ~5 секунд для верхнього світу. */
    public static void tick(ServerLevel level, SpreadConfig cfg) {
        if (!cfg.raidsEnabled || level.players().isEmpty()) return;
        long now = level.getGameTime();
        RaidData data = RaidData.get(level);
        if (data.next == 0L) {
            data.next = Math.max(now + 2400L, (long) (cfg.raidStartDay * DAY));
            data.setDirty();
            return;
        }
        if (now < data.next) return;

        List<Integer> order = weightedOrder(level.random, cfg);
        int launched = 0;
        for (int type : order) {
            launched = launchType(level, cfg, type, null);
            if (launched > 0) break;
        }
        data.next = now + (launched > 0 ? Math.max(1, cfg.raidIntervalDays) * DAY : 6000L);
        data.setDirty();
    }

    private static List<Integer> weightedOrder(RandomSource random, SpreadConfig cfg) {
        double[] w = {cfg.weightPlayer, cfg.weightVillage, cfg.weightBarbarian, cfg.weightPasture, cfg.weightIndustry};
        List<Integer> left = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        List<Integer> out = new ArrayList<>();
        while (!left.isEmpty()) {
            double total = 0;
            for (int t : left) total += Math.max(0.0001, w[t]);
            double r = random.nextDouble() * total;
            for (int t : left) {
                r -= Math.max(0.0001, w[t]);
                if (r <= 0) { out.add(t); left.remove((Integer) t); break; }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ запуск

    /** Запускає рейд заданого типу. {@code only} = гравець, від якого шукати цілі (null = будь-який). Повертає кількість нападів. */
    public static int launchType(ServerLevel level, SpreadConfig cfg, int type, ServerPlayer only) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (only != null ? p == only : (!p.isCreative() && !p.isSpectator())) players.add(p);
        }
        Collections.shuffle(players, new java.util.Random(level.random.nextLong()));
        double day = level.getGameTime() / (double) DAY;
        double radius = cfg.raidSearchRadius;

        for (ServerPlayer p : players) {
            List<RaidTargets.Target> targets = switch (type) {
                case RaidTargets.PLAYER -> RaidTargets.player(level, p, only != null);
                case RaidTargets.VILLAGE -> RaidTargets.villages(level, p, cfg.raidVillageCount, radius);
                case RaidTargets.BARBARIAN -> RaidTargets.barbarians(level, p, radius);
                case RaidTargets.PASTURE -> RaidTargets.pastures(level, p, Math.min(radius, 128), cfg.pastureMinAnimals);
                default -> RaidTargets.industry(level, p, Math.min(radius, 160), cfg.industryMinBlocks);
            };
            if (type == RaidTargets.BARBARIAN || type == RaidTargets.PASTURE || type == RaidTargets.INDUSTRY) {
                if (targets.size() > 1) targets = new ArrayList<>(targets.subList(0, 1));
            }
            int launched = 0;
            for (RaidTargets.Target t : targets) {
                int count = raidSize(cfg, day, type, targets.size());
                int spawned = spawnGroup(level, cfg, t, count);
                if (spawned > 0) {
                    launched++;
                    announce(level, cfg, t, spawned);
                }
            }
            if (launched > 0) return launched;
        }
        return 0;
    }

    private static int raidSize(SpreadConfig cfg, double day, int type, int targets) {
        double n = cfg.raidMobsBase + day * cfg.raidMobsPerDay;
        if (type == RaidTargets.VILLAGE && targets > 1) n *= 0.7;
        if (type == RaidTargets.PASTURE) n *= 0.8;
        return Math.max(3, (int) Math.min(cfg.raidMobsMax, Math.round(n)));
    }

    private static void announce(ServerLevel level, SpreadConfig cfg, RaidTargets.Target t, int mobs) {
        FleshSpread.LOGGER.info("Рейд зараження ({}): {} {} (х{})", RaidTargets.NAMES[t.type()], t.name(),
                t.pos().toShortString(), mobs);
        if (!cfg.raidAnnounce) return;
        String what = switch (t.type()) {
            case RaidTargets.PLAYER -> "на гравця " + t.name();
            case RaidTargets.VILLAGE -> "на поселення " + t.name();
            case RaidTargets.BARBARIAN -> "на поселення варварів";
            case RaidTargets.PASTURE -> "на пасовище";
            default -> "на промисловий об'єкт";
        };
        Component msg = Component.literal("☣ Зараження йде " + what + " (" + t.pos().getX() + ", " + t.pos().getY() + ", "
                + t.pos().getZ() + ")");
        for (ServerPlayer p : level.players()) {
            p.sendSystemMessage(msg);
        }
    }

    // ------------------------------------------------------------------ моби

    private static EntityType<?> pickType(RandomSource random, double day) {
        double total = 0;
        for (Raider r : RAIDERS) if (day >= r.minDay()) total += r.weight();
        double v = random.nextDouble() * total;
        for (Raider r : RAIDERS) {
            if (day < r.minDay()) continue;
            v -= r.weight();
            if (v <= 0) return type(r.id());
        }
        return type("scp_6101");
    }

    private static EntityType<?> type(String path) {
        ResourceLocation id = new ResourceLocation("flesh_that_hates", path);
        return ForgeRegistries.ENTITY_TYPES.containsKey(id) ? ForgeRegistries.ENTITY_TYPES.getValue(id) : null;
    }

    private static int spawnGroup(ServerLevel level, SpreadConfig cfg, RaidTargets.Target target, int count) {
        double day = level.getGameTime() / (double) DAY;
        double base = level.random.nextDouble() * Math.PI * 2;
        int spawned = 0;
        long end = level.getGameTime() + cfg.raidDurationSeconds * 20L;
        for (int i = 0; i < count; i++) {
            for (int attempt = 0; attempt < 8; attempt++) {
                double ang = base + (level.random.nextDouble() - 0.5) * 1.4;
                double dist = cfg.raidSpawnDistanceMin
                        + level.random.nextDouble() * Math.max(1, cfg.raidSpawnDistanceMax - cfg.raidSpawnDistanceMin);
                int x = (int) Math.floor(target.pos().getX() + Math.cos(ang) * dist);
                int z = (int) Math.floor(target.pos().getZ() + Math.sin(ang) * dist);
                BlockPos pos = Nests.findSurface(level, x, z);
                if (pos == null) continue;
                EntityType<?> et = pickType(level.random, day);
                if (et == null) return spawned;
                Entity e = et.spawn(level, pos, MobSpawnType.EVENT);
                if (e == null) continue;
                CompoundTag d = e.getPersistentData();
                d.putLong(END, end);
                d.putInt(TYPE, target.type());
                d.putInt(TX, target.pos().getX());
                d.putInt(TY, target.pos().getY());
                d.putInt(TZ, target.pos().getZ());
                d.putInt(BUDGET, cfg.industryBreakBudgetPerMob);
                if (count >= 6 && level.random.nextDouble() < cfg.raidBreakthroughShare) d.putBoolean(FLANK, true);
                if (e instanceof Mob m) attachGoal(m);
                spawned++;
                break;
            }
        }
        return spawned;
    }

    public static void attachGoal(Mob mob) {
        for (WrappedGoal g : mob.goalSelector.getAvailableGoals()) {
            if (g.getGoal() instanceof RaidGoal) return;
        }
        mob.goalSelector.addGoal(2, new RaidGoal(mob));
        mob.goalSelector.addGoal(1, new RaidThreatGoal(mob));
    }
}
