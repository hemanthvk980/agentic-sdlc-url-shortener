package io.github.hemanthvk980.shortener.link.repo;

import io.github.hemanthvk980.shortener.link.domain.ClickEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    interface DailyCount {
        LocalDate getClickDate();
        long getClicks();
    }

    interface ReferrerCount {
        String getReferrer();
        long getClicks();
    }

    @Query("""
            select c.clickDate as clickDate, count(c) as clicks from ClickEvent c
            where c.linkId = :linkId and c.clickDate >= :since
            group by c.clickDate order by c.clickDate""")
    List<DailyCount> countByDay(@Param("linkId") Long linkId, @Param("since") LocalDate since);

    @Query("""
            select coalesce(c.referrerHost, '(direct)') as referrer, count(c) as clicks from ClickEvent c
            where c.linkId = :linkId
            group by coalesce(c.referrerHost, '(direct)') order by count(c) desc""")
    List<ReferrerCount> topReferrers(@Param("linkId") Long linkId, Pageable page);

    @Modifying
    @Query("delete from ClickEvent c where c.clickedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
