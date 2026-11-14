package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;

/**
 * One slash of the UB's burst on a victim's body: the fast half, cut from Djeeta's official
 * effect art.
 *
 * <h2>Where its motion comes from, and why</h2>
 * The <b>server</b> decides it. A slash has a direction — it lands on the victim <em>from</em> the
 * caster — and only the server knows where the caster was relative to the body that was hit, so
 * {@code UbBurstPlan} sends each point with its own velocity and this class applies it. Deriving the
 * direction here, from the local player's position (which is what this class used to do), was only
 * correct while the burst was caster-only; now that the victim's own client receives it too, "the
 * local player" is not the burst's centre and the spray would point the wrong way for everybody
 * except the caster.
 *
 * <p>What stays client-side is everything that must vary per particle: which atlas cell, the size,
 * the lifetime, and a small random spread <em>around</em> the server's direction so a ring of slashes
 * is not a regimented fan. Speed is the server's, because it is what makes the burst lean along the
 * blow.
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

    /**
     * Peak sideways wobble added to the server's velocity, as a fraction of it.
     *
     * <p>Small on purpose: the direction is the effect, so this is the difference between "a spray"
     * and "a printed stencil", not a re-randomisation.
     */
    private static final double SPREAD = 0.18;

    /** Set once, so a client log line can prove the provider really created a particle. */
    private static volatile boolean firstLogged;

    /** The atlas cell this particle shows for its whole life. */
    private final int tile;

    /**
     * @param level    the client level
     * @param x        spawn x, already on the ring the server sent
     * @param y        spawn y
     * @param z        spawn z
     * @param vx       the server's x velocity, blocks per tick
     * @param vy       the server's y velocity
     * @param vz       the server's z velocity
     * @param sprites  the sprite set from {@code ub_spark.json}
     */
    UbSparkParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz,
            SpriteSet sprites) {
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

        // The server's direction, plus a little wobble. `setParticleSpeed` rather than the
        // six-argument super constructor: that one re-randomises the speed it is handed.
        double wobbleX = (this.random.nextDouble() - 0.5) * 2.0 * SPREAD;
        double wobbleZ = (this.random.nextDouble() - 0.5) * 2.0 * SPREAD;
        this.setParticleSpeed(vx * (1.0 + wobbleX), vy + (this.random.nextDouble() - 0.5) * 0.05,
                vz * (1.0 + wobbleZ));

        if (!firstLogged) {
            firstLogged = true;
            Astrum.LOGGER.info("astrum: first UB spark particle created at ({}, {}, {}) — lifetime {}t, "
                    + "quad {}, atlas cell {}/{}, velocity ({}, {}, {})", fmt(x), fmt(y), fmt(z),
                    this.lifetime, fmt(this.quadSize), this.tile, TILES, fmt(vx), fmt(vy), fmt(vz));
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