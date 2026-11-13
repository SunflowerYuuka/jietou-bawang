package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;

/**
 * One slash of the UB's outward ring: the fast half of the burst, cut from Djeeta's official
 * effect art.
 *
 * <h2>Where its motion comes from, and why</h2>
 * The server spawns each slash at a point on the ring (see {@code UbBurstPlan}) but does not send a
 * velocity: vanilla's particle packet randomises the velocity it carries, which would make the ring
 * spray in arbitrary directions. So the direction is computed <b>here</b>, from the spawn point and
 * the local player's position. That is sound because the burst is caster-only — the player receiving
 * the packet is the caster (the server's per-player overload guarantees it) — and it means every
 * slash leaves the ring outward instead of back through the caster.
 *
 * <p>Only direction is taken from the player; the spawn position, the ring radius and the count all
 * came from the server. The result is a real world-space burst: individual slashes drift outward,
 * arc under gravity, fade on their own lifetime, and can be hidden by a wall because they are
 * ordinary particles.
 *
 * <h2>The sprite atlas is a set of variants, not an animation</h2>
 * {@code particles/ub_spark.json} names one {@code ub_spark.png}, which is a horizontal strip of
 * {@link #TILES} 64x64 cells taken from the official PCR texture set (white slash streaks, blue
 * crescents, a blue blade, a white flame, a golden lightning streak, a chevron). Those cells are
 * <b>different sprites, not frames of one</b> — animating a crescent into a lightning streak would
 * be nonsense — so each particle picks <b>one</b> cell at random when it is created and keeps it for
 * its whole life. The variation between casts therefore comes from which cells the cast happened to
 * draw, exactly as the official art varies between its own sprites.
 *
 * <p>Colour is <b>not</b> tinted here: the official sprites already carry their palette (blue
 * crescents, white slashes, one golden accent), and the generator normalized their exposure. Tinting
 * them gold — what the previous AI-art version did — would flatten that palette into one colour.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
final class UbSparkParticle extends TextureSheetParticle {

    /** Cells in {@code ub_spark.png}; must match the committed atlas and {@code UbParticleAtlasTest}. */
    static final int TILES = 10;

    /** Set once, so a client log line can prove the provider really created a particle. */
    private static volatile boolean firstLogged;

    /** The atlas cell this particle shows for its whole life. */
    private final int tile;

    /**
     * @param level   the client level
     * @param x       spawn x, already on the ring from the server
     * @param y       spawn y
     * @param z       spawn z
     * @param sprites the sprite set from {@code ub_spark.json}
     */
    UbSparkParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
        super(level, x, y, z);
        this.pickSprite(sprites);
        this.tile = this.random.nextInt(TILES);
        this.lifetime = 8 + this.random.nextInt(7);           // 0.40-0.70 s
        // The cell's artwork occupies the middle CORE/TILE of the quad (the generator keeps the
        // border empty so no square edge can show), so the quad is sized for the visible slash
        // rather than for the cell.
        this.quadSize = 0.30F + this.random.nextFloat() * 0.30F;
        this.gravity = 0.22F;
        this.friction = 0.94F;
        this.hasPhysics = false;                             // light: no block collision, no bounce
        this.roll = this.random.nextFloat() * (float) (Math.PI * 2.0);
        this.oRoll = this.roll;

        // Outward from the caster. The receiver of a caster-only particle packet is the caster, so
        // "the local player" is the burst's centre. A little jitter keeps the ring from looking ruled.
        Player player = Minecraft.getInstance().player;
        double dx = player == null ? 0.0 : x - player.getX();
        double dz = player == null ? 0.0 : z - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        double angle = length > 1.0E-3
                ? Math.atan2(dz, dx)
                : this.random.nextDouble() * Math.PI * 2.0;
        angle += (this.random.nextDouble() - 0.5) * 0.6;
        double speed = 0.16 + this.random.nextDouble() * 0.18;
        this.xd = Math.cos(angle) * speed;
        this.zd = Math.sin(angle) * speed;
        this.yd = 0.02 + this.random.nextDouble() * 0.10;

        if (!firstLogged) {
            firstLogged = true;
            Astrum.LOGGER.info("astrum: first UB spark particle created at ({}, {}, {}) — lifetime {}t, "
                    + "quad {}, atlas cell {}/{}", fmt(x), fmt(y), fmt(z), this.lifetime,
                    fmt(this.quadSize), this.tile, TILES);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.removed) {
            float life = (float) this.age / (float) Math.max(1, this.lifetime);
            // Snap in over the first eighth, hold, then fade out over the last half: a slash that
            // popped in at full strength reads as a sprite being switched on.
            float fade = life < 0.125F ? life / 0.125F
                    : (life > 0.50F ? (1.0F - life) / 0.50F : 1.0F);
            this.setAlpha(Math.max(0.0F, Math.min(1.0F, fade)));
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    // ------------------------------------------------------------------ the atlas cell

    /** {@return the U span of one cell inside the stitched sprite} */
    private float tileSpanU() {
        return (this.sprite.getU1() - this.sprite.getU0()) / TILES;
    }

    @Override
    protected float getU0() {
        return this.sprite.getU0() + tileSpanU() * this.tile;
    }

    @Override
    protected float getU1() {
        return this.sprite.getU0() + tileSpanU() * (this.tile + 1);
    }

    @Override
    protected float getV0() {
        return this.sprite.getV0();
    }

    @Override
    protected float getV1() {
        return this.sprite.getV1();
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }
}