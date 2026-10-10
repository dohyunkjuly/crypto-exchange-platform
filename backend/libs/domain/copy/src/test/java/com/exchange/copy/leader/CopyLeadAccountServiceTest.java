package com.exchange.copy.leader;

import com.exchange.copy.leader.CopyLeadAccountService;
import com.exchange.copy.settings.CopyQuoteAsset;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

import com.exchange.common.id.Ulid;
import com.exchange.copy.leader.exception.LeadBalanceBelowMinimumException;
import com.exchange.copy.leader.exception.NotALeaderException;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.transfer.TransferService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** A lead account keeps its minimum, and its coins, for as long as anyone is copying it. */
class CopyLeadAccountServiceTest {

    private static final long ONE = 100_000_000L;
    private static final long CUSTOMER = 1L;
    private static final long LEAD_ACCOUNT = 5L;
    private static final long USDT = 3L;
    private static final long BTC = 4L;

    private final CopyLeaderRepository leaders = Mockito.mock(CopyLeaderRepository.class);
    private final CopySettingsRepository settings = Mockito.mock(CopySettingsRepository.class);
    private final BalanceRepository balances = Mockito.mock(BalanceRepository.class);
    private final TransferService transfers = Mockito.mock(TransferService.class);
    private final AssetService assets = Mockito.mock(AssetService.class);
    private final CopyLeadAccountService service = new CopyLeadAccountService(leaders, settings, balances, transfers,
            new CopyQuoteAsset(assets, "USDT"));

    private final CopyLeader leader = new CopyLeader(Ulid.randomUuid(), CUSTOMER, "Mina", null);

    @BeforeEach
    void setUp() {
        leader.setLeadUserId(LEAD_ACCOUNT);
        leader.setStatus(LeaderStatus.ACTIVE);
        Mockito.when(leaders.findByUserId(CUSTOMER)).thenReturn(Optional.of(leader));
        Asset usdt = Mockito.mock(Asset.class);
        Mockito.when(usdt.getId()).thenReturn(USDT);
        Mockito.when(assets.getBySymbol("USDT")).thenReturn(usdt);
        CopySettings row = Mockito.mock(CopySettings.class);
        Mockito.when(row.getLeadMinBalance()).thenReturn(500 * ONE);
        Mockito.when(settings.findById(CopySettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        Balance held = Mockito.mock(Balance.class);
        Mockito.when(held.getAvailable()).thenReturn(800 * ONE);
        Mockito.when(balances.findByUserIdAndAssetId(LEAD_ACCOUNT, USDT)).thenReturn(Optional.of(held));
    }

    @Test
    @DisplayName("funds go in from the leader's own account under the client's transfer id")
    void funds() {
        UUID id = Ulid.randomUuid();
        service.fund(CUSTOMER, id, 200 * ONE);
        Mockito.verify(transfers).transfer(id, CUSTOMER, LEAD_ACCOUNT, USDT, 200 * ONE, CUSTOMER);
    }

    @Test
    @DisplayName("while leading, USDT comes out down to the minimum and a coin does not come out at all")
    void whileLeading() {
        UUID id = Ulid.randomUuid();
        service.withdraw(CUSTOMER, id, USDT, 300 * ONE);
        Mockito.verify(transfers).transfer(id, LEAD_ACCOUNT, CUSTOMER, USDT, 300 * ONE, CUSTOMER);

        assertThatThrownBy(() -> service.withdraw(CUSTOMER, Ulid.randomUuid(), USDT, 301 * ONE))
                .as("800 less 301 is under the 500 it keeps").isInstanceOf(LeadBalanceBelowMinimumException.class);
        assertThatThrownBy(() -> service.withdraw(CUSTOMER, Ulid.randomUuid(), BTC, ONE))
                .as("a coin leaving unsold shrinks the holding with nothing for a follower to copy")
                .isInstanceOf(LeadBalanceBelowMinimumException.class);
    }

    @Test
    @DisplayName("once the leader has stopped or been stopped, everything comes out, in whatever it is held")
    void afterLeading() {
        for (LeaderStatus status : new LeaderStatus[] {LeaderStatus.CLOSED, LeaderStatus.SUSPENDED}) {
            leader.setStatus(status);
            service.withdraw(CUSTOMER, Ulid.randomUuid(), USDT, 800 * ONE);
            service.withdraw(CUSTOMER, Ulid.randomUuid(), BTC, ONE);
        }
        Mockito.verify(transfers, Mockito.times(4)).transfer(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("an application that was never approved has no lead account to fund")
    void noAccount() {
        leader.setLeadUserId(null);
        assertThatThrownBy(() -> service.fund(CUSTOMER, Ulid.randomUuid(), ONE)).isInstanceOf(NotALeaderException.class);
        assertThatThrownBy(() -> service.balances(77L)).isInstanceOf(NotALeaderException.class);
    }
}
