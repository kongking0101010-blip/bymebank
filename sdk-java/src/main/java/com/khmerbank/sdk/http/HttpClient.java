package com.khmerbank.sdk.http;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.khmerbank.sdk.exception.KhmerBankApiException;
import com.khmerbank.sdk.exception.KhmerBankException;
import com.khmerbank.sdk.model.ApiResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class HttpClient {

    private final java.net.http.HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper mapper;

    public HttpClient(String baseUrl, String apiKey, int timeoutSeconds, ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    public <T> T get(String path, Class<T> dataType) {
        return send("GET", path, null, mapper.getTypeFactory().constructType(dataType));
    }

    public <T> List<T> getList(String path, Class<T> elementType) {
        TypeFactory tf = mapper.getTypeFactory();
        JavaType list = tf.constructCollectionType(List.class, elementType);
        return send("GET", path, null, list);
    }

    public <T> T post(String path, Object body, Class<T> dataType) {
        return send("POST", path, body, mapper.getTypeFactory().constructType(dataType));
    }

    public void delete(String path) {
        send("DELETE", path, null, mapper.getTypeFactory().constructType(Void.class));
    }

    @SuppressWarnings("unchecked")
    private <T> T send(String method, String path, Object body, JavaType dataType) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("X-API-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "khmerbank-java-sdk/1.0.0");

            String json = body == null ? "" : mapper.writeValueAsString(body);
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json);

            HttpRequest httpReq = switch (method) {
                case "GET"    -> req.GET().build();
                case "DELETE" -> req.DELETE().build();
                default       -> req.method(method, publisher).build();
            };

            HttpResponse<String> res = this.http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            int status = res.statusCode();

            if (status >= 200 && status < 300) {
                JavaType envelope = mapper.getTypeFactory()
                        .constructParametricType(ApiResponse.class, dataType);
                ApiResponse<T> wrapper = mapper.readValue(res.body(), envelope);
                return wrapper.getData();
            }

            Map<String, Object> err = (Map<String, Object>) mapper.readValue(res.body(), Map.class);
            throw new KhmerBankApiException(
                    status,
                    String.valueOf(err.getOrDefault("code", "UNKNOWN")),
                    String.valueOf(err.getOrDefault("message", "Request failed")));
        } catch (KhmerBankApiException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new KhmerBankException("HTTP error: " + e.getMessage(), e);
        }
    }
}
