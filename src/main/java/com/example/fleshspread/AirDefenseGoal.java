package com.example.fleshspread;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * ППО: стрибками (ривками з піднесенням) добирається до висоти підривної установки й ламає все, до чого дістало.
 */
public class AirDefenseGoal extends Goal {
    private final Mob mob;
    private final List<BlockPos> devices = new ArrayList<>();
    private int hop;
    private int chew;
    private boolean loaded;

    public AirDefenseGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        for (long l : mob.getPersistentData().getLongArray(AirDefense.AA_DEV)) {
            devices.add(BlockPos.of(l));
        }
    }

    private void save() {
        mob.getPersistentData().putLongArray(AirDefense.AA_DEV, devices.stream().mapToLong(BlockPos::asLong).toArray());
    }

    @Override
    public boolean canUse() {
        CompoundTag d = mob.getPersistentData();
        if (!d.getBoolean(AirDefense.AA)) return false;
        if (mob.level().getGameTime() >= d.getLong(AirDefense.AA_END)) {
            mob.discard(); // термін ППО вичерпано
            return false;
        }
        load();
        return !devices.isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        // знаходимо найближчу ще існуючу ціль
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int i = devices.size() - 1; i >= 0; i--) {
            BlockPos p = devices.get(i);
            if (level.hasChunkAt(p) && !AirDefense.isDevice(level.getBlockState(p))) {
                devices.remove(i);
                continue;
            }
            double dd = p.distSqr(mob.blockPosition());
            if (dd < bd) {
                bd = dd;
                best = p;
            }
        }
        if (best == null) {
            save();
            return;
        }
        Vec3 goal = Vec3.atCenterOf(best);
        mob.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.6D);
        mob.getLookControl().setLookAt(goal.x, goal.y, goal.z);

        Vec3 delta = goal.subtract(mob.position());
        double len = delta.length();
        // стрибок: ривок у бік установки з піднесенням
        if (--hop <= 0 && len > 3.0D) {
            hop = 16;
            Vec3 v = delta.normalize().scale(Math.min(1.25D, 0.4D + len * 0.05D));
            mob.setDeltaMovement(v.x, v.y + 0.35D, v.z);
            mob.hasImpulse = true;
        }
        // удар: ламаємо все, до чого дістали
        if (len < 4.0D && --chew <= 0) {
            chew = 8;
            int broken = 0;
            BlockPos base = mob.blockPosition();
            int max = SpreadConfig.get().aaBreakPerHit;
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-3, -3, -3), base.offset(3, 3, 3))) {
                if (broken >= max) break;
                if (AirDefense.isDevice(level.getBlockState(p))) {
                    level.destroyBlock(p.immutable(), false, mob);
                    devices.remove(p);
                    broken++;
                }
            }
            if (broken > 0) {
                mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                level.playSound(null, mob.blockPosition(), SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 1.0F, 0.8F);
                save();
            }
        }
    }
}
