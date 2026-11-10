package com.sutra.masterdata.service.impl;

import com.sutra.masterdata.service.internal.CodeGenerator;
import com.sutra.masterdata.service.BusinessEntityService;
import com.sutra.masterdata.service.CustomerService;
import com.sutra.common.audit.AuditService;

import static com.sutra.common.audit.AuditService.details;

import com.sutra.masterdata.entity.Customer;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.masterdata.exception.CustomerNotFoundException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.masterdata.repository.CustomerRepository;
import com.sutra.masterdata.client.UsageClient;
import com.sutra.masterdata.dto.request.MasterDataRequests.CustomerRequest;
import com.sutra.masterdata.dto.response.MasterDataResponses.CustomerResponse;
import com.sutra.common.web.PageResponse;
import com.sutra.common.util.Text;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CustomerServiceImpl implements CustomerService {

    static final String AUDIT_TYPE = "CUSTOMER";

    private final CustomerRepository customerRepository;
    private final BusinessEntityService entityService;
    private final UsageClient usage;
    private final AuditService auditService;

    public CustomerServiceImpl(CustomerRepository customerRepository, BusinessEntityService entityService,
                           UsageClient usage, AuditService auditService) {
        this.customerRepository = customerRepository;
        this.entityService = entityService;
        this.usage = usage;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    @Override
    public PageResponse<CustomerResponse> search(String q, int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(Customer::getName));
        return PageResponse.of(customerRepository.search(Text.blankToNull(q), pageable), CustomerResponse::from);
    }

    @Transactional(readOnly = true)
    @Override
    public CustomerResponse get(Long id) {
        return CustomerResponse.from(find(id));
    }

    @Override
    public CustomerResponse create(CustomerRequest request) {
        String code = CodeGenerator.normalise(request.code());
        if (customerRepository.existsByCodeIgnoreCase(code)) {
            throw new DuplicateResourceException("A customer with code " + code + " already exists");
        }
        Customer customer = new Customer();
        apply(customer, request, code);
        customer = customerRepository.save(customer);
        auditService.record(AUDIT_TYPE, customer.getId(), "CREATED", details("code", code, "name", customer.getName()));
        return CustomerResponse.from(customer);
    }

    @Override
    public CustomerResponse update(Long id, CustomerRequest request) {
        Customer customer = find(id);
        String code = CodeGenerator.normalise(request.code());
        if (customerRepository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw new DuplicateResourceException("A customer with code " + code + " already exists");
        }
        apply(customer, request, code);
        auditService.record(AUDIT_TYPE, id, "UPDATED", details("code", code, "active", customer.isActive()));
        return CustomerResponse.from(customer);
    }

    @Override
    public void delete(Long id) {
        Customer customer = find(id);
        if (usage.procurementUsage("customers", id).inUse()) {
            throw new BusinessRuleException("CUSTOMER_IN_USE",
                    customer.getName() + " is referenced by purchase requests; deactivate it instead");
        }
        customerRepository.delete(customer);
        auditService.record(AUDIT_TYPE, id, "DELETED", details("code", customer.getCode()));
    }

    @Override
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
