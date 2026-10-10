package com.exchange.user.identity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** In the order they were made: ids are random (RandomUidGenerator), so key order means nothing. */
    @Query("SELECT u FROM User u WHERE u.parentUserId = :parentUserId ORDER BY u.createdAt, u.id")
    List<User> findByParentUserId(@Param("parentUserId") Long parentUserId);

    /** Signups in {@code [from, to)}: customers who registered, never a sub a master created. */
    long countByTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(UserType type, java.time.Instant from, java.time.Instant to);

    /**
     * The admin's customer list: primary accounts only, since a sub is shown under its master,
     * and never the exchange's own rows. {@code q} matches the email as a substring, or the id
     * exactly when it is a number -- a support ticket carries one or the other.
     */
    @Query("""
            SELECT u FROM User u
             WHERE u.type = com.exchange.user.identity.UserType.PRIMARY
               AND (:q IS NULL OR u.email LIKE CONCAT('%', :q, '%') OR u.id = :id)
               AND (:status IS NULL OR u.status = :status)
             ORDER BY u.createdAt DESC
            """)
    Page<User> searchCustomers(@Param("q") String q, @Param("id") Long id,
                               @Param("status") UserStatus status, Pageable pageable);
}
