package com.example;

import java.util.Locale;
import java.util.logging.Logger;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class App {
	private static final Logger logger = Logger.getLogger(App.class.getName());

	/**
	 * Inputs:      args (String[]) — command-line arguments passed to the JVM
	 * Outputs:     void — launches the Spring Boot application context
	 * Functionality: Entry point; bootstraps the entire Spring Boot application.
	 * Dependencies: org.springframework.boot.SpringApplication
	 * Called by:   JVM on startup
	 */
	public static void main(String[] args) {
		SpringApplication.run(App.class, args);
	}

	/**
	 * Inputs:      None
	 * Outputs:     CommandLineRunner — a Spring bean that runs after the context is fully loaded
	 * Functionality: Conditionally runs db.initializeSchemaAtStartup() if the INIT_SCHEMA_ON_STARTUP
	 *               environment variable is set to "true", "1", or "yes".
	 * Dependencies: org.springframework.boot.CommandLineRunner, db.initializeSchemaAtStartup
	 * Called by:   Spring Boot framework after application context startup
	 */
	@Bean
	CommandLineRunner initializeSchemaOnStartup() {
		return args -> {
			String flag = System.getenv("INIT_SCHEMA_ON_STARTUP");
			boolean shouldInit = flag != null && ("true".equals(flag.toLowerCase(Locale.ROOT))
					|| "1".equals(flag)
					|| "yes".equals(flag.toLowerCase(Locale.ROOT)));

			if (!shouldInit) {
				logger.info("Skipping startup schema initialization (set INIT_SCHEMA_ON_STARTUP=true to enable).");
				return;
			}

			logger.info("Running startup schema initialization because INIT_SCHEMA_ON_STARTUP is enabled.");
			try {
				db.initializeSchemaAtStartup();
			} catch (Exception e) {
				logger.severe(
						"Startup schema initialization failed; continuing without schema init: " + e.getMessage());
				logger.log(java.util.logging.Level.FINE, "Startup schema initialization stack trace", e);
			}
		};
	}
}
