package com.exchange.copy.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.copy.stats.ReturnMath.Point;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReturnMathTest {

    @Test
    @DisplayName("one point, or none, measures nothing: a leader approved today has no return, not a return of zero")
    void nothingToCompare() {
        assertThat(ReturnMath.chainedBps(List.of())).isNull();
        assertThat(ReturnMath.chainedBps(List.of(new Point(1_000, 0)))).isNull();
    }

    @Test
    @DisplayName("steps multiply: up a tenth and then down a tenth is down one percent, not level")
    void stepsAreChained() {
        assertThat(ReturnMath.chainedBps(List.of(new Point(1_000, 0), new Point(1_100, 0)))).isEqualTo(1_000);
        assertThat(ReturnMath.chainedBps(List.of(new Point(1_000, 0), new Point(1_100, 0), new Point(990, 0))))
                .isEqualTo(-100);
    }

    @Test
    @DisplayName("money added is not a gain and money taken out is not a loss")
    void transfersAreTakenOff() {
        // 1,000 grew to 1,100 and 500 was added: 1,600 on the books, a tenth earned.
        assertThat(ReturnMath.chainedBps(List.of(new Point(1_000, 0), new Point(1_600, 500)))).isEqualTo(1_000);
        // 1,000 lost a tenth and 400 was taken out: 500 on the books.
        assertThat(ReturnMath.chainedBps(List.of(new Point(1_000, 0), new Point(500, -400)))).isEqualTo(-1_000);
        // Doubling 100 and then adding 10,000 still shows the double.
        assertThat(ReturnMath.chainedBps(List.of(new Point(100, 0), new Point(200, 0), new Point(10_200, 10_000))))
                .isEqualTo(10_000);
    }

    @Test
    @DisplayName("a step that starts from an empty account is skipped; the ones around it still count")
    void anEmptyAccountGrowsNothing() {
        assertThat(ReturnMath.chainedBps(List.of(new Point(0, 0), new Point(1_000, 1_000)))).isNull();
        assertThat(ReturnMath.chainedBps(List.of(new Point(0, 0), new Point(1_000, 1_000), new Point(1_200, 0))))
                .isEqualTo(2_000);
    }

    @Test
    @DisplayName("everything lost is minus one hundred percent, and never less")
    void aWipeOut() {
        assertThat(ReturnMath.chainedBps(List.of(new Point(1_000, 0), new Point(0, 0)))).isEqualTo(-10_000);
        assertThat(ReturnMath.chainedBps(List.of(new Point(1_000, 0), new Point(100, 500)))).isEqualTo(-10_000);
    }

    @Test
    @DisplayName("the drawdown is the deepest fall from a high, and what was earned is in the account's own units")
    void drawdownAndEarnings() {
        List<Point> points = List.of(new Point(1_000, 0), new Point(1_200, 0), new Point(900, 0), new Point(1_500, 500));
        // 1,200 to 900 is a quarter off the high; the later rise does not undo it.
        assertThat(ReturnMath.maxDrawdownBps(points)).isEqualTo(2_500);
        assertThat(ReturnMath.earned(points)).isEqualTo(0);
        assertThat(ReturnMath.maxDrawdownBps(List.of(new Point(1_000, 0), new Point(1_100, 0)))).isZero();
        assertThat(ReturnMath.maxDrawdownBps(List.of(new Point(1_000, 0)))).isNull();
        assertThat(ReturnMath.earned(List.of(new Point(1_000, 0)))).isNull();
        assertThat(ReturnMath.growth(points)).extracting(g -> g.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString())
                .containsExactly("1.00", "1.20", "0.90", "1.00");
    }
}
