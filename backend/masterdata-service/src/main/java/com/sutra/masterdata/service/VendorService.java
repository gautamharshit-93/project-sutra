package com.sutra.masterdata.service;

import com.sutra.common.web.PageResponse;
import com.sutra.masterdata.dto.request.MasterDataRequests.VendorProfileRequest;
import com.sutra.masterdata.dto.request.MasterDataRequests.VendorRequest;
import com.sutra.masterdata.dto.request.MasterDataRequests.VendorSignupRequest;
import com.sutra.masterdata.dto.response.MasterDataResponses.VendorResponse;
import com.sutra.masterdata.entity.Vendor;

public interface VendorService {

    PageResponse<VendorResponse> search(String q, int page, int size);

    VendorResponse get(Long id);

    VendorResponse create(VendorRequest request);

    VendorResponse register(VendorSignupRequest request);

    VendorResponse updateProfile(Long id, VendorProfileRequest request);

    VendorResponse update(Long id, VendorRequest request);

    void delete(Long id);

    Vendor find(Long id);

    Vendor findActive(Long id);
}
