package com.cadbbox.parser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * <p>Parses STEP (ISO-10303-21) CAD files, builds the assembly tree, computes a
 * bounding box per part, and exposes the result over REST.
 */
@SpringBootApplication
public class StepBboxParserApplication {

    public static void main(String[] args) {
        SpringApplication.run(StepBboxParserApplication.class, args);
    }
}
