package com.sutra.document.service.impl;

import com.sutra.api.document.DocumentContracts.InspectedFile;
import com.sutra.api.document.DocumentContracts.StoredDocument;
import com.sutra.api.enums.FileFormat;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.document.config.DocumentProperties;
import com.sutra.document.entity.Document;
import com.sutra.document.exception.InvalidQuotationFileException;
import com.sutra.document.repository.DocumentRepository;
import com.sutra.document.service.DocumentService;
import com.sutra.document.service.internal.FileContentValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
@Transactional
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    private final DocumentRepository repository;
    private final FileContentValidator validator;
    private final DocumentProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public DocumentServiceImpl(DocumentRepository repository, FileContentValidator validator,
                               DocumentProperties properties, Clock clock, TransactionTemplate tx) {
        this.repository = repository;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
    }

    @Override
    public List<StoredDocument> store(List<MultipartFile> files, Long uploadedById, String uploadedByName) {
        List<MultipartFile> nonEmpty = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (nonEmpty.isEmpty()) {
            throw new InvalidQuotationFileException("No file was uploaded, or the files are empty.");
        }
        List<Document> checked = new ArrayList<>();
        for (MultipartFile file : nonEmpty) { // check all first: store every file or none
            String fileName = FileContentValidator.sanitiseFileName(file.getOriginalFilename());
            byte[] bytes = readBytes(file);
            FileFormat format = validator.validate(fileName, bytes);
            Document d = new Document();
            d.setFileName(fileName);
            d.setContentType(format.mimeType());
            d.setFileFormat(format);
            d.setSizeBytes(bytes.length);
            d.setSha256(sha256(bytes));
            d.setContent(bytes);
            d.setUploadedById(uploadedById);
            d.setUploadedByName(uploadedByName);
            d.setUploadedAt(Instant.now(clock));
            checked.add(d);
        }
        List<Document> saved = repository.saveAll(checked);
        return saved.stream().map(DocumentServiceImpl::toStored).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public InspectedFile inspect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidQuotationFileException("No file was uploaded, or the file is empty.");
        }
        String fileName = FileContentValidator.sanitiseFileName(file.getOriginalFilename());
        byte[] bytes = readBytes(file);
        FileFormat format = validator.validate(fileName, bytes);
        return new InspectedFile(fileName, format, bytes.length, sha256(bytes));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredDocument> metadata(List<Long> ids) {
        return ids == null || ids.isEmpty() ? List.of() : repository.findMetadata(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public Document get(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Document", id));
    }

    @Override
    public void attach(List<Long> ids, String ownerType, Long ownerId) {
        if (ids != null && !ids.isEmpty()) {
            repository.attach(ids, ownerType, ownerId);
        }
    }

    @Override
    public void delete(List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            repository.deleteAllByIdInBatch(ids);
        }
    }

    /**
     * Not one transaction: each orphan is deleted on its own, so a file that is referenced after all (its owner was
     * saved but the attach call failed; the owner's foreign key protects it) is skipped without blocking the rest.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int removeOrphans() {
        Instant before = Instant.now(clock).minus(Duration.ofMinutes(properties.orphanAfterMinutes()));
        int removed = 0;
        for (Long id : repository.findPendingBefore(before)) {
            try {
                tx.executeWithoutResult(status -> {
                    repository.deleteById(id);
                    repository.flush();
                });
                removed++;
            } catch (DataIntegrityViolationException e) {
                log.debug("Upload {} is referenced by its owner; kept", id);
            }
        }
        if (removed > 0) {
            log.info("Removed {} upload(s) that were never attached to a record", removed);
        }
        return removed;
    }

    static StoredDocument toStored(Document d) {
        return new StoredDocument(d.getId(), d.getFileName(), d.getContentType(), d.getFileFormat(), d.getSizeBytes(),
                d.getSha256(), d.getUploadedAt());
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidQuotationFileException("The uploaded file could not be read.");
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
