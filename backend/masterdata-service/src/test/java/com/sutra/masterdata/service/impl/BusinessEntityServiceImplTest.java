package com.sutra.masterdata.service.impl;

import com.sutra.common.audit.AuditService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.masterdata.entity.BusinessEntity;
import com.sutra.masterdata.entity.SubEntity;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.masterdata.exception.EntityNotFoundException;
import com.sutra.masterdata.repository.BusinessEntityRepository;
import com.sutra.masterdata.repository.CustomerRepository;
import com.sutra.masterdata.client.UsageClient;
import com.sutra.masterdata.repository.SubEntityRepository;
import com.sutra.masterdata.dto.request.MasterDataRequests.EntityRequest;
import com.sutra.masterdata.dto.request.MasterDataRequests.SubEntityRequest;
import com.sutra.masterdata.support.Fixtures;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BusinessEntityServiceImplTest {

    @Mock BusinessEntityRepository entityRepository;
    @Mock SubEntityRepository subEntityRepository;
    @Mock CustomerRepository customerRepository;
    @Mock UsageClient usage;
    @Mock AuditService auditService;
    @InjectMocks BusinessEntityServiceImpl service;

    @Test
    void createNormalisesCodeSavesAndAudits() {
        when(entityRepository.existsByCodeIgnoreCase("HQ-01")).thenReturn(false);
        when(entityRepository.save(any())).thenAnswer(inv -> {
            BusinessEntity e = inv.getArgument(0);
            e.setId(5L);
            return e;
        });

        var response = service.create(new EntityRequest(" hq-01 ", " Head Office ", null, "", null));

        assertThat(response.code()).isEqualTo("HQ-01");
        assertThat(response.name()).isEqualTo("Head Office");
        assertThat(response.address()).as("blank becomes null").isNull();
        assertThat(response.active()).isTrue();
        verify(auditService).record(eq("ENTITY"), eq(5L), eq("CREATED"), anyMap());
    }

    @Test
    void duplicateCodeIsRejected() {
        when(entityRepository.existsByCodeIgnoreCase("HQ")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new EntityRequest("hq", "Head Office", null, null, true)))
                .isInstanceOf(DuplicateResourceException.class);
        verify(entityRepository, never()).save(any());
    }

    @Test
    void unknownEntityThrowsEntityNotFound() {
        when(entityRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(9L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("9");
    }

    @Test
    void entityWithSubEntitiesCannotBeDeleted() {
        BusinessEntity entity = Fixtures.entity();
        when(entityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(subEntityRepository.countByEntityId(entity.getId())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(entity.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("ENTITY_HAS_SUB_ENTITIES");
        verify(entityRepository, never()).delete(any());
    }

    @Test
    void entityReferencedByPurchaseRequestsCannotBeDeleted() {
        BusinessEntity entity = Fixtures.entity();
        when(entityRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(usage.procurementUsage("entities", entity.getId())).thenReturn(new UsageClient.Usage(1, 0));

        assertThatThrownBy(() -> service.delete(entity.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("ENTITY_IN_USE");
    }

    @Test
    void subEntityIsCreatedUnderItsParent() {
        BusinessEntity parent = Fixtures.entity();
        when(entityRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(subEntityRepository.save(any())).thenAnswer(inv -> {
            SubEntity s = inv.getArgument(0);
            s.setId(77L);
            return s;
        });

        var response = service.createSubEntity(parent.getId(), new SubEntityRequest("it", "IT Department", null, true));

        assertThat(response.entityId()).isEqualTo(parent.getId());
        assertThat(response.code()).isEqualTo("IT");
        assertThat(parent.getSubEntities()).hasSize(1);
    }

    @Test
    void subEntityOfAnotherParentIsRejected() {
        BusinessEntity other = Fixtures.entity();
        SubEntity sub = new SubEntity();
        sub.setId(3L);
        sub.setEntity(other);
        when(subEntityRepository.findById(3L)).thenReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.findSub(999L, 3L))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("SUB_ENTITY_PARENT_MISMATCH");
    }
}
