package io.github.burakboduroglu.ratekit.billing.config;

import io.github.burakboduroglu.ratekit.common.ApiScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * A configured caller: a name for logs, the SHA-256 of its key (the key itself is not kept) and what
 * it may do (ADR 0017).
 */
public record ApiCaller(String name, byte[] keyHash, Set<ApiScope> scopes) {

    /** @throws IllegalArgumentException for a blank name or key, no scopes, or a scope that does not exist */
    public static ApiCaller of(String name, String key, Collection<String> scopeNames) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("an API key needs a name");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("the key of caller '" + name + "' must not be blank");
        }
        if (scopeNames == null || scopeNames.isEmpty()) {
            throw new IllegalArgumentException("caller '" + name + "' needs at least one scope");
        }
        Set<ApiScope> scopes = EnumSet.noneOf(ApiScope.class);
        scopeNames.forEach(scope -> scopes.add(ApiScope.parse(scope.trim())));
        return new ApiCaller(name, hash(key), Set.copyOf(scopes));
    }

    /** Compares hashes in constant time, so the answer says nothing about how much of a guess was right. */
    public boolean matches(byte[] givenHash) {
        return MessageDigest.isEqual(keyHash, givenHash);
    }

    public boolean allows(ApiScope scope) {
        return scopes.contains(scope);
    }

    /** SHA-256, so keys of any length compare as equal-length values. */
    public static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
    }
}
