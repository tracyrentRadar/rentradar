package com.rentradar.backend.web;

import com.rentradar.backend.security.AuthenticatedUser;
import com.rentradar.backend.service.PropertyService;
import com.rentradar.backend.web.dto.CreatePropertyRequest;
import com.rentradar.backend.web.dto.PropertyResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/properties")
public class PropertyController {

    private final PropertyService properties;

    public PropertyController(PropertyService properties) {
        this.properties = properties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PropertyResponse create(
            @Valid @RequestBody CreatePropertyRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return PropertyResponse.from(
                properties.create(request.toCommand(), principal.userId()));
    }
}