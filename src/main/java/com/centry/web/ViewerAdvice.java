package com.centry.web;

import com.centry.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Puts the signed-in person on every browser page's model for the app bar; null on the login page. */
@ControllerAdvice(basePackageClasses = ViewerAdvice.class)
@RequiredArgsConstructor
class ViewerAdvice {

    private final UserRepository users;

    @ModelAttribute("viewer")
    Viewer viewer(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        String login = authentication.getName();
        return Viewer.of(users.displayName(login).filter(name -> !name.isBlank()).orElse(login));
    }
}
