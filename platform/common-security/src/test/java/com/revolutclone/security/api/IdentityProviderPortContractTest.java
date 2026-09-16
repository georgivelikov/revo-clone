package com.revolutclone.security.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revolutclone.security.keycloak.KeycloakIdentityAdapter;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One suite, run against both {@link KeycloakIdentityAdapter} and {@link InMemoryIdentityAdapter}:
 * create, find, assign role, delete, and duplicate-email behaviour must be identical in both.
 * This is what keeps the in-memory adapter honest - an in-memory double that quietly diverges
 * from the real thing is worse than no double at all.
 *
 * <p>The Keycloak adapter here manages the container's default {@code master} realm directly
 * via the admin CLI's password grant, rather than depending on the {@code revolut-backend}
 * confidential client from the realm export (phase 00 task 11, not yet built) - the port's
 * contract does not depend on which realm or which grant type authenticated the admin client.
 */
@Testcontainers
class IdentityProviderPortContractTest {

    private static final String TEST_ROLE = "contract-test-role";

    @Container
    static KeycloakContainer keycloakContainer = new KeycloakContainer("quay.io/keycloak/keycloak:26.7");

    private static IdentityProviderPort keycloakAdapter;
    private static IdentityProviderPort inMemoryAdapter;

    @BeforeAll
    static void setUpAdapters() {
        Keycloak adminClient = keycloakContainer.getKeycloakAdminClient();

        RoleRepresentation role = new RoleRepresentation();
        role.setName(TEST_ROLE);
        adminClient.realm("master").roles().create(role);

        keycloakAdapter = new KeycloakIdentityAdapter(adminClient, "master");
        inMemoryAdapter = new InMemoryIdentityAdapter();
    }

    private static List<IdentityProviderPort> adapters() {
        return List.of(inMemoryAdapter, keycloakAdapter);
    }

    private static NewUser freshUser() {
        String unique = UUID.randomUUID().toString();
        return new NewUser(unique + "@contract-test.revolutclone.com", "Ada", "Lovelace");
    }

    @Test
    void createReturnsAProviderAssignedIdAndTheGivenFields() {
        for (IdentityProviderPort port : adapters()) {
            NewUser newUser = freshUser();

            ProviderUser created = port.create(newUser);

            assertThat(created.providerUserId()).isNotBlank();
            assertThat(created.email()).isEqualTo(newUser.email());
            assertThat(created.firstName()).isEqualTo(newUser.firstName());
            assertThat(created.lastName()).isEqualTo(newUser.lastName());
        }
    }

    @Test
    void findByEmailReturnsTheCreatedUserAndEmptyForAnUnknownOne() {
        for (IdentityProviderPort port : adapters()) {
            NewUser newUser = freshUser();
            ProviderUser created = port.create(newUser);

            assertThat(port.findByEmail(newUser.email()))
                    .contains(created);
            assertThat(port.findByEmail("no-such-user-" + UUID.randomUUID() + "@revolutclone.com"))
                    .isEmpty();
        }
    }

    @Test
    void assignRoleSucceedsForAnExistingUserAndFailsForAnUnknownOne() {
        for (IdentityProviderPort port : adapters()) {
            ProviderUser created = port.create(freshUser());

            port.assignRole(created.providerUserId(), TEST_ROLE);

            assertThatThrownBy(() -> port.assignRole("no-such-provider-id-" + UUID.randomUUID(), TEST_ROLE))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    void setPasswordSucceedsForAnExistingUser() {
        for (IdentityProviderPort port : adapters()) {
            ProviderUser created = port.create(freshUser());

            port.setPassword(created.providerUserId(), "a-strong-password-123".toCharArray());
        }
    }

    @Test
    void deleteRemovesTheUserSoFindByEmailNoLongerSeesIt() {
        for (IdentityProviderPort port : adapters()) {
            NewUser newUser = freshUser();
            ProviderUser created = port.create(newUser);

            port.delete(created.providerUserId());

            assertThat(port.findByEmail(newUser.email())).isEmpty();
        }
    }

    @Test
    void duplicateEmailBehaviourIsIdenticalInBoth() {
        for (IdentityProviderPort port : adapters()) {
            NewUser newUser = freshUser();
            port.create(newUser);

            assertThatThrownBy(() -> port.create(newUser))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void listAllIncludesACreatedUser() {
        for (IdentityProviderPort port : adapters()) {
            NewUser newUser = freshUser();
            ProviderUser created = port.create(newUser);

            List<ProviderUser> all = port.listAll(0, 500);

            assertThat(all).extracting(ProviderUser::providerUserId).contains(created.providerUserId());
        }
    }
}
