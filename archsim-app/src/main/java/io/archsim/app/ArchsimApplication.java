package io.archsim.app;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.CalibrationProperties;
import io.archsim.app.config.SignInProperties;

/**
 * ArchSim's application: the HTTP API over PostgreSQL, started in one of its {@link Role
 * roles} ({@code archsim-app api}, {@code archsim-app migrate}). Settings come from the
 * same {@code ARCHSIM_*} environment variables as the Python API's. They are bound here,
 * not scanned for, so that test slices such as {@code @WebMvcTest} have them too: the web
 * layer's filters read them.
 */
@SpringBootApplication
@EnableConfigurationProperties({ ArchsimProperties.class, SignInProperties.class, CalibrationProperties.class })
public class ArchsimApplication {

	/**
	 * Starts the role the first argument names; the rest are Spring Boot's arguments.
	 * @param args the role, then Spring Boot's arguments such as
	 * {@code --server.port=8000}
	 */
	public static void main(String[] args) {
		Role role = Role.of(args);
		ConfigurableApplicationContext context = new SpringApplicationBuilder(ArchsimApplication.class)
			.web(role.webApplicationType())
			.profiles(role.profile())
			.run(Arrays.copyOfRange(args, 1, args.length));
		if (role.exitsWhenStarted()) {
			System.exit(SpringApplication.exit(context));
		}
	}

}
