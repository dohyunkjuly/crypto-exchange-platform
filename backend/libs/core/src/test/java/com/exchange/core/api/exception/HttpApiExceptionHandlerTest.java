package com.exchange.core.api.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class HttpApiExceptionHandlerTest {

    enum Colour { RED }

    record Body(Colour colour, long amount) {
    }

    @RestController
    static class TestController {
        @PostMapping("/body")
        void body(@RequestBody Body body) {
        }

        @GetMapping("/param")
        void param(@RequestParam("colour") Colour colour) {
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("bug");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new HttpApiExceptionHandler())
            .build();

    @Test
    @DisplayName("a body that is not JSON is a 400 validation failure, not a 500")
    void malformed_json_is_400() throws Exception {
        expect(post("/body").contentType(MediaType.APPLICATION_JSON).content("{"), 400, 1000);
    }

    @Test
    @DisplayName("an unknown enum name in a body is a 400 validation failure, not a 500")
    void unknown_enum_in_body_is_400() throws Exception {
        expect(post("/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"colour\":\"BLUE\",\"amount\":1}"), 400, 1000);
    }

    @Test
    @DisplayName("a value of the wrong type in a body is a 400 validation failure, not a 500")
    void wrong_type_in_body_is_400() throws Exception {
        expect(post("/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"colour\":\"RED\",\"amount\":{}}"), 400, 1000);
    }

    @Test
    @DisplayName("an unknown enum name in a query parameter is a 400 naming the parameter")
    void unknown_enum_in_query_is_400() throws Exception {
        expect(get("/param").param("colour", "BLUE"), 400, 1000);
        String body = mvc.perform(get("/param").param("colour", "BLUE")).andReturn()
                .getResponse().getContentAsString();
        assertTrue(body.contains("Invalid value for parameter 'colour'"), body);
    }

    @Test
    @DisplayName("a known path with the wrong verb is a 405, not a 500")
    void wrong_method_is_405() throws Exception {
        expect(post("/param"), 405, 1002);
    }

    @Test
    @DisplayName("a missing required query parameter is a 400 validation failure")
    void missing_parameter_is_400() throws Exception {
        expect(get("/param"), 400, 1000);
    }

    @Test
    @DisplayName("an unknown path is a 404, not a 500")
    void unknown_path_is_404() {
        var response = new HttpApiExceptionHandler().handleNoSuchEndpoint(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "/nope"));
        assertEquals(404, response.getStatusCode().value());
        assertEquals(1001, response.getBody().code());
    }

    @Test
    @DisplayName("an unclassified exception is still a 500")
    void a_bug_is_still_500() throws Exception {
        expect(get("/boom"), 500, 9000);
    }

    private void expect(RequestBuilder request, int status, int code) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(status, result.getResponse().getStatus(), body);
        assertTrue(body.contains("\"code\":" + code), body);
    }
}
