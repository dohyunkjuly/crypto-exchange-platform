package com.exchange.wallet.ledger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /** The account statement. */
    Page<LedgerEntry> findByUserIdAndAssetIdOrderByCreatedAtDesc(
            Long userId, Long assetId, Pageable pageable);

    /**
     * One customer's statement across assets, newest first, for the admin console. Every filter
     * but the customer is optional.
     */
    @Query("""
            SELECT e FROM LedgerEntry e
             WHERE e.userId = :userId
               AND (:assetId IS NULL OR e.assetId = :assetId)
               AND (:type IS NULL OR e.entryType = :type)
               AND (:from IS NULL OR e.createdAt >= :from)
               AND (:to IS NULL OR e.createdAt < :to)
             ORDER BY e.createdAt DESC, e.id DESC
            """)
    Slice<LedgerEntry> statement(@Param("userId") Long userId, @Param("assetId") Long assetId,
                                 @Param("type") LedgerEntryType type, @Param("from") Instant from,
                                 @Param("to") Instant to, Pageable pageable);

    /**
     * Everyone's entries, newest first, read backwards along the primary key: ids are ULIDs, so
     * key order is time order and a time range is a key range ({@code Ulid.floor}). No index on
     * {@code created_at} alone is needed for it.
     */
    @Query("""
            SELECT e FROM LedgerEntry e
             WHERE (:assetId IS NULL OR e.assetId = :assetId)
               AND (:type IS NULL OR e.entryType = :type)
               AND (:lo IS NULL OR e.id >= :lo)
               AND (:hi IS NULL OR e.id < :hi)
             ORDER BY e.id DESC
            """)
    Slice<LedgerEntry> latest(@Param("assetId") Long assetId, @Param("type") LedgerEntryType type,
                              @Param("lo") UUID lo, @Param("hi") UUID hi, Pageable pageable);

    /** "What did this trade do to balances?" -- reconciliation and support. */
    List<LedgerEntry> findByReferenceTypeAndReferenceId(ReferenceType type, String referenceId);

    /** Cheaper than catching the unique-constraint violation when replaying a Kafka event. */
    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);

    /** The same guard as {@link #findByIdempotencyKey}, checked for many keys in one
     *  round trip instead of one per movement -- which of {@code idempotencyKeys} has
     *  already been recorded. */
    @Query("SELECT e.idempotencyKey FROM LedgerEntry e WHERE e.idempotencyKey IN :idempotencyKeys")
    List<String> findExistingIdempotencyKeys(
            @Param("idempotencyKeys") Collection<String> idempotencyKeys);

    /**
     * Rebuilds a balance from the ledger. The ledger is the truth and wallet_balances is a
     * projection of it, so this is what reconciliation compares against.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e "
            + "WHERE e.userId = :userId AND e.assetId = :assetId")
    long sumAmountByUserIdAndAssetId(@Param("userId") Long userId, @Param("assetId") Long assetId);

    /**
     * Customer-side fee flow over {@code [from, to)}, grouped by asset and type, for the
     * treasury rollup and the live half of a sweep (docs/reference/treasury.md). Signed, as
     * stored. Half-open on purpose: the same instant is the end of one window and the start
     * of the next, and an hour counted twice over-credits the fee account.
     */
    @Query("SELECT e.assetId, e.entryType, SUM(e.amount) FROM LedgerEntry e "
            + "WHERE e.entryType IN :types AND e.createdAt >= :from AND e.createdAt < :to "
            + "GROUP BY e.assetId, e.entryType")
    List<Object[]> sumByAssetAndTypeBetween(@Param("types") Collection<LedgerEntryType> types,
                                            @Param("from") Instant from, @Param("to") Instant to);

    /** The same window for one asset, summed across the types: {@code earned} is one number. */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e "
            + "WHERE e.entryType IN :types AND e.assetId = :assetId "
            + "AND e.createdAt >= :from AND e.createdAt < :to")
    long sumByAssetBetween(@Param("types") Collection<LedgerEntryType> types,
                           @Param("assetId") Long assetId,
                           @Param("from") Instant from, @Param("to") Instant to);

    /**
     * One asset's fee flow per UTC day over {@code [from, to)}, for the treasury chart. Native,
     * because JPQL has no way to bucket by day; {@code DATE()} reads the session timezone, which
     * every app pins to UTC in its JDBC URL -- the same UTC the rows are stamped in.
     */
    @Query(value = "SELECT DATE(created_at) AS day, entry_type, SUM(amount) FROM wallet_ledger_entries "
            + "WHERE entry_type IN (:types) AND asset_id = :assetId "
            + "AND created_at >= :from AND created_at < :to "
            + "GROUP BY DATE(created_at), entry_type ORDER BY day", nativeQuery = true)
    List<Object[]> sumByDayAndType(@Param("types") Collection<String> types,
                                   @Param("assetId") Long assetId,
                                   @Param("from") Instant from, @Param("to") Instant to);

    /** Everything of one kind one account has had of one asset: what the fee sweep has credited. */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e "
            + "WHERE e.userId = :userId AND e.assetId = :assetId AND e.entryType = :type")
    long sumForAccount(@Param("userId") Long userId, @Param("assetId") Long assetId,
                       @Param("type") LedgerEntryType type);

    /** When one account last saw an entry of a kind: how far the fee sweep has got. */
    @Query("SELECT MAX(e.createdAt) FROM LedgerEntry e "
            + "WHERE e.userId = :userId AND e.entryType = :type")
    Optional<Instant> newestOf(@Param("userId") Long userId, @Param("type") LedgerEntryType type);

    /** Where the very first summary has to start; empty until a fee has ever been charged. */
    @Query("SELECT MIN(e.createdAt) FROM LedgerEntry e WHERE e.entryType IN :types")
    Optional<Instant> oldestOf(@Param("types") Collection<LedgerEntryType> types);

    /** Every asset that has ever carried fee flow: what a sweep has to consider. */
    @Query("SELECT DISTINCT e.assetId FROM LedgerEntry e WHERE e.entryType IN :types")
    List<Long> assetIdsWithAnyOf(@Param("types") Collection<LedgerEntryType> types);
}
