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

package org.springframework.cloud.gateway.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicate;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.support.ArgumentHints;
import org.springframework.cloud.gateway.support.ConfigurationUtils;
import org.springframework.cloud.gateway.support.NameUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.expression.Expression;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.tuple.Tuple;
import org.springframework.tuple.TupleBuilder;
import org.springframework.validation.Validator;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;

/**
 * {@link RouteLocator} that loads routes from a {@link RouteDefinitionLocator}
 * @author Spencer Gibb
 */
public class RouteDefinitionRouteLocator implements RouteLocator, BeanFactoryAware, ApplicationEventPublisherAware {
	protected final Log logger = LogFactory.getLog(getClass());

	private final RouteDefinitionLocator routeDefinitionLocator;
	@Deprecated
	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();
	private final Map<String, Class<? extends RoutePredicate>> routePredicates = new HashMap<>();
	@Deprecated
	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();
	private final Map<String, Class<? extends GatewayFilter>> gatewayFilters = new HashMap<>();
	private final GatewayProperties gatewayProperties;
	private final SpelExpressionParser parser = new SpelExpressionParser();
	private BeanFactory beanFactory;
	private ApplicationEventPublisher publisher;

	public RouteDefinitionRouteLocator(RouteDefinitionLocator routeDefinitionLocator,
									   List<RoutePredicateFactory> predicates,
									   List<GatewayFilterFactory> gatewayFilterFactories,
									   GatewayProperties gatewayProperties) {
		this.routeDefinitionLocator = routeDefinitionLocator;
		initFactories(predicates);
		gatewayFilterFactories.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));
		this.gatewayProperties = gatewayProperties;
	}

	@Autowired
	private Validator validator;

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	private void initFactories(List<RoutePredicateFactory> predicates) {
		predicates.forEach(factory -> {
			String key = factory.name();
			if (this.predicates.containsKey(key)) {
				this.logger.warn("A RoutePredicateFactory named "+ key
						+ " already exists, class: " + this.predicates.get(key)
						+ ". It will be overwritten.");
			}
			this.predicates.put(key, factory);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded RoutePredicateFactory [" + key + "]");
			}
		});
	}

	@SuppressWarnings("unchecked")
	public void setGatewayFilterClasses(List<Class> classes) {
		classes.forEach(clazz -> {
			String name = NameUtils.normalizeFilterName(clazz);
			if (this.gatewayFilters.containsKey(name)) {
				this.logger.warn("A GatewayFilter named "+ name
						+ " already exists, class: " + this.gatewayFilters.get(name)
						+ ". It will be overwritten.");
			}
			this.gatewayFilters.put(name, (Class<GatewayFilter>)clazz);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded GatewayFilter [" + name + "]");
			}
		});
	}

	@SuppressWarnings("unchecked")
	public void setRoutePredicateClasses(List<Class> classes) {
		classes.forEach(clazz -> {
			String name = NameUtils.normalizePredicateName(clazz);
			if (this.routePredicates.containsKey(name)) {
				this.logger.warn("A RoutePredicate named "+ name
						+ " already exists, class: " + this.routePredicates.get(name)
						+ ". It will be overwritten.");
			}
			this.routePredicates.put(name, (Class<RoutePredicate>)clazz);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded RoutePredicate [" + name + "]");
			}
		});
	}

	@Override
	public Flux<Route> getRoutes() {
		return this.routeDefinitionLocator.getRouteDefinitions()
				.map(this::convertToRoute)
				//TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition matched: " + route.getId());
					}
					return route;
				});


		/* TODO: trace logging
			if (logger.isTraceEnabled()) {
				logger.trace("RouteDefinition did not match: " + routeDefinition.getId());
			}*/
	}

	private Route convertToRoute(RouteDefinition routeDefinition) {
		Predicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);

		return Route.builder(routeDefinition)
				.predicate(predicate)
				.replaceFilters(gatewayFilters)
				.build();
	}

	private List<GatewayFilter> loadGatewayFilters(String id, List<FilterDefinition> filterDefinitions) {
		List<GatewayFilter> filters = filterDefinitions.stream()
				.map(definition -> {
					GatewayFilterFactory factory = this.gatewayFilterFactories.get(definition.getName());
					GatewayFilter gatewayFilter = null;
					if (factory == null) {
						Class filterClass = this.gatewayFilters.get(definition.getName());
						if (filterClass == null) {
							throw new IllegalArgumentException("Unable to find GatewayFilterFactory or GatewayFilter with name " + definition.getName());
						}
						gatewayFilter = GatewayFilter.class.cast(this.beanFactory.getBean(filterClass));
					}
					Map<String, String> args = definition.getArgs();
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition " + id + " applying filter " + args + " to " + definition.getName());
					}

					if (gatewayFilter == null) {
						Tuple tuple = getTuple(factory, args, this.parser, this.beanFactory);
						gatewayFilter = factory.apply(tuple);
					} else {
                        Map<String, Object> properties = getMap(gatewayFilter, args, this.parser, this.beanFactory);
						ConfigurationUtils.bind(gatewayFilter, properties,
								"", definition.getName(), validator);
                        if (this.publisher != null) {
                        	this.publisher.publishEvent(new FilterArgsEvent(this, id, properties));
						}
					}
					return gatewayFilter;
				})
				.collect(Collectors.toList());

		ArrayList<GatewayFilter> ordered = new ArrayList<>(filters.size());
		for (int i = 0; i < filters.size(); i++) {
			ordered.add(new OrderedGatewayFilter(filters.get(i), i+1));
		}

		return ordered;
	}

	@SuppressWarnings("Duplicates")
	@Deprecated
	//TODO: make argument resolving a strategy
	/* for testing */ static Tuple getTuple(ArgumentHints hasArguments, Map<String, String> args, SpelExpressionParser parser, BeanFactory beanFactory) {
		TupleBuilder builder = TupleBuilder.tuple();

		List<String> argNames = hasArguments.argNames();
		if (!argNames.isEmpty()) {
			// ensure size is the same for key replacement later
			if (hasArguments.validateArgs() && args.size() != argNames.size()) {
				throw new IllegalArgumentException("Wrong number of arguments. Expected " + argNames
						+ " " + argNames + ". Found " + args.size() + " " + args + "'");
			}
		}

		int entryIdx = 0;
		for (Map.Entry<String, String> entry : args.entrySet()) {
			String key = normalizeKey(entry.getKey(), entryIdx, hasArguments, args);
			Object value = getValue(parser, beanFactory, entry.getValue());

			builder.put(key, value);
			entryIdx++;
		}

		Tuple tuple = builder.build();

		if (hasArguments.validateArgs()) {
			for (String name : argNames) {
				if (!tuple.hasFieldName(name)) {
					throw new IllegalArgumentException("Missing argument '" + name + "'. Given " + tuple);
				}
			}
		}
		return tuple;
	}

	private static Object getValue(SpelExpressionParser parser, BeanFactory beanFactory, String entryValue) {
		Object value;
		String rawValue = entryValue;
		if (rawValue != null) {
            rawValue = rawValue.trim();
        }
		if (rawValue != null && rawValue.startsWith("#{") && entryValue.endsWith("}")) {
            // assume it's spel
            StandardEvaluationContext context = new StandardEvaluationContext();
            context.setBeanResolver(new BeanFactoryResolver(beanFactory));
            Expression expression = parser.parseExpression(entryValue, new TemplateParserContext());
            value = expression.getValue(context);
        } else {
            value = entryValue;
        }
		return value;
	}

	@SuppressWarnings("Duplicates")
	/* for testing */ static Map<String, Object> getMap(ArgumentHints hasArguments, Map<String, String> args, SpelExpressionParser parser, BeanFactory beanFactory) {
		Map<String, Object> map = new HashMap<>();

		int entryIdx = 0;
		for (Map.Entry<String, String> entry : args.entrySet()) {
			String key = normalizeKey(entry.getKey(), entryIdx, hasArguments, args);
			Object value = getValue(parser, beanFactory, entry.getValue());

			map.put(key, value);
			entryIdx++;
		}

		return map;
	}

	private static String normalizeKey(String key, int entryIdx, ArgumentHints argHints, Map<String, String> args) {
		// RoutePredicateFactory has name hints and this has a fake key name
		// replace with the matching key hint
		if (key.startsWith(NameUtils.GENERATED_NAME_PREFIX) && !argHints.argNames().isEmpty()
                && entryIdx < args.size() && entryIdx < argHints.argNames().size()) {
            key = argHints.argNames().get(entryIdx);
        }
		return key;
	}

	private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
		List<GatewayFilter> filters = new ArrayList<>();

		//TODO: support option to apply defaults after route specific filters?
		if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters("defaultFilters",
					this.gatewayProperties.getDefaultFilters()));
		}

		if (!routeDefinition.getFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(routeDefinition.getId(), routeDefinition.getFilters()));
		}

		AnnotationAwareOrderComparator.sort(filters);
		return filters;
	}

	private Predicate<ServerWebExchange> combinePredicates(RouteDefinition routeDefinition) {
		List<PredicateDefinition> predicates = routeDefinition.getPredicates();
		Predicate<ServerWebExchange> predicate = lookup(routeDefinition, predicates.get(0));

		for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {
			Predicate<ServerWebExchange> found = lookup(routeDefinition, andPredicate);
			predicate = predicate.and(found);
		}

		return predicate;
	}

	private Predicate<ServerWebExchange> lookup(RouteDefinition routeDefinition, PredicateDefinition predicate) {
		RoutePredicateFactory predicateFactory = this.predicates.get(predicate.getName());
		RoutePredicate routePredicate = null;
		if (predicateFactory == null) {
			Class filterClass = this.routePredicates.get(predicate.getName());
			if (filterClass == null) {
				throw new IllegalArgumentException("Unable to find RoutePredicateFactory or RoutePredicate with name " + predicate.getName());
			}
			routePredicate = RoutePredicate.class.cast(this.beanFactory.getBean(filterClass));
		}
		Map<String, String> args = predicate.getArgs();
		if (logger.isDebugEnabled()) {
			logger.debug("RouteDefinition " + routeDefinition.getId() + " applying "
					+ args + " to " + predicate.getName());
		}

		if (routePredicate == null) {
			Tuple tuple = getTuple(predicateFactory, args, this.parser, this.beanFactory);
			routePredicate = exchange -> predicateFactory.apply(tuple).test(exchange);
		} else {
			Map<String, Object> properties = getMap(routePredicate, args, this.parser, this.beanFactory);
			ConfigurationUtils.bind(routePredicate, properties,
					"", predicate.getName(), validator);
			if (this.publisher != null) {
				this.publisher.publishEvent(new PredicateArgsEvent(this, routeDefinition.getId(), properties));
			}
		}

		return routePredicate;
	}
}
