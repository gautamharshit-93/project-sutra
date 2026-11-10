package com.sutra.document.repository;

import com.sutra.api.document.DocumentContracts.StoredDocument;
import com.sutra.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    /** Metadata only: listing files never reads their bytes. */
    @Query("""
            select new com.sutra.api.document.DocumentContracts$StoredDocument(
                d.id, d.fileName, d.contentType, d.fileFormat, d.sizeBytes, d.sha256, d.uploadedAt)
            from Document d where d.id in :ids order by d.id
            """)
    List<StoredDocument> findMetadata(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query("""
            update Document d set d.status = com.sutra.document.entity.Document$Status.ATTACHED,
                   d.ownerType = :ownerType, d.ownerId = :ownerId
            where d.id in :ids
            """)
    int attach(@Param("ids") Collection<Long> ids, @Param("ownerType") String ownerType,
               @Param("ownerId") Long ownerId);

    @Query("""
            select d.id from Document d
            where d.status = com.sutra.document.entity.Document$Status.PENDING and d.uploadedAt < :before
            """)
    List<Long> findPendingBefore(@Param("before") Instant before);
}
