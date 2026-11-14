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
 * One slow rising mote: the "after the flash" half of the UB burst, cut from Djeeta's official
 * effect art, born on the body the cast hit.
 *
 * <h2>Why a second type instead of one type with a random role</h2>
 * A single type would have to choose "fast slash or slow mote" inside one provider, and the server
 * would then have no way to say how many of each it wanted. Two types keep the count in
 * {@code UbBurstPlan} — a few motes, many slashes — and keep the two motions honest: a mote never
 * accelerates outward, and a slash never lingers over the fight.
 *
 * <p>Its <b>rise</b> is its own (a constant here, because "embers go up" needs no server input); its
 * <b>drift</b> comes from the server, along the way the blow travelled, so the embers lean away from
 * the caster the way smoke does behind a passing blade. That is the same split the spark uses, and
 * the same reason: only the server knows where the blow came from.
 *
 * <p>Its atlas is the same shape as the spark's — a strip of 64x64 cells, here {@link #TILES} soft
 * orbs, sparkle stars and two golden shapes — and the same rule applies: the cell is a <b>variant</b>
 * chosen once, not an animation frame. These are the calm, luminous half of the official art, which
 * is why they read as embers rather than as a second volley.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
final class UbMoteParticle extends TextureSheetParticle {

    /** Cells in {@code ub_mote.png}; must match the committed atlas and {@code UbParticleAtlasTest}. */
    static final int TILES = 6;

    /** Set once, so a client log line can prove the provider really created a particle. */
    private static volatile boolean firstLogged;

    /** Phase of the sideways drift, per particle, so motes do not sway in lockstep. */
    private final double swayPhase;

    /** The atlas cell this particle shows for its whole life. */
    private final int tile;

    /**
     * @param level   the client level
     * @param x       spawn x, on the mote spiral the server sent
     * @param y       spawn y, above the victim's feet
     * @param z       spawn z
     * @param vx      the server's x drift, blocks per tick
     * @param vy      the server's y velocity (the rise is added here; see the class javadoc)
     * @param vz      the server's z drift
     * @param sprites the sprite set from {@code ub_mote.json}
     */
    UbMoteParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz,
            SpriteSet sprites) {
        super(level, x, y, z);
        this.pickSprite(sprites);
        this.tile = this.random.nextInt(TILES);
        // 0.8-1.4 s: the motes outlive the slashes but do not hang over the next action.
        this.lifetime = 16 + this.random.nextInt(13);
        this.quadSize = 0.10F + this.random.nextFloat() * 0.10F;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.hasPhysics = false;
        this.roll = this.random.nextFloat() * (float) (Math.PI * 2.0);
        this.oRoll = this.roll;
        this.swayPhase = this.random.nextDouble() * Math.PI * 2.0;
        this.yd = Math.max(vy, 0.0) + 0.018 + this.random.nextDouble() * 0.026;
        this.xd = vx;
        this.zd = vz;
        this.setAlpha(0.0F);

        if (!firstLogged) {
            firstLogged = true;
            Astrum.LOGGER.info("astrum: first UB mote particle created at ({}, {}, {}) — lifetime {}t, "
                    + "quad {}, atlas cell {}/{}, drift ({}, {}, {})", fmt(x), fmt(y), fmt(z),
                    this.lifetime, fmt(this.quadSize), this.tile, TILES, fmt(vx), fmt(vy), fmt(vz));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.removed) {
            // A slow sideways drift on top of the rise: embers in still air, not a straight column.
            this.x += Math.sin((this.age + this.swayPhase) * 0.30) * 0.004;
            this.z += Math.cos((this.age + this.swayPhase) * 0.30) * 0.004;
            float life = (float) this.age / (float) Math.max(1, this.lifetime);
            // Fade in for the first few ticks, out over the last third: no popping at either end.
            float in = Math.min(1.0F, life * 4.0F);
            float out = Math.min(1.0F, (1.0F - life) * 3.0F);
            this.setAlpha(Math.max(0.0F, Math.min(1.0F, Math.min(in, out))));
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