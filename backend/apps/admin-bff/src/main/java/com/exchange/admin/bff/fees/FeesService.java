package com.exchange.admin.bff.fees;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.bff.fees.dto.FeeViews.Level;
import com.exchange.admin.bff.fees.dto.FeeViews.LevelBody;
import com.exchange.common.money.Money;
import com.exchange.core.api.exception.ApiException;
import com.exchange.fee.FeeProduct;
import com.exchange.fee.tier.FeeTierService;
import com.exchange.fee.tier.FeeTierService.Rung;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The spot fee schedule, for staff. The rules are {@link FeeTierService}'s; this translates its
 * refusals into this app's codes and audits each change with the level as it was and as it became.
 * Only spot is traded, so only spot is shown.
 */
@Service
public class FeesService {

    private final FeeTierService tiers;
    private final AdminAuditService audit;

    public FeesService(FeeTierService tiers, AdminAuditService audit) {
        this.tiers = tiers;
        this.audit = audit;
    }

    public List<Level> list() {
        return tiers.ladder(FeeProduct.SPOT).stream().map(Level::of).toList();
    }

    @Transactional
    public Level change(Long by, int level, LevelBody body, String ip) {
        Level was = tiers.ladder(FeeProduct.SPOT).stream().filter(t -> t.getLevel() == level).findFirst()
                .map(Level::of).orElseThrow(() -> new ApiException(AdminErrorCode.FEE_LEVEL_NOT_FOUND));
        Level now = Level.of(refused(() -> tiers.change(FeeProduct.SPOT, level, rung(body))));
        audit.record(by, AdminAction.FEE_LEVEL_CHANGED, "fee_level", String.valueOf(level), json(was), json(now), ip);
        return now;
    }

    @Transactional
    public Level add(Long by, LevelBody body, String ip) {
        Level now = Level.of(refused(() -> tiers.add(FeeProduct.SPOT, rung(body))));
        audit.record(by, AdminAction.FEE_LEVEL_ADDED, "fee_level", String.valueOf(now.level()), null, json(now), ip);
        return now;
    }

    private static Rung rung(LevelBody b) {
        return new Rung(b.name(), Money.toWire(b.min30dVolume()), b.makerFeeBps(), b.takerFeeBps());
    }

    /** The domain's plain refusals, as this app's codes. */
    private static <T> T refused(Supplier<T> call) {
        try {
            return call.get();
        } catch (FeeTierService.NoSuchLevel e) {
            throw new ApiException(AdminErrorCode.FEE_LEVEL_NOT_FOUND);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ApiException(AdminErrorCode.INVALID_FEE_LEVEL, e.getMessage());
        }
    }

    private static String json(Level l) {
        return "{\"name\":\"" + l.name().replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"min30dVolume\":\""
                + l.min30dVolume() + "\",\"makerFeeBps\":" + l.makerFeeBps() + ",\"takerFeeBps\":" + l.takerFeeBps() + "}";
    }
}
