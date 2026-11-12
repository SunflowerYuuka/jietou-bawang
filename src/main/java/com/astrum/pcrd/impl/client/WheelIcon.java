package com.astrum.pcrd.impl.client;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * What one wheel segment shows for a character: its portrait if the pack ships one, plus the marks
 * that tell a tier-3 character apart (spec §6.2: 轮盘里 3 阶角色的图标要换成强化版，否则玩家看不出 3 阶的意义).
 *
 * <h2>Why "distinct" is three fields and not a comment</h2>
 * The pack has no UI art yet, and a character without a portrait must still be recognizable. So an
 * icon is a record — texture, tint, ring count, marker — and the enhanced variant differs from the
 * base one in every visible part of it: gold instead of white, a second ring, and a marker glyph.
 * {@code WheelIconTest} asserts exactly that difference, which is the only part of "you can tell
 * tier 3 apart" that can be checked without eyes.
 *
 * <p>When a pack does ship a portrait, the tier-3 difference is drawn <em>around</em> it (gold
 * frames and the marker), so no second texture has to exist for the enhanced state to be visible.
 *
 * @param texture the portrait to blit, empty when the character has none and {@code rings}/{@code
 *                marker} are all there is to draw
 * @param tint    ARGB multiply colour for the icon; opaque either way (spec §5.5: 图标与文字不透明)
 * @param rings   how many concentric rings to draw behind the icon; {@code 2} marks tier 3
 * @param marker  a short glyph drawn over the icon; {@code ""} for none
 */
@ApiStatus.Internal
public record WheelIcon(Optional<ResourceLocation> texture, int tint, int rings, String marker) {

    /** Icons are drawn opaque: no alpha in either tint, whatever the band behind them does. */
    public static final int BASE_TINT = 0xFFFFFFFF;

    /** Tier-3 gold. Opaque, and far enough from white to read at 13 px. */
    public static final int ENHANCED_TINT = 0xFFFFC93C;

    /** Ring count for a base character. */
    public static final int BASE_RINGS = 1;

    /** Ring count for a tier-3 character: the extra ring is the "enhanced" shape. */
    public static final int ENHANCED_RINGS = 2;

    /** Glyph drawn over a tier-3 icon. */
    public static final String ENHANCED_MARKER = "II";

    /** Empty marker for a base character. */
    public static final String BASE_MARKER = "";

    /** {@return the icon for a character at the given affection tier} */
    public static WheelIcon of(Optional<ResourceLocation> portrait, boolean enhanced) {
        return new WheelIcon(portrait, enhanced ? ENHANCED_TINT : BASE_TINT,
                enhanced ? ENHANCED_RINGS : BASE_RINGS,
                enhanced ? ENHANCED_MARKER : BASE_MARKER);
    }

    /** {@return whether this icon is the enhanced one} */
    public boolean enhanced() {
        return rings > BASE_RINGS;
    }
}
