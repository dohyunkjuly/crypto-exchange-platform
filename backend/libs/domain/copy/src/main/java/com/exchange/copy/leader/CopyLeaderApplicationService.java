package com.exchange.copy.leader;

import com.exchange.copy.settings.CopyQuoteAsset;
import com.exchange.copy.leader.exception.LeadAmountOutOfRangeException;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.user.eligibility.EligibilityService;
import com.exchange.wallet.transfer.TransferService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Becoming a leader, in one step (docs/reference/copy-trading.md): the application, the lead
 * account and its funding exist together or not at all, so a leader is never listed empty.
 *
 * <p>The bar is the API key's ({@link EligibilityService#requireCanCreateApiKey}): verified to
 * the level that may trade unattended, with a second factor. Leading is trading other people's
 * money with no one watching each order, which is the same kind of trust.
 *
 * <p>Separate from {@link CopyLeaderService} because admin-bff constructs that one and has
 * neither the wallet's transfers nor the gate; nothing of this is staff's.
 */
@Service
public class CopyLeaderApplicationService {

    private final CopyLeaderService leaders;
    private final CopySettingsRepository settings;
    private final EligibilityService eligibility;
    private final TransferService transfers;
    private final CopyQuoteAsset quoteAsset;

    public CopyLeaderApplicationService(CopyLeaderService leaders, CopySettingsRepository settings,
                                        EligibilityService eligibility, TransferService transfers,
                                        CopyQuoteAsset quoteAsset) {
        this.leaders = leaders;
        this.settings = settings;
        this.eligibility = eligibility;
        this.transfers = transfers;
        this.quoteAsset = quoteAsset;
    }

    /**
     * @param transferId the client's id for the funding, so a request sent twice funds once
     * @param amount     moved from the customer's own account into the lead account, in the copy asset
     * @throws com.exchange.user.eligibility.exception.KycRequiredException not verified far enough
     * @throws com.exchange.user.eligibility.exception.MfaRequiredException no second factor
     * @throws LeadAmountOutOfRangeException under the lead minimum or over the maximum
     * @throws com.exchange.copy.leader.exception.AlreadyLeaderException already leading or suspended
     * @throws com.exchange.copy.leader.exception.DisplayNameTakenException somebody else has that name
     */
    @Transactional
    public CopyLeader apply(Long userId, String displayName, String bio, boolean showPortfolio, UUID transferId,
                            long amount, String ipAddress) {
        eligibility.requireCanCreateApiKey(userId);
        CopySettings set = settings.findById(CopySettings.SINGLETON_ID).orElseThrow();
        if (amount < set.getLeadMinBalance() || amount > set.getLeadMaxBalance()) {
            throw new LeadAmountOutOfRangeException("a lead account starts with between the minimum and the maximum");
        }
        CopyLeader leader = leaders.apply(userId, displayName, bio, ipAddress);
        leader.setShowPortfolio(showPortfolio);
        transfers.transfer(transferId, userId, leader.getLeadUserId(), quoteAsset.id(), amount, userId);
        return leader;
    }
}
