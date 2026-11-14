package com.example.fleshspread;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/** Дані виміру плоті: де лігво боса (Серце плоті), чи воно вже збудоване, і де стоять портали повернення. */
public class FleshData extends SavedData {
    private static final String NAME = "flesh_spread_dimension";
    public BlockPos boss;
    public boolean bossBuilt;
    public final List<BlockPos> arrivals = new ArrayList<>();

    public static FleshData get(ServerLevel flesh) {
        return flesh.getDataStorage().computeIfAbsent(FleshData::load, FleshData::new, NAME);
    }

    public static FleshData load(CompoundTag tag) {
        FleshData d = new FleshData();
        if (tag.contains("boss")) d.boss = BlockPos.of(tag.getLong("boss"));
        d.bossBuilt = tag.getBoolean("bossBuilt");
        for (long l : tag.getLongArray("arrivals")) d.arrivals.add(BlockPos.of(l));
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (boss != null) tag.putLong("boss", boss.asLong());
        tag.putBoolean("bossBuilt", bossBuilt);
        long[] a = new long[arrivals.size()];
        for (int i = 0; i < a.length; i++) a[i] = arrivals.get(i).asLong();
        tag.putLongArray("arrivals", a);
        return tag;
    }
}
