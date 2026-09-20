package com.sajidriaz.orderplatform.inventoryservice.repository;

import com.sajidriaz.orderplatform.inventoryservice.entity.StockItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StockItemRepository extends JpaRepository<StockItemEntity, String> {

    /**
     * <strong>The no-oversell mechanism</strong> (ADR-0015 §4, scenario S-6).
     *
     * <p>One statement does the check and the change together: the {@code WHERE} clause
     * re-evaluates availability against the row as it exists at write time, under the row lock
     * PostgreSQL takes for the UPDATE. Two transactions racing for the last unit therefore
     * serialise on that row — the first updates 1 row, the second re-evaluates
     * {@code on_hand - reserved >= quantity} against the already-incremented value, matches
     * nothing, and updates 0 rows. No oversell, at READ COMMITTED, with no SERIALIZABLE
     * escalation and no application-level lock.
     *
     * <p>What makes this correct is that the caller must branch on the <em>return value</em>.
     * A read-modify-write — load the entity, check {@code available()}, save — would reintroduce
     * exactly the race this avoids, because both transactions can read the same last unit
     * before either writes.
     *
     * <p>{@code lock_version} is bumped by hand because this is a native UPDATE that bypasses
     * Hibernate's optimistic-lock handling; without it a {@code @Version}-managed entity loaded
     * in the same transaction would hold a stale version and fail on a later flush.
     *
     * @return 1 when the reservation was taken, 0 when there was not enough available (or the
     *         SKU does not exist — callers distinguish the two by looking the SKU up)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update inventory.stock_item
               set reserved = reserved + :quantity,
                   lock_version = lock_version + 1,
                   updated_at = now()
             where sku = :sku
               and on_hand - reserved >= :quantity
            """, nativeQuery = true)
    int tryReserve(@Param("sku") String sku, @Param("quantity") int quantity);

    /**
     * Give a held quantity back (compensation or TTL expiry). Conditional on
     * {@code reserved >= quantity} so a double release can never drive {@code reserved}
     * negative; a 0 return means the release was already applied.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update inventory.stock_item
               set reserved = reserved - :quantity,
                   lock_version = lock_version + 1,
                   updated_at = now()
             where sku = :sku
               and reserved >= :quantity
            """, nativeQuery = true)
    int releaseReserved(@Param("sku") String sku, @Param("quantity") int quantity);

    /**
     * Complete the sale: the held quantity leaves both {@code reserved} and {@code on_hand}.
     * Conditional on both, so it cannot produce a negative quantity or violate the
     * {@code reserved <= on_hand} constraint.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update inventory.stock_item
               set reserved = reserved - :quantity,
                   on_hand = on_hand - :quantity,
                   lock_version = lock_version + 1,
                   updated_at = now()
             where sku = :sku
               and reserved >= :quantity
               and on_hand >= :quantity
            """, nativeQuery = true)
    int commitReserved(@Param("sku") String sku, @Param("quantity") int quantity);

    /**
     * Availability for a display read: {@code on_hand - reserved}. Empty when the SKU is
     * unknown. Never used to decide a reservation — that is {@link #tryReserve} alone.
     */
    @Query(value = "select on_hand - reserved from inventory.stock_item where sku = :sku", nativeQuery = true)
    Optional<Integer> findAvailable(@Param("sku") String sku);
}
