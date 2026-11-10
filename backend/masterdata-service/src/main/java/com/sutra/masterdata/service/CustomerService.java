package com.sutra.masterdata.service;

import com.sutra.common.web.PageResponse;
import com.sutra.masterdata.dto.request.MasterDataRequests.CustomerRequest;
import com.sutra.masterdata.dto.response.MasterDataResponses.CustomerResponse;
import com.sutra.masterdata.entity.Customer;

public interface CustomerService {

    PageResponse<CustomerResponse> search(String q, int page, int size);

    CustomerResponse get(Long id);

    CustomerResponse create(CustomerRequest request);

    CustomerResponse update(Long id, CustomerRequest request);

    void delete(Long id);

    Customer find(Long id);
}
