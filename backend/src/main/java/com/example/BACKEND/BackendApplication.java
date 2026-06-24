package com.example.BACKEND;

import com.example.BACKEND.catalogue.agent.scale.ScaleProperties;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ScaleProperties.class, SemanticPlanningProperties.class})
public class BackendApplication {

	public static void main(String[] args) {

        SpringApplication.run(BackendApplication.class, args);
	}

}