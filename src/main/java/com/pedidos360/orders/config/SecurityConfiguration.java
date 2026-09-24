package com.pedidos360.orders.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.security.RestProblemSecurityHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestProblemSecurityHandler problemHandler,
            JwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny()))
                .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::rolesAuthorities);
        return converter;
    }

    private Collection<GrantedAuthority> rolesAuthorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            roles = new ArrayList<>();
        }
        return roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(String::trim)
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role))
                .toList();
    }

    @Bean
    @Profile("local")
    JwtDecoder localJwtDecoder(AppSecurityProperties properties) {
        byte[] secret = secretBytes(properties);
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(jwtValidators(properties));
        return decoder;
    }

    private byte[] secretBytes(AppSecurityProperties properties) {
        if (properties.hmacSecret() == null || properties.hmacSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "LOCAL_JWT_HMAC_SECRET debe tener al menos 32 bytes y solo se permite con el perfil local"
            );
        }
        return properties.hmacSecret().getBytes(StandardCharsets.UTF_8);
    }

    @Bean
    @Profile("!local")
    JwtDecoder entraJwtDecoder(AppSecurityProperties properties) {
        requireText(properties.issuer(), "ENTRA_ISSUER");
        requireText(properties.audience(), "ENTRA_API_AUDIENCE");
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(properties.issuer());
        decoder.setJwtValidator(jwtValidators(properties));
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> jwtValidators(AppSecurityProperties properties) {
        OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefault();
        return new DelegatingOAuth2TokenValidator<>(
                standard,
                new IssuerValidator(properties.issuer()),
                new AudienceValidator(properties.audience())
        );
    }

    private void requireText(String value, String setting) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(setting + " es obligatorio fuera del perfil local");
        }
    }

    @Bean
    RestProblemSecurityHandler restProblemSecurityHandler(ObjectMapper objectMapper) {
        return new RestProblemSecurityHandler(objectMapper);
    }
}
