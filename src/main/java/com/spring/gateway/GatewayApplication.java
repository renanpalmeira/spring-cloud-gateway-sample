package com.spring.gateway;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.Map;

import io.netty.buffer.ByteBufAllocator;
import io.vavr.control.Either;
import io.vavr.control.Try;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;

import org.reactivestreams.Publisher;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
//import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;
//import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory.ResponseAdapter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.RewriteFunction;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.cloud.gateway.support.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.support.DefaultClientResponse;
import org.springframework.cloud.gateway.support.DefaultServerRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ClientHttpResponse;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.filter.reactive.HiddenHttpMethodFilter;
import org.springframework.web.reactive.function.BodyExtractor;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class React {

	private React() {
		throw new IllegalStateException("Utility class");
	}

	public static <T> Mono<T> hydrateMonoWithFallback(Mono<T> mono, T fallback) {
		return mono
				.switchIfEmpty(Mono.just(fallback))
				.onErrorReturn(fallback);
	}

	public static Either<Throwable, Mono<Void>> mutateResponseToJson(ServerWebExchange exchange,
																	 Map<String, String> json) {

		return Try.of(() -> {
			byte[] bytes = new ObjectMapper().writeValueAsString(json).getBytes(StandardCharsets.UTF_8);
			DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);

			ServerHttpResponse response = exchange.getResponse();
			response.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_UTF8_VALUE);
			response.getHeaders().set(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length));
			return response.writeWith(Flux.just(buffer));
		}).toEither();
	}

}

class ResponseAdapter implements ClientHttpResponse {
	private final Flux<DataBuffer> flux;
	private final HttpHeaders headers;

	public ResponseAdapter(Publisher<? extends DataBuffer> body, HttpHeaders headers) {
		this.headers = headers;
		if (body instanceof Flux) {
			this.flux = (Flux)body;
		} else {
			this.flux = ((Mono)body).flux();
		}

	}

	public Flux<DataBuffer> getBody() {
		return this.flux;
	}

	public HttpHeaders getHeaders() {
		return this.headers;
	}

	public HttpStatus getStatusCode() {
		return null;
	}

	public int getRawStatusCode() {
		return 0;
	}

	public MultiValueMap<String, ResponseCookie> getCookies() {
		return null;
	}
}

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
//						.filters(f -> f.modifyRequestBody(String.class, Void.class,
//								(exchange, map) -> {
//									exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
//
//									return exchange.getResponse().setComplete();
//								}))
						.uri("http://httpbin.org:80"))
				.build();
	}

	@Component
	public static class SampleFilter implements GlobalFilter, Ordered {

		@Override
		public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
			ServerHttpRequest request = exchange.getRequest();
			String bodyString = getRequestBody(request);
			Map<String, String> split = Splitter.on("&").withKeyValueSeparator("=").split(bodyString);

			System.out.println("split = " + split);

			DataBuffer bodyDataBuffer = stringBuffer(Joiner.on("&").withKeyValueSeparator("=").join(split));
			int len = bodyDataBuffer.readableByteCount();
			URI requestUri = request.getURI();
			URI ex = UriComponentsBuilder.fromUri(requestUri).build(true).toUri();
			ServerHttpRequest newRequest = request.mutate().uri(ex).build();

			HttpHeaders myHeaders = new HttpHeaders();
			copyMultiValueMap(request.getHeaders(), myHeaders);
			myHeaders.remove(HttpHeaders.CONTENT_LENGTH);
			myHeaders.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(len));

			Flux<DataBuffer> bodyFlux = Flux.just(bodyDataBuffer);
			newRequest = new ServerHttpRequestDecorator(newRequest) {
				@Override
				public Flux<DataBuffer> getBody() {
					return bodyFlux;
				}

				@Override
				public HttpHeaders getHeaders() {
					return myHeaders;
				}
			};

			ServerWebExchange newExchange = exchange.mutate().request(newRequest).build();

			if (split.containsKey("key")) {
				return chain.filter(newExchange);
			}

			newExchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);

			Map<String, String> json =
					ImmutableMap.of("error", "lock.");

			return React.mutateResponseToJson(exchange, json).fold(
					fail -> exchange.getResponse().setComplete(),
					success -> success
			);

			//return chain.filter(exchange);
			//return returnMono(chain, exchange);
		}

		private static <K, V> void copyMultiValueMap(MultiValueMap<K,V> source, MultiValueMap<K,V> target) {
			source.forEach((key, value) -> target.put(key, new LinkedList<>(value)));
		}

		private DataBuffer stringBuffer(String value){
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			NettyDataBufferFactory nettyDataBufferFactory = new
					NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
			DataBuffer buffer = nettyDataBufferFactory.allocateBuffer(bytes.length);
			buffer.write(bytes);
			return buffer;
		}



		private String getRequestBody(ServerHttpRequest request) {
			Flux<DataBuffer> body = request.getBody();
			StringBuilder sb = new StringBuilder();

			body.subscribe(buffer -> {
				byte[] bytes = new byte[buffer.readableByteCount()];
				buffer.read(bytes);
				DataBufferUtils.release(buffer);
				String bodyString = new String(bytes, StandardCharsets.UTF_8);
				sb.append(bodyString);
			});
			String str = sb.toString();

			return str;
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}
	}
}
