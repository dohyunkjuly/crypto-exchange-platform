package com.exchange.spot.openapi.common.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body has been read once, up front, and can be read again.
 *
 * <p>The signature covers the body, so the auth interceptor needs the bytes before the controller
 * does -- and a servlet body is a stream that reads once. Spring's {@code
 * ContentCachingRequestWrapper} caches only as the body is consumed, which is after the
 * interceptor, so this reads it eagerly instead. Bounded: a signed request is an order or a
 * query, never a file.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    static final int MAX_BODY_BYTES = 64 * 1024;

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new IOException("request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
    }

    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return in.read(); }
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
