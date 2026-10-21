package com.paybridge.core.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GraphQlLimitsTest {
    private static final String SDL = "type Query { n: Node, items(limit: Int): [Node!]!, one: Node } "
            + "type Node { n: Node, v: Int }";

    private static GraphQL engine() {
        var registry = new SchemaParser().parse(SDL);
        var schema = new SchemaGenerator().makeExecutableSchema(registry, RuntimeWiring.newRuntimeWiring().build());
        return GraphQL.newGraphQL(schema)
                .instrumentation(new ChainedInstrumentation(List.of(GraphQlLimits.depth(), GraphQlLimits.complexity())))
                .build();
    }

    /** Root field is level 1; the leaf v is the last level. */
    private static String nested(int depth) {
        return "{ " + "n { ".repeat(depth - 1) + "v" + " }".repeat(depth - 1) + " }";
    }

    private static String aliases(int count, String fieldText) {
        return "{ " + IntStream.range(0, count).mapToObj(i -> "a" + i + ": " + fieldText + " ")
                .collect(Collectors.joining()) + "}";
    }

    private static boolean rejected(String query, String expectedMessagePart) {
        ExecutionResult result = engine().execute(query);
        return result.getErrors().stream().anyMatch(e -> e.getMessage().contains(expectedMessagePart));
    }

    @Test
    void theLimitsAreTheDocumentedOnes() {
        assertThat(GraphQlLimits.MAX_DEPTH).isEqualTo(8);
        assertThat(GraphQlLimits.MAX_COMPLEXITY).isEqualTo(300);
        assertThat(GraphQlLimits.MAX_DOCUMENT_BYTES).isEqualTo(8 * 1024);
    }

    @Test
    void depthEightIsAllowedAndDepthNineIsRejected() {
        assertThat(rejected(nested(8), "depth")).isFalse();
        assertThat(rejected(nested(9), "depth")).isTrue();
    }

    @Test
    void aListFieldCostsFivePerPageOfTwentyFiveAndEveryFieldCostsOne() {
        // items(limit:100){v} = 1 (items) + 5*ceil(100/25)=20 + 1 (v) = 22. Thirteen = 286 passes, fourteen = 308 fails.
        assertThat(rejected(aliases(13, "items(limit: 100) { v }"), "complexity")).isFalse();
        assertThat(rejected(aliases(14, "items(limit: 100) { v }"), "complexity")).isTrue();
    }

    @Test
    void aMissingLimitCountsAsOnePage() {
        // items{v} = 1 + 5 + 1 = 7; forty-three of them = 301 > 300, forty-two = 294
        assertThat(rejected(aliases(42, "items { v }"), "complexity")).isFalse();
        assertThat(rejected(aliases(43, "items { v }"), "complexity")).isTrue();
    }
}
