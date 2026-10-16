package com.paybridge.probe.client;

import com.apollographql.apollo.api.ApolloResponse;
import com.apollographql.apollo.api.Operation;
import com.apollographql.apollo.runtime.java.ApolloCall;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Same shape the production ApolloPaymentGateway will use: one stage per call, failures kept as data. */
public final class CompletionStageAdapter {
    private CompletionStageAdapter() {
    }

    public static <D extends Operation.Data> CompletionStage<ApolloResponse<D>> execute(ApolloCall<D> call) {
        CompletableFuture<ApolloResponse<D>> future = new CompletableFuture<>();
        call.enqueue(future::complete);
        return future;
    }
}
