package io.irn.minidoodle.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mini Doodle — Scheduling API")
                        .version("0.1.0")
                        .description("""
                                Mini Doodle — a meeting scheduling platform. Users own calendars of \
                                fixed-duration slots; meetings are proposed, voted on by participants, \
                                and confirmed once every required participant agrees.
                                """)
                        .contact(new Contact().name("Isidro Rebollo").url("https://github.com/isidrorn")));
    }
}
