package com.rentradar.backend.web;

import com.rentradar.backend.security.AuthenticationService;
import com.rentradar.backend.web.dto.AuthResponse;
import com.rentradar.backend.web.dto.LoginRequest;
import com.rentradar.backend.web.dto.RefreshTokenRequest;
import com.rentradar.backend.web.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only unauthenticated endpoints in the system. Everything here is a POST,
 * including logout, because each one changes server state and none of them
 * belong in a URL, a browser history or an access log.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authentication;

    public AuthController(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return AuthResponse.from(authentication.register(
                request.email(),
                request.password(),
                request.role(),
                request.marketId(),
                request.locale()));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return AuthResponse.from(
                authentication.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return AuthResponse.from(authentication.refresh(request.refreshToken()));
    }

    /**
     * Always 204, whether or not the token was known. Reporting a miss would
     * confirm to a holder of a stolen token that it had already been retired.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authentication.logout(request.refreshToken());
    }
}