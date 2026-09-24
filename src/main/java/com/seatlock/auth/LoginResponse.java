package com.seatlock.auth;

public record LoginResponse(String token, String tokenType, long expiresInSeconds) {

    public static LoginResponse of(String token, long expiresInSeconds) {
        return new LoginResponse(token, "Bearer", expiresInSeconds);
    }
}