package com.exchange.fee.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import com.exchange.fee.FeeProduct;
import com.exchange.fee.tier.FeeTierService.Rung;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FeeTierServiceTest {

    private static final long ONE = 100_000_000L;

    private final FeeTierRepository repository = Mockito.mock(FeeTierRepository.class);
    private final FeeTierService feeTiers = new FeeTierService(repository);
    private final FeeTier vip0 = new FeeTier(FeeProduct.SPOT, 0, "VIP 0", 0, 10, 10);
    private final FeeTier vip1 = new FeeTier(FeeProduct.SPOT, 1, "VIP 1", 1_000_000 * ONE, 9, 10);
    private final FeeTier vip2 = new FeeTier(FeeProduct.SPOT, 2, "VIP 2", 5_000_000 * ONE, 8, 9);

    @BeforeEach
    void setUp() {
        Mockito.when(repository.findByProductOrderByLevelAsc(FeeProduct.SPOT)).thenReturn(List.of(vip0, vip1, vip2));
        for (FeeTier t : List.of(vip0, vip1, vip2)) {
            Mockito.when(repository.findByProductAndLevel(FeeProduct.SPOT, t.getLevel())).thenReturn(Optional.of(t));
        }
        Mockito.when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("a level changes in place, a maker rebate included")
    void changes() {
        FeeTier changed = feeTiers.change(FeeProduct.SPOT, 1, new Rung(" VIP 1 ", 2_000_000 * ONE, -2, 8));
        assertThat(changed).isSameAs(vip1);
        assertThat(vip1.getName()).isEqualTo("VIP 1");
        assertThat(vip1.getMakerFeeBps()).isEqualTo(-2);
        assertThat(vip1.getMin30dVolume()).isEqualTo(2_000_000 * ONE);
    }

    @Test
    @DisplayName("the ladder stays climbable and affordable")
    void refuses() {
        assertThatThrownBy(() -> feeTiers.change(FeeProduct.SPOT, 1, new Rung("VIP 1", 5_000_000 * ONE, 9, 10)))
                .hasMessageContaining("less volume than level 2");
        assertThatThrownBy(() -> feeTiers.change(FeeProduct.SPOT, 2, new Rung("VIP 2", ONE, 8, 9)))
                .hasMessageContaining("more volume than level 1");
        assertThatThrownBy(() -> feeTiers.change(FeeProduct.SPOT, 0, new Rung("VIP 0", ONE, 10, 10)))
                .hasMessageContaining("volume is 0");
        // A rebate bigger than the fee on the other side: every trade would cost the exchange.
        assertThatThrownBy(() -> feeTiers.change(FeeProduct.SPOT, 1, new Rung("VIP 1", 2_000_000 * ONE, -11, 10)))
                .hasMessageContaining("rebate");
        assertThatThrownBy(() -> feeTiers.change(FeeProduct.SPOT, 1, new Rung("VIP 1", 2_000_000 * ONE, 0, -1)))
                .hasMessageContaining("taker");
        assertThatThrownBy(() -> feeTiers.change(FeeProduct.SPOT, 7, new Rung("VIP 7", ONE, 1, 1)))
                .isInstanceOf(FeeTierService.NoSuchLevel.class);
    }

    @Test
    @DisplayName("a new level goes on top, asking for more volume than the highest")
    void adds() {
        FeeTier added = feeTiers.add(FeeProduct.SPOT, new Rung("VIP 3", 10_000_000 * ONE, 7, 8));
        assertThat(added.getLevel()).isEqualTo(3);
        assertThatThrownBy(() -> feeTiers.add(FeeProduct.SPOT, new Rung("VIP 3", 5_000_000 * ONE, 7, 8)))
                .hasMessageContaining("more volume than level 2");
    }
}
