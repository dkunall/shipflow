package com.shipflow.security;

import com.shipflow.common.error.ShipFlowException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, derived from the JWT: subject, roles and (for merchant users) the merchant they
 * belong to. Ownership decisions are made on this value object, never on raw claims in controllers.
 */
public record Actor(String subject, Set<Role> roles, Optional<UUID> merchantId) {

    public static final String MERCHANT_CLAIM = "merchant_id";

    public boolean has(Role role) {
        return roles.contains(role);
    }

    /** OPS and ADMIN see across merchants. */
    public boolean isInternal() {
        return has(Role.OPS) || has(Role.ADMIN);
    }

    /** Merchant scope for merchant-only users; internal users must pass an explicit merchant. */
    public UUID requireMerchant() {
        return merchantId.orElseThrow(() -> ShipFlowException.forbidden("token is not bound to a merchant"));
    }

    public void assertCanAccessMerchant(UUID ownerMerchantId) {
        if (isInternal()) {
            return;
        }
        if (merchantId.isEmpty() || !merchantId.get().equals(ownerMerchantId)) {
            throw ShipFlowException.forbidden("resource belongs to another merchant");
        }
    }

    public static Actor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw ShipFlowException.forbidden("not authenticated");
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        jwtAuth.getAuthorities().forEach(a -> {
            String name = a.getAuthority();
            if (name.startsWith("ROLE_")) {
                try {
                    roles.add(Role.valueOf(name.substring(5)));
                } catch (IllegalArgumentException ignored) {
                    // unknown roles carry no permissions
                }
            }
        });
        Optional<UUID> merchant = Optional.ofNullable(jwtAuth.getToken().getClaimAsString(MERCHANT_CLAIM)).map(s -> {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                throw ShipFlowException.forbidden("malformed merchant claim");
            }
        });
        return new Actor(jwtAuth.getName(), Set.copyOf(roles), merchant);
    }
}
