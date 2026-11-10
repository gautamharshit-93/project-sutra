package com.sutra.masterdata.service;

import com.sutra.common.web.PageResponse;
import com.sutra.masterdata.dto.request.MasterDataRequests.EntityRequest;
import com.sutra.masterdata.dto.request.MasterDataRequests.SubEntityRequest;
import com.sutra.masterdata.dto.response.MasterDataResponses.EntityResponse;
import com.sutra.masterdata.dto.response.MasterDataResponses.SubEntityResponse;
import com.sutra.masterdata.entity.BusinessEntity;
import com.sutra.masterdata.entity.SubEntity;
import java.util.List;

/** Parent entities and their sub-entities. */
public interface BusinessEntityService {

    PageResponse<EntityResponse> search(String q, int page, int size);

    EntityResponse get(Long id);

    EntityResponse create(EntityRequest request);

    EntityResponse update(Long id, EntityRequest request);

    void delete(Long id);

    List<SubEntityResponse> listSubEntities(Long entityId);

    SubEntityResponse createSubEntity(Long entityId, SubEntityRequest request);

    SubEntityResponse updateSubEntity(Long entityId, Long subEntityId, SubEntityRequest request);

    void deleteSubEntity(Long entityId, Long subEntityId);

    BusinessEntity find(Long id);

    SubEntity findSub(Long entityId, Long subEntityId);
}
