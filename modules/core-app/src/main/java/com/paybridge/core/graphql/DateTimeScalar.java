package com.paybridge.core.graphql;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** ISO-8601 UTC with milliseconds, for example 2026-10-16T02:00:00.120Z (API contract section 5.2). */
@DgsScalar(name = "DateTime")
public class DateTimeScalar implements Coercing<Instant, String> {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(java.time.ZoneOffset.UTC);

    @Override
    public String serialize(Object value, GraphQLContext context, Locale locale) throws CoercingSerializeException {
        if (value instanceof Instant instant) {
            return FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS));
        }
        throw new CoercingSerializeException("DateTime must be serialized from an Instant");
    }

    @Override
    public Instant parseValue(Object input, GraphQLContext context, Locale locale) throws CoercingParseValueException {
        if (input instanceof String s) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e) {
                throw new CoercingParseValueException("Invalid DateTime");
            }
        }
        throw new CoercingParseValueException("DateTime must be a string");
    }

    @Override
    public Instant parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale)
            throws CoercingParseLiteralException {
        if (input instanceof StringValue s) {
            try {
                return Instant.parse(s.getValue());
            } catch (DateTimeParseException e) {
                throw new CoercingParseLiteralException("Invalid DateTime");
            }
        }
        throw new CoercingParseLiteralException("DateTime must be a string");
    }
}
