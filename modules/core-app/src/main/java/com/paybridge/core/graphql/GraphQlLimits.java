package com.paybridge.core.graphql;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.schema.GraphQLTypeUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API contract 5.4: depth 8, complexity 300 (1 per field, plus 5 per page of 25 for list fields), document 8 KiB. */
public final class GraphQlLimits {
    public static final int MAX_DEPTH = 8;
    public static final int MAX_COMPLEXITY = 300;
    public static final int MAX_DOCUMENT_BYTES = 8 * 1024;
    private static final int DEFAULT_PAGE = 25;
    private static final int LIST_COST = 5;

    private GraphQlLimits() {}

    public static MaxQueryDepthInstrumentation depth() {
        return new MaxQueryDepthInstrumentation(MAX_DEPTH);
    }

    public static MaxQueryComplexityInstrumentation complexity() {
        return new MaxQueryComplexityInstrumentation(MAX_COMPLEXITY, (env, childComplexity) -> {
            int cost = 1;
            var type = GraphQLTypeUtil.unwrapNonNull(env.getFieldDefinition().getType());
            if (GraphQLTypeUtil.isList(type)) {
                Object limit = env.getArguments().get("limit");
                int size = limit instanceof Number n && n.intValue() > 0 ? n.intValue() : DEFAULT_PAGE;
                cost += LIST_COST * ((size + DEFAULT_PAGE - 1) / DEFAULT_PAGE);
            }
            return cost + childComplexity;
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {
        @Bean
        MaxQueryDepthInstrumentation maxQueryDepth() {
            return depth();
        }

        @Bean
        MaxQueryComplexityInstrumentation maxQueryComplexity() {
            return complexity();
        }
    }
}
