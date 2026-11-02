package com.sutra.controller;

import com.sutra.request.MasterDataRequests.EntityRequest;
import com.sutra.request.MasterDataRequests.SubEntityRequest;
import com.sutra.response.MasterDataResponses.EntityResponse;
import com.sutra.response.MasterDataResponses.SubEntityResponse;
import com.sutra.response.PageResponse;
import com.sutra.service.BusinessEntityService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Entities and sub-entities. Everyone signed in can read; only administrators can change. */
@RestController
@RequestMapping("/api/entities")
public class EntityController {

    private final BusinessEntityService service;

    public EntityController(BusinessEntityService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<EntityResponse> search(@RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return service.search(q, page, size);
    }

    @GetMapping("/{id}")
    public EntityResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public EntityResponse create(@Valid @RequestBody EntityRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EntityResponse update(@PathVariable Long id, @Valid @RequestBody EntityRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/sub-entities")
    public List<SubEntityResponse> listSubEntities(@PathVariable Long id) {
        return service.listSubEntities(id);
    }

    @PostMapping("/{id}/sub-entities")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public SubEntityResponse createSubEntity(@PathVariable Long id, @Valid @RequestBody SubEntityRequest request) {
        return service.createSubEntity(id, request);
    }

    @PutMapping("/{id}/sub-entities/{subId}")
    @PreAuthorize("hasRole('ADMIN')")
    public SubEntityResponse updateSubEntity(@PathVariable Long id, @PathVariable Long subId,
                                             @Valid @RequestBody SubEntityRequest request) {
        return service.updateSubEntity(id, subId, request);
    }

    @DeleteMapping("/{id}/sub-entities/{subId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteSubEntity(@PathVariable Long id, @PathVariable Long subId) {
        service.deleteSubEntity(id, subId);
        return ResponseEntity.noContent().build();
    }
}
