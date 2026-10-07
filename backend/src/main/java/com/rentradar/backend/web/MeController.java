package com.rentradar.backend.web;

import com.rentradar.backend.domain.UserAccount;
import com.rentradar.backend.repository.UserAccountRepository;
import com.rentradar.backend.security.AuthenticatedUser;
import com.rentradar.backend.web.dto.MeResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserAccountRepository users;

    public MeController(UserAccountRepository users) {
        this.users = users;
    }

    /**
     * The id comes from the token, never from the request, so one user cannot
     * read another by changing a path variable.
     */
    @GetMapping
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        UserAccount account = users.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return MeResponse.from(account);
    }
}