/*
 * Copyright 2018 the original author or authors.
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
package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.selectors;

import com.google.common.collect.Maps;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolutionState;
import org.gradle.internal.resolve.result.ComponentIdResolveResult;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

class SelectorStateResolverResults {
    public final Map<ResolvableSelectorState, ComponentIdResolveResult> results = Maps.newHashMap();

    public <T extends ComponentResolutionState> Collection<T> getResolved(ComponentStateFactory<T> componentFactory) {
        Map<ComponentIdResolveResult, T> builder = Maps.newHashMap();
        for (ResolvableSelectorState selectorState : results.keySet()) {
            ComponentIdResolveResult idResolveResult = results.get(selectorState);
            T componentState = builder.get(idResolveResult);
            if (componentState == null) {
                if (idResolveResult.getFailure() == null) {
                    componentState = componentFactory.getRevision(idResolveResult.getId(), idResolveResult.getModuleVersionId(), idResolveResult.getMetadata());
                } else {
                    throw idResolveResult.getFailure();
                }
                builder.put(idResolveResult, componentState);
            }
            if (selectorState.isForce()) {
                return Collections.singletonList(componentState);
            }
        }

        return builder.values();
    }

    /**
     * Check already resolved results for a compatible version, and use it for this dependency rather than re-resolving.
     */
    boolean alreadyHaveResolution(ResolvableSelectorState dep) {
        for (ComponentIdResolveResult discovered : results.values()) {
            if (included(dep, discovered)) {
                results.put(dep, discovered);
                return true;
            }
        }
        return false;
    }

    void registerResolution(ResolvableSelectorState dep, ComponentIdResolveResult resolveResult) {
        if (resolveResult.getFailure() != null) {
            results.put(dep, resolveResult);
            return;
        }

        // Check already-resolved dependencies and use this version if it's compatible
        for (ResolvableSelectorState other : results.keySet()) {
            if (included(other, resolveResult)) {
                results.put(other, resolveResult);
            }
        }

        results.put(dep, resolveResult);
    }

    private boolean included(ResolvableSelectorState dep, ComponentIdResolveResult candidate) {
        if (candidate.getFailure() != null) {
            return false;
        }
        VersionSelector preferredSelector = dep.getVersionConstraint().getPreferredSelector();
        if (preferredSelector == null || !preferredSelector.canShortCircuitWhenVersionAlreadyPreselected()) {
            return false;
        }
        return preferredSelector.accept(candidate.getModuleVersionId().getVersion());
    }
}
