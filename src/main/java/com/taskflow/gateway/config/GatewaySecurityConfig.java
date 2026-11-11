package com.taskflow.gateway.config;

import java.util.Base64;
import java.util.function.Function;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import java.time.Instant;
import java.util.Map;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GatewaySecurityConfig.class);
    @Bean SecretKey jwtSecret(@Value("${taskflow.jwt.secret-base64}") String encoded) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("JWT_SECRET_BASE64 must be valid Base64", ex); }
        if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET_BASE64 must decode to at least 32 bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean ReactiveJwtDecoder jwtDecoder(SecretKey key) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("taskflow-user-service"));
        return decoder;
    }

    @Bean ServerAuthenticationConverter cookieBearerTokenConverter() {
        return exchange -> {
            if (isPublicAuthRequest(exchange)) return Mono.empty();
            String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7))
                return Mono.just(new BearerTokenAuthenticationToken(authorization.substring(7).trim()));
            var cookie = exchange.getRequest().getCookies().getFirst("TASKFLOW_ACCESS");
            return cookie == null ? Mono.empty() : Mono.just(new BearerTokenAuthenticationToken(cookie.getValue()));
        };
    }

    private static boolean isPublicAuthRequest(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        return method == HttpMethod.OPTIONS
                || (method == HttpMethod.GET && "/api/auth/csrf".equals(path))
                || (method == HttpMethod.POST && ("/api/auth/register".equals(path)
                    || "/api/auth/login".equals(path) || "/api/users/register".equals(path)));
    }

    @Bean ServerCsrfTokenRepository csrfTokenRepository() {
        CookieServerCsrfTokenRepository repository = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookiePath("/");
        return repository;
    }

    @Bean ServerAuthenticationEntryPoint gatewayUnauthorized(ObjectMapper mapper) {
        return (exchange, ex) -> { log.warn("Gateway rejected unauthenticated request path={}", exchange.getRequest().getPath().value()); return write(exchange, mapper, 401, "Unauthorized", "Authentication is required or the access token is invalid or expired"); };
    }
    @Bean ServerAccessDeniedHandler gatewayForbidden(ObjectMapper mapper) {
        return (exchange, ex) -> { log.warn("Gateway denied request path={}", exchange.getRequest().getPath().value()); return write(exchange, mapper, 403, "Forbidden", "You are not allowed to access this resource"); };
    }
    private static Mono<Void> write(ServerWebExchange exchange, ObjectMapper mapper, int status, String error, String message) {
        var response = exchange.getResponse(); response.setStatusCode(org.springframework.http.HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = mapper.writeValueAsBytes(Map.of("timestamp", Instant.now().toString(), "status", status,
                    "error", error, "message", message, "validationErrors", Map.of(), "path", exchange.getRequest().getPath().value()));
            DataBuffer buffer = response.bufferFactory().wrap(bytes); return response.writeWith(Mono.just(buffer));
        } catch (Exception serializationFailure) { return response.setComplete(); }
    }

    @Bean SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, ServerAuthenticationConverter tokenConverter,
            ServerCsrfTokenRepository csrfRepository,
            ServerAuthenticationEntryPoint unauthorized, ServerAccessDeniedHandler forbidden) {
        ServerWebExchangeMatcher csrfMatcher = exchange -> {
            String path = exchange.getRequest().getPath().pathWithinApplication().value();
            // The task service validates the CSRF token for these routed requests.
            // Avoid validating the same cookie/header pair independently at both layers.
            if (path.equals("/api/tasks") || path.startsWith("/api/tasks/"))
                return ServerWebExchangeMatcher.MatchResult.notMatch();
            String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7))
                return ServerWebExchangeMatcher.MatchResult.notMatch();
            HttpMethod method = exchange.getRequest().getMethod();
            if (method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.TRACE || method == HttpMethod.OPTIONS)
                return ServerWebExchangeMatcher.MatchResult.notMatch();
            return ServerWebExchangeMatcher.MatchResult.match();
        };
        return http.csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(csrfMatcher))
                .cors(Customizer.withDefaults())
                .authorizeExchange(a -> a.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/users/register", "/api/auth/register", "/api/auth/login").permitAll()
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/docs/**", "/actuator/health").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.bearerTokenConverter(tokenConverter).jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .build();
    }
}
