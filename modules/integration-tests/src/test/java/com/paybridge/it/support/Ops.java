package com.paybridge.it.support;

/** The console's fixed operation documents for the identity part of the schema. */
public final class Ops {
    private Ops() {}

    public static final String USER_FIELDS = "id username displayName admin enabled passwordChangeRequired "
            + "memberships { merchantId merchantName role }";
    public static final String PROBLEM = "... on OperationProblem { code message retryAfterSeconds "
            + "fieldProblems { field code message } }";

    public static final String ME = "query Me { me { " + USER_FIELDS + " } }";
    public static final String MY_MERCHANTS = "query MyMerchants { myMerchants { id name currency role } }";
    public static final String USERS = "query Users($enabled: Boolean, $limit: Int, $cursor: String) { users(enabled: $enabled, "
            + "limit: $limit, cursor: $cursor) { __typename ... on UserList { items { id username enabled } page { nextCursor limit } } "
            + PROBLEM + " } }";
    public static final String USER = "query User($id: ID!) { user(id: $id) { " + USER_FIELDS + " } }";
    public static final String CREATE_USER = "mutation CreateUser($input: CreateUserInput!) { createUser(input: $input) "
            + "{ __typename ... on UserCreated { temporaryCredential user { " + USER_FIELDS + " } } " + PROBLEM + " } }";
    public static final String UPDATE_USER = "mutation UpdateUser($id: ID!, $input: UpdateUserInput!) { updateUser(id: $id, input: $input) "
            + "{ __typename ... on UserUpdated { user { " + USER_FIELDS + " } } " + PROBLEM + " } }";
    public static final String RESET_PASSWORD = "mutation ResetUserPassword($id: ID!) { resetUserPassword(id: $id) "
            + "{ __typename ... on PasswordReset { userId temporaryCredential passwordChangeRequired } " + PROBLEM + " } }";
    public static final String SET_MEMBERSHIPS = "mutation SetUserMemberships($id: ID!, $memberships: [MembershipInput!]!) "
            + "{ setUserMemberships(id: $id, memberships: $memberships) { __typename ... on MembershipsSet { userId "
            + "memberships { merchantId role } } " + PROBLEM + " } }";
    public static final String CHANGE_PASSWORD = "mutation ChangePassword($input: ChangePasswordInput!) { changePassword(input: $input) "
            + "{ __typename ... on PasswordChanged { changed } " + PROBLEM + " } }";
    public static final String INVOICES = "query Invoices { invoices { __typename " + PROBLEM + " } }";
}
