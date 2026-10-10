package com.exchange.bff.subaccount.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** {@code transferId}: chosen by the client so a retry after a timeout is the same transfer,
 *  never a second one. Optional; without it every call is a new transfer. */
public record TransferRequest(@NotNull Long fromUserId, @NotNull Long toUserId, @NotBlank String asset,
                              @NotBlank @Pattern(regexp = "\\d+(\\.\\d+)?") String amount,
                              String transferId) {
}
