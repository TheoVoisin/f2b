package fr.filbleu.tripupdates;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FilBleuApplication {

    public static void main(String[] args) {
        SpringApplication.run(FilBleuApplication.class, args);
    }
}
