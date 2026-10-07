package io.github.burakboduroglu.ratekit.rating.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record TariffResponse(long id, String meter, String model, Instant effectiveFrom, JsonNode params) {
}
