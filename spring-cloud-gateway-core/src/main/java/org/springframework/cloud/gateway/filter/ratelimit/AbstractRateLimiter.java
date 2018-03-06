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
 *
 */

package org.springframework.cloud.gateway.filter.ratelimit;

import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.support.ConfigurationUtils;
import org.springframework.context.ApplicationListener;
import org.springframework.validation.Validator;

import java.util.HashMap;
import java.util.Map;

public abstract class AbstractRateLimiter<C> implements RateLimiter<C>, ApplicationListener<FilterArgsEvent> {
	private Map<String, C> config = new HashMap<>();
	private String configurationPropertyName;
	private final Validator validator;

	protected AbstractRateLimiter(String configurationPropertyName, Validator validator) {
		this.configurationPropertyName = configurationPropertyName;
		this.validator = validator;
	}

	protected String getConfigurationPropertyName() {
		return configurationPropertyName;
	}

	protected Validator getValidator() {
		return validator;
	}

	@Override
	public Map<String, C> getConfig() {
		return this.config;
	}

	@Override
	public void onApplicationEvent(FilterArgsEvent event) {
		Map<String, Object> args = event.getArgs();

		if (args.isEmpty() || !hasRelevantKey(args)) {
			return;
		}

		String routeId = event.getRouteId();
		C routeConfig = newConfig();
		ConfigurationUtils.bind(routeConfig, args,
				configurationPropertyName, configurationPropertyName, validator);
		this.config.put(routeId, routeConfig);
	}

	private boolean hasRelevantKey(Map<String, Object> args) {
		return args.keySet().stream()
				.anyMatch(key -> key.startsWith(configurationPropertyName + "."));
	}

	@Override
	public String toString() {
		return getClass().getSimpleName()+"{" +
				"config=" + config +
				", configurationPropertyName='" + configurationPropertyName + '\'' +
				'}';
	}
}
