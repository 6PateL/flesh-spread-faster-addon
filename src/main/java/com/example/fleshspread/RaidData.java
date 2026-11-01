package com.example.fleshspread;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Коли наступний рейд зараження. */
public class RaidData extends SavedData {
    private static final String NAME = "flesh_spread_raids";
    public long next = 0L;

    public static RaidData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(RaidData::load, RaidData::new, NAME);
    }

    public static RaidData load(CompoundTag tag) {
        RaidData d = new RaidData();
        d.next = tag.getLong("next");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("next", next);
        return tag;
    }
}
