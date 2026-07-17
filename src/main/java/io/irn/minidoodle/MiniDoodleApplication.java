package io.irn.minidoodle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MiniDoodleApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniDoodleApplication.class, args);
    }
}
