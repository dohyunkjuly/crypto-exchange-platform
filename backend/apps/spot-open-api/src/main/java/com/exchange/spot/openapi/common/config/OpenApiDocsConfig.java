package com.exchange.spot.openapi.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What springdoc publishes at {@code /v3/api-docs} and {@code /swagger-ui.html}: the REST half of
 * the contract, generated from the controllers so it cannot drift from them. The signing scheme
 * is described as four API-key headers; the worked examples are in api-docs/,
 * since a generated page cannot show a signature being computed.
 */
@Configuration
public class OpenApiDocsConfig {

    @Bean
    public OpenAPI openApi() {
        SecurityScheme key = header("X-ACCESS-KEY", "The key's public id.");
        return new OpenAPI()
                .info(new Info().title("Spot API").version("v1")
                        .description("The public, signed spot trading API. Every response is "
                                + "{status, code, data, message}; money is a decimal string; time is ISO-8601 UTC. "
                                + "Private endpoints are signed with HMAC-SHA256 over timestamp + method + path?query + body "
                                + "(see api-docs/ for the examples)."))
                .components(new Components()
                        .addSecuritySchemes("apiKey", key)
                        .addSecuritySchemes("timestamp", header("X-ACCESS-TIMESTAMP", "Request time, epoch milliseconds."))
                        .addSecuritySchemes("signature", header("X-ACCESS-SIGN", "Lowercase hex HMAC-SHA256 of the canonical string."))
                        .addSecuritySchemes("recvWindow", header("X-ACCESS-RECV-WINDOW", "Optional; ms the request stays valid, default 5000, max 60000.")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey").addList("timestamp").addList("signature"));
    }

    private static SecurityScheme header(String name, String description) {
        return new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name(name).description(description);
    }
}
