package com.sutra.service;

import static com.sutra.service.AuditService.details;

import com.sutra.entity.BusinessEntity;
import com.sutra.entity.SubEntity;
import com.sutra.exception.BusinessRuleException;
import com.sutra.exception.DuplicateResourceException;
import com.sutra.exception.EntityNotFoundException;
import com.sutra.exception.ResourceNotFoundException;
import com.sutra.repository.BusinessEntityRepository;
import com.sutra.repository.CustomerRepository;
import com.sutra.repository.PurchaseRequestRepository;
import com.sutra.repository.SubEntityRepository;
import com.sutra.request.MasterDataRequests.EntityRequest;
import com.sutra.request.MasterDataRequests.SubEntityRequest;
import com.sutra.response.MasterDataResponses.EntityResponse;
import com.sutra.response.MasterDataResponses.SubEntityResponse;
import com.sutra.response.PageResponse;
import com.sutra.util.Text;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Parent entities and their sub-entities. */
@Service
@Transactional
public class BusinessEntityService {

    static final String AUDIT_TYPE = "ENTITY";
    static final String SUB_AUDIT_TYPE = "SUB_ENTITY";

    private final BusinessEntityRepository entityRepository;
    private final SubEntityRepository subEntityRepository;
    private final CustomerRepository customerRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final AuditService auditService;

    public BusinessEntityService(BusinessEntityRepository entityRepository, SubEntityRepository subEntityRepository,
                                 CustomerRepository customerRepository,
                                 PurchaseRequestRepository purchaseRequestRepository, AuditService auditService) {
        this.entityRepository = entityRepository;
        this.subEntityRepository = subEntityRepository;
        this.customerRepository = customerRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<EntityResponse> search(String q, int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(BusinessEntity::getName));
        return PageResponse.of(entityRepository.search(Text.blankToNull(q), pageable), EntityResponse::from);
    }

    @Transactional(readOnly = true)
    public EntityResponse get(Long id) {
        return EntityResponse.from(find(id));
    }

    public EntityResponse create(EntityRequest request) {
        String code = normaliseCode(request.code());
        if (entityRepository.existsByCodeIgnoreCase(code)) {
            throw new DuplicateResourceException("An entity with code " + code + " already exists");
        }
        BusinessEntity entity = new BusinessEntity();
        apply(entity, request, code);
        entity = entityRepository.save(entity);
        auditService.record(AUDIT_TYPE, entity.getId(), "CREATED", details("code", code, "name", entity.getName()));
        return EntityResponse.from(entity);
    }

    public EntityResponse update(Long id, EntityRequest request) {
        BusinessEntity entity = find(id);
        String code = normaliseCode(request.code());
        if (entityRepository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw new DuplicateResourceException("An entity with code " + code + " already exists");
        }
        apply(entity, request, code);
        auditService.record(AUDIT_TYPE, id, "UPDATED", details("code", code, "name", entity.getName(),
                "active", entity.isActive()));
        return EntityResponse.from(entity);
    }

    public void delete(Long id) {
        BusinessEntity entity = find(id);
        if (subEntityRepository.countByEntityId(id) > 0) {
            throw new BusinessRuleException("ENTITY_HAS_SUB_ENTITIES",
                    "Delete or move the sub-entities of " + entity.getName() + " first, or deactivate it instead");
        }
        if (customerRepository.countByEntityId(id) > 0 || purchaseRequestRepository.countByEntityId(id) > 0) {
            throw new BusinessRuleException("ENTITY_IN_USE",
                    entity.getName() + " is referenced by customers or purchase requests; deactivate it instead");
        }
        entityRepository.delete(entity);
        auditService.record(AUDIT_TYPE, id, "DELETED", details("code", entity.getCode()));
    }

    // ---------- sub-entities ----------

    @Transactional(readOnly = true)
    public List<SubEntityResponse> listSubEntities(Long entityId) {
        find(entityId);
        return subEntityRepository.findByEntityIdOrderByCode(entityId).stream().map(SubEntityResponse::from).toList();
    }

    public SubEntityResponse createSubEntity(Long entityId, SubEntityRequest request) {
        BusinessEntity parent = find(entityId);
        String code = normaliseCode(request.code());
        if (subEntityRepository.existsByEntityIdAndCodeIgnoreCase(entityId, code)) {
            throw new DuplicateResourceException("Sub-entity code " + code + " already exists under " + parent.getName());
        }
        SubEntity sub = new SubEntity();
        sub.setEntity(parent);
        applySub(sub, request, code);
        sub = subEntityRepository.save(sub);
        parent.getSubEntities().add(sub);
        auditService.record(SUB_AUDIT_TYPE, sub.getId(), "CREATED", details("entityId", entityId, "code", code));
        return SubEntityResponse.from(sub);
    }

    public SubEntityResponse updateSubEntity(Long entityId, Long subEntityId, SubEntityRequest request) {
        SubEntity sub = findSub(entityId, subEntityId);
        String code = normaliseCode(request.code());
        if (subEntityRepository.existsByEntityIdAndCodeIgnoreCaseAndIdNot(entityId, code, subEntityId)) {
            throw new DuplicateResourceException("Sub-entity code " + code + " already exists under this entity");
        }
        applySub(sub, request, code);
        auditService.record(SUB_AUDIT_TYPE, subEntityId, "UPDATED", details("entityId", entityId, "code", code,
                "active", sub.isActive()));
        return SubEntityResponse.from(sub);
    }

    public void deleteSubEntity(Long entityId, Long subEntityId) {
        SubEntity sub = findSub(entityId, subEntityId);
        if (purchaseRequestRepository.countBySubEntityId(subEntityId) > 0) {
            throw new BusinessRuleException("SUB_ENTITY_IN_USE",
                    sub.getName() + " is referenced by purchase requests; deactivate it instead");
        }
        sub.getEntity().getSubEntities().remove(sub);
        subEntityRepository.delete(sub);
        auditService.record(SUB_AUDIT_TYPE, subEntityId, "DELETED", details("entityId", entityId, "code", sub.getCode()));
    }

    // ---------- helpers ----------

    public BusinessEntity find(Long id) {
        return entityRepository.findById(id).orElseThrow(() -> new EntityNotFoundException(id));
    }

    /** Loads a sub-entity and verifies it belongs to the given parent. */
    public SubEntity findSub(Long entityId, Long subEntityId) {
        SubEntity sub = subEntityRepository.findById(subEntityId)
                .orElseThrow(() -> new ResourceNotFoundException("Sub-entity", subEntityId));
        if (!sub.getEntity().getId().equals(entityId)) {
            throw new BusinessRuleException("SUB_ENTITY_PARENT_MISMATCH",
                    "Sub-entity " + subEntityId + " does not belong to entity " + entityId);
        }
        return sub;
    }

    private static void apply(BusinessEntity entity, EntityRequest request, String code) {
        entity.setCode(code);
        entity.setName(request.name().trim());
        entity.setDescription(Text.blankToNull(request.description()));
        entity.setAddress(Text.blankToNull(request.address()));
        entity.setActive(request.active() == null || request.active());
    }

    private static void applySub(SubEntity sub, SubEntityRequest request, String code) {
        sub.setCode(code);
        sub.setName(request.name().trim());
        sub.setDescription(Text.blankToNull(request.description()));
        sub.setActive(request.active() == null || request.active());
    }

    static String normaliseCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
