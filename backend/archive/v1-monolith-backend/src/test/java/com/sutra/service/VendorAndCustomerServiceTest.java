package com.sutra.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.entity.BusinessEntity;
import com.sutra.entity.Customer;
import com.sutra.entity.Vendor;
import com.sutra.exception.BusinessRuleException;
import com.sutra.exception.CustomerNotFoundException;
import com.sutra.exception.DuplicateResourceException;
import com.sutra.exception.EntityNotFoundException;
import com.sutra.exception.VendorNotFoundException;
import com.sutra.repository.CustomerRepository;
import com.sutra.repository.PurchaseRequestRepository;
import com.sutra.repository.QuotationRepository;
import com.sutra.repository.VendorRepository;
import com.sutra.request.MasterDataRequests.CustomerRequest;
import com.sutra.request.MasterDataRequests.VendorRequest;
import com.sutra.support.Fixtures;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

class VendorAndCustomerServiceTest {

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Vendors {
        @Mock VendorRepository vendorRepository;
        @Mock QuotationRepository quotationRepository;
        @Mock AuditService auditService;
        @InjectMocks VendorService service;

        @Test
        void createsVendorWithContactDetails() {
            when(vendorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.create(new VendorRequest("alpha", "Alpha Computers", "Ravi", "ravi@alpha.in",
                    "+91 98765 43210", "Pune", "27AAACA1234A1Z5", null, true));

            assertThat(response.code()).isEqualTo("ALPHA");
            assertThat(response.email()).isEqualTo("ravi@alpha.in");
            assertThat(response.taxNumber()).isEqualTo("27AAACA1234A1Z5");
        }

        @Test
        void duplicateCodeOnUpdateIsRejected() {
            Vendor v = Fixtures.vendor("Alpha");
            when(vendorRepository.findById(v.getId())).thenReturn(Optional.of(v));
            when(vendorRepository.existsByCodeIgnoreCaseAndIdNot("BETA", v.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.update(v.getId(),
                    new VendorRequest("beta", "Alpha", null, null, null, null, null, null, true)))
                    .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        void missingVendorThrowsVendorNotFound() {
            when(vendorRepository.findById(4L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.get(4L)).isInstanceOf(VendorNotFoundException.class);
        }

        @Test
        void vendorWithQuotationsCannotBeDeleted() {
            Vendor v = Fixtures.vendor("Alpha");
            when(vendorRepository.findById(v.getId())).thenReturn(Optional.of(v));
            when(quotationRepository.countByVendorId(v.getId())).thenReturn(3L);

            assertThatThrownBy(() -> service.delete(v.getId())).isInstanceOf(BusinessRuleException.class);
            verify(vendorRepository, never()).delete(any());
        }

        @Test
        void inactiveVendorCannotReceiveQuotations() {
            Vendor v = Fixtures.vendor("Alpha");
            v.setActive(false);
            when(vendorRepository.findById(v.getId())).thenReturn(Optional.of(v));

            assertThatThrownBy(() -> service.findActive(v.getId()))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("code").isEqualTo("VENDOR_INACTIVE");
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Customers {
        @Mock CustomerRepository customerRepository;
        @Mock BusinessEntityService entityService;
        @Mock PurchaseRequestRepository purchaseRequestRepository;
        @Mock AuditService auditService;
        @InjectMocks CustomerService service;

        @Test
        void customerIsLinkedToItsEntity() {
            BusinessEntity entity = Fixtures.entity();
            when(entityService.find(entity.getId())).thenReturn(entity);
            when(customerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var response = service.create(new CustomerRequest("c1", "Acme", entity.getId(), null, null, null, null, null));

            assertThat(response.entityId()).isEqualTo(entity.getId());
            assertThat(response.entityName()).isEqualTo("Head Office");
        }

        @Test
        void unknownEntityLinkIsRejected() {
            when(entityService.find(42L)).thenThrow(new EntityNotFoundException(42L));

            assertThatThrownBy(() -> service.create(new CustomerRequest("c1", "Acme", 42L, null, null, null, null, null)))
                    .isInstanceOf(EntityNotFoundException.class);
            verify(customerRepository, never()).save(any());
        }

        @Test
        void customerUsedByRequestsCannotBeDeleted() {
            Customer c = new Customer();
            c.setId(8L);
            c.setName("Acme");
            when(customerRepository.findById(8L)).thenReturn(Optional.of(c));
            when(purchaseRequestRepository.countByCustomerId(8L)).thenReturn(1L);

            assertThatThrownBy(() -> service.delete(8L)).isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void missingCustomerThrowsCustomerNotFound() {
            when(customerRepository.findById(1L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.get(1L)).isInstanceOf(CustomerNotFoundException.class);
        }
    }
}
