package com.spring.gateway;

import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class GatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(GatewayApplication.class, args);
	}

	@Bean
	public RouteLocator myRoutes(RouteLocatorBuilder builder) {
		return builder.routes()
				.route(p -> p
						.path("/**")
						.uri("http://httpbin.org:80"))
				.build();
	}

	@Component
	public static class SampleFilter implements GlobalFilter, Ordered {

		@Override
		public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
			List<HttpMessageReader<?>> messageReaders = HandlerStrategies.withDefaults().messageReaders();

			Mono<String> cachedRequestBodyObject =
					DataBufferUtils.join(exchange.getRequest().getBody()).flatMap((dataBuffer) -> {
						DataBufferUtils.retain(dataBuffer);
						final Flux<DataBuffer> cachedFlux =
								Flux.defer(() -> Flux.just(dataBuffer.slice(0, dataBuffer.readableByteCount())));

						ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
							public Flux<DataBuffer> getBody() {
								return cachedFlux;
							}
						};
						return ServerRequest
								.create(exchange.mutate().request(mutatedRequest).build(), messageReaders)
								.bodyToMono(String.class)
								.doOnNext((objectValue) -> {
									exchange.getAttributes().put("cachedRequestBodyObject", objectValue);
									exchange.getAttributes().put("cachedRequestBody", cachedFlux);
								});
					});

			return cachedRequestBodyObject.flatMap(formData -> {
				if (formData.contains("key")) {
					exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);

					return exchange.getResponse().setComplete();
				}

				return chain.filter(exchange);
			});
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}
	}
}
