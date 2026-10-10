package com.exchange.fee.tier;

import com.exchange.fee.FeeProduct;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff editing the published fee schedule (docs/reference/admin.md). One row per level, changed
 * in place (V16): an order keeps the rate it was accepted with, so a change applies to new orders
 * only. The audit row is the caller's to write, in the same transaction.
 *
 * <p>The rules keep the ladder one a customer can climb and the exchange can afford: level 0
 * starts at no volume, each level asks for more volume than the one below, the taker fee is never
 * negative, and a maker rebate never exceeds the taker fee -- otherwise a trade between two
 * customers would cost the exchange money.
 *
 * <p>No level is removed: a customer assigned to it would have no rate at all.
 */
@Service
public class FeeTierService {

    /** What staff set on a level. Volume is 1e8-scaled, in the quote asset; rates in bps. */
    public record Rung(String name, long min30dVolume, int makerFeeBps, int takerFeeBps) {
    }

    private final FeeTierRepository tiers;

    public FeeTierService(FeeTierRepository tiers) {
        this.tiers = tiers;
    }

    public List<FeeTier> ladder(FeeProduct product) {
        return tiers.findByProductOrderByLevelAsc(product);
    }

    /**
     * @throws NoSuchLevel no such level
     * @throws IllegalArgumentException a rung that breaks the ladder's rules
     */
    @Transactional
    public FeeTier change(FeeProduct product, int level, Rung rung) {
        FeeTier tier = tiers.findByProductAndLevel(product, level).orElseThrow(() -> new NoSuchLevel(level));
        List<FeeTier> ladder = ladder(product);
        check(rung, level, below(ladder, level), above(ladder, level));
        apply(tier, rung);
        return tiers.save(tier);
    }

    /** A new top level, one above the highest. @throws IllegalArgumentException as {@link #change} */
    @Transactional
    public FeeTier add(FeeProduct product, Rung rung) {
        List<FeeTier> ladder = ladder(product);
        int level = ladder.isEmpty() ? 0 : ladder.get(ladder.size() - 1).getLevel() + 1;
        check(rung, level, ladder.isEmpty() ? null : ladder.get(ladder.size() - 1), null);
        FeeTier tier = new FeeTier(product, level, rung.name().trim(), rung.min30dVolume(), rung.makerFeeBps(),
                rung.takerFeeBps());
        return tiers.save(tier);
    }

    /** No such level: kept apart from the other refusals so the caller can answer 404. */
    public static final class NoSuchLevel extends IllegalArgumentException {
        NoSuchLevel(int level) {
            super("no fee level " + level);
        }
    }

    private static void check(Rung r, int level, FeeTier below, FeeTier above) {
        if (r.name() == null || r.name().isBlank() || r.name().trim().length() > 32) {
            throw new IllegalArgumentException("a level needs a name of up to 32 characters");
        }
        if (r.takerFeeBps() < 0 || r.takerFeeBps() >= 10_000) {
            throw new IllegalArgumentException("the taker fee is 0 to 9999 bps");
        }
        if (r.makerFeeBps() >= 10_000 || r.makerFeeBps() < -r.takerFeeBps()) {
            throw new IllegalArgumentException("a maker rebate cannot exceed the taker fee");
        }
        if (level == 0 && r.min30dVolume() != 0) {
            throw new IllegalArgumentException("level 0 is where everyone starts: its volume is 0");
        }
        if (r.min30dVolume() < 0) {
            throw new IllegalArgumentException("volume cannot be negative");
        }
        if (below != null && r.min30dVolume() <= below.getMin30dVolume()) {
            throw new IllegalArgumentException("level " + level + " must ask for more volume than level "
                    + below.getLevel());
        }
        if (above != null && r.min30dVolume() >= above.getMin30dVolume()) {
            throw new IllegalArgumentException("level " + level + " must ask for less volume than level "
                    + above.getLevel());
        }
    }

    private static FeeTier below(List<FeeTier> ladder, int level) {
        FeeTier found = null;
        for (FeeTier t : ladder) {
            if (t.getLevel() < level) {
                found = t;
            }
        }
        return found;
    }

    private static FeeTier above(List<FeeTier> ladder, int level) {
        return ladder.stream().filter(t -> t.getLevel() > level).findFirst().orElse(null);
    }

    private static void apply(FeeTier tier, Rung r) {
        tier.setName(r.name().trim());
        tier.setMin30dVolume(r.min30dVolume());
        tier.setMakerFeeBps(r.makerFeeBps());
        tier.setTakerFeeBps(r.takerFeeBps());
    }
}
