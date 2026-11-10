package com.sutra.document.service;

import com.sutra.api.document.DocumentContracts.InspectedFile;
import com.sutra.api.document.DocumentContracts.StoredDocument;
import com.sutra.document.entity.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Original files: checked by content, stored byte for byte, served to the owning service. */
public interface DocumentService {

    /** Checks every file first, then stores all of them (PENDING) or none. */
    List<StoredDocument> store(List<MultipartFile> files, Long uploadedById, String uploadedByName);

    /** Checks a file's real content without storing it. */
    InspectedFile inspect(MultipartFile file);

    List<StoredDocument> metadata(List<Long> ids);

    /** One document with its bytes. */
    Document get(Long id);

    void attach(List<Long> ids, String ownerType, Long ownerId);

    void delete(List<Long> ids);

    /** Removes uploads that were never attached to a record (the owning transaction failed). Returns how many. */
    int removeOrphans();
}
