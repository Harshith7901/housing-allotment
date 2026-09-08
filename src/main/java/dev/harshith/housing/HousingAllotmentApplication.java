package dev.harshith.housing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Backend for a public housing allotment scheme.
 *
 * <p>Architecture in one paragraph: everything that decides anything lives in
 * {@code dev.harshith.housing.core} as plain Java with no Spring, no JPA and no clock —
 * pure functions from published inputs to published outputs. This module is the shell
 * around it: HTTP, persistence, authorisation, phase enforcement and the audit chain. The
 * split is not tidiness for its own sake. The core is the part that has to be
 * reproducible by a third party in another language a year from now, and it can only be
 * reproducible if it does not depend on a framework, a database or the time of day.
 */
@SpringBootApplication
public class HousingAllotmentApplication {

    public static void main(String[] args) {
        SpringApplication.run(HousingAllotmentApplication.class, args);
    }
}
