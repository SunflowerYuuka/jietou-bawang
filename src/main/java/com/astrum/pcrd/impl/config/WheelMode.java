package com.astrum.pcrd.impl.config;

/**
 * How the radial menu is laid out on screen (spec §5.5, 2026-11-12).
 *
 * <h2>Why this is a player choice and not a decision made for them</h2>
 * The two layouts differ only in what the middle of the ring looks like, which is a taste question
 * with a real cost either way: {@link #DISC} reads as one complete plate, {@link #RING} leaves the
 * middle clear so more of the battlefield stays visible. Spec §5.5 therefore ships both and lets the
 * player pick; neither mode changes the input rules, the geometry constants or the hit testing.
 *
 * <p>Lives next to {@link ClientConfig} rather than with the renderer because the config file is where
 * it is written and read; it carries no client-only types, so loading it on a dedicated server (which
 * does register the client config spec) is harmless.
 */
public enum WheelMode {

    /**
     * Hollow centre: sector fills, icons and labels sit on the band
     * ({@code innerRadius..outerRadius}).
     */
    RING,

    /**
     * One complete translucent disc out to the outer radius; each sector's icon and label sit at that
     * sector's own centroid (spec §5.5: 图标与文字画在扇形自身的中心点).
     */
    DISC
}
