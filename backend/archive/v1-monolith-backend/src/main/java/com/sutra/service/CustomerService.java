package com.sutra.service;

import static com.sutra.service.AuditService.details;

import com.sutra.entity.Customer;
import com.sutra.exception.BusinessRuleException;
import com.sutra.exception.CustomerNotFoundException;
import com.sutra.exception.DuplicateResourceException;
import com.sutra.repository.CustomerRepository;
import com.sutra.repository.PurchaseRequestRepository;
import com.sutra.request.MasterDataRequests.CustomerRequest;
import com.sutra.response.MasterDataResponses.CustomerResponse;
import com.sutra.response.PageResponse;
import com.sutra.util.Text;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CustomerService {

    static final String AUDIT_TYPE = "CUSTOMER";

    private final CustomerRepository customerRepository;
    private final BusinessEntityService entityService;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final AuditService auditService;

    public CustomerService(CustomerRepository customerRepository, BusinessEntityService entityService,
                           PurchaseRequestRepository purchaseRequestRepository, AuditService auditService) {
        this.customerRepository = customerRepository;
        this.entityService = entityService;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> search(String q, int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(Customer::getName));
        return PageResponse.of(customerRepository.search(Text.blankToNull(q), pageable), CustomerResponse::from);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(Long id) {
        return CustomerResponse.from(find(id));
    }

    public CustomerResponse create(CustomerRequest request) {
        String code = BusinessEntityService.normaliseCode(request.code());
        if (customerRepository.existsByCodeIgnoreCase(code)) {
            throw new DuplicateResourceException("A customer with code " + code + " already exists");
        }
        Customer customer = new Customer();
        apply(customer, request, code);
        customer = customerRepository.save(customer);
        auditService.record(AUDIT_TYPE, customer.getId(), "CREATED", details("code", code, "name", customer.getName()));
        return CustomerResponse.from(customer);
    }

    public CustomerResponse update(Long id, CustomerRequest request) {
        Customer customer = find(id);
        String code = BusinessEntityService.normaliseCode(request.code());
        if (customerRepository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw new DuplicateResourceException("A customer with code " + code + " already exists");
        }
        apply(customer, request, code);
        auditService.record(AUDIT_TYPE, id, "UPDATED", details("code", code, "active", customer.isActive()));
        return CustomerResponse.from(customer);
    }

    public void delete(Long id) {
        Customer customer = find(id);
        if (purchaseRequestRepository.countByCustomerId(id) > 0) {
            throw new BusinessRuleException("CUSTOMER_IN_USE",
                    customer.getName() + " is referenced by purchase requests; deactivate it instead");
        }
        customerRepository.delete(customer);
        auditService.record(AUDIT_TYPE, id, "DELETED", details("code", customer.getCode()));
    }

    public Customer find(Long id) {
        return customerRepository.findById(id).orElseThrow(() -> new CustomerNotFoundException(id));
    }

    private void apply(Customer customer, CustomerRequest request, String code) {
        customer.setCode(code);
        customer.setName(request.name().trim());
        customer.setEntity(request.entityId() == null ? null : entityService.find(request.entityId()));
        customer.setContactPerson(Text.blankToNull(request.contactPerson()));
        customer.setEmail(Text.blankToNull(request.email()));
        customer.setPhone(Text.blankToNull(request.phone()));
        customer.setAddress(Text.blankToNull(request.address()));
        customer.setActive(request.active() == null || request.active());
    }
}
