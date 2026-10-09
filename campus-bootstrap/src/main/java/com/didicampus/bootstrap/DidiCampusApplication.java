package com.didicampus.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.didicampus")
public class DidiCampusApplication {

    public static void main(String[] args) {
        SpringApplication.run(DidiCampusApplication.class, args);
    }
}
