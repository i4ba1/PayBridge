package com.paybridge.probe.graphql;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.paybridge.probe.domain.EchoResult;
import graphql.GraphQLException;

@DgsComponent
public class ProbeDataFetchers {

    @DgsQuery
    public String ping() {
        return "pong";
    }

    @DgsMutation
    public EchoResult echo(@InputArgument String message) {
        if ("boom".equals(message)) {
            throw new GraphQLException("probe-requested failure");
        }
        return new EchoResult(message, message.length());
    }
}
