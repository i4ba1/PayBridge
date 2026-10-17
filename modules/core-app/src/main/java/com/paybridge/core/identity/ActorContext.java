package com.paybridge.core.identity;

/** Who is acting, and in which merchant. merchantId is null for user level and global operations. */
public record ActorContext(String userId, String merchantId, String correlationId) {}
