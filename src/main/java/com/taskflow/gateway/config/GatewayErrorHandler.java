package com.taskflow.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(GatewayErrorHandler.class);
    private final ObjectMapper mapper;
    public GatewayErrorHandler(ObjectMapper mapper){this.mapper=mapper;}
    @Override public Mono<Void> handle(ServerWebExchange exchange,Throwable ex){
        int status=500; String error="Internal Server Error"; String message="Unexpected gateway error";
        if(ex instanceof ResponseStatusException r){status=r.getStatusCode().value();error=HttpStatus.valueOf(status).getReasonPhrase();message=r.getReason()==null?error:r.getReason();}
        else if(ex.getClass().getSimpleName().contains("NotFound")){status=404;error="Not Found";message="No gateway route matches this path";}
        else if(ex instanceof ConnectException||ex instanceof TimeoutException||ex instanceof WebClientRequestException){status=503;error="Service Unavailable";message="A downstream service is unavailable";}
        log.error("Gateway failure status={} path={} type={}",status,exchange.getRequest().getPath().value(),ex.getClass().getSimpleName());
        var response=exchange.getResponse(); if(response.isCommitted())return Mono.error(ex);
        response.setStatusCode(HttpStatus.valueOf(status));response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try{byte[] body=mapper.writeValueAsBytes(Map.of("timestamp",Instant.now().toString(),"status",status,"error",error,"message",message,"validationErrors",Map.of(),"path",exchange.getRequest().getPath().value()));DataBuffer buffer=response.bufferFactory().wrap(body);return response.writeWith(Mono.just(buffer));}
        catch(IOException serializationFailure){return response.setComplete();}
    }
}
