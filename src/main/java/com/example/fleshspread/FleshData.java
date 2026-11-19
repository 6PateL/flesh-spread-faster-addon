package com.example.fleshspread;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * Дані виміру плоті: де вхід у лігво (туди веде компас), де сама арена й Серце плоті, і де стоять портали повернення.
 * Прапорець перемоги зберігається окремо, у звичайному світі (див. {@link #global}).
 */
public class FleshData extends SavedData {
    private static final String NAME = "flesh_spread_dimension";
    public BlockPos boss;        // вхід у лігво на поверхні (ціль компаса)
    public BlockPos center;      // центр арени (рівень підлоги)
    public BlockPos heart;       // де стоїть Серце плоті
    public int lairStage = -1;   // крок будівництва печери (-1 = не починали)
    public boolean lairDone;
    public int lairVersion;
    public boolean fightActive;  // іде бій з Утробою (поки є гравці біля арени)
    public int fightGen;         // номер бою: бос старого бою, що прогрузився пізніше, зникає
    public BlockPos fightCenter;
    public int fightRadius;
    public boolean defeated;     // лише в даних звичайного світу: інфекцію переможено
    public final List<BlockPos> arrivals = new ArrayList<>();

    public static FleshData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FleshData::load, FleshData::new, NAME);
    }

    /** Загальні прапорці світу (зберігаються у звичайному світі). */
    public static FleshData global(MinecraftServer server) {
        return get(server.overworld());
    }

    public static boolean defeated(MinecraftServer server) {
        return server != null && global(server).defeated;
    }

    public static FleshData load(CompoundTag tag) {
        FleshData d = new FleshData();
        if (tag.contains("boss")) d.boss = BlockPos.of(tag.getLong("boss"));
        if (tag.contains("center")) d.center = BlockPos.of(tag.getLong("center"));
        if (tag.contains("heart")) d.heart = BlockPos.of(tag.getLong("heart"));
        d.lairStage = tag.contains("lairStage") ? tag.getInt("lairStage") : -1;
        d.lairDone = tag.getBoolean("lairDone");
        d.lairVersion = tag.getInt("lairVersion");
        d.defeated = tag.getBoolean("defeated");
        d.fightActive = tag.getBoolean("fightActive");
        d.fightGen = tag.getInt("fightGen");
        d.fightRadius = tag.getInt("fightRadius");
        if (tag.contains("fightCenter")) d.fightCenter = BlockPos.of(tag.getLong("fightCenter"));
        for (long l : tag.getLongArray("arrivals")) d.arrivals.add(BlockPos.of(l));
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (boss != null) tag.putLong("boss", boss.asLong());
        if (center != null) tag.putLong("center", center.asLong());
        if (heart != null) tag.putLong("heart", heart.asLong());
        tag.putInt("lairStage", lairStage);
        tag.putBoolean("lairDone", lairDone);
        tag.putInt("lairVersion", lairVersion);
        tag.putBoolean("defeated", defeated);
        tag.putBoolean("fightActive", fightActive);
        tag.putInt("fightGen", fightGen);
        tag.putInt("fightRadius", fightRadius);
        if (fightCenter != null) tag.putLong("fightCenter", fightCenter.asLong());
        long[] a = new long[arrivals.size()];
        for (int i = 0; i < a.length; i++) a[i] = arrivals.get(i).asLong();
        tag.putLongArray("arrivals", a);
        return tag;
    }
}
