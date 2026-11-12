package com.astrum.pcrd.impl.combat;

import java.util.Locale;
import org.jetbrains.annotations.ApiStatus;

/**
 * Who counts as an ally for {@code area_charge} (spec §7.4).
 *
 * <h2>Why this is not {@code isAlliedTo()}</h2>
 * {@code Entity.isAlliedTo} returns {@code false} when either side has no scoreboard team. LAN co-op
 * players normally never create one, so an alliance test built on it would leave them unable to charge
 * each other while the caster still charged itself — the exact failure spec §7.4 and risk item 13 call
 * out. The modes below make the policy explicit instead of inferring it from a method whose "no team"
 * answer means "not allied".
 *
 * <h2>Unknown values fall back, they do not throw</h2>
 * Config files are hand-edited. {@code astrum-server.toml} is validated on load, but an older file, a
 * typo in a resource pack's override, or a value written by a future build can still reach
 * {@link #parse(String)}. The permissive default — {@link #ALL_PLAYERS} — is the one that keeps the
 * mechanic working, so that is what an unrecognised value gets; the alternative (throw, or pick
 * {@code SAME_TEAM_ONLY}) would silently switch the effect off for every team-less table.
 */
@ApiStatus.Internal
public enum AllyMode {

    /** Every other player is an ally; {@code includeSelf} decides whether the caster joins them. */
    ALL_PLAYERS,

    /** Only players on the caster's own scoreboard team are allies. */
    SAME_TEAM_ONLY;

    /**
     * {@return the mode named by {@code raw}, or {@link #ALL_PLAYERS} when it names none}
     *
     * <p>Case and surrounding whitespace are tolerated because a TOML author writing
     * {@code same_team_only} means the same thing as {@code SAME_TEAM_ONLY}; the loaded value is
     * compared against {@link #name()} rather than trusted as a constant.
     *
     * @param raw the configured string, possibly {@code null} or nonsense
     */
    public static AllyMode parse(String raw) {
        if (raw == null) {
            return ALL_PLAYERS;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (AllyMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return ALL_PLAYERS;
    }
}
