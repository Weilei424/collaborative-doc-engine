package com.mwang.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwang.backend.service.HeaderCurrentUserProvider;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final HeaderCurrentUserProvider userProvider;
    private final ObjectMapper objectMapper;

    public SecurityConfig(HeaderCurrentUserProvider userProvider, ObjectMapper objectMapper) {
        this.userProvider = userProvider;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                (request, response, authException) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
            .addFilterBefore(new UserIdAuthenticationFilter(userProvider, objectMapper),
                             UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
