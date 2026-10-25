package com.centry.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The sign-in page. Spring Security handles the POST; see {@code SecurityConfig}. */
@Controller
class LoginController {

    @GetMapping("/login")
    String login() {
        return "login";
    }
}
