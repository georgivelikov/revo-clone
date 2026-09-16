package com.revolutclone.security.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A broker-free {@link IdentityProviderPort}. Exists to repay the interface: a Keycloak
 * Testcontainer costs real seconds per test class, and without a port the only alternative
 * would be mocking the admin client. {@link IdentityProviderPortContractTest} runs the same
 * assertions against this and {@link com.revolutclone.security.keycloak.KeycloakIdentityAdapter}
 * so the two can never silently diverge.
 */
public class InMemoryIdentityAdapter implements IdentityProviderPort {

    private final Map<String, ProviderUser> usersById = new ConcurrentHashMap<>();
    private final Map<String, char[]> passwordsById = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> rolesById = new ConcurrentHashMap<>();

    @Override
    public synchronized ProviderUser create(NewUser user) {
        if (findByEmail(user.email()).isPresent()) {
            throw new IllegalStateException("a user with email " + user.email() + " already exists");
        }
        ProviderUser created = new ProviderUser(
                UUID.randomUUID().toString(), user.email(), user.firstName(), user.lastName());
        usersById.put(created.providerUserId(), created);
        return created;
    }

    @Override
    public void setPassword(String providerUserId, char[] password) {
        requireExists(providerUserId);
        passwordsById.put(providerUserId, password.clone());
    }

    @Override
    public void assignRole(String providerUserId, String role) {
        requireExists(providerUserId);
        rolesById.computeIfAbsent(providerUserId, id -> ConcurrentHashMap.newKeySet()).add(role);
    }

    @Override
    public Optional<ProviderUser> findByEmail(String email) {
        return usersById.values().stream()
                .filter(u -> u.email().equalsIgnoreCase(email))
                .findFirst();
    }

    @Override
    public void delete(String providerUserId) {
        usersById.remove(providerUserId);
        passwordsById.remove(providerUserId);
        rolesById.remove(providerUserId);
    }

    @Override
    public List<ProviderUser> listAll(int page, int size) {
        List<ProviderUser> all = new ArrayList<>(new LinkedHashMap<>(usersById).values());
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }

    private void requireExists(String providerUserId) {
        if (!usersById.containsKey(providerUserId)) {
            throw new NoSuchElementException("no such provider user: " + providerUserId);
        }
    }
}
