package com.example.fleshspread;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.EnumSet;
import java.util.List;

/** Веде рейдера до цілі: нападає на жертв поруч або ламає блоки Create. Працює раз на пів секунди. */
public class RaidGoal extends Goal {
    private final Mob mob;
    private int tick;
    private int idle;

    public RaidGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    private boolean raiding() {
        CompoundTag d = mob.getPersistentData();
        return d.contains(InfectedRaids.END) && mob.level().getGameTime() < d.getLong(InfectedRaids.END);
    }

    @Override
    public boolean canUse() {
        CompoundTag d = mob.getPersistentData();
        if (d.contains(InfectedRaids.END) && !raiding()) {
            end();
            return false;
        }
        LivingEntity t = mob.getTarget();
        if (t != null && t.isAlive()) return false; // бій веде звичайний ШІ
        return raiding();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        tick = 0;
        idle = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    private void end() {
        CompoundTag d = mob.getPersistentData();
        d.remove(InfectedRaids.END);
        d.remove(InfectedRaids.TYPE);
        d.remove(InfectedRaids.TX);
        d.remove(InfectedRaids.TY);
        d.remove(InfectedRaids.TZ);
        d.remove(InfectedRaids.BUDGET);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level) || ++tick % 10 != 0) return;
        CompoundTag d = mob.getPersistentData();
        int type = d.getInt(InfectedRaids.TYPE);
        BlockPos target = new BlockPos(d.getInt(InfectedRaids.TX), d.getInt(InfectedRaids.TY), d.getInt(InfectedRaids.TZ));

        if (type == RaidTargets.PLAYER && tick % 100 == 0) { // гравець ходить: оновлюємо ціль
            Player nearest = level.getNearestPlayer(mob, 160.0D);
            if (nearest != null && !nearest.isCreative() && !nearest.isSpectator()) {
                target = nearest.blockPosition();
                d.putInt(InfectedRaids.TX, target.getX());
                d.putInt(InfectedRaids.TY, target.getY());
                d.putInt(InfectedRaids.TZ, target.getZ());
            }
        }

        if (type == RaidTargets.INDUSTRY) {
            industry(level, d, target);
            return;
        }

        LivingEntity victim = nearestVictim(level, type, 28.0D);
        if (victim != null) {
            mob.setTarget(victim);
            idle = 0;
            return;
        }
        if (mob.blockPosition().distSqr(target) > 14.0D * 14.0D) {
            if (tick % 20 == 0) stepToward(level, Vec3.atCenterOf(target));
        } else if ((idle += 10) > 600) {
            end(); // тут нікого не лишилось
        }
    }

    private void industry(ServerLevel level, CompoundTag d, BlockPos target) {
        if (mob.blockPosition().distSqr(target) > 4.0D * 4.0D) {
            LivingEntity p = nearestVictim(level, RaidTargets.PLAYER, 10.0D); // захищається від того, хто заважає
            if (p != null) {
                mob.setTarget(p);
                return;
            }
            if (tick % 20 == 0) stepToward(level, Vec3.atCenterOf(target));
            return;
        }
        if (tick % 20 != 0) return;
        int budget = d.getInt(InfectedRaids.BUDGET);
        int broken = 0;
        BlockPos base = mob.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-3, -2, -3), base.offset(3, 3, 3))) {
            if (budget - broken <= 0 || broken >= 2) break;
            if (RaidTargets.isCreateBlock(level.getBlockState(p))) {
                level.destroyBlock(p.immutable(), true, mob);
                broken++;
            }
        }
        if (broken > 0) {
            mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            d.putInt(InfectedRaids.BUDGET, budget - broken);
            idle = 0;
        } else if ((idle += 20) > 100) {
            end();
        }
        if (d.getInt(InfectedRaids.BUDGET) <= 0) end();
    }

    private LivingEntity nearestVictim(ServerLevel level, int type, double radius) {
        AABB box = mob.getBoundingBox().inflate(radius, 12.0D, radius);
        List<LivingEntity> list = level.getEntitiesOfClass(LivingEntity.class, box, e -> isVictim(type, e));
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : list) {
            double dd = e.distanceToSqr(mob);
            if (dd < bd) {
                bd = dd;
                best = e;
            }
        }
        return best;
    }

    private boolean isVictim(int type, LivingEntity e) {
        if (e == mob || !e.isAlive()) return false;
        if (e instanceof Player p) return !p.isCreative() && !p.isSpectator();
        var key = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        String ns = key == null ? "" : key.getNamespace();
        if (ns.equals("flesh_that_hates")) return false;
        return switch (type) {
            case RaidTargets.VILLAGE -> e instanceof AbstractVillager || ns.equals("recruits") || e.getType() == net.minecraft.world.entity.EntityType.IRON_GOLEM;
            case RaidTargets.BARBARIAN -> ns.equals("hostile_civilization");
            case RaidTargets.PASTURE -> RaidTargets.isLivestock(e);
            default -> false;
        };
    }

    /** Шлях шукається лише на короткій відстані: йдемо до точки до 16 блоків у потрібному напрямку. */
    private void stepToward(ServerLevel level, Vec3 goal) {
        Vec3 pos = mob.position();
        Vec3 delta = goal.subtract(pos);
        double len = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (len < 1.0D) return;
        double step = Math.min(len, 16.0D);
        double wx = pos.x + delta.x / len * step;
        double wz = pos.z + delta.z / len * step;
        BlockPos wp = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(wx, pos.y, wz));
        double wy = len <= 16.0D ? goal.y : wp.getY();
        mob.getNavigation().moveTo(wx, wy, wz, 1.15D);
    }
}
