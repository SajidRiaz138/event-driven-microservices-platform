package com.sajidriaz.orderplatform.inventoryservice.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The availability invariant in isolation: {@code available = onHand - reserved}, and no
 * operation may make {@code reserved} exceed {@code onHand} or drive either negative.
 */
class StockItemEntityTest {

    @Test
    void availableIsOnHandMinusReserved() {
        StockItemEntity item = new StockItemEntity("SKU-1001", 10, 4);
        assertThat(item.available()).isEqualTo(6);
        assertThat(item.canReserve(6)).isTrue();
        assertThat(item.canReserve(7)).isFalse();
    }

    @Test
    void reserveTakesExactlyWhatIsAvailableAndRefusesMore() {
        StockItemEntity item = new StockItemEntity("SKU-1001", 3, 0);

        assertThat(item.reserve(3)).isTrue();
        assertThat(item.getReserved()).isEqualTo(3);
        assertThat(item.available()).isZero();

        // Insufficient stock returns false rather than throwing: it is a normal business outcome
        // (it becomes StockReservationFailed), not an error.
        assertThat(item.reserve(1)).isFalse();
        assertThat(item.getReserved()).isEqualTo(3);
        assertThat(item.getOnHand()).isEqualTo(3);
    }

    @Test
    void releaseGivesStockBackAndRefusesToReleaseMoreThanIsHeld() {
        StockItemEntity item = new StockItemEntity("SKU-1001", 5, 3);

        item.releaseReserved(2);
        assertThat(item.getReserved()).isEqualTo(1);
        assertThat(item.available()).isEqualTo(4);

        assertThatThrownBy(() -> item.releaseReserved(2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only 1 is reserved");
    }

    @Test
    void commitRemovesStockFromBothReservedAndOnHand() {
        StockItemEntity item = new StockItemEntity("SKU-1001", 5, 2);

        item.commitReserved(2);

        // available is unchanged by a commit — the stock was already unavailable while reserved,
        // and is now gone rather than merely held.
        assertThat(item.getReserved()).isZero();
        assertThat(item.getOnHand()).isEqualTo(3);
        assertThat(item.available()).isEqualTo(3);
    }

    @Test
    void commitRefusesMoreThanIsHeld() {
        StockItemEntity item = new StockItemEntity("SKU-1001", 5, 1);
        assertThatThrownBy(() -> item.commitReserved(2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(item.getOnHand()).isEqualTo(5);
    }

    @Test
    void constructorRejectsStateThatWouldAlreadyBeOversold() {
        assertThatThrownBy(() -> new StockItemEntity("SKU-1001", 1, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved must not exceed onHand");
        assertThatThrownBy(() -> new StockItemEntity("SKU-1001", -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroAndNegativeQuantitiesAreRejected() {
        StockItemEntity item = new StockItemEntity("SKU-1001", 5, 0);
        assertThatThrownBy(() -> item.reserve(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item.releaseReserved(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
