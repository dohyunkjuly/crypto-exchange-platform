package com.exchange.copy.leader;

import com.exchange.copy.leader.CopyLeaderApplicationService;
import com.exchange.copy.leader.CopyLeaderService;
import com.exchange.copy.settings.CopyQuoteAsset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

import com.exchange.common.id.Ulid;
import com.exchange.copy.leader.exception.LeadAmountOutOfRangeException;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.user.eligibility.EligibilityService;
import com.exchange.user.eligibility.exception.KycRequiredException;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.transfer.TransferService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class CopyLeaderApplicationServiceTest {

    private static final long ONE = 100_000_000L;
    private static final Long CUSTOMER = 7L;
    private static final Long LEAD = 70L;
    private static final Long USDT = 1L;

    private final CopyLeaderService leaders = Mockito.mock(CopyLeaderService.class);
    private final CopySettingsRepository settings = Mockito.mock(CopySettingsRepository.class);
    private final EligibilityService eligibility = Mockito.mock(EligibilityService.class);
    private final TransferService transfers = Mockito.mock(TransferService.class);
    private final AssetService assets = Mockito.mock(AssetService.class);
    private final CopySettings row = Mockito.mock(CopySettings.class);
    private final CopyLeader leader = new CopyLeader(Ulid.randomUuid(), CUSTOMER, "Mina", null);
    private final UUID transferId = UUID.randomUUID();
    private CopyLeaderApplicationService service;

    @BeforeEach
    void wire() {
        Asset usdt = Mockito.mock(Asset.class);
        Mockito.when(usdt.getId()).thenReturn(USDT);
        Mockito.when(assets.getBySymbol("USDT")).thenReturn(usdt);
        service = new CopyLeaderApplicationService(leaders, settings, eligibility, transfers,
                new CopyQuoteAsset(assets, "USDT"));
        Mockito.when(settings.findById(CopySettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        Mockito.when(row.getLeadMinBalance()).thenReturn(500 * ONE);
        Mockito.when(row.getLeadMaxBalance()).thenReturn(1_000_000 * ONE);
        leader.setLeadUserId(LEAD);
        Mockito.when(leaders.apply(CUSTOMER, "Mina", "swing", "127.0.0.1")).thenReturn(leader);
    }

    @Test
    @DisplayName("one step: the leader is made, its lead account funded with the amount, with the chosen visibility")
    void instant() {
        CopyLeader made = service.apply(CUSTOMER, "Mina", "swing", false, transferId, 600 * ONE, "127.0.0.1");

        assertThat(made.getLeadUserId()).isEqualTo(LEAD);
        assertThat(made.isShowPortfolio()).isFalse();
        InOrder order = Mockito.inOrder(eligibility, leaders, transfers);
        order.verify(eligibility).requireCanCreateApiKey(CUSTOMER);
        order.verify(leaders).apply(CUSTOMER, "Mina", "swing", "127.0.0.1");
        order.verify(transfers).transfer(transferId, CUSTOMER, LEAD, USDT, 600 * ONE, CUSTOMER);
    }

    @Test
    @DisplayName("not verified far enough, or an amount outside the range, moves nothing and makes nothing")
    void refusedBeforeAnythingMoves() {
        assertThatThrownBy(() -> service.apply(CUSTOMER, "Mina", null, true, transferId, 499 * ONE, "127.0.0.1"))
                .isInstanceOf(LeadAmountOutOfRangeException.class);
        assertThatThrownBy(() -> service.apply(CUSTOMER, "Mina", null, true, transferId, 1_000_001 * ONE, "127.0.0.1"))
                .isInstanceOf(LeadAmountOutOfRangeException.class);
        Mockito.doThrow(new KycRequiredException(com.exchange.user.kyc.KycLevel.INTERMEDIATE)).when(eligibility).requireCanCreateApiKey(CUSTOMER);
        assertThatThrownBy(() -> service.apply(CUSTOMER, "Mina", null, true, transferId, 600 * ONE, "127.0.0.1"))
                .isInstanceOf(KycRequiredException.class);
        Mockito.verify(leaders, Mockito.never()).apply(any(), any(), any(), any());
        Mockito.verify(transfers, Mockito.never()).transfer(any(), any(), any(), any(), anyLong(), any());
    }
}
