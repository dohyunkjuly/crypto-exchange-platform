package com.exchange.admin.bff.auth.dto;

import jakarta.validation.constraints.Pattern;

public record CodeRequest(@Pattern(regexp = "\\d{6}") String code) {
}
