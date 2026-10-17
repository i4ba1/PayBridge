package com.paybridge.simulator.service;

import java.time.Instant;

/** The charge as the provider contract shows it (API contract 8.2). amountMinor is a string, revision a number. */
public record ChargeView(String reference, String providerKey, String status, long revision, String amountMinor,
                         String currency, String declineCode, Instant createdAt, Instant updatedAt) {}
