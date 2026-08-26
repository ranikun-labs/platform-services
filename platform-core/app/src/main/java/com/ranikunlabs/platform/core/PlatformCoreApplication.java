package com.ranikunlabs.platform.core;

import com.ranikunlabs.platform.identity.IdentityModuleConfiguration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(IdentityModuleConfiguration.class)
public class PlatformCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformCoreApplication.class, args);
    }
}
