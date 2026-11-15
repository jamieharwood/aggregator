package com.aggregator.integration;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;

/** One WireMock server shared by the integration tests, standing in for every downstream endpoint. */
final class Downstream {

    static final WireMockServer SERVER = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        SERVER.start();
    }

    private Downstream() {
    }

    static String baseUrl() {
        return "http://localhost:" + SERVER.port();
    }
}
