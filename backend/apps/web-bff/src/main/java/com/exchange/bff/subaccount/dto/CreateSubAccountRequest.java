package com.exchange.bff.subaccount.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSubAccountRequest(@NotBlank @Size(max = 64) String label) {
}
