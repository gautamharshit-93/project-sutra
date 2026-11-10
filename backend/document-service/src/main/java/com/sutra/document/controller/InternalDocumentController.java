package com.sutra.document.controller;

import com.sutra.api.document.DocumentContracts.AttachRequest;
import com.sutra.api.document.DocumentContracts.DeleteRequest;
import com.sutra.api.document.DocumentContracts.InspectedFile;
import com.sutra.api.document.DocumentContracts.StoredDocument;
import com.sutra.document.entity.Document;
import com.sutra.document.service.DocumentService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Files, for the other services only (service tokens; the gateway never forwards {@code /internal}). Users reach
 * their files through the owning service, which checks who may see them.
 */
@RestController
@RequestMapping("/internal/documents")
public class InternalDocumentController {

    private final DocumentService documents;

    public InternalDocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<StoredDocument> store(@RequestPart("files") List<MultipartFile> files,
                                      @RequestParam Long uploadedById, @RequestParam String uploadedByName) {
        return documents.store(files, uploadedById, uploadedByName);
    }

    @PostMapping(path = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InspectedFile inspect(@RequestPart("file") MultipartFile file) {
        return documents.inspect(file);
    }

    @GetMapping("/metadata")
    public List<StoredDocument> metadata(@RequestParam List<Long> ids) {
        return documents.metadata(ids);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        Document d = documents.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(d.getFileName(), StandardCharsets.UTF_8).build().toString())
                .body(d.getContent());
    }

    @PostMapping("/attach")
    public ResponseEntity<Void> attach(@RequestBody AttachRequest request) {
        documents.attach(request.documentIds(), request.ownerType(), request.ownerId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/delete")
    public ResponseEntity<Void> delete(@RequestBody DeleteRequest request) {
        documents.delete(request.documentIds());
        return ResponseEntity.noContent().build();
    }
}
