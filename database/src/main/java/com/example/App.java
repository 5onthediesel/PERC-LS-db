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

	public static void main(String[] args) {
		SpringApplication.run(App.class, args);
	}

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
