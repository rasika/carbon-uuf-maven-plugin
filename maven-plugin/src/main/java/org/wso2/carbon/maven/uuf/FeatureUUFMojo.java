/*
 *  Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.wso2.carbon.maven.uuf;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;
import org.wso2.maven.p2.feature.generate.Advice;
import org.wso2.maven.p2.feature.generate.Bundle;
import org.wso2.maven.p2.feature.generate.Feature;
import org.wso2.maven.p2.feature.generate.FeatureGenerator;
import org.wso2.maven.p2.feature.generate.FeatureResourceBundle;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.apache.maven.artifact.Artifact.SCOPE_RUNTIME;

/**
 * Create a UUF application artifact.
 */
@Mojo(name = "create-feature", inheritByDefault = false,
      requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
      threadSafe = true, defaultPhase = LifecyclePhase.PACKAGE)
public class FeatureUUFMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.artifactId}")
    private String id;

    @Parameter(defaultValue = "${project.version}")
    private String version;

    @Parameter(defaultValue = "${project.name}")
    private String label;

    @Parameter(defaultValue = "${project.description}")
    private String description;

    @Parameter(defaultValue = "%providerName")
    private String providerName;

    @Parameter(defaultValue = "%copyright")
    private String copyright;

    @Parameter(defaultValue = "%licenseURL")
    private String licenceUrl;

    @Parameter(defaultValue = "%license")
    private String licence;

    /**
     * path to manifest file
     */
    @Parameter
    private File manifest;

    /**
     * path to properties file
     */
    @Parameter
    private File propertyFile;

    /**
     * list of properties precedence over propertyFile
     */
    @Parameter
    private Properties properties;

    /**
     * Collection of bundles
     */
    @Parameter
    private List<Bundle> bundles;

    /**
     * Collection of required Features
     */
    @Parameter
    private List<Feature> importFeatures;

    /**
     * Collection of required Features
     */
    @Parameter
    private List<Feature> includeFeatures;

    /**
     * define advice file content
     */
    @Parameter
    private List<Advice> adviceFileContents;

    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${localRepository}")
    private ArtifactRepository localRepository;

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}")
    private List<ArtifactRepository> remoteRepositories;

    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private MavenProjectHelper projectHelper;

    @Component(hint = "default")
    private DependencyGraphBuilder dependencyGraphBuilder;

    public void execute() throws MojoExecutionException, MojoFailureException {
        addBackendDependencies(bundles);
        FeatureGenerator featureGenerator = constructFeatureGenerator();
        featureGenerator.generate();
    }

    private void addBackendDependencies(List<Bundle> bundles) throws MojoExecutionException {
        List<Bundle> transitiveDependencies = getTransitiveDependencies(dependencyGraphBuilder, session, project);
        bundles.addAll(transitiveDependencies);
        DependencyManagement dependencyManagement = project.getDependencyManagement();
        for (Bundle bundle : transitiveDependencies) {
            Dependency dependency = new Dependency();
            dependency.setGroupId(bundle.getGroupId());
            dependency.setArtifactId(bundle.getArtifactId());
            dependency.setVersion(bundle.getVersion());
            dependency.setType(bundle.getType());
            dependencyManagement.addDependency(dependency);
        }
    }

    /**
     * Collects the transitive dependencies of the current projects.
     *
     * @param graph the dependency graph builder
     * @return the set of resolved transitive dependencies.
     */
    private static List<Bundle> getTransitiveDependencies(DependencyGraphBuilder graph, MavenSession session,
                                                          MavenProject project) throws MojoExecutionException {
        ArtifactFilter artifactFilter = new ScopeArtifactFilter(SCOPE_RUNTIME);
        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setProject(project);
        List<Bundle> bundles = new ArrayList<>();
        try {
            DependencyNode rootNode = graph.buildDependencyGraph(buildingRequest, artifactFilter);
            CollectingDependencyNodeVisitor visitor = new CollectingDependencyNodeVisitor();
            rootNode.accept(visitor);
            for (DependencyNode node : visitor.getNodes()) {
                Artifact artifact = node.getArtifact();
                String scope = artifact.getScope();
                String type = artifact.getType();
                if (type.equals("jar")) {
                    String symbolicName = getSymbolicName(artifact.getFile());
                    if (scope != null && scope.equals(SCOPE_RUNTIME) && symbolicName != null) {
                        Bundle bundle = new Bundle();
                        bundle.setGroupId(artifact.getGroupId());
                        bundle.setArtifactId(artifact.getArtifactId());
                        bundle.setVersion(artifact.getVersion());
                        bundle.setBundleVersion(bundle.getOSGIVersion());
                        bundle.setType(type);
                        bundle.setArtifact(artifact);
                        bundle.setSymbolicName(symbolicName);
                        bundles.add(bundle);
                    }
                }
            }
        } catch (DependencyGraphBuilderException e) {
            throw new MojoExecutionException("Couldn't build dependency graph", e);
        }
        return bundles;
    }

    private static String getSymbolicName(File bundle) throws MojoExecutionException {
        if (bundle == null) return null;
        try (JarFile jarFile = new JarFile(bundle)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) return null;
            Attributes attributes = manifest.getMainAttributes();
            return attributes.getValue("Bundle-SymbolicName");
        } catch (IOException e) {
            throw new MojoExecutionException("Couldn't read the bundle in '" + bundle.toString() + "'", e);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("Couldn't read the symbolic-name in '" + bundle.toString() + "'", e);
        }
    }

    /**
     * Generates the FeatureGenerator object in order to generate the feature.
     *
     * @return FeatureGenerator
     */
    public FeatureGenerator constructFeatureGenerator() {
        FeatureResourceBundle resourceBundle = new FeatureResourceBundle();
        resourceBundle.setId(id);
        resourceBundle.setVersion(version);
        resourceBundle.setLabel(label);
        resourceBundle.setDescription(description);
        resourceBundle.setProviderName(providerName);
        resourceBundle.setCopyright(copyright);
        resourceBundle.setLicence(licence);
        resourceBundle.setLicenceUrl(licenceUrl);
        resourceBundle.setManifest(manifest);
        resourceBundle.setPropertyFile(propertyFile);
        resourceBundle.setProperties(properties);
        resourceBundle.setBundles(bundles);
        resourceBundle.setImportFeatures(importFeatures);
        resourceBundle.setIncludeFeatures(includeFeatures);
        resourceBundle.setAdviceFileContent(adviceFileContents);
        resourceBundle.setRepositorySystem(repositorySystem);
        resourceBundle.setLocalRepository(localRepository);
        resourceBundle.setRemoteRepositories(remoteRepositories);
        resourceBundle.setProject(project);
        resourceBundle.setProjectHelper(projectHelper);
        resourceBundle.setLog(getLog());
        return new FeatureGenerator(resourceBundle);
    }
}