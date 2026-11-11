package com.omnistream.ai.repository;

import com.omnistream.ai.model.IngestionEvent;
import jakarta.persistence.LockModeType;
import com.omnistream.ai.dto.response.EventSummary;
import org.springframework.data.domain.Pageable;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface IngestionEventRepository extends JpaRepository<IngestionEvent, UUID> {
    @Query("""
        select new com.omnistream.ai.dto.response.EventSummary(
            e.id,e.sourceId,e.eventType,e.status,e.category,e.createdAt,e.updatedAt)
        from IngestionEvent e order by e.createdAt desc, e.id desc
        """)
    List<EventSummary> recent(Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from IngestionEvent e where e.id = :id")
    Optional<IngestionEvent> lock(@Param("id") UUID id);
}
