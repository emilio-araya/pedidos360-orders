package com.pedidos360.orders.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.orders.security.RestProblemSecurityHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain entraSecurityFilterChain(
            HttpSecurity http,
            RestProblemSecurityHandler problemHandler,
            @Qualifier("entraJwtDecoder") JwtDecoder decoder,
            JwtAuthenticationConverter converter
    ) throws Exception {
        return baseSecurity(http)
                .securityMatcher("/api/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler)
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter)))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain cognitoSecurityFilterChain(
            HttpSecurity http,
            RestProblemSecurityHandler problemHandler,
            @Qualifier("cognitoJwtDecoder") JwtDecoder decoder,
            JwtAuthenticationConverter converter
    ) throws Exception {
        return baseSecurity(http)
                .securityMatcher("/aws/api/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/aws/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler)
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter)))
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        return baseSecurity(http)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    private HttpSecurity baseSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny()));
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter entraRoles = new JwtGrantedAuthoritiesConverter();
        entraRoles.setAuthoritiesClaimName("roles");
        entraRoles.setAuthorityPrefix("ROLE_");
        JwtGrantedAuthoritiesConverter cognitoRoles = new JwtGrantedAuthoritiesConverter();
        cognitoRoles.setAuthoritiesClaimName("cognito:groups");
        cognitoRoles.setAuthorityPrefix("ROLE_");
        JwtGrantedAuthoritiesConverter entraScopes = new JwtGrantedAuthoritiesConverter();
        entraScopes.setAuthoritiesClaimName("scp");
        entraScopes.setAuthorityPrefix("SCOPE_");
        JwtGrantedAuthoritiesConverter cognitoScopes = new JwtGrantedAuthoritiesConverter();
        cognitoScopes.setAuthoritiesClaimName("scope");
        cognitoScopes.setAuthorityPrefix("SCOPE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            addAuthorities(authorities, entraRoles.convert(jwt));
            addAuthorities(authorities, cognitoRoles.convert(jwt));
            addAuthorities(authorities, entraScopes.convert(jwt));
            addAuthorities(authorities, cognitoScopes.convert(jwt));
            return authorities;
        });
        return converter;
    }

    private void addAuthorities(Collection<GrantedAuthority> target, Collection<GrantedAuthority> values) {
        if (values != null) target.addAll(values);
    }

    @Bean
    @Qualifier("entraJwtDecoder")
    @Profile("local")
    JwtDecoder localEntraJwtDecoder(AppSecurityProperties properties) {
        byte[] secret = secretBytes(properties);
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(jwtValidators(properties));
        return decoder;
    }

    @Bean
    @Qualifier("cognitoJwtDecoder")
    @Profile("local")
    JwtDecoder localCognitoJwtDecoder() {
        return token -> {
            throw new JwtException("Cognito no está disponible en el perfil local");
        };
    }

    @Bean
    @Qualifier("entraJwtDecoder")
    @Profile("!local")
    JwtDecoder entraJwtDecoder(AppSecurityProperties properties) {
        requireText(properties.issuer(), "ENTRA_ISSUER");
        requireText(properties.audience(), "ENTRA_API_AUDIENCE");
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(properties.issuer());
        decoder.setJwtValidator(jwtValidators(properties));
        return decoder;
    }

    @Bean
    @Qualifier("cognitoJwtDecoder")
    @Profile("!local")
    JwtDecoder cognitoJwtDecoder(CognitoSecurityProperties properties) {
        if (!properties.isConfigured()) {
            return token -> {
                throw new JwtException("Cognito no está configurado para este ambiente");
            };
        }
        NimbusJwtDecoder decoder = properties.jwkSetUri() == null || properties.jwkSetUri().isBlank()
                ? (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(properties.issuer())
                : NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new IssuerValidator(properties.issuer()),
                new CognitoAudienceValidator(properties.audience()),
                new AccessTokenValidator()
        ));
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> jwtValidators(AppSecurityProperties properties) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new IssuerValidator(properties.issuer()),
                new AudienceValidator(properties.audience())
        );
    }

    private byte[] secretBytes(AppSecurityProperties properties) {
        if (properties.hmacSecret() == null || properties.hmacSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "LOCAL_JWT_HMAC_SECRET debe tener al menos 32 bytes y solo se permite con el perfil local"
            );
        }
        return properties.hmacSecret().getBytes(StandardCharsets.UTF_8);
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
