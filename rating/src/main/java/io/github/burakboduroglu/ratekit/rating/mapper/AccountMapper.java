package io.github.burakboduroglu.ratekit.rating.mapper;

import io.github.burakboduroglu.ratekit.common.Money;
import io.github.burakboduroglu.ratekit.rating.domain.Account;
import io.github.burakboduroglu.ratekit.rating.dto.AccountResponse;
import io.github.burakboduroglu.ratekit.rating.dto.TopUpRequest;
import io.github.burakboduroglu.ratekit.rating.dto.TopUpResponse;
import org.springframework.stereotype.Component;

/** Converts between the account API shapes and the domain; amounts leave as 4-digit decimal strings. */
@Component
public class AccountMapper {

    public AccountResponse toResponse(Account account) {
        return new AccountResponse(account.id(), account.balance().toString());
    }

    public Money amount(TopUpRequest request) {
        return new Money(request.amount()); // @Digits allows at most 4 decimals, so this never rounds
    }

    public TopUpResponse toResponse(TopUpRequest request, Account account) {
        return new TopUpResponse(account.id(), request.topUpId(), amount(request).toString(), account.balance().toString());
    }
}
