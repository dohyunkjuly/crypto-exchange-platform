package com.exchange.spot.openapi.subaccount.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** {@code transferId}: the client's own id, so a retry after a timeout is the same transfer. */
public record TransferRequest(@NotNull Long fromUserId, @NotNull Long toUserId, @NotBlank String asset,
                              @NotBlank @Pattern(regexp = "\\d+(\\.\\d+)?") String amount,
                              String transferId) {
}
