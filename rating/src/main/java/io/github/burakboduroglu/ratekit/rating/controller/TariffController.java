package io.github.burakboduroglu.ratekit.rating.controller;

import io.github.burakboduroglu.ratekit.rating.dto.TariffRequest;
import io.github.burakboduroglu.ratekit.rating.dto.TariffResponse;
import io.github.burakboduroglu.ratekit.rating.mapper.TariffApiMapper;
import io.github.burakboduroglu.ratekit.rating.service.TariffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/tariffs")
public class TariffController {

    private final TariffService tariffs;
    private final TariffApiMapper mapper;

    public TariffController(TariffService tariffs, TariffApiMapper mapper) {
        this.tariffs = tariffs;
        this.mapper = mapper;
    }

    @Operation(summary = "Add a tariff version for a meter",
            description = "Versions are never edited: a price change is a new version starting at least one cache TTL (30 s by default) from now.")
    @ApiResponse(responseCode = "201", description = "Version stored")
    @ApiResponse(responseCode = "400", description = "Unknown model or parameters rating could not price with")
    @ApiResponse(responseCode = "409", description = "The meter already has a version starting at that instant")
    @ApiResponse(responseCode = "422", description = "effectiveFrom is earlier than now plus the cache TTL")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TariffResponse add(@Valid @RequestBody TariffRequest request) {
        return mapper.toResponse(tariffs.add(request.meter(), request.model(), request.effectiveFrom(), mapper.paramsJson(request)));
    }

    @Operation(summary = "List a meter's tariff versions, oldest first")
    @GetMapping
    public List<TariffResponse> list(@RequestParam String meter) {
        return tariffs.versions(meter).stream().map(mapper::toResponse).toList();
    }
}
