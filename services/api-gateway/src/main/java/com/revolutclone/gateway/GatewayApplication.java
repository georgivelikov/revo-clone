package com.revolutclone.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * common-security's two auto-configurations are excluded here rather than left to try to
 * wire themselves in: both declare beans typed on Servlet-only Spring Security classes
 * ({@code HttpSecurity}, {@code WebMvcConfigurer}), which do not exist on this WebFlux-only
 * service's classpath (its {@code spring-boot-starter-web} dependency is excluded in the
 * module pom). This gateway reuses only {@link com.revolutclone.security.api.ConfigurableRolesClaimConverter}
 * and {@link com.revolutclone.security.RevolutSecurityProperties} directly - see
 * {@link com.revolutclone.gateway.security.GatewaySecurityConfig}.
 *
 * <p>The exclusion below uses {@code excludeName} (a class-name string), never
 * {@code exclude} (a {@code Class<?>} literal): referencing
 * {@code com.revolutclone.security.SecurityAutoConfiguration} by class literal forces the JVM
 * to load and verify it - including its {@code implements WebMvcConfigurer} clause - which
 * throws {@code NoClassDefFoundError} on this classpath since {@code WebMvcConfigurer} (from
 * {@code spring-webmvc}) was never pulled in. {@code excludeName} never loads the class at
 * all, sidestepping the problem entirely.
 */
@SpringBootApplication(excludeName = {
        "com.revolutclone.security.SecurityAutoConfiguration",
        "com.revolutclone.security.keycloak.KeycloakSecurityAutoConfiguration"
})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
