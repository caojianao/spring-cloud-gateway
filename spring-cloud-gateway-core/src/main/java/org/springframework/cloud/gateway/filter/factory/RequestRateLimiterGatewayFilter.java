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

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * User Request Rate Limiter filter. See https://stripe.com/blog/rate-limiters and
 */
public class RequestRateLimiterGatewayFilter implements GatewayFilter {

	public static final String KEY_RESOLVER_KEY = "keyResolver";

	private final RateLimiter defaultRateLimiter;
	private final KeyResolver defaultKeyResolver;
	private KeyResolver keyResolver;
	private RateLimiter rateLimiter;

	public RequestRateLimiterGatewayFilter(RateLimiter defaultRateLimiter,
										   KeyResolver defaultKeyResolver) {
		this.defaultRateLimiter = defaultRateLimiter;
		this.defaultKeyResolver = defaultKeyResolver;
		this.keyResolver = defaultKeyResolver;
		this.rateLimiter = defaultRateLimiter;
	}

	public KeyResolver getDefaultKeyResolver() {
		return defaultKeyResolver;
	}

	public KeyResolver getKeyResolver() {
		return keyResolver;
	}

	public RequestRateLimiterGatewayFilter setKeyResolver(KeyResolver keyResolver) {
        this.keyResolver = keyResolver;
		return this;
	}

	public RateLimiter getDefaultRateLimiter() {
		return defaultRateLimiter;
	}

	public RateLimiter getRateLimiter() {
		return rateLimiter;
	}

	public RequestRateLimiterGatewayFilter setRateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
		return this;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);

		KeyResolver resolver = (this.keyResolver == null) ? defaultKeyResolver : keyResolver;
		RateLimiter<Object> limiter = (this.rateLimiter == null) ? defaultRateLimiter : rateLimiter;

		return resolver.resolve(exchange).flatMap(key ->
		// TODO: if key is empty?
		limiter.isAllowed(route.getId(), key).flatMap(response -> {
			// TODO: set some headers for rate, tokens left

			if (response.isAllowed()) {
				return chain.filter(exchange);
			}
			exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
			return exchange.getResponse().setComplete();
		}));
	}

}
