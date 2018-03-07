/*
 * Copyright 2013-2018 the original author or authors.
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
 */

package org.springframework.cloud.gateway.route.builder;

import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.function.Consumer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AddRequestHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.AddRequestParameterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.AddResponseHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.HystrixGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.PrefixPathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.PreserveHostHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RedirectToGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RemoveRequestHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RemoveResponseHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SaveSessionGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SecureHeadersGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SetPathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SetRequestHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SetResponseHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SetStatusGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.tuple.Tuple;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.tuple.TupleBuilder.tuple;

import reactor.retry.Repeat;

public class GatewayFilterSpec extends UriSpec {

	private static final Log log = LogFactory.getLog(GatewayFilterSpec.class);

	static final Tuple EMPTY_TUPLE = tuple().build();

	public GatewayFilterSpec(Route.Builder routeBuilder, RouteLocatorBuilder.Builder builder) {
		super(routeBuilder, builder);
	}
	
	public GatewayFilterSpec initialize(GatewayFilter gatewayFilter) {
		gatewayFilter.afterConfigurationSet();
		return filter(gatewayFilter);
	}

	public GatewayFilterSpec filter(GatewayFilter gatewayFilter) {
		if (gatewayFilter instanceof Ordered) {
			this.routeBuilder.filter(gatewayFilter);
			return this;
		}
		return this.filter(gatewayFilter, 0);
	}

	public GatewayFilterSpec filter(GatewayFilter gatewayFilter, int order) {
		if (gatewayFilter instanceof Ordered) {
			this.routeBuilder.filter(gatewayFilter);
			log.warn("GatewayFilter already implements ordered "+gatewayFilter.getClass()
					+ "ignoring order parameter: "+order);
			return this;
		}
		this.routeBuilder.filter(new OrderedGatewayFilter(gatewayFilter, order));
		return this;
	}

	public GatewayFilterSpec filters(GatewayFilter... gatewayFilters) {
		this.routeBuilder.filters(gatewayFilters);
		return this;
	}

	public GatewayFilterSpec filters(Collection<GatewayFilter> gatewayFilters) {
		this.routeBuilder.filters(gatewayFilters);
		return this;
	}

	public GatewayFilterSpec addRequestHeader(String headerName, String headerValue) {
		return initialize(getBean(AddRequestHeaderGatewayFilterFactory.class)
				.apply(c -> c.setName(headerName).setValue(headerValue)));
	}

	public GatewayFilterSpec addRequestParameter(String param, String value) {
		return initialize(getBean(AddRequestParameterGatewayFilterFactory.class)
				.apply(c -> c.setName(param).setValue(value)));
	}

	public GatewayFilterSpec addResponseHeader(String headerName, String headerValue) {
		return initialize(getBean(AddResponseHeaderGatewayFilterFactory.class)
				.apply(c -> c.setName(headerName).setValue(headerValue)));
	}

	public GatewayFilterSpec hystrix(Consumer<HystrixGatewayFilterFactory.Config> configConsumer) {
		return initialize(getBean(HystrixGatewayFilterFactory.class)
				.apply(configConsumer));
	}

	public GatewayFilterSpec prefixPath(String prefix) {
		return initialize(getBean(PrefixPathGatewayFilterFactory.class).apply(prefix));
	}

	public GatewayFilterSpec preserveHostHeader() {
		return initialize(getBean(PreserveHostHeaderGatewayFilterFactory.class).apply());
	}

	public GatewayFilterSpec redirect(int status, URI url) {
		return redirect(String.valueOf(status), url.toString());
	}

	public GatewayFilterSpec redirect(int status, String url) {
		return redirect(String.valueOf(status), url);
	}

	public GatewayFilterSpec redirect(String status, URI url) {
		return redirect(status, url.toString());
	}

	public GatewayFilterSpec redirect(String status, String url) {
		return initialize(getBean(RedirectToGatewayFilterFactory.class).apply(status, url));
	}

	public GatewayFilterSpec redirect(HttpStatus status, URL url) {
		return initialize(getBean(RedirectToGatewayFilterFactory.class).apply(status, url));
	}

	public GatewayFilterSpec removeRequestHeader(String headerName) {
		return initialize(getBean(RemoveRequestHeaderGatewayFilterFactory.class).apply(headerName));
	}

	public GatewayFilterSpec removeResponseHeader(String headerName) {
		return initialize(getBean(RemoveResponseHeaderGatewayFilterFactory.class).apply(headerName));
	}

	// public GatewayFilterSpec requestRateLimiter() {
	// 	return initialize(getBean(RequestRateLimiterGatewayFilterFactory.class).apply(config -> {}));
	// }

    public RequestRateLimiterSpec requestRateLimiter() {
		return new RequestRateLimiterSpec(getBean(RequestRateLimiterGatewayFilterFactory.class));
	}

	public class RequestRateLimiterSpec {
		private final RequestRateLimiterGatewayFilterFactory filter;

		public RequestRateLimiterSpec(RequestRateLimiterGatewayFilterFactory filter) {
			this.filter = filter;
		}

		public <C, R extends RateLimiter<C>> RequestRateLimiterSpec rateLimiter(Class<R> rateLimiterType,
																				Consumer<C> configConsumer) {
			R rateLimiter = getBean(rateLimiterType);
			C config = rateLimiter.newConfig();
			configConsumer.accept(config);
			rateLimiter.getConfig().put(routeBuilder.getId(), config);
			return this;
		}

		public GatewayFilterSpec configure(Consumer<RequestRateLimiterGatewayFilterFactory.Config> configConsumer) {
			initialize(this.filter.apply(configConsumer));
			return GatewayFilterSpec.this;
		}

		// useful when nothing to configure
		public GatewayFilterSpec and() {
			return configure(config -> {});
		}

	}

	public GatewayFilterSpec rewritePath(String regex, String replacement) {
		return initialize(getBean(RewritePathGatewayFilterFactory.class).apply(regex, replacement));
	}

	/**
	 * 5xx errors and GET are retryable
	 * @param retries max number of retries
	 */
	public GatewayFilterSpec retry(int retries) {
		return initialize(getBean(RetryGatewayFilterFactory.class)
				.apply(new RetryGatewayFilterFactory.Retry()
						.retries(retries)));
	}

	/**
	 * @param retries max number of retries
	 * @param httpStatusSeries the http status series that is retryable
	 * @param httpMethod the http method that is retryable
	 */
	public GatewayFilterSpec retry(int retries, HttpStatus.Series httpStatusSeries, HttpMethod httpMethod) {
		return retry(new RetryGatewayFilterFactory.Retry()
						.retries(retries)
						.series(httpStatusSeries)
						.methods(httpMethod));
	}

	public GatewayFilterSpec retry(RetryGatewayFilterFactory.Retry retry) {
		return initialize(getBean(RetryGatewayFilterFactory.class).apply(retry));
	}

	public GatewayFilterSpec retry(Repeat<ServerWebExchange> repeat) {
		return initialize(getBean(RetryGatewayFilterFactory.class).apply(repeat));
	}

	public GatewayFilterSpec secureHeaders() {
		return initialize(getBean(SecureHeadersGatewayFilterFactory.class).apply(EMPTY_TUPLE));
	}

	public GatewayFilterSpec setPath(String template) {
		return initialize(getBean(SetPathGatewayFilterFactory.class).apply(template));
	}

	public GatewayFilterSpec setRequestHeader(String headerName, String headerValue) {
		return initialize(getBean(SetRequestHeaderGatewayFilterFactory.class).apply(headerName, headerValue));
	}

	public GatewayFilterSpec setResponseHeader(String headerName, String headerValue) {
		return initialize(getBean(SetResponseHeaderGatewayFilterFactory.class).apply(headerName, headerValue));
	}

	public GatewayFilterSpec setStatus(int status) {
		return setStatus(String.valueOf(status));
	}

	public GatewayFilterSpec setStatus(String status) {
		return initialize(getBean(SetStatusGatewayFilterFactory.class).apply(status));
	}

	public GatewayFilterSpec setStatus(HttpStatus status) {
		return initialize(getBean(SetStatusGatewayFilterFactory.class).apply(status));
	}

	public GatewayFilterSpec saveSession() {
		return initialize(getBean(SaveSessionGatewayFilterFactory.class).apply(EMPTY_TUPLE));
	}

	public GatewayFilterSpec stripPrefix(int parts) {
		return initialize(getBean(StripPrefixGatewayFilterFactory.class).apply(parts));
	}

	private String routeId() {
		return routeBuilder.getId();
	}
}
