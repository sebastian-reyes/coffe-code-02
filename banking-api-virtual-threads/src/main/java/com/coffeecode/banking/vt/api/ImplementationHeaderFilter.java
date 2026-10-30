package com.coffeecode.banking.vt.api;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Añade {@code X-Implementation: virtual-threads} a todas las respuestas. Permite saber qué API
 * respondió sin introducir diferencias en el cuerpo JSON.
 */
@Component
public class ImplementationHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Implementation", "virtual-threads");
        chain.doFilter(request, response);
    }
}
