package com.revolutclone.security.keycloak;

import com.revolutclone.security.api.IdentityProviderPort;
import com.revolutclone.security.api.NewUser;
import com.revolutclone.security.api.ProviderUser;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * The one adapter that talks to Keycloak's Admin REST API, selected by
 * {@code revolut.identity.provider=keycloak}. Everything here is Keycloak-specific by
 * design - {@link IdentityProviderPort} is what the rest of the project sees.
 */
public class KeycloakIdentityAdapter implements IdentityProviderPort {

    private final Keycloak admin;
    private final String realm;

    public KeycloakIdentityAdapter(Keycloak admin, String realm) {
        this.admin = admin;
        this.realm = realm;
    }

    private RealmResource realm() {
        return admin.realm(realm);
    }

    private UsersResource users() {
        return realm().users();
    }

    @Override
    public ProviderUser create(NewUser user) {
        UserRepresentation representation = new UserRepresentation();
        representation.setUsername(user.email());
        representation.setEmail(user.email());
        representation.setFirstName(user.firstName());
        representation.setLastName(user.lastName());
        representation.setEnabled(true);
        representation.setEmailVerified(true);

        try (Response response = users().create(representation)) {
            if (response.getStatus() == 409) {
                throw new IllegalStateException("a user with email " + user.email() + " already exists");
            }
            if (response.getStatus() >= 300) {
                throw new IllegalStateException("Keycloak user creation failed with status " + response.getStatus());
            }
            String providerUserId = extractCreatedId(response);
            return toProviderUser(providerUserId, representation);
        }
    }

    private static String extractCreatedId(Response response) {
        String location = response.getHeaderString("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    @Override
    public void setPassword(String providerUserId, char[] password) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(new String(password));
        credential.setTemporary(false);
        userResource(providerUserId).resetPassword(credential);
    }

    @Override
    public void assignRole(String providerUserId, String role) {
        // Validate the user first so a nonexistent user fails the same way (NoSuchElementException)
        // regardless of whether the role name itself is also invalid - keeps behaviour aligned
        // with InMemoryIdentityAdapter, which has no separate concept of a "role catalogue".
        UserResource user = userResource(providerUserId);
        RoleRepresentation roleRepresentation = realm().roles().get(role).toRepresentation();
        user.roles().realmLevel().add(List.of(roleRepresentation));
    }

    @Override
    public Optional<ProviderUser> findByEmail(String email) {
        return users().searchByEmail(email, true).stream()
                .findFirst()
                .map(u -> new ProviderUser(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName()));
    }

    @Override
    public void delete(String providerUserId) {
        userResource(providerUserId).remove();
    }

    @Override
    public List<ProviderUser> listAll(int page, int size) {
        return users().list(page * size, size).stream()
                .map(u -> new ProviderUser(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName()))
                .toList();
    }

    private UserResource userResource(String providerUserId) {
        UserResource resource = users().get(providerUserId);
        try {
            resource.toRepresentation();
        } catch (jakarta.ws.rs.NotFoundException e) {
            throw new NoSuchElementException("no such provider user: " + providerUserId);
        }
        return resource;
    }

    private static ProviderUser toProviderUser(String providerUserId, UserRepresentation representation) {
        return new ProviderUser(providerUserId, representation.getEmail(),
                representation.getFirstName(), representation.getLastName());
    }
}
