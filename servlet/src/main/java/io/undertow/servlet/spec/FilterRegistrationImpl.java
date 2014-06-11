/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.servlet.spec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;

import io.undertow.servlet.api.Deployment;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.FilterMappingInfo;

/**
 * @author Stuart Douglas
 */
public class FilterRegistrationImpl implements FilterRegistration, FilterRegistration.Dynamic {

    private final FilterInfo filterInfo;
    private final Deployment deployment;

    public FilterRegistrationImpl(final FilterInfo filterInfo, final Deployment deployment) {
        this.filterInfo = filterInfo;
        this.deployment = deployment;
    }

    @Override
    public void addMappingForServletNames(final EnumSet<DispatcherType> dispatcherTypes, final boolean isMatchAfter, final String... servletNames) {
        DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();

        for(final String servlet : servletNames){
            if(isMatchAfter) {
                if(dispatcherTypes == null || dispatcherTypes.isEmpty()) {
                    deploymentInfo.addFilterServletNameMapping(filterInfo.getName(), servlet, DispatcherType.REQUEST);
                } else {
                    for(final DispatcherType dispatcher : dispatcherTypes) {
                        deploymentInfo.addFilterServletNameMapping(filterInfo.getName(), servlet, dispatcher);
                    }
                }
            } else {
                if(dispatcherTypes == null || dispatcherTypes.isEmpty()) {
                    deploymentInfo.insertFilterServletNameMapping(0, filterInfo.getName(), servlet, DispatcherType.REQUEST);
                } else {
                    for(final DispatcherType dispatcher : dispatcherTypes) {
                        deploymentInfo.insertFilterServletNameMapping(0, filterInfo.getName(), servlet, dispatcher);
                    }
                }
            }
        }
        deployment.getServletPaths().invalidate();
    }

    @Override
    public Collection<String> getServletNameMappings() {
        DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();
        final List<String> ret = new ArrayList<>();
        for(final FilterMappingInfo mapping : deploymentInfo.getFilterMappings()) {
            if(mapping.getMappingType() == FilterMappingInfo.MappingType.SERVLET) {
                if(mapping.getFilterName().equals(filterInfo.getName())) {
                    ret.add(mapping.getMapping());
                }
            }
        }
        return ret;
    }

    @Override
    public void addMappingForUrlPatterns(final EnumSet<DispatcherType> dispatcherTypes, final boolean isMatchAfter, final String... urlPatterns) {
        DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();
        for(final String url : urlPatterns){
            if(isMatchAfter) {
                if(dispatcherTypes == null || dispatcherTypes.isEmpty()) {
                    deploymentInfo.addFilterUrlMapping(filterInfo.getName(), url, DispatcherType.REQUEST);
                } else {
                    for(final DispatcherType dispatcher : dispatcherTypes) {
                        deploymentInfo.addFilterUrlMapping(filterInfo.getName(), url, dispatcher);
                    }
                }
            } else {
                if(dispatcherTypes == null || dispatcherTypes.isEmpty()) {
                    deploymentInfo.insertFilterUrlMapping(0, filterInfo.getName(), url, DispatcherType.REQUEST);
                } else {
                    for(final DispatcherType dispatcher : dispatcherTypes) {
                        deploymentInfo.insertFilterUrlMapping(0, filterInfo.getName(), url, dispatcher);
                    }
                }
            }
        }
        deployment.getServletPaths().invalidate();
    }

    @Override
    public Collection<String> getUrlPatternMappings() {
        DeploymentInfo deploymentInfo = deployment.getDeploymentInfo();
        final List<String> ret = new ArrayList<>();
        for(final FilterMappingInfo mapping : deploymentInfo.getFilterMappings()) {
            if(mapping.getMappingType() == FilterMappingInfo.MappingType.URL) {
                if(mapping.getFilterName().equals(filterInfo.getName())) {
                    ret.add(mapping.getMapping());
                }
            }
        }
        return ret;
    }

    @Override
    public String getName() {
        return filterInfo.getName();
    }

    @Override
    public String getClassName() {
        return filterInfo.getFilterClass().getName();
    }

    @Override
    public boolean setInitParameter(final String name, final String value) {
        if(filterInfo.getInitParams().containsKey(name)) {
            return false;
        }
        filterInfo.addInitParam(name, value);
        return true;
    }

    @Override
    public String getInitParameter(final String name) {
        return filterInfo.getInitParams().get(name);
    }

    @Override
    public Set<String> setInitParameters(final Map<String, String> initParameters) {
        final Set<String> ret = new HashSet<>();
        for(Map.Entry<String, String> entry : initParameters.entrySet()) {
            if(!setInitParameter(entry.getKey(), entry.getValue())) {
                ret.add(entry.getKey());
            }
        }
        return ret;
    }

    @Override
    public Map<String, String> getInitParameters() {
        return filterInfo.getInitParams();
    }

    @Override
    public void setAsyncSupported(final boolean isAsyncSupported) {
        filterInfo.setAsyncSupported(isAsyncSupported);
    }
}
