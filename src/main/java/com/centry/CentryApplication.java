package com.centry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CentryApplication {

	public static void main(String[] args) {
		SpringApplication.run(CentryApplication.class, args);
	}

}
