package io.github.hemanthvk980.shortener.link.repo;

import io.github.hemanthvk980.shortener.link.domain.Link;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findByCode(String code);

    boolean existsByCode(String code);

    Optional<Link> findByIdempotencyKey(String idempotencyKey);

    /** Atomic increment in the database: no lost updates under concurrent clicks, no entity load. */
    @Modifying
    @Query("update Link l set l.clickCount = l.clickCount + 1 where l.id = :id")
    int incrementClickCount(@Param("id") Long id);
}
