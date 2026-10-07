package io.github.burakboduroglu.ratekit.rating.controller;

import io.github.burakboduroglu.ratekit.rating.dto.AccountResponse;
import io.github.burakboduroglu.ratekit.rating.dto.CreateAccountRequest;
import io.github.burakboduroglu.ratekit.rating.dto.TopUpRequest;
import io.github.burakboduroglu.ratekit.rating.dto.TopUpResponse;
import io.github.burakboduroglu.ratekit.rating.mapper.AccountMapper;
import io.github.burakboduroglu.ratekit.rating.service.AccountService;
import io.github.burakboduroglu.ratekit.rating.service.TopUpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    private final AccountService accounts;
    private final TopUpService topUps;
    private final AccountMapper mapper;

    public AccountController(AccountService accounts, TopUpService topUps, AccountMapper mapper) {
        this.accounts = accounts;
        this.topUps = topUps;
        this.mapper = mapper;
    }

    @Operation(summary = "Open a prepaid account", description = "The balance starts at zero; add money with a top-up.")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Invalid account id")
    @ApiResponse(responseCode = "409", description = "An account with this id already exists")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return mapper.toResponse(accounts.create(request.accountId()));
    }

    @Operation(summary = "Read an account and its balance")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "404", description = "No such account")
    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable String accountId) {
        return mapper.toResponse(accounts.find(accountId));
    }

    @Operation(summary = "Add money to the balance",
            description = "Safe to retry: a repeated topUpId with the same amount changes nothing and answers 200.")
    @ApiResponse(responseCode = "201", description = "Credited")
    @ApiResponse(responseCode = "200", description = "Already credited earlier with this topUpId; nothing changed")
    @ApiResponse(responseCode = "400", description = "Invalid amount or id")
    @ApiResponse(responseCode = "404", description = "No such account")
    @ApiResponse(responseCode = "409", description = "This topUpId was used with a different amount")
    @PostMapping("/{accountId}/top-ups")
    public ResponseEntity<TopUpResponse> topUp(@PathVariable String accountId, @Valid @RequestBody TopUpRequest request) {
        TopUpService.Result result = topUps.topUp(accountId, request.topUpId(), mapper.amount(request));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(mapper.toResponse(request, result.account()));
    }
}
