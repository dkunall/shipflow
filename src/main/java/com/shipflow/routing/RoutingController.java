package com.shipflow.routing;

import com.shipflow.security.Actor;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hubs")
@Validated
public class RoutingController {
    private final RoutingService routing;

    public RoutingController(RoutingService routing) {
        this.routing = routing;
    }

    @PostMapping("/{hubCode}/route-plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public RoutingService.Plan plan(@PathVariable String hubCode,
                                    @RequestParam(defaultValue = "60") @DecimalMin("1") @DecimalMax("300") double radiusKm) {
        return routing.plan(hubCode, radiusKm, Actor.current().subject());
    }
}
