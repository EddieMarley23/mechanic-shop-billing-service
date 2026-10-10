package org.project.mechanic_shop.billing.shared.config.system;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableAsync
@EnableScheduling
public class SystemConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
