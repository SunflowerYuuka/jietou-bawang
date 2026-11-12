package com.astrum.pcrd.impl.config;

import org.jetbrains.annotations.ApiStatus;

/**
 * Which screen corner the UB gauge is pinned to (spec §7: HUD 位置可配置、可整体关闭).
 *
 * <p>Deliberately free of Minecraft types: it is a config value, read by
 * {@code impl.client.HudLayout} to turn "a corner and two offsets" into pixels. Keeping the enum in
 * {@code impl.config} points the dependency the right way — the client renderer knows about config,
 * never the reverse.
 *
 * <p>{@link #BOTTOM_LEFT} is the default because it is the one corner that collides with nothing:
 * the vanilla hotbar occupies the bottom <em>centre</em>, and Jade/AppleSkin-class mods occupy the
 * top-right. The others are offered for players who have rearranged their own HUD, and
 * {@code impl.client.HudLayout} still reserves space for the hotbar and experience bar so even a
 * bottom anchor cannot land on them.
 */
@ApiStatus.Internal
public enum HudAnchor {
    /** Bottom edge, left side. Default: clear of the hotbar and of the top-right corner. */
    BOTTOM_LEFT,
    /** Bottom edge, right side. Clear of the hotbar; may meet a bottom-right mod HUD. */
    BOTTOM_RIGHT,
    /** Top edge, left side. May meet a mod that draws status bars there. */
    TOP_LEFT,
    /** Top edge, right side. Where Jade/AppleSkin draw — only for players who moved them. */
    TOP_RIGHT
}
