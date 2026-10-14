package com.pharos.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableRetry
@SpringBootApplication
@EnableAsync
@MapperScan("com.pharos.rag.**.mapper")
public class PharosBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(PharosBackendApplication.class, args);
    }
}
