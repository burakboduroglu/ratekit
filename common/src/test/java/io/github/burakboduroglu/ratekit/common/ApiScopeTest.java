package io.github.burakboduroglu.ratekit.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ApiScopeTest {

    @Test
    void everyScopeIsFoundByItsConfiguredName() {
        for (ApiScope scope : ApiScope.values()) {
            assertEquals(scope, ApiScope.parse(scope.value()));
        }
    }

    @Test
    void namesAreResourceColonAction() {
        assertEquals("events:write", ApiScope.EVENTS_WRITE.value());
        assertEquals("billing:read", ApiScope.BILLING_READ.value());
    }

    @Test
    void anUnknownNameIsRefusedAndTheKnownOnesAreListed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ApiScope.parse("events:read"));

        assertEquals("unknown API scope 'events:read'; known: [events:write, accounts:read, accounts:write, "
                + "tariffs:read, tariffs:write, billing:read, billing:write]", e.getMessage());
    }
}
