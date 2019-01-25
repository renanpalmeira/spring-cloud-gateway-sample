package com.spring.gateway;

import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.RewriteFunction;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.cloud.gateway.support.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.support.DefaultServerRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ReactiveHttpInputMessage;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyExtractor;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
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
			Class inClass = String.class;
			Class outClass = String.class;
			ServerRequest serverRequest = new DefaultServerRequest(exchange);

			Mono<?> modifiedBody = serverRequest.bodyToMono(inClass).flatMap((o) -> {
				// here i can just get the form data, but i don't change response; here i just can change the form data
				return Mono.just(o);
			});

			BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, outClass);
			CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, exchange.getRequest().getHeaders());
			return bodyInserter.insert(outputMessage, new BodyInserterContext()).then(Mono.defer(() -> {
				ServerHttpRequestDecorator decorator = new ServerHttpRequestDecorator(exchange.getRequest()) {
					public HttpHeaders getHeaders() {
						HttpHeaders httpHeaders = new HttpHeaders();
						httpHeaders.putAll(super.getHeaders());
						httpHeaders.set("Transfer-Encoding", "chunked");
						return httpHeaders;
					}

					public Flux<DataBuffer> getBody() {
						return outputMessage.getBody();
					}
				};

				return chain.filter(exchange.mutate().request(decorator).build());
			}));
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}
	}
}
