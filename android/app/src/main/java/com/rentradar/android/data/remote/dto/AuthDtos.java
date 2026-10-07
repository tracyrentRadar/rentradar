package com.rentradar.android.data.remote.dto;

/**
 * Gson ignores JSON fields it does not know and leaves unmatched Java fields
 * null, so these are deliberately a tolerant superset of the backend payload.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public static final class LoginRequest {
        public final String email;
        public final String password;

        public LoginRequest(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    public static final class RegisterRequest {
        public final String email;
        public final String password;
        public final String role;
        public final String marketId;
        public final String locale;

        public RegisterRequest(String email, String password, String role,
                               String marketId, String locale) {
            this.email = email;
            this.password = password;
            this.role = role;
            this.marketId = marketId;
            this.locale = locale;
        }
    }

    public static final class RefreshRequest {
        public final String refreshToken;

        public RefreshRequest(String refreshToken) {
            this.refreshToken = refreshToken;
        }
    }

    public static final class UserSummary {
        public String id;
        public String email;
        public String role;
        public String marketId;
        public String locale;
    }

    public static final class AuthResponse {
        public String accessToken;
        public String refreshToken;
        public String tokenType;
        public Long expiresIn;
        public UserSummary user;
    }
}