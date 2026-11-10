package com.sutra.masterdata.dto.response;

import com.sutra.masterdata.entity.BusinessEntity;
import com.sutra.masterdata.entity.Customer;
import com.sutra.masterdata.entity.SubEntity;
import com.sutra.masterdata.entity.Vendor;
import java.time.Instant;
import java.util.List;

public final class MasterDataResponses {

    private MasterDataResponses() {
    }

    public record SubEntityResponse(Long id, Long entityId, String entityName, String code, String name,
                                    String description, boolean active, Instant createdAt, Instant updatedAt) {
        public static SubEntityResponse from(SubEntity s) {
            return new SubEntityResponse(s.getId(), s.getEntity().getId(), s.getEntity().getName(), s.getCode(),
                    s.getName(), s.getDescription(), s.isActive(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    public record EntityResponse(Long id, String code, String name, String description, String address,
                                 boolean active, List<SubEntityResponse> subEntities,
                                 Instant createdAt, Instant updatedAt) {
        public static EntityResponse from(BusinessEntity e) {
            return new EntityResponse(e.getId(), e.getCode(), e.getName(), e.getDescription(), e.getAddress(),
                    e.isActive(), e.getSubEntities().stream().map(SubEntityResponse::from).toList(),
                    e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    public record CustomerResponse(Long id, String code, String name, Long entityId, String entityName,
                                   String contactPerson, String email, String phone, String address,
                                   boolean active, Instant createdAt, Instant updatedAt) {
        public static CustomerResponse from(Customer c) {
            return new CustomerResponse(c.getId(), c.getCode(), c.getName(),
                    c.getEntity() == null ? null : c.getEntity().getId(),
                    c.getEntity() == null ? null : c.getEntity().getName(),
                    c.getContactPerson(), c.getEmail(), c.getPhone(), c.getAddress(), c.isActive(),
                    c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    public record VendorResponse(Long id, String code, String name, String contactPerson, String email,
                                 String phone, String address, String taxNumber, String website,
                                 boolean active, Instant createdAt, Instant updatedAt) {
        public static VendorResponse from(Vendor v) {
            return new VendorResponse(v.getId(), v.getCode(), v.getName(), v.getContactPerson(), v.getEmail(),
                    v.getPhone(), v.getAddress(), v.getTaxNumber(), v.getWebsite(), v.isActive(),
                    v.getCreatedAt(), v.getUpdatedAt());
        }
    }
}
