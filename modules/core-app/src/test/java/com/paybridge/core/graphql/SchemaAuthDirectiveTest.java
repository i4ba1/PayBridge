package com.paybridge.core.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.language.Directive;
import graphql.language.EnumValue;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** API contract 5.1: a schema test fails the build when any Query or Mutation field has no valid @auth. */
class SchemaAuthDirectiveTest {
    private static final Set<String> REQUIRES = Set.of("SERVICE_ANONYMOUS", "USER", "VIEWER", "OPERATOR", "ADMIN");
    private static final Set<String> SCOPES = Set.of("NONE", "MERCHANT", "GLOBAL");

    private static TypeDefinitionRegistry schema() throws Exception {
        try (var in = SchemaAuthDirectiveTest.class.getResourceAsStream("/graphql/paybridge.graphqls")) {
            return new SchemaParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static List<FieldDefinition> rootFields(TypeDefinitionRegistry registry) {
        var fields = new ArrayList<FieldDefinition>();
        for (String name : List.of("Query", "Mutation")) {
            fields.addAll(registry.getType(name, ObjectTypeDefinition.class).orElseThrow().getFieldDefinitions());
        }
        return fields;
    }

    private static String arg(Directive d, String name) {
        var a = d.getArgument(name);
        return a == null ? null : ((EnumValue) a.getValue()).getName();
    }

    @Test
    void everyRootFieldDeclaresAValidAuthDirective() throws Exception {
        var fields = rootFields(schema());
        assertThat(fields.size()).as("the schema defines the whole API").isGreaterThan(30);
        for (FieldDefinition f : fields) {
            var directives = f.getDirectives("auth");
            assertThat(directives).as("@auth on %s", f.getName()).hasSize(1);
            Directive d = directives.get(0);
            assertThat(REQUIRES).as("requires of %s", f.getName()).contains(arg(d, "requires"));
            if (arg(d, "scope") != null) {
                assertThat(SCOPES).as("scope of %s", f.getName()).contains(arg(d, "scope"));
            }
        }
    }

    @Test
    void onlyTheDocumentedOperationsAreAnonymous() throws Exception {
        var anonymous = rootFields(schema()).stream()
                .filter(f -> "SERVICE_ANONYMOUS".equals(arg(f.getDirectives("auth").get(0), "requires")))
                .map(FieldDefinition::getName).toList();
        // API contract 7.4: no other operation may be added to this list without an FRD amendment.
        assertThat(anonymous).containsExactlyInAnyOrder("setupStatus", "bootstrapAdministrator", "authenticate");
    }

    @Test
    void administratorOperationsAreGlobalExceptTheMerchantScopedDisposition() throws Exception {
        for (FieldDefinition f : rootFields(schema())) {
            Directive d = f.getDirectives("auth").get(0);
            if ("ADMIN".equals(arg(d, "requires")) && !f.getName().equals("recordCaseDisposition")) {
                assertThat(arg(d, "scope")).as("scope of %s", f.getName()).isEqualTo("GLOBAL");
            }
        }
    }
}
