package com.sutra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** SUTRA master data service: entities, sub-entities, customers and vendors (schema "masterdata"). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MasterDataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MasterDataServiceApplication.class, args);
    }
}
