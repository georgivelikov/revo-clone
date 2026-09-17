package com.revolutclone.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.security.api.IdentityProviderPort;
import com.revolutclone.security.api.NewUser;
import com.revolutclone.security.api.ProviderUser;
import com.revolutclone.security.keycloak.KeycloakIdentityAdapter;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 01.3 found, live against {@code docker-compose.infra.yml}, that {@link
 * RegisterUserIntegrationTest} passing proved nothing about whether the real system works: that
 * test authenticates its admin client as Keycloak's bootstrap superadmin against the {@code
 * master} realm, which can do anything. Production - and this service, everywhere outside that
 * one test - authenticates as a confidential client via {@code client_credentials}, holding
 * only whatever {@code realm-management} client roles it was actually granted. Those two
 * permission sets are not the same, and the gap between them is exactly where a real bug lived:
 * {@code manage-users} lets a service account create users and modify their role mappings, but
 * {@link KeycloakIdentityAdapter#assignRole} first reads the realm's role catalogue ({@code GET
 * /roles/{name}}) to resolve the role's id, and that read needs {@code view-realm} - a
 * permission {@code manage-users} does not include. Registration failed with a 403 the moment
 * it tried to assign the {@code customer} role, live, with the exact grants
 * {@code infra/keycloak/revolut-realm.json} shipped before this task added {@code view-realm}
 * to that file.
 *
 * <p>This test closes that gap by building a realm, a confidential client and a role
 * programmatically with EXACTLY {@code infra/keycloak/revolut-realm.json}'s
 * {@code manage-users} + {@code view-realm} grant (nothing more), then driving {@link
 * IdentityProviderPort} through that client's service account exactly as the running service
 * does - never through the superadmin. (A realm-import-file approach was tried first and
 * dropped: {@code dasniko:testcontainers-keycloak:4.3.1}'s {@code withRealmImportFile} did not
 * reliably import against this image/version combination in this environment - the imported
 * realm was silently absent at container-ready time - so this test does not depend on that
 * mechanism working, and building the fixture in code also means a future edit to the real
 * realm file's grant list has to be mirrored here to keep passing, which is the point.)
 */
@Testcontainers
class RealRealmPermissionsTest {

    private static final String REALM = "revolut-permissions-check";
    private static final String BACKEND_CLIENT_ID = "revolut-backend";
    private static final String BACKEND_CLIENT_SECRET = "revolut-backend-test-secret";
    private static final String CUSTOMER_ROLE = "customer";

    @Container
    static KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.7");

    @BeforeAll
    static void createRealmWithExactlyTheProductionGrants() {
        Keycloak superadmin = keycloak.getKeycloakAdminClient();

        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(REALM);
        realm.setEnabled(true);
        superadmin.realms().create(realm);

        RoleRepresentation customerRole = new RoleRepresentation();
        customerRole.setName(CUSTOMER_ROLE);
        superadmin.realm(REALM).roles().create(customerRole);

        ClientRepresentation backend = new ClientRepresentation();
        backend.setClientId(BACKEND_CLIENT_ID);
        backend.setSecret(BACKEND_CLIENT_SECRET);
        backend.setEnabled(true);
        backend.setPublicClient(false);
        backend.setServiceAccountsEnabled(true);
        backend.setStandardFlowEnabled(false);
        backend.setDirectAccessGrantsEnabled(false);
        try (var response = superadmin.realm(REALM).clients().create(backend)) {
            // Created; nothing further needed from the response body.
        }

        String backendInternalId = superadmin.realm(REALM).clients()
                .findByClientId(BACKEND_CLIENT_ID).get(0).getId();
        String serviceAccountUserId = superadmin.realm(REALM).clients()
                .get(backendInternalId).getServiceAccountUser().getId();
        String realmManagementClientId = superadmin.realm(REALM).clients()
                .findByClientId("realm-management").get(0).getId();

        // Exactly infra/keycloak/revolut-realm.json's grant for revolut-backend's service
        // account: manage-users plus the view-realm this task added to fix the live 403.
        List<RoleRepresentation> grants = List.of("manage-users", "view-realm").stream()
                .map(name -> superadmin.realm(REALM).clients().get(realmManagementClientId)
                        .roles().get(name).toRepresentation())
                .toList();
        superadmin.realm(REALM).users().get(serviceAccountUserId)
                .roles().clientLevel(realmManagementClientId).add(grants);
    }

    @Test
    void serviceAccountGrantsAreEnoughToRegisterAndAssignTheCustomerRole() {
        Keycloak backendClient = KeycloakBuilder.builder()
                .serverUrl(keycloak.getAuthServerUrl())
                .realm(REALM)
                .clientId(BACKEND_CLIENT_ID)
                .clientSecret(BACKEND_CLIENT_SECRET)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
        IdentityProviderPort port = new KeycloakIdentityAdapter(backendClient, REALM);

        String email = "real-realm-check+" + System.nanoTime() + "@demo.io";
        ProviderUser created = port.create(new NewUser(email, "Real", "Realm"));
        port.setPassword(created.providerUserId(), "a-strong-password".toCharArray());

        // This is the call that returned 403 live: manage-users alone cannot read the realm's
        // role catalogue that assignRole needs to resolve "customer" to an id.
        port.assignRole(created.providerUserId(), CUSTOMER_ROLE);

        assertThat(port.findByEmail(email)).isPresent();
    }
}
