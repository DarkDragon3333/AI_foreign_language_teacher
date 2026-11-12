package study.ai_foreign_language_teacher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiForeignLanguageTeacherApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiForeignLanguageTeacherApplication.class, args);
    }

}
