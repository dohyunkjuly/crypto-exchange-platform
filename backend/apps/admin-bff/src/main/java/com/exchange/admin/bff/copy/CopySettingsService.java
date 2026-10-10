package com.exchange.admin.bff.copy;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.bff.copy.dto.CopySettingsViews.Settings;
import com.exchange.admin.bff.copy.dto.CopySettingsViews.SettingsBody;
import com.exchange.common.money.Money;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.core.api.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copy trading's settings, for staff (docs/reference/copy-trading.md): the one row every copy
 * is sized and judged by. How far a copied buy may pay is each follower's own. Each change
 * is audited in the same transaction with the row as it was and as it became.
 *
 * <p>A change applies from the next copy: the copier and web-bff read the row each time, and
 * a copy already placed keeps the price it was placed at. The rules live here and not in the
 * copy lib because this app is the only writer.
 */
@Service
public class CopySettingsService {

    private static final String TARGET = "copy_settings";

    private final CopySettingsRepository settings;
    private final AdminAuditService audit;

    public CopySettingsService(CopySettingsRepository settings, AdminAuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Settings get() {
        return view(row());
    }

    @Transactional
    public Settings change(Long by, SettingsBody body, String ip) {
        CopySettings row = row();
        String was = json(row);
        long minRatio = amount(body.minFixedRatioAmount());
        long minAmount = amount(body.minFixedAmount());
        // A follower with no minimum would copy orders too small for any market to take.
        if (minRatio <= 0 || minAmount <= 0) {
            throw new ApiException(AdminErrorCode.INVALID_COPY_SETTINGS, "A minimum to follow must be more than zero");
        }
        row.setProfitShareBps(body.profitShareBps());
        row.setFailureLimit(body.failureLimit());
        row.setMaxFollowersPerLeader(body.maxFollowersPerLeader());
        row.setMaxCopiesPerUser(body.maxCopiesPerUser());
        row.setMinFixedRatioAmount(minRatio);
        row.setMinFixedAmount(minAmount);
        long leadMin = amount(body.leadMinBalance());
        long leadMax = amount(body.leadMaxBalance());
        if (leadMax < leadMin) {
            throw new ApiException(AdminErrorCode.INVALID_COPY_SETTINGS, "The most a lead account may hold is under its minimum");
        }
        row.setLeadMinBalance(leadMin);
        row.setLeadMaxBalance(leadMax);
        row.setCloseSellThreshold(amount(body.closeSellThreshold()));
        settings.saveAndFlush(row);
        audit.record(by, AdminAction.COPY_SETTINGS_CHANGED, TARGET, String.valueOf(CopySettings.SINGLETON_ID), was,
                json(row), ip);
        return view(row);
    }

    private CopySettings row() {
        return settings.findById(CopySettings.SINGLETON_ID).orElseThrow();
    }

    private static long amount(String decimal) {
        try {
            return Money.toWire(decimal);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ApiException(AdminErrorCode.INVALID_COPY_SETTINGS, e.getMessage());
        }
    }

    private Settings view(CopySettings s) {
        return new Settings(s.getProfitShareBps(), s.getFailureLimit(), s.getMaxFollowersPerLeader(),
                s.getMaxCopiesPerUser(), Money.toDecimal(s.getMinFixedRatioAmount()),
                Money.toDecimal(s.getMinFixedAmount()), Money.toDecimal(s.getLeadMinBalance()), Money.toDecimal(s.getLeadMaxBalance()),
                Money.toDecimal(s.getCloseSellThreshold()), s.getUpdatedAt());
    }

    private static String json(CopySettings s) {
        return "{\"profitShareBps\":" + s.getProfitShareBps()
                + ",\"failureLimit\":" + s.getFailureLimit() + ",\"maxFollowersPerLeader\":" + s.getMaxFollowersPerLeader()
                + ",\"maxCopiesPerUser\":" + s.getMaxCopiesPerUser()
                + ",\"minFixedRatioAmount\":\"" + Money.toDecimal(s.getMinFixedRatioAmount())
                + "\",\"minFixedAmount\":\"" + Money.toDecimal(s.getMinFixedAmount())
                + "\",\"leadMinBalance\":\"" + Money.toDecimal(s.getLeadMinBalance())
                + "\",\"leadMaxBalance\":\"" + Money.toDecimal(s.getLeadMaxBalance())
                + "\",\"closeSellThreshold\":\"" + Money.toDecimal(s.getCloseSellThreshold()) + "\"}";
    }
}
