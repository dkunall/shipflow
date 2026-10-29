package com.shipflow.arch;

import com.shipflow.carrier.port.BookingRequest;
import com.shipflow.carrier.port.CarrierPort;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/** Layering rules the code already follows; they fail the build if someone breaks them. */
class ArchitectureTest {
    private static JavaClasses classes;

    @BeforeAll
    static void load() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.shipflow");
    }

    @Test
    void domainPackagesStayFreeOfWebJdbcAndApplicationCode() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.shipflow..api..", "com.shipflow..application..", "com.shipflow..persistence..",
                        "org.springframework.web..", "org.springframework.jdbc..",
                        "org.springframework.transaction..", "org.springframework.stereotype..")
                .check(classes);
    }

    @Test
    void controllersDoNotTalkToTheDatabaseDirectly() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAssignableTo(JdbcTemplate.class)
                .orShould().dependOnClassesThat().haveFullyQualifiedName("jakarta.persistence.EntityManager")
                .check(classes);
    }

    @Test
    void onlyTheCarrierModuleCallsCarrierApis() {
        // everything else goes through CarrierGateway (breaker, retry, timeout, no-transaction guard)
        noClasses().that().resideOutsideOfPackage("com.shipflow.carrier..")
                .should().callMethod(CarrierPort.class, "book", BookingRequest.class)
                .check(classes);
    }

    @Test
    void constructorInjectionOnly() {
        noFields().should().beAnnotatedWith(Autowired.class).check(classes);
    }
}
