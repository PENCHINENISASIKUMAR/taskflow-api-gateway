package com.taskflow.gateway.config;

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class GatewayLoggingFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(GatewayLoggingFilter.class);
    private final ObjectMapper mapper;
    public GatewayLoggingFilter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public int getOrder() { return -1; }
    @Override public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange,
            org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        var req = exchange.getRequest();
        log.info("Gateway request id={} method={} path={}", req.getId(), req.getMethod(), req.getPath().value());
        return exchange.getPrincipal().doOnNext(principal -> log.debug("JWT validated request id={} subject={}", req.getId(), principal.getName()))
                .then(Mono.defer(() -> chain.filter(exchange)))
                .doOnSuccess(ignored -> log.info("Gateway response id={} status={}", req.getId(), exchange.getResponse().getStatusCode()))
                .onErrorResume(ex -> {
                    log.error("Gateway downstream failure id={} path={} cause={}", req.getId(), req.getPath().value(), ex.getClass().getSimpleName());
                    var response = exchange.getResponse();
                    if (response.isCommitted()) return Mono.error(ex);
                    response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE); response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    try {
                        byte[] bytes = mapper.writeValueAsBytes(Map.of("timestamp", Instant.now().toString(), "status", 503,
                                "error", "Service Unavailable", "message", "A downstream service is unavailable",
                                "validationErrors", Map.of(), "path", req.getPath().value()));
                        DataBuffer buffer = response.bufferFactory().wrap(bytes); return response.writeWith(Mono.just(buffer));
                    } catch (Exception serializationFailure) { return response.setComplete(); }
                });
    }
}
