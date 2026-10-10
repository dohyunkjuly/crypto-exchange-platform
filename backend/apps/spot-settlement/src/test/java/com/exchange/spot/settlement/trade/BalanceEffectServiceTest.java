package com.exchange.spot.settlement.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.balance.BalanceService.MovementKind;
import com.exchange.wallet.balance.BalanceService.PendingMovement;
import com.exchange.wallet.ledger.ReferenceType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link BalanceService} is mocked -- what's under test is that {@link
 * BalanceEffectService#applyGroup} makes ONE {@code applyBatch} call per group,
 * translating each {@link BalanceMutation.Kind} to its matching {@link MovementKind} in
 * order, not {@code BalanceService}'s own arithmetic (that's {@code
 * BalanceServiceTest}'s job).
 */
@ExtendWith(MockitoExtension.class)
class BalanceEffectServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ASSET_ID = 2L;

    @Mock
    private BalanceService balanceService;

    @Test
    void applyGroup_translatesEveryMutationIntoOneAggregateApplyBatchCall() {
        BalanceEffectService service = new BalanceEffectService(balanceService);

        service.applyGroup(List.of(
                mutation(BalanceMutation.Kind.SETTLE_LOCKED, 100L, "settle"),
                mutation(BalanceMutation.Kind.CREDIT, 200L, "credit"),
                mutation(BalanceMutation.Kind.DEBIT, 5L, "fee"),
                mutation(BalanceMutation.Kind.REBATE, 3L, "rebate"),
                mutation(BalanceMutation.Kind.UNLOCK, 400L, "unlock")));

        ArgumentCaptor<List<PendingMovement>> captor = ArgumentCaptor.forClass(List.class);
        verify(balanceService).applyBatch(eq(USER_ID), eq(ASSET_ID), captor.capture());
        assertThat(captor.getValue()).containsExactly(
                new PendingMovement(MovementKind.SETTLE_LOCKED, 100L, ReferenceType.TRADE, "ref", "settle"),
                new PendingMovement(MovementKind.CREDIT, 200L, ReferenceType.TRADE, "ref", "credit"),
                new PendingMovement(MovementKind.DEBIT, 5L, ReferenceType.TRADE, "ref", "fee"),
                new PendingMovement(MovementKind.REBATE, 3L, ReferenceType.TRADE, "ref", "rebate"),
                new PendingMovement(MovementKind.UNLOCK, 400L, ReferenceType.TRADE, "ref", "unlock"));
    }

    @Test
    void applyGroup_onAnEmptyGroup_isANoOp() {
        BalanceEffectService service = new BalanceEffectService(balanceService);

        service.applyGroup(List.of());

        verifyNoInteractions(balanceService);
    }

    private static BalanceMutation mutation(BalanceMutation.Kind kind, long amount, String key) {
        return new BalanceMutation(kind, USER_ID, ASSET_ID, amount, ReferenceType.TRADE, "ref", key);
    }
}
