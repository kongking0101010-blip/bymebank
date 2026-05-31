package com.khmerbank.service.bank;

import com.khmerbank.exception.ApiException;
import com.khmerbank.model.enums.BankType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
@Component
public class BankRouter {

    private final Map<BankType, BankIntegration> integrations;

    public BankRouter(List<BankIntegration> all) {
        this.integrations = all.stream()
                .collect(Collectors.toMap(BankIntegration::bankType, Function.identity()));
    }

    public BankIntegration get(BankType type) {
        BankIntegration i = integrations.get(type);
        if (i == null) {
            throw ApiException.badRequest("BANK_NOT_SUPPORTED",
                    "Bank " + type + " is not configured");
        }
        return i;
    }
}
