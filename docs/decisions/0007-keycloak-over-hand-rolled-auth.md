# 0007. Keycloak over hand-rolled auth

Date: 2026-10-03

## Context

Every service needs to authenticate requests, and the project wanted a real OIDC/JWT boundary to
build against — not a toy that simulates one — without spending the project's limited time budget
writing and securing a password/token/session system from scratch, which is itself a deep,
security-sensitive domain unrelated to the project's actual subject (ledgers, sagas, eventual
consistency).

## Decision

Keycloak as the OIDC identity provider, pre-provisioned with a `revolut` realm. Every business
service is a Spring Security OAuth2 resource server validating JWTs, never issuing or storing
credentials itself. `identity-service` talks to Keycloak's Admin API for user provisioning,
behind `IdentityProviderPort` — an interface with two implementations, `KeycloakIdentityAdapter`
and a broker-free `InMemoryIdentityAdapter`, proven behaviorally identical by one shared contract
test, so that the "identity is portable" claim is a verified fact about the test suite rather
than an assumption about the interface shape. A `ConfigurableRolesClaimConverter` reads the
roles-claim path from configuration rather than hardcoding Keycloak's own `realm_access.roles`
shape, so the resource-server side of the boundary isn't quietly Keycloak-specific either.

## Consequences

**Good.** Resource-server code exercises the same validation path, the same token shapes, and
the same failure modes a production OIDC integration would — there is no "fake" auth layer to
discard later. The two-adapter-plus-contract-test structure means the identity portability
boundary is something this project can actually demonstrate working, not just diagram.

**Bad, and the project's own sharpest lesson in how an infrastructure default can hide a real
bug.** Keycloak's `KC_HOSTNAME` setting fixes the `iss` claim and every URL in its OIDC discovery
document, regardless of which address actually served the request — and for most of this
project's history that was set to a Docker-internal hostname, reachable from every backend
service but from no real browser. Because the only things that ever exercised login were direct
password-grant calls to one known-working URL (the seed and demo scripts), this silently blocked
every *browser-based* login the project could have had, for the project's entire history, without
a single test ever failing. It was only found in task 09.6, live, in a real browser. Keycloak
itself is also a heavyweight local dependency — its own container, its own startup time, and a
realm-import quirk where `--import-realm` only fires against an empty database, so an edited
realm file needs a full volume reset to actually take effect, not a restart.
