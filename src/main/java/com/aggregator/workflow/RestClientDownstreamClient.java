package com.aggregator.workflow;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link DownstreamClient} backed by {@link RestClient}. The client is deliberately built without
 * Spring's HTTP client observation: {@link EndpointInvoker} owns the single client span per call.
 */
@Component
public class RestClientDownstreamClient implements DownstreamClient {

    private final RestClient restClient;

    public RestClientDownstreamClient(@Value("${proxy.connect-timeout:2s}") Duration connectTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    @Override
    public DownstreamResponse exchange(DownstreamRequest request) {
        RestClient.RequestBodySpec spec = restClient.method(request.method())
                .uri(request.uri())
                .headers(headers -> headers.addAll(request.headers()));
        if (request.body() != null) {
            spec.body(request.body());
        }
        return spec.exchange((clientRequest, clientResponse) -> {
            MediaType contentType;
            try {
                contentType = clientResponse.getHeaders().getContentType();
            } catch (RuntimeException ex) {
                contentType = null;
            }
            return new DownstreamResponse(clientResponse.getStatusCode().value(), contentType,
                    clientResponse.getBody().readAllBytes());
        });
    }
}
