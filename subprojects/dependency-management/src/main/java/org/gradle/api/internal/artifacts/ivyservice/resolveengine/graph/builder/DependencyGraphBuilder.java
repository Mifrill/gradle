/*
 * Copyright 2017 the original author or authors.
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
package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.internal.artifacts.ComponentSelectorConverter;
import org.gradle.api.internal.artifacts.ResolveContext;
import org.gradle.api.internal.artifacts.ResolvedVersionConstraint;
import org.gradle.api.internal.artifacts.dsl.ModuleReplacementsData;
import org.gradle.api.internal.artifacts.ivyservice.dependencysubstitution.DependencySubstitutionApplicator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ConflictResolverDetails;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.ModuleExclusions;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphSelector;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.CapabilitiesConflictHandler;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.DefaultCapabilitiesConflictHandler;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.DefaultConflictResolverDetails;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.ModuleConflictHandler;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.PotentialConflict;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.specs.Spec;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.model.DependencyMetadata;
import org.gradle.internal.id.IdGenerator;
import org.gradle.internal.id.LongIdGenerator;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver;
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver;
import org.gradle.internal.resolve.resolver.ResolveContextToComponentResolver;
import org.gradle.internal.resolve.result.ComponentIdResolveResult;
import org.gradle.internal.resolve.result.DefaultBuildableComponentResolveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DependencyGraphBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(DependencyGraphBuilder.class);
    private final ModuleConflictHandler moduleConflictHandler;
    private final Spec<? super DependencyMetadata> edgeFilter;
    private final ResolveContextToComponentResolver moduleResolver;
    private final DependencyToComponentIdResolver idResolver;
    private final ComponentMetaDataResolver metaDataResolver;
    private final AttributesSchemaInternal attributesSchema;
    private final ModuleExclusions moduleExclusions;
    private final BuildOperationExecutor buildOperationExecutor;
    private final ModuleReplacementsData moduleReplacementsData;
    private final ComponentSelectorConverter componentSelectorConverter;
    private final DependencySubstitutionApplicator dependencySubstitutionApplicator;
    private final ImmutableAttributesFactory attributesFactory;
    private final CapabilitiesConflictHandler capabilitiesConflictHandler;

    public DependencyGraphBuilder(DependencyToComponentIdResolver componentIdResolver, ComponentMetaDataResolver componentMetaDataResolver,
                                  ResolveContextToComponentResolver resolveContextToComponentResolver,
                                  ModuleConflictHandler moduleConflictHandler,
                                  CapabilitiesConflictHandler capabilitiesConflictHandler,
                                  Spec<? super DependencyMetadata> edgeFilter,
                                  AttributesSchemaInternal attributesSchema,
                                  ModuleExclusions moduleExclusions,
                                  BuildOperationExecutor buildOperationExecutor, ModuleReplacementsData moduleReplacementsData,
                                  DependencySubstitutionApplicator dependencySubstitutionApplicator, ComponentSelectorConverter componentSelectorConverter,
                                  ImmutableAttributesFactory attributesFactory) {
        this.idResolver = componentIdResolver;
        this.metaDataResolver = componentMetaDataResolver;
        this.moduleResolver = resolveContextToComponentResolver;
        this.moduleConflictHandler = moduleConflictHandler;
        this.edgeFilter = edgeFilter;
        this.attributesSchema = attributesSchema;
        this.moduleExclusions = moduleExclusions;
        this.buildOperationExecutor = buildOperationExecutor;
        this.moduleReplacementsData = moduleReplacementsData;
        this.dependencySubstitutionApplicator = dependencySubstitutionApplicator;
        this.componentSelectorConverter = componentSelectorConverter;
        this.attributesFactory = attributesFactory;
        this.capabilitiesConflictHandler = capabilitiesConflictHandler;
    }

    public void resolve(final ResolveContext resolveContext, final DependencyGraphVisitor modelVisitor) {

        IdGenerator<Long> idGenerator = new LongIdGenerator();
        DefaultBuildableComponentResolveResult rootModule = new DefaultBuildableComponentResolveResult();
        moduleResolver.resolve(resolveContext, rootModule);

        final ResolveState resolveState = new ResolveState(idGenerator, rootModule, resolveContext.getName(), idResolver, metaDataResolver, edgeFilter, attributesSchema, moduleExclusions, moduleReplacementsData, componentSelectorConverter, attributesFactory, dependencySubstitutionApplicator);
        moduleConflictHandler.registerResolver(new DirectDependencyForcingResolver(resolveState.getRoot().getComponent()));

        traverseGraph(resolveState);

        validateGraph(resolveState);

        resolveState.getRoot().getComponent().setRoot();

        assembleResult(resolveState, modelVisitor);

    }

    /**
     * Traverses the dependency graph, resolving conflicts and building the paths from the root configuration.
     */
    private void traverseGraph(final ResolveState resolveState) {
        resolveState.onMoreSelected(resolveState.getRoot());
        final List<EdgeState> dependencies = Lists.newArrayList();
        final Map<ModuleVersionIdentifier, ComponentIdentifier> componentIdentifierCache = Maps.newHashMap();

        final PendingDependenciesHandler pendingDependenciesHandler = new DefaultPendingDependenciesHandler();

        while (resolveState.peek() != null || moduleConflictHandler.hasConflicts() || capabilitiesConflictHandler.hasConflicts()) {
            if (resolveState.peek() != null) {
                final NodeState node = resolveState.pop();
                LOGGER.debug("Visiting configuration {}.", node);

                // Register capabilities for this node
                registerCapabilities(resolveState, node.getComponent());

                // Initialize and collect any new outgoing edges of this node
                dependencies.clear();
                node.visitOutgoingDependencies(dependencies, pendingDependenciesHandler);
                resolveEdges(node, dependencies, resolveState, componentIdentifierCache);
            } else {
                // We have some batched up conflicts. Resolve the first, and continue traversing the graph
                if (moduleConflictHandler.hasConflicts()) {
                    moduleConflictHandler.resolveNextConflict(resolveState.getReplaceSelectionWithConflictResultAction());
                } else {
                    capabilitiesConflictHandler.resolveNextConflict(resolveState.getReplaceSelectionWithConflictResultAction());
                }
            }

        }
    }

    private void registerCapabilities(final ResolveState resolveState, final ComponentState moduleRevision) {
        moduleRevision.forEachCapability(new Action<Capability>() {
            @Override
            public void execute(Capability capability) {
                // This is a performance optimization. Most modules do not declare capabilities. So, instead of systematically registering
                // an implicit capability for each module that we see, we only consider modules which _declare_ capabilities. If they do,
                // then we try to find a module which provides the same capability. It that module has been found, then we register it.
                // Otherwise, we have nothing to do. This avoids most of registrations.
                Collection<ComponentState> implicitProvidersForCapability = Collections.emptyList();
                for (ModuleResolveState state : resolveState.getModules()) {
                    if (state.getId().getGroup().equals(capability.getGroup()) && state.getId().getName().equals(capability.getName())) {
                        implicitProvidersForCapability = state.getVersions();
                        break;
                    }
                }
                PotentialConflict c = capabilitiesConflictHandler.registerCandidate(
                    DefaultCapabilitiesConflictHandler.candidate(moduleRevision, capability, implicitProvidersForCapability)
                );
                if (c.conflictExists()) {
                    c.withParticipatingModules(resolveState.getDeselectVersionAction());
                }
            }
        });
    }

    private void resolveEdges(final NodeState node,
                              final List<EdgeState> dependencies,
                              final ResolveState resolveState,
                              final Map<ModuleVersionIdentifier, ComponentIdentifier> componentIdentifierCache) {
        if (dependencies.isEmpty()) {
            return;
        }
        performSelectionSerially(dependencies, resolveState);
        maybeDownloadMetadataInParallel(node, componentIdentifierCache, dependencies);
        attachToTargetRevisionsSerially(dependencies);

    }

    private void performSelectionSerially(List<EdgeState> dependencies, ResolveState resolveState) {
        for (EdgeState dependency : dependencies) {
            assert dependency.getTargetComponent() == null;
            SelectorState selector = dependency.getSelector();

            performSelection(resolveState, dependency, selector);

            selector.getTargetModule().addUnattachedDependency(dependency);
        }
    }

    /**
     * Attempts to resolve a target `ComponentState` for the given dependency.
     * On successful resolve, a `ComponentState` is constructed for the identifier, recorded as {@link SelectorState#selected},
     * and added to the graph.
     * On resolve failure, the failure is recorded and no `ComponentState` is selected.
     */
    private void performSelection(ResolveState resolveState, EdgeState dependency, SelectorState selector) {
        // Selector already resolved: just attach the edge.
        if (selector.selected != null) {
            dependency.start(selector.selected);
            return;
        }

        ComponentIdResolveResult idResolveResult = selector.resolve();
        if (idResolveResult.getFailure() != null) {
            // Resolve failure, nothing more to do.
            return;
        }

        ComponentState candidate = resolveState.getRevision(idResolveResult.getId(), idResolveResult.getModuleVersionId(), idResolveResult.getMetadata());

        ModuleResolveState module = selector.getTargetModule();
        ComponentState currentSelection = module.getSelected();

        // TODO:DAZ Should not need to select the candidate if the current selection ends up being chosen.
        // But some of the logic to choose between currentSelection and candidate requires that these be set.
        dependency.start(candidate);
        selector.select(candidate);

        // If no current selection for module, just use the candidate.
        if (currentSelection == null) {
            // This is the first time we've seen the module, so register with conflict resolver.
            if (!moduleHasConflicts(resolveState, module)) {
                // No conflicting modules. Select it for now
                LOGGER.debug("Selecting new module {}", module.getId());
                module.select(candidate);
            }
            return;
        }

        // Choose the best option from the current selection and the new candidate.
        // This choice is made considering _all_ selectors registered for this module.
        ComponentState selected = chooseBest(module, selector, currentSelection, candidate);

        // If current selection is still the best choice, then only need to point new edge/selector at current selection.
        if (selected == currentSelection) {
            dependency.start(currentSelection);
            selector.select(currentSelection);

            // Since we have a new selector for the current selection, check if it's now rejected.
            // TODO:DAZ It's wasteful to recheck all of the existing selectors. Should only check the new one.
            maybeMarkRejected(currentSelection);
            return;
        }

        // New candidate is a preferred choice over current selection. Reset the module state and reselect.
        assert selected == candidate;
        resolveState.getDeselectVersionAction().execute(module.getId());
        module.restart(candidate);

        // Check the candidate against all selectors to see if it's rejected.
        maybeMarkRejected(candidate);
    }

    /**
     * Chooses the best out of 2 components for the module, considering all selectors for the module.
     */
    private ComponentState chooseBest(ModuleResolveState module, SelectorState selector, ComponentState currentSelection, final ComponentState candidate) {
        if (currentSelection == candidate) {
            return candidate;
        }

        // See if the new selector agrees with the current selection. If so, keep the current selection.
        if (selectorAgreesWith(selector, currentSelection.getVersion())) {
            return currentSelection;
        }

        // See if all known selectors agree with the candidate selection. If so, use the candidate.
        if (allSelectorsAgreeWith(module.getSelectors(), candidate.getVersion(), new Predicate<SelectorState>() {
            @Override
            public boolean apply(@Nullable SelectorState input) {
                return !candidate.getSelectedBy().contains(input);
            }
        })) {
            return candidate;
        }

        // Do conflict resolution to choose the best out of current selection and candidate.
        List<ComponentState> candidates = ImmutableList.of(currentSelection, candidate);
        ConflictResolverDetails<ComponentState> details = new DefaultConflictResolverDetails<ComponentState>(candidates);
        moduleConflictHandler.getResolver().select(details);
        if (details.hasFailure()) {
            throw UncheckedException.throwAsUncheckedException(details.getFailure());
        }
        return details.getSelected();
    }

    private boolean moduleHasConflicts(ResolveState resolveState, ModuleResolveState module) {
        // A new module. Check for conflict with capabilities and module replacements.
        PotentialConflict c = moduleConflictHandler.registerCandidate(module);
        if (c.conflictExists()) {
            // We have a conflict
            LOGGER.debug("Found new conflicting module {}", module);

            // For each module participating in the conflict, deselect the currently selection, and remove all outgoing edges from the version.
            // This will propagate through the graph and prune configurations that are no longer required.
            c.withParticipatingModules(resolveState.getDeselectVersionAction());
            return true;
        }
        return false;
    }

    private void maybeMarkRejected(ComponentState selected) {
        if (selected.isRejected()) {
            return;
        }

        String version = selected.getVersion();
        ModuleResolveState moduleResolveState = selected.getModule();
        for (SelectorState selector : moduleResolveState.getSelectors()) {
            if (selector.getVersionConstraint() != null && selector.getVersionConstraint().getRejectedSelector() != null && selector.getVersionConstraint().getRejectedSelector().accept(version)) {
                selected.reject();
                return;
            }
        }
    }

    /**
     * Prepares the resolution of edges, either serially or concurrently.
     * It uses a simple heuristic to determine if we should perform concurrent resolution, based on the the number of edges, and whether they have unresolved metadata.
     */
    private void maybeDownloadMetadataInParallel(NodeState node, Map<ModuleVersionIdentifier, ComponentIdentifier> componentIdentifierCache, List<EdgeState> dependencies) {
        List<EdgeState> requiringDownload = null;
        for (EdgeState dependency : dependencies) {
            ComponentState targetComponent = dependency.getTargetComponent();
            if (targetComponent != null && targetComponent.isSelected() && !targetComponent.alreadyResolved()) {
                if (!metaDataResolver.isFetchingMetadataCheap(toComponentId(targetComponent.getId(), componentIdentifierCache))) {
                    // Avoid initializing the list if there are no components requiring download (a common case)
                    if (requiringDownload == null) {
                        requiringDownload = Lists.newArrayList();
                    }
                    requiringDownload.add(dependency);
                }
            }
        }
        // Only download in parallel if there is more than 1 component to download
        if (requiringDownload != null && requiringDownload.size() > 1) {
            final ImmutableList<EdgeState> toDownloadInParallel = ImmutableList.copyOf(requiringDownload);
            LOGGER.debug("Submitting {} metadata files to resolve in parallel for {}", toDownloadInParallel.size(), node);
            buildOperationExecutor.runAll(new Action<BuildOperationQueue<RunnableBuildOperation>>() {
                @Override
                public void execute(BuildOperationQueue<RunnableBuildOperation> buildOperationQueue) {
                    for (final EdgeState dependency : toDownloadInParallel) {
                        buildOperationQueue.add(new DownloadMetadataOperation(dependency.getTargetComponent()));
                    }
                }
            });
        }
    }

    private ComponentIdentifier toComponentId(ModuleVersionIdentifier id, Map<ModuleVersionIdentifier, ComponentIdentifier> componentIdentifierCache) {
        ComponentIdentifier identifier = componentIdentifierCache.get(id);
        if (identifier == null) {
            identifier = DefaultModuleComponentIdentifier.newId(id);
            componentIdentifierCache.put(id, identifier);
        }
        return identifier;
    }

    private void attachToTargetRevisionsSerially(List<EdgeState> dependencies) {
        // the following only needs to be done serially to preserve ordering of dependencies in the graph: we have visited the edges
        // but we still didn't add the result to the queue. Doing it from resolve threads would result in non-reproducible graphs, where
        // edges could be added in different order. To avoid this, the addition of new edges is done serially.
        for (EdgeState dependency : dependencies) {
            if (dependency.getTargetComponent() != null) {
                dependency.attachToTargetConfigurations();
            }
        }
    }

    private void validateGraph(ResolveState resolveState) {
        // TODO In order to collect all of the rejection failures, this should be done via a DependencyGraphVisitor.
        for (ModuleResolveState module : resolveState.getModules()) {
            if (module.getSelected() != null && module.getSelected().isRejected()) {
                throw new GradleException(new RejectedModuleMessageBuilder().buildFailureMessage(module));
            }
        }
    }

    /**
     * Populates the result from the graph traversal state.
     */
    private void assembleResult(ResolveState resolveState, DependencyGraphVisitor visitor) {
        visitor.start(resolveState.getRoot());

        // Visit the selectors
        for (DependencyGraphSelector selector : resolveState.getSelectors()) {
            visitor.visitSelector(selector);
        }

        // Visit the nodes prior to visiting the edges
        for (NodeState nodeState : resolveState.getNodes()) {
            if (nodeState.isSelected()) {
                visitor.visitNode(nodeState);
            }
        }

        // Collect the components to sort in consumer-first order
        List<ComponentState> queue = new ArrayList<ComponentState>();
        for (ModuleResolveState module : resolveState.getModules()) {
            if (module.getSelected() != null) {
                queue.add(module.getSelected());
            }
        }

        // Visit the edges after sorting the components in consumer-first order
        while (!queue.isEmpty()) {
            ComponentState component = queue.get(0);
            if (component.getVisitState() == VisitState.NotSeen) {
                component.setVisitState(VisitState.Visiting);
                int pos = 0;
                for (NodeState node : component.getNodes()) {
                    if (!node.isSelected()) {
                        continue;
                    }
                    for (EdgeState edge : node.getIncomingEdges()) {
                        ComponentState owner = edge.getFrom().getOwner();
                        if (owner.getVisitState() == VisitState.NotSeen) {
                            queue.add(pos, owner);
                            pos++;
                        } // else, already visited or currently visiting (which means a cycle), skip
                    }
                }
                if (pos == 0) {
                    // have visited all consumers, so visit this node
                    component.setVisitState(VisitState.Visited);
                    queue.remove(0);
                    for (NodeState node : component.getNodes()) {
                        if (node.isSelected()) {
                            visitor.visitEdges(node);
                        }
                    }
                }
            } else if (component.getVisitState() == VisitState.Visiting) {
                // have visited all consumers, so visit this node
                component.setVisitState(VisitState.Visited);
                queue.remove(0);
                for (NodeState node : component.getNodes()) {
                    if (node.isSelected()) {
                        visitor.visitEdges(node);
                    }
                }
            } else {
                // else, already visited previously, skip
                queue.remove(0);
            }
        }

        visitor.finish(resolveState.getRoot());
    }

    /**
     * Check if all of the supplied selectors agree with the version chosen
     */
    private static boolean allSelectorsAgreeWith(Collection<SelectorState> allSelectors, String version, Predicate<SelectorState> filter) {
        boolean atLeastOneAgrees = false;
        for (SelectorState selectorState : allSelectors) {
            if (filter.apply(selectorState)) {
                ResolvedVersionConstraint versionConstraint = selectorState.getVersionConstraint();
                if (versionConstraint != null) {
                    VersionSelector candidateSelector = versionConstraint.getPreferredSelector();
                    if (candidateSelector == null || !candidateSelector.canShortCircuitWhenVersionAlreadyPreselected() || !candidateSelector.accept(version)) {
                        return false;
                    }
                    candidateSelector = versionConstraint.getRejectedSelector();
                    if (candidateSelector != null && candidateSelector.accept(version)) {
                        return false;
                    }
                    atLeastOneAgrees = true;
                }
            }
        }
        return atLeastOneAgrees;
    }

    private static boolean selectorAgreesWith(SelectorState selectorState, String version) {
        ResolvedVersionConstraint versionConstraint = selectorState.getVersionConstraint();
        if (versionConstraint == null || versionConstraint.getPreferredSelector() == null) {
            return false;
        }
        VersionSelector candidateSelector = versionConstraint.getPreferredSelector();
        return !candidateSelector.requiresMetadata()
            && candidateSelector.canShortCircuitWhenVersionAlreadyPreselected()
            && candidateSelector.accept(version);
    }


    enum VisitState {
        NotSeen, Visiting, Visited
    }

}
