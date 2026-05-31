package com.khmerbank.sdk.api;

import com.khmerbank.sdk.http.HttpClient;
import com.khmerbank.sdk.model.LinkMerchantRequest;
import com.khmerbank.sdk.model.MerchantResponse;

import java.util.List;
import java.util.UUID;

public class MerchantApi {

    private final HttpClient http;

    public MerchantApi(HttpClient http) { this.http = http; }

    public MerchantResponse link(LinkMerchantRequest request) {
        return http.post("/api/v1/merchants", request, MerchantResponse.class);
    }

    public List<MerchantResponse> list() {
        return http.getList("/api/v1/merchants", MerchantResponse.class);
    }

    public void delete(UUID id) {
        http.delete("/api/v1/merchants/" + id);
    }
}
