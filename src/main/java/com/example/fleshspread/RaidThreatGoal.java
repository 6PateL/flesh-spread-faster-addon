package com.example.fleshspread;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Під час рейду інфіковані не женуться за слабкою ціллю, ігноруючи озброєних захисників: раз на пів секунди,
 * якщо поруч є захисник (або той, хто щойно вдарив), ціль перемикається на нього.
 */
public class RaidThreatGoal extends Goal {
    private final Mob mob;
    private int tick;

    public RaidThreatGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        var d = mob.getPersistentData();
        return d.contains(InfectedRaids.END) && mob.level().getGameTime() < d.getLong(InfectedRaids.END)
                && ++tick % 10 == 0;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        int type = mob.getPersistentData().getInt(InfectedRaids.TYPE);
        if (type == RaidTargets.INDUSTRY && mob.getTarget() == null) return;
        LivingEntity def = RaidGoal.nearestDefender(level, mob, type, 20.0D);
        LivingEntity cur = mob.getTarget();
        if (def != null && def != cur && (cur == null || !RaidGoal.isDefender(cur))) {
            mob.setTarget(def);
        }
    }
}
