/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.cleanup.internal.content.search;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.cleanup.internal.datastore.search.criteria.AssetCleanupEvaluator;
import org.sonatype.nexus.cleanup.internal.datastore.search.criteria.ComponentCleanupEvaluator;
import org.sonatype.nexus.cleanup.storage.CleanupPolicy;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.entity.Continuations;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.extdirect.model.PagedResponse;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.store.InternalIds;
import org.sonatype.nexus.repository.query.QueryOptions;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @since 3.38
 */
@Named
@Singleton
public class DataStoreCleanupComponentBrowse
    extends ComponentSupport
    implements CleanupComponentBrowse
{
  private final Map<String, AssetCleanupEvaluator> assetCriteria;

  private final Map<String, ComponentCleanupEvaluator> componentCriteria;

  @Inject
  public DataStoreCleanupComponentBrowse(
      final Map<String, ComponentCleanupEvaluator> componentCriteria,
      final Map<String, AssetCleanupEvaluator> assetCriteria)
  {
    this.componentCriteria = checkNotNull(componentCriteria);
    this.assetCriteria = checkNotNull(assetCriteria);
  }

  @Override
  public Stream<EntityId> browse(final CleanupPolicy policy, final Repository repository) {
    checkNotNull(policy);
    checkNotNull(repository);

    return Continuations.streamOf(browseComponentsFn(repository))
      .filter(createComponentFilter(repository, policy))
      .map(InternalIds::internalComponentId)
      .map(InternalIds::toExternalId);
  }

  @Override
  public PagedResponse<Component> browseByPage(
      final CleanupPolicy policy,
      final Repository repository,
      final QueryOptions options)
  {
    checkNotNull(policy);
    checkNotNull(repository);
    checkNotNull(options);
    checkNotNull(options.getStart());
    checkNotNull(options.getLimit());

    Predicate<FluentComponent> componentFilter = createComponentFilter(repository, policy);

    Optional<Predicate<FluentComponent>> optionsFilter = createOptionsFilter(options);
    if (optionsFilter.isPresent()) {
      componentFilter = componentFilter.and(optionsFilter.get());
    }

    List<Component> result =
    Continuations.streamOf(browseComponentsFn(repository), Continuations.BROWSE_LIMIT, options.getLastId())
        .filter(componentFilter)
        .limit(options.getLimit())
        .map(Component.class::cast)
        .collect(Collectors.toList());

    // We return -1 as we don't have an inexpensive way of computing the total number of matching results
    return new PagedResponse<>(-1, result);
  }

  /**
   * Creates a Predicate that will return true if any of the Component's name/group/version
   * matches the provided filter.
   */
  private Optional<Predicate<FluentComponent>> createOptionsFilter(final QueryOptions options) {
    String filter = options.getFilter();
    if (filter != null && filter.trim().length() > 0) {
      Predicate<FluentComponent> optionsFilter = (component) ->
          component.name().contains(filter) ||
          component.namespace().contains(filter) ||
          component.version().contains(filter);
      return Optional.of(optionsFilter);
    }
    else {
      return Optional.empty();
    }
  }

  /*
   * Creates a Predicate which will return true if the Component and any of its Assets match all of the specified
   * cleanup criteria.
   */
  private Predicate<FluentComponent> createComponentFilter(final Repository repository, final CleanupPolicy policy) {
    validateCleanupPolicy(policy);

    List<BiPredicate<Component, Iterable<Asset>>> componentFilters = policy.getCriteria().entrySet().stream()
        .filter(entry -> componentCriteria.containsKey(entry.getKey()))
        .map(entry -> componentCriteria.get(entry.getKey()).getPredicate(repository, entry.getValue()))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    componentFilters.add(createAssetFilter(repository, policy));

    return component -> {
      Iterable<Asset> assets = component.assets().stream()
          .map(Asset.class::cast)
          .collect(Collectors.toList());

      return componentFilters.stream()
          .allMatch(fn -> fn.test(component, assets));
    };
  }

  /*
   * Creates a BiFunction which combines the asset-specific criteria of a cleanup-policy which will return true if any
   * individual Asset matches all (asset) criteria of the policy.
   *
   * Note: the Component argument of the BiFunction is ignored and included only to expose the same API as the Component
   * filter.
   */
  private BiPredicate<Component, Iterable<Asset>> createAssetFilter(
      final Repository repository,
      final CleanupPolicy policy)
  {
    List<Predicate<Asset>> filters = policy.getCriteria().entrySet().stream()
        .map(entry -> {
          if (!assetCriteria.containsKey(entry.getKey())) {
            return null;
          }
          return assetCriteria.get(entry.getKey()).getPredicate(repository, entry.getValue());
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    return (component, assets) -> StreamSupport.stream(assets.spliterator(), false)
        .anyMatch(asset -> filters.stream()
            .allMatch(p -> p.test(asset)));
  }

  /*
   * Checks the cleanup policy configuration to ensure that all criteria are known to the system.
   */
  private void validateCleanupPolicy(final CleanupPolicy policy) {
    String missingCriteria = policy.getCriteria().keySet().stream()
        .filter(((Predicate<String>) componentCriteria::containsKey).negate())
        .filter(((Predicate<String>) assetCriteria::containsKey).negate())
        .collect(Collectors.joining(", "));

    if (!missingCriteria.isEmpty()) {
      log.error("Failed to create filters for cleanup criteria(s): {}", missingCriteria);
      log.debug("Known criteria are for components: {} and assets: {} ", componentCriteria.keySet(),
          assetCriteria.keySet());
      throw new UnsupportedOperationException("Criteria of type(s) [" + missingCriteria + "] not supported");
    }
  }

  private static BiFunction<Integer, String, Continuation<FluentComponent>> browseComponentsFn(
      final Repository repository)
  {
    return repository.facet(ContentFacet.class).components()::browse;
  }
}
