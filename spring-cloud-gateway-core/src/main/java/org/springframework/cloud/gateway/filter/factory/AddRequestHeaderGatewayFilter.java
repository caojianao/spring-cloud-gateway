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

import java.util.Arrays;
import java.util.List;

import javax.validation.constraints.NotEmpty;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * @author Spencer Gibb
 */
@Validated
public class AddRequestHeaderGatewayFilter implements GatewayFilter {
    @NotEmpty
    private String name;
    @NotEmpty
    private String value;

    public String getName() {
        return name;
    }

    public AddRequestHeaderGatewayFilter setName(String name) {
        this.name = name;
        return this;
    }

    public String getValue() {
        return value;
    }

    public AddRequestHeaderGatewayFilter setValue(String value) {
        this.value = value;
        return this;
    }

    @Override
    public List<String> argNames() {
        return Arrays.asList(NAME_KEY, VALUE_KEY);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(name, value)
                .build();

        return chain.filter(exchange.mutate().request(request).build());
    }
}
