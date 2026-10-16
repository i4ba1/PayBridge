package com.paybridge.probe.web;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    @GetMapping("/probe/session")
    public String session(HttpSession session) {
        session.setAttribute("seen", "yes");
        return session.getId();
    }
}
