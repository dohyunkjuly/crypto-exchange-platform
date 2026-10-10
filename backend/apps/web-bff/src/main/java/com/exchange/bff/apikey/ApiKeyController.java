package com.exchange.bff.apikey;

import com.exchange.bff.apikey.dto.ApiKeyView;
import com.exchange.bff.apikey.dto.CreateApiKeyRequest;
import com.exchange.bff.apikey.dto.CreatedApiKeyView;
import com.exchange.bff.apikey.dto.UpdateApiKeyRequest;
import com.exchange.bff.common.security.CurrentUser;
import com.exchange.user.apikey.ApiKeyRevokedReason;
import com.exchange.user.apikey.ApiKeyService;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.apikey.dto.CreatedApiKey;
import com.exchange.user.identity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bff/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ApiKeyController(ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    /**
     * Mints a key. Requires a current 2FA code on top of the session -- see
     * {@code CreateApiKeyRequest}.
     *
     * <p>Revoking deliberately does NOT require one. A leaked key needs killing
     * immediately, and making that depend on having your phone is how a key stays live
     * during the exact minutes it matters. Revocation can only reduce what an attacker
     * can do, so the asymmetry is the right way round.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKeyView create(@Valid @RequestBody CreateApiKeyRequest request,
                                    @CurrentUser User user, HttpServletRequest http) {
        CreatedApiKey created = apiKeyService.create(user.getId(), request.label(),
                request.canRead(), request.canTrade(), request.canWithdraw(),
                request.ipAllowlist(), request.expiresAt(), request.mfaCode(),
                clientIp(http));
        return CreatedApiKeyView.of(created);
    }

    @GetMapping
    public List<ApiKeyView> list(@CurrentUser User user) {
        return apiKeyService.list(user.getId()).stream().map(this::toView).toList();
    }

    /** The same body as creation, minus nothing: what the key may do, and the code that proves
     *  whoever is asking holds the phone. Read permission cannot be removed, as at creation. */
    @PatchMapping("/{id}")
    public ApiKeyView update(@PathVariable UUID id, @Valid @RequestBody UpdateApiKeyRequest request,
                             @CurrentUser User user, HttpServletRequest http) {
        return toView(apiKeyService.update(user.getId(), id, request.label(), true, request.canTrade(),
                request.canWithdraw(), request.ipAllowlist(), request.expiresAt(), request.mfaCode(), clientIp(http)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, @CurrentUser User user, HttpServletRequest http) {
        apiKeyService.revoke(user.getId(), id, ApiKeyRevokedReason.REVOKED_BY_USER, clientIp(http));
    }

    private ApiKeyView toView(UserApiKey key) {
        return ApiKeyView.of(key, objectMapper);
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
