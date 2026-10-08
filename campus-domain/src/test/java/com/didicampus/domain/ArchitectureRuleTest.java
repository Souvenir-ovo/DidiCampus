package com.didicampus.domain;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.didicampus.domain",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureRuleTest {

    @ArchTest
    static final ArchRule domain_does_not_depend_on_frameworks = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "com.baomidou..",
                    "org.redisson..",
                    "org.apache.rocketmq..",
                    "org.mybatis..",
                    "javax.sql..",
                    "java.sql.."
            );

    @ArchTest
    static final ArchRule ports_are_declared_in_ports_packages = classes()
            .that().areInterfaces()
            .and().haveSimpleNameEndingWith("Port")
            .or().areInterfaces()
            .and().haveSimpleNameEndingWith("Repository")
            .should().resideInAPackage("..domain..ports..");

    @ArchTest
    static final ArchRule models_do_not_know_ports = noClasses()
            .that().resideInAPackage("..domain..model..")
            .should().dependOnClassesThat().resideInAPackage("..domain..ports..");
}
