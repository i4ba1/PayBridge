package com.paybridge.application.payment;

/** Who is asking and in which merchant. Plain values: the web layer's own actor type stays in core-app. */
public record AdmissionActor(String merchantId, String userId, String correlationId) {}
