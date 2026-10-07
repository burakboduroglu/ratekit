package io.github.burakboduroglu.ratekit.rating.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.burakboduroglu.ratekit.rating.dto.TariffRequest;
import io.github.burakboduroglu.ratekit.rating.dto.TariffResponse;
import io.github.burakboduroglu.ratekit.rating.repository.TariffRow;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Component;

/** Converts between the tariff API shapes and stored rows; {@link TariffMapper} turns rows into price models. */
@Component
public class TariffApiMapper {

    private final ObjectMapper json;

    public TariffApiMapper(ObjectMapper json) {
        this.json = json;
    }

    public String paramsJson(TariffRequest request) {
        return request.params().toString();
    }

    public TariffResponse toResponse(TariffRow row) {
        try {
            return new TariffResponse(row.id(), row.meter(), row.model(), row.effectiveFrom(), json.readTree(row.paramsJson()));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
