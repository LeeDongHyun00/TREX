package com.trex.server.dto;

public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInMillis,
        UserResponse user
) {

    public static TokenResponse of(String accessToken, long expiresInMillis, UserResponse user) {
        return new TokenResponse(accessToken, "Bearer", expiresInMillis, user);
    }
}
