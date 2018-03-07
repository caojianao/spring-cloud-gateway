/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.cloud.gateway.filter.factory;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.tuple.Tuple;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixObservableCommand;
import com.netflix.hystrix.HystrixObservableCommand.Setter;
import com.netflix.hystrix.exception.HystrixRuntimeException;

import static com.netflix.hystrix.exception.HystrixRuntimeException.FailureType.TIMEOUT;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.containsEncodedQuery;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setResponseStatus;

import reactor.core.publisher.Mono;
import rx.Observable;
import rx.RxReactiveStreams;
import rx.Subscription;

/**
 * @author Spencer Gibb
 */
public class HystrixGatewayFilter implements GatewayFilter {

	public static final String FALLBACK_URI = "fallbackUri";

	private final DispatcherHandler dispatcherHandler;

	private String name;
	private String fallbackUri;
	private Setter setter;
	private URI fallback;

	public HystrixGatewayFilter(DispatcherHandler dispatcherHandler) {
		this.dispatcherHandler = dispatcherHandler;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getFallbackUri() {
		return fallbackUri;
	}

	public HystrixGatewayFilter setFallbackUri(String fallbackUri) {
		this.fallbackUri = fallbackUri;
		return this;
	}

	public HystrixGatewayFilter setSetter(Setter setter) {
		this.setter = setter;
		return this;
	}

	@Override
	public void afterConfigurationSet() {
		//TODO: if no name is supplied, generate one from command id (useful for default filter)

		if (this.fallbackUri != null) {
			fallback = URI.create(fallbackUri);
			if (!"forward".equals(fallback.getScheme())) {
				throw new IllegalArgumentException("Hystrix Filter currently only supports 'forward' URIs, found " + fallbackUri);
			}
		}

		if (setter == null) {
			HystrixCommandGroupKey groupKey = HystrixCommandGroupKey.Factory.asKey(getClass().getSimpleName());
			HystrixCommandKey commandKey = HystrixCommandKey.Factory.asKey(name);

			setter = Setter.withGroupKey(groupKey)
					.andCommandKey(commandKey);
		}

	}

	@Override
	public List<String> argNames() {
		return Arrays.asList(NAME_KEY);
	}

	@Override
	public boolean validateArgs() {
		return false;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        RouteHystrixCommand command = new RouteHystrixCommand(setter, fallback, exchange, chain);

        return Mono.create(s -> {
            Subscription sub = command.toObservable().subscribe(s::success, s::error, s::success);
            s.onCancel(sub::unsubscribe);
        }).onErrorResume((Function<Throwable, Mono<Void>>) throwable -> {
            if (throwable instanceof HystrixRuntimeException) {
                HystrixRuntimeException e = (HystrixRuntimeException) throwable;
                if (e.getFailureType() == TIMEOUT) { //TODO: optionally set status
                    setResponseStatus(exchange, HttpStatus.GATEWAY_TIMEOUT);
                    return exchange.getResponse().setComplete();
                }
            }
            return Mono.empty();
        }).then();
	}

	//TODO: replace with HystrixMonoCommand that we write
	private class RouteHystrixCommand extends HystrixObservableCommand<Void> {

		private final URI fallbackUri;
		private final ServerWebExchange exchange;
		private final GatewayFilterChain chain;

		RouteHystrixCommand(Setter setter, URI fallbackUri, ServerWebExchange exchange, GatewayFilterChain chain) {
			super(setter);
			this.fallbackUri = fallbackUri;
			this.exchange = exchange;
			this.chain = chain;
		}

		@Override
		protected Observable<Void> construct() {
			return RxReactiveStreams.toObservable(this.chain.filter(exchange));
		}

		@Override
		protected Observable<Void> resumeWithFallback() {
			if (this.fallbackUri == null) {
				return super.resumeWithFallback();
			}

			//TODO: copied from RouteToRequestUrlFilter
			URI uri = exchange.getRequest().getURI();
			//TODO: assume always?
			boolean encoded = containsEncodedQuery(uri);
			URI requestUrl = UriComponentsBuilder.fromUri(uri)
					.host(null)
					.port(null)
					.uri(this.fallbackUri)
					.build(encoded)
					.toUri();
			exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, requestUrl);

			ServerHttpRequest request = this.exchange.getRequest().mutate().uri(requestUrl).build();
			ServerWebExchange mutated = exchange.mutate().request(request).build();
			return RxReactiveStreams.toObservable(HystrixGatewayFilter.this.dispatcherHandler.handle(mutated));
		}
	}
}
