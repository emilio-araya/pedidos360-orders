package com.pedidos360.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * El prefijo de {@link CognitoSecurityProperties} debe coincidir con el
 * anidamiento de application-oracle.yml. Si cognito quedara como hermano de
 * issuer, los valores se enlazarian fuera del prefijo app.security.cognito,
 * isConfigured() devolveria false y el namespace /aws/api/** no autenticaria.
 */
class CognitoPropertiesNestingTest {

    private static final List<String> REQUIRED_KEYS = List.of(
            "app.security.cognito.issuer",
            "app.security.cognito.audience",
            "app.security.cognito.jwk-set-uri");

    @Test
    void declaresCognitoUnderTheSecurityPrefix() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("oracle", new ClassPathResource("application-oracle.yml"));

        assertThat(sources).isNotEmpty();
        PropertySource<?> source = sources.get(0);

        for (String key : REQUIRED_KEYS) {
            assertThat(source.containsProperty(key))
                    .as("falta la propiedad %s en application-oracle.yml; CognitoSecurityProperties lee app.security.cognito", key)
                    .isTrue();
        }
    }
}
