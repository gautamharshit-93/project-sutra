package com.sutra.masterdata.service.impl;

import com.sutra.masterdata.service.internal.CodeGenerator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.common.audit.AuditService;
import com.sutra.masterdata.client.UsageClient;
import com.sutra.masterdata.entity.BusinessEntity;
import com.sutra.masterdata.entity.Vendor;
import com.sutra.masterdata.exception.VendorNotFoundException;
import com.sutra.masterdata.repository.BusinessEntityRepository;
import com.sutra.masterdata.repository.CustomerRepository;
import com.sutra.masterdata.repository.SubEntityRepository;
import com.sutra.masterdata.repository.VendorRepository;
import com.sutra.masterdata.dto.request.MasterDataRequests.EntityRequest;
import com.sutra.masterdata.dto.request.MasterDataRequests.SubEntityRequest;
import com.sutra.masterdata.dto.request.MasterDataRequests.VendorProfileRequest;
import com.sutra.masterdata.dto.request.MasterDataRequests.VendorSignupRequest;
import com.sutra.masterdata.support.Fixtures;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** People type names only: codes are generated; vendors come from approved sign-ups and keep their own profile. */
class SelfServiceMasterDataTest {

    @Test
    void codesAreMadeFromTheNameAndKeptUnique() {
        Set<String> taken = Set.of("AXIOMAUTOC", "AXIOMAUTOC-2");
        assertThat(CodeGenerator.fromName("Axiom Auto Components Pvt Ltd", taken::contains)).isEqualTo("AXIOMAUTOC-3");
        assertThat(CodeGenerator.fromName("Chakan plant", c -> false)).isEqualTo("CHAKANPLAN");
        assertThat(CodeGenerator.fromName("कावेरी", c -> false)).isEqualTo("CODE");
        assertThat(CodeGenerator.fromName("A", c -> false)).isEqualTo("CODEA");
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Entities {
        @Mock BusinessEntityRepository entityRepository;
        @Mock SubEntityRepository subEntityRepository;
        @Mock CustomerRepository customerRepository;
        @Mock UsageClient usage;
        @Mock AuditService auditService;
        @InjectMocks BusinessEntityServiceImpl service;

        @Test
        void anEntityNeedsOnlyAName() {
            when(entityRepository.existsByCodeIgnoreCase(anyString())).thenReturn(false);
            when(entityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var created = service.create(new EntityRequest(null, "Axiom Auto Components", null, null, null));

            assertThat(created.code()).isEqualTo("AXIOMAUTOC");
            assertThat(created.active()).isTrue();
        }

        @Test
        void aBlankCodeOnUpdateKeepsTheExistingCode() {
            BusinessEntity e = Fixtures.entity();
            when(entityRepository.findById(e.getId())).thenReturn(Optional.of(e));
            when(entityRepository.existsByCodeIgnoreCaseAndIdNot(e.getCode(), e.getId())).thenReturn(false);

            var updated = service.update(e.getId(), new EntityRequest("", "MTSL Pune", null, null, true));

            assertThat(updated.code()).isEqualTo(e.getCode());
            assertThat(updated.name()).isEqualTo("MTSL Pune");
        }

        @Test
        void aSubEntityGetsACodeUniqueWithinItsEntity() {
            BusinessEntity e = Fixtures.entity();
            when(entityRepository.findById(e.getId())).thenReturn(Optional.of(e));
            when(subEntityRepository.existsByEntityIdAndCodeIgnoreCase(e.getId(), "CHAKANPLAN")).thenReturn(true);
            when(subEntityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var sub = service.createSubEntity(e.getId(), new SubEntityRequest(null, "Chakan plant", null, null));

            assertThat(sub.code()).isEqualTo("CHAKANPLAN-2");
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Vendors {
        @Mock VendorRepository vendorRepository;
        @Mock UsageClient usage;
        @Mock AuditService auditService;
        @InjectMocks VendorServiceImpl service;

        @Test
        void anApprovedSignUpBecomesAnActiveVendor() {
            when(vendorRepository.existsByCodeIgnoreCase(anyString())).thenReturn(false);
            when(vendorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var v = service.register(new VendorSignupRequest("Kaveri Castings", "Meena Rao", "sales@kaveri.example",
                    "+91 80 4000 1234", "Peenya, Bengaluru", "29ABCDE1234F1Z5", null));

            assertThat(v.code()).isEqualTo("KAVERICAST");
            assertThat(v.active()).isTrue();
            assertThat(v.taxNumber()).isEqualTo("29ABCDE1234F1Z5");
            verify(auditService).record(eq("VENDOR"), any(), eq("CREATED"), any());
        }

        @Test
        void aVendorChangesItsContactDetailsButNotItsNameOrCode() {
            Vendor v = Fixtures.vendor("Kaveri");
            String name = v.getName();
            String code = v.getCode();
            when(vendorRepository.findById(v.getId())).thenReturn(Optional.of(v));

            var updated = service.updateProfile(v.getId(), new VendorProfileRequest("Meena", "meena@kaveri.example",
                    "+91 80 4000 9999", " ", "29ABCDE1234F1Z5", null));

            assertThat(updated.phone()).isEqualTo("+91 80 4000 9999");
            assertThat(updated.address()).isNull(); // blank means cleared
            assertThat(updated.name()).isEqualTo(name);
            assertThat(updated.code()).isEqualTo(code);
        }

        @Test
        void anUnknownVendorProfileIsNotFound() {
            when(vendorRepository.findById(99L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.updateProfile(99L, new VendorProfileRequest(null, null, null, null, null, null)))
                    .isInstanceOf(VendorNotFoundException.class);
        }
    }
}
