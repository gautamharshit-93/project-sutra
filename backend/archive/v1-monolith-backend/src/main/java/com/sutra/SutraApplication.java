package com.sutra;

import com.sutra.config.ExtractionProperties;
import com.sutra.config.SutraProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SutraProperties.class, ExtractionProperties.class})
public class SutraApplication {

    public static void main(String[] args) {
        SpringApplication.run(SutraApplication.class, args);
    }
}
