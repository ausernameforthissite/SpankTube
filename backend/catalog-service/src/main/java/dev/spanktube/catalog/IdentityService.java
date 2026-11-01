package dev.spanktube.catalog;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class IdentityService {

    Identity required(HttpServletRequest request) {
        String username = request.getHeader("X-SpankTube-User");
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authenticated user");
        }
        String roles = request.getHeader("X-SpankTube-Roles");
        boolean admin = roles != null && roles.contains("ROLE_ADMIN");
        return new Identity(username, admin);
    }

    void admin(HttpServletRequest request) {
        if (!required(request).admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN role is required");
        }
    }

    record Identity(String username, boolean admin) {
    }
}
