package com.paybridge.probe.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paybridge.probe.ProbeApplication;
import com.paybridge.probe.domain.EchoResult;
import com.paybridge.probe.graphql.ProbeDataFetchers;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.io.DataInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Fast probes that need no containers: Java 25 class files, mocking, coverage target classes. */
class ToolchainProbeTest {

    /** Final class to mock with the inline mock maker. */
    static final class FinalCollaborator {
        EchoResult make(String s) {
            return new EchoResult(s, s.length());
        }
    }

    /** Service that consumes a record. */
    record Greeting(String text) {
    }

    static class GreetingService {
        private final FinalCollaborator collaborator;

        GreetingService(FinalCollaborator collaborator) {
            this.collaborator = collaborator;
        }

        Greeting greet(String name) {
            return new Greeting(collaborator.make(name).message());
        }
    }

    @Test
    void probeClassesAreJava25ClassFiles() throws Exception {
        try (InputStream in = ProbeApplication.class.getResourceAsStream("ProbeApplication.class");
             DataInputStream data = new DataInputStream(in)) {
            assertThat(data.readInt()).isEqualTo(0xCAFEBABE);
            data.readUnsignedShort();
            assertThat(data.readUnsignedShort()).isEqualTo(69);
        }
    }

    @Test
    void mockitoMocksFinalClassAndRecordUsingService() {
        FinalCollaborator collaborator = mock(FinalCollaborator.class);
        when(collaborator.make("a")).thenReturn(new EchoResult("mocked", 6));
        assertThat(new GreetingService(collaborator).greet("a")).isEqualTo(new Greeting("mocked"));
    }

    @Test
    void dataFetchersRunAndCountForCoverage() {
        ProbeDataFetchers fetchers = new ProbeDataFetchers();
        assertThat(fetchers.ping()).isEqualTo("pong");
        assertThat(fetchers.echo("abc")).isEqualTo(new EchoResult("abc", 3));
    }

    @Test
    void probeCoreIsFreeOfCycles() {
        var classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.paybridge.probe");
        SlicesRuleDefinition.slices().matching("com.paybridge.probe.(*)..").should().beFreeOfCycles().check(classes);
    }
}
