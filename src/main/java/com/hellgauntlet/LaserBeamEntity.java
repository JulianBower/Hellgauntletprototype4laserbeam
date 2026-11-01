package com.hellgauntlet;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * One straight black beam. It shoots forward over a few ticks (the red lightning on its edge appears at
 * the moving front) and deletes entities it passes through. Where it hits a block a void ball appears.
 *
 * Single shot: the ball pulses 3 times, then the beam fades.
 * Continuous: the beam follows the player's aim for as long as they hold the button, with a ball
 * pulsing at the end, and fades when they let go.
 */
public class LaserBeamEntity extends Entity {
    public static final int GROW_TICKS = 5;
    public static final int HOLD_TICKS = 3;
    public static final int FADE_TICKS = 8;
    public static final float THICKNESS = 0.3F;

    /** How close to the beam line an entity has to be to get deleted. */
    private static final double HIT_MARGIN = 0.6;
    private static final int PULSES = 3;
    private static final int PULSE_INTERVAL = 4;
    /** Continuous beam: a new ball every this many ticks, and durability paid every 2.5 seconds. */
    private static final int CONTINUOUS_BALL_INTERVAL = 8;
    private static final int CONTINUOUS_COST_INTERVAL = 50;

    private static final EntityDataAccessor<Float> LENGTH =
            SynchedEntityData.defineId(LaserBeamEntity.class, EntityDataSerializers.FLOAT);
    /** Tick at which the beam starts fading, or -1 while it is still going. */
    private static final EntityDataAccessor<Integer> FADE_START =
            SynchedEntityData.defineId(LaserBeamEntity.class, EntityDataSerializers.INT);
    private static final DustParticleOptions RED = new DustParticleOptions(new Vector3f(1.0F, 0.0F, 0.0F), 0.7F);

    private UUID owner;
    private int ballRadius;
    private int cost;
    private boolean hitBlock;
    private boolean continuous;

    public LaserBeamEntity(EntityType<? extends LaserBeamEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** Single shot with a fixed length. */
    public void setup(float length, UUID owner, int ballRadius, boolean hitBlock) {
        this.entityData.set(LENGTH, length);
        this.entityData.set(FADE_START, GROW_TICKS + HOLD_TICKS);
        this.owner = owner;
        this.ballRadius = ballRadius;
        this.hitBlock = hitBlock;
        this.continuous = false;
    }

    /** Continuous beam that follows the owner while they keep using the gauntlet. */
    public void setupContinuous(UUID owner, int ballRadius, int cost) {
        this.entityData.set(FADE_START, -1);
        this.owner = owner;
        this.ballRadius = ballRadius;
        this.cost = cost;
        this.continuous = true;
    }

    public float getLength() {
        return this.entityData.get(LENGTH);
    }

    public int getFadeStart() {
        return this.entityData.get(FADE_START);
    }

    /** How far the front of the beam has travelled after t ticks. */
    public static float headLength(float length, float t) {
        return length * Math.max(0.0F, Math.min(1.0F, t / GROW_TICKS));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            clientEffects();
        } else if (this.level() instanceof ServerLevel serverLevel) {
            serverTick(serverLevel);
        }
    }

    private void serverTick(ServerLevel level) {
        if (this.continuous && getFadeStart() < 0) {
            followOwner(level);
        }

        float length = getLength();
        Vec3 dir = this.getLookAngle();
        Vec3 start = this.position();

        // Continuous beams delete along their whole length every tick; single shots only the new part
        float prev = this.continuous ? 0.0F : headLength(length, this.tickCount - 1);
        float cur = headLength(length, this.tickCount);
        if (cur > prev && (!this.continuous || getFadeStart() < 0)) {
            deleteAlong(level, start.add(dir.scale(prev)), start.add(dir.scale(cur)));
        }

        Vec3 end = start.add(dir.scale(length));
        if (this.continuous) {
            if (getFadeStart() < 0 && this.hitBlock && this.tickCount >= GROW_TICKS
                    && (this.tickCount - GROW_TICKS) % CONTINUOUS_BALL_INTERVAL == 0) {
                VoidBlasts.start(level, end, this.owner, this.ballRadius, true, false);
            }
        } else if (this.hitBlock && this.tickCount == GROW_TICKS) {
            VoidBlasts.startPulsing(level, end, this.owner, this.ballRadius, PULSES, PULSE_INTERVAL);
        }

        int fadeStart = getFadeStart();
        if (fadeStart >= 0 && this.tickCount > fadeStart + FADE_TICKS) {
            this.discard();
        }
    }

    /** Continuous beam: follow the player's aim, pay durability, or start fading if they stopped. */
    private void followOwner(ServerLevel level) {
        Player player = this.owner == null ? null : level.getPlayerByUUID(this.owner);
        ItemStack used = player == null ? ItemStack.EMPTY : player.getUseItem();
        boolean active = player != null && player.isUsingItem()
                && used.getItem() instanceof GauntletItem && GauntletItem.isContinuousLaser(used);
        if (!active) {
            this.entityData.set(FADE_START, this.tickCount);
            return;
        }

        Vec3 look = player.getLookAngle();
        Vec3 start = player.getEyePosition().add(look.scale(0.6)).add(0.0, -0.25, 0.0);
        Vec3 far = start.add(look.scale(GauntletItem.LASER_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(start, far,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        this.hitBlock = hit.getType() == HitResult.Type.BLOCK;
        double length = this.hitBlock ? start.distanceTo(hit.getLocation()) : GauntletItem.LASER_RANGE;

        this.moveTo(start.x, start.y, start.z, player.getYRot(), player.getXRot());
        this.entityData.set(LENGTH, (float) length);

        if ((this.tickCount - 1) % CONTINUOUS_COST_INTERVAL == 0) {
            used.hurtAndBreak(this.cost, player, LivingEntity.getSlotForHand(player.getUsedItemHand()));
        }
    }

    private void deleteAlong(ServerLevel level, Vec3 a, Vec3 b) {
        AABB area = new AABB(a, b).inflate(HIT_MARGIN + 1.0);
        for (Entity e : level.getEntities((Entity) null, area, x -> VoidBlasts.canErase(x, this.owner))) {
            AABB box = e.getBoundingBox().inflate(HIT_MARGIN);
            if (box.contains(a) || box.clip(a, b).isPresent()) {
                VoidBlasts.erase(level, e);
            }
        }
    }

    /** Red zigzag lightning around the beam, strongest at the moving front. */
    private void clientEffects() {
        float length = getLength();
        int fadeStart = getFadeStart();
        if (length <= 0.0F || (fadeStart >= 0 && this.tickCount > fadeStart + FADE_TICKS / 2)) {
            return;
        }
        Vec3 dir = this.getLookAngle();
        Vec3 start = this.position();
        Vec3 up = Math.abs(dir.y) > 0.99 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 u = dir.cross(up).normalize();
        Vec3 v = dir.cross(u).normalize();

        float prev = headLength(length, this.tickCount - 1);
        float cur = headLength(length, this.tickCount);
        boolean growing = cur > prev;
        float from = Math.max(0.0F, prev - 1.5F);

        if (growing) {
            for (int bolt = 0; bolt < 14; bolt++) {
                bolt(start, dir, u, v, from + this.random.nextDouble() * (cur - from), cur);
            }
        } else {
            // steady beam: some lightning along its whole length, extra at the front
            for (int bolt = 0; bolt < 6; bolt++) {
                bolt(start, dir, u, v, this.random.nextDouble() * cur, cur);
            }
            for (int bolt = 0; bolt < 5; bolt++) {
                bolt(start, dir, u, v, Math.max(0.0, cur - 2.0) + this.random.nextDouble() * Math.min(2.0, cur), cur);
            }
        }
    }

    private void bolt(Vec3 start, Vec3 dir, Vec3 u, Vec3 v, double d, double max) {
        double angle = this.random.nextDouble() * Math.PI * 2.0;
        for (int step = 0; step < 6; step++) {
            if (d > max) {
                break;
            }
            double r = THICKNESS + 0.08 + (this.random.nextDouble() - 0.5) * 0.3;
            Vec3 p = start.add(dir.scale(d)).add(u.scale(Math.cos(angle) * r)).add(v.scale(Math.sin(angle) * r));
            this.level().addParticle(RED, p.x, p.y, p.z, 0, 0, 0);
            d += 0.35;
            angle += (this.random.nextDouble() - 0.5) * 0.9;
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(LENGTH, 0.0F);
        builder.define(FADE_START, -1);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
