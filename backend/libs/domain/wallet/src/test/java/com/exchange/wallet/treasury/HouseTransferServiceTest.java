package com.exchange.wallet.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import com.exchange.common.id.Ulid;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.LedgerEntryRepository;
import com.exchange.wallet.ledger.ReferenceType;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** A move is a debit and the same credit, under the move's id, and happens once. */
class HouseTransferServiceTest {

    private static final Long FEES = 1L;
    private static final Long TREASURY = 2L;
    private static final Long USDT = 3L;

    private final BalanceService balances = Mockito.mock(BalanceService.class);
    private final LedgerEntryRepository ledger = Mockito.mock(LedgerEntryRepository.class);
    private final HouseTransferService transfers = new HouseTransferService(balances, ledger);

    @Test
    @DisplayName("debits one account and credits the other the same amount, ledgered as an admin transfer")
    void movesBothSides() {
        UUID id = Ulid.randomUuid();

        assertThat(transfers.move(id, FEES, TREASURY, USDT, 500L)).isTrue();

        Mockito.verify(balances).transferOut(FEES, USDT, 500L, ReferenceType.ADMIN, id.toString(), "house:" + id + ":out");
        Mockito.verify(balances).transferIn(TREASURY, USDT, 500L, ReferenceType.ADMIN, id.toString(), "house:" + id + ":in");
    }

    @Test
    @DisplayName("the same id again moves nothing: a retried request is the same move")
    void onceById() {
        UUID id = Ulid.randomUuid();
        Mockito.when(ledger.existsByIdempotencyKey("house:" + id + ":out")).thenReturn(true);

        assertThat(transfers.move(id, FEES, TREASURY, USDT, 500L)).isFalse();

        Mockito.verifyNoInteractions(balances);
    }

    @Test
    @DisplayName("nothing, nothing negative, and nothing to the same account")
    void refusesWhatIsNotAMove() {
        assertThatThrownBy(() -> transfers.move(Ulid.randomUuid(), FEES, TREASURY, USDT, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfers.move(Ulid.randomUuid(), FEES, TREASURY, USDT, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfers.move(Ulid.randomUuid(), FEES, FEES, USDT, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        Mockito.verify(balances, Mockito.never()).transferOut(any(), any(), anyLong(), any(), anyString(), anyString());
    }
}
