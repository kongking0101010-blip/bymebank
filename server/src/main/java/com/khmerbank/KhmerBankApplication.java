package com.khmerbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableCaching
@EnableRetry
public class KhmerBankApplication {
    public static void main(String[] args) {
        SpringApplication.run(KhmerBankApplication.class, args);
    }
}
