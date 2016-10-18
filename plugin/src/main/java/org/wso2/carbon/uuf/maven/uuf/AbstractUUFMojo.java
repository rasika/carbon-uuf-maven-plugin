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

package org.wso2.carbon.uuf.maven.uuf;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.assembly.InvalidAssemblerConfigurationException;
import org.apache.maven.plugin.assembly.archive.ArchiveCreationException;
import org.apache.maven.plugin.assembly.archive.AssemblyArchiver;
import org.apache.maven.plugin.assembly.format.AssemblyFormattingException;
import org.apache.maven.plugin.assembly.model.Assembly;
import org.apache.maven.plugin.assembly.model.FileSet;
import org.apache.maven.plugin.assembly.mojos.AbstractAssemblyMojo;
import org.apache.maven.plugin.assembly.utils.AssemblyFormatUtils;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.name;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

public abstract class AbstractUUFMojo extends AbstractAssemblyMojo {

    protected static final String COMPONENT_ASSEMBLY_FORMAT = "zip";
    protected static final String THEME_ASSEMBLY_FORMAT = "tar";
    private static final String OSGI_IMPORT_PACKAGES = "Import-Package";
    private static final String DEPLOYMENT_FOLDER_NAME = "uufapps";
    private static final String ADVICE_FILENAME = "p2.inf";

    /**
     * Maven Project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The artifactId of the project.
     */
    @Parameter(defaultValue = "${project.artifactId}", required = true, readonly = true)
    private String artifactId;

    /**
     * The output directory of the assembled distribution file.
     */
    @Parameter(defaultValue = "${project.build.directory}", required = true, readonly = true)
    private String outputDirectoryPath;

    /**
     * Maven ProjectHelper.
     */
    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Maven AssemblyArchiver.
     */
    @Component
    private AssemblyArchiver assemblyArchiver;

    /**
     * Plugin manager to maven executor.
     */
    @Component
    private BuildPluginManager pluginManager;

    /**
     * The carbon feature plugin version to use.
     */
    @Parameter(defaultValue = "2.0.1")
    private String carbonFeaturePluginVersion;

    /**
     * Instructions for MavenPlugin.
     */
    @Parameter
    private Map instructions = new LinkedHashMap();

    protected abstract Assembly getAssembly() throws MojoFailureException;

    /**
     * Create the binary distribution. This method is invoked by Maven when running the MOJO.
     *
     * @throws org.apache.maven.plugin.MojoExecutionException
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        // AbstractAssemblyMojo does not allow child classes to plug-in a custom AssemblyReader.
        // Hence needed to reimplement the below method.
        setAppendAssemblyId(false);
        createOsgiImportsConfig();
        Assembly assembly = getAssembly();
        List<String> formats = assembly.getFormats();
        if (formats.isEmpty()) {
            throw new MojoFailureException(
                    "Assembly is incorrectly configured: " + assembly.getId() + "archive format is not specified");
        }
        final String fullName = AssemblyFormatUtils.getDistributionName(assembly, this);
        try {
            String currentFormat = formats.get(0);
            File destFile = assemblyArchiver.createArchive(assembly, fullName, currentFormat, this, true);
            MavenProject project = getProject();
            String classifier = getClassifier();
            String type = project.getArtifact().getType();
            if (destFile.isFile()) {
                if (isAssemblyIdAppended()) {
                    projectHelper.attachArtifact(project, currentFormat, assembly.getId(), destFile);
                } else if (classifier != null) {
                    projectHelper.attachArtifact(project, currentFormat, classifier, destFile);
                } else if (!"pom".equals(type) && currentFormat.equals(type)) {
                    final StringBuilder message = new StringBuilder();
                    message.append("Configuration options: 'appendAssemblyId' is set to false, " +
                                           "and 'classifier' is missing.");
                    message.append("\nInstead of attaching the assembly file: ").append(destFile);
                    message.append(", it will become the file for main project artifact.");
                    message.append("\nNOTE: If multiple descriptors or descriptor-formats are provided " +
                                           "for this project, the value of this file will be " + "non-deterministic!");
                    getLog().warn(message);
                    final File existingFile = project.getArtifact().getFile();
                    if ((existingFile != null) && existingFile.exists()) {
                        getLog().warn("Replacing pre-existing project main-artifact file: " + existingFile +
                                              "\n with assembly file: " + destFile);
                    }
                    project.getArtifact().setFile(destFile);
                } else {
                    projectHelper.attachArtifact(project, currentFormat, null, destFile);
                }
            } else {
                getLog().warn("Assembly file: " + destFile + " is not a regular file (it may be a directory). " +
                                      "It cannot be attached to the project build for installation or " +
                                      "deployment.");
            }
        } catch (final ArchiveCreationException | AssemblyFormattingException e) {
            throw new MojoExecutionException("Failed to create assembly: " + e.getMessage(), e);
        } catch (final InvalidAssemblerConfigurationException e) {
            throw new MojoFailureException(assembly, "Assembly is incorrectly configured: " + assembly.getId(),
                                           "Assembly: " + assembly.getId() + " is not configured correctly: " +
                                                   e.getMessage());
        }
    }

    protected FileSet createFileSet(String sourceDirectory, String destDirectory) {
        FileSet fileSet = new FileSet();
        fileSet.setDirectory(sourceDirectory);
        fileSet.setOutputDirectory(destDirectory);
        fileSet.setExcludes(createExcludesList());
        return fileSet;
    }

    protected List<String> createExcludesList() {
        List<String> excludes = new ArrayList<>();
        excludes.add("**/target/**");
        excludes.add("**/pom.xml");
        excludes.add("**/assembly.xml");
        excludes.add("**/*.iml");
        excludes.add("**/*.ipr");
        excludes.add("**/*.iwr");
        excludes.add("**/*.eclipse");
        return excludes;
    }

    protected List<FileSet> createFileSetList(FileSet... fileSets) {
        return Arrays.asList(fileSets);
    }

    private void createOsgiImportsConfig() throws MojoExecutionException {
        String[] osgiImports = getOsgiImports();
        if (osgiImports == null) {
            //if no osgi imports found, just skip file creation...
            return;
        }
        Path uufOsgiConfigOutDirectory = getUUFOsgiConfigOutDirectory();
        try {
            createDirectoryIfNotExists(uufOsgiConfigOutDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException("Error creating directory: " + uufOsgiConfigOutDirectory, e);
        }
        Path osgiImportsConfig = uufOsgiConfigOutDirectory.resolve("osgi-imports");
        StringBuilder content = new StringBuilder();
        content.append("# Auto-generated by UUF Maven Plugin. Do NOT modify manually.\n");
        for (String importLine : osgiImports) {
            content.append(importLine.trim()).append("\n");
        }
        try {
            Files.write(osgiImportsConfig, content.toString().getBytes(Charset.forName("UTF-8")));
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Cannot create file '" + osgiImportsConfig + "' when trying to create osgi imports config", e);
        }
    }

    protected boolean createDirectoryIfNotExists(Path directory) throws IOException {
        try {
            Files.createDirectories(directory);
            return true;
        } catch (FileAlreadyExistsException e) {
            // the directory already exists.
            return false;
        }
    }

    protected void createFeature() throws MojoExecutionException {
        // Add temp p2.inf into resources
        File tempP2File = createTempP2File();
        File tempFolder = tempP2File.getParentFile();
        Resource resource = new Resource();
        resource.setDirectory(tempFolder.getAbsolutePath());
        project.addResource(resource);

        // Copy sources into maven-shared-resources
        try {
            Path output = Paths.get(outputDirectoryPath);
            FileUtils.copyDirectory(getBasedir(),
                                    output.resolve("maven-shared-resources/uufapps/" + getSimpleArtifactId()).toFile()
            );
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot copy project files into maven-shared resources.");
        }

        // Execute carbon-feature-plugin
        File tempPropertyFile = createFeaturesPropertyFile();
        executeMojo(
                plugin(
                        groupId("org.wso2.carbon.maven"),
                        artifactId("carbon-feature-plugin"),
                        version(carbonFeaturePluginVersion)
                ),
                goal("generate"),
                configuration(
                        element(name("propertyFile"), tempPropertyFile.getAbsolutePath()),
                        element(name("adviceFileContents"),
                                element(name("advice"),
                                        element(name("name"), "org.wso2.carbon.p2.category.type"),
                                        element(name("value"), "server")
                                ),
                                element(name("advice"),
                                        element(name("name"), "org.eclipse.equinox.p2.type.group"),
                                        element(name("value"), "false")
                                )
                        )
                ),
                executionEnvironment(getProject(), getMavenSession(), pluginManager)
        );
    }

    private File createFeaturesPropertyFile() throws MojoExecutionException {
        // Read feature.properties
        String content = "";
        try (InputStream featureProperties = getClass().getClassLoader().getResourceAsStream("feature.properties")) {
            if (featureProperties == null) {
                throw new MojoExecutionException("Cannot find 'feature.properties' in resources folder.");
            }
            content = IOUtils.toString(featureProperties, StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read 'feature.properties' in resources folder.");
        }

        // Create temp feature.properties
        String tempPrefix = "feature";
        String tempSuffix = ".properties";
        File tempPropertyFile;
        OutputStream tempPropertyOut = null;
        try {
            tempPropertyFile = File.createTempFile(tempPrefix, tempSuffix);
            tempPropertyOut = new FileOutputStream(tempPropertyFile);
            IOUtils.write(content, tempPropertyOut, StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot create temp file '' in " + tempPrefix + tempSuffix);
        } finally {
            IOUtils.closeQuietly(tempPropertyOut);
        }
        return tempPropertyFile;
    }

    private File createTempP2File() throws MojoExecutionException {
        File tempAdviceFile;
        OutputStream outputStream = null;
        OutputStreamWriter writer = null;
        try {
            File tempFolder = File.createTempFile("temp", Long.toString(System.nanoTime()));
            // Delete already created regular temp file and create a new temp folder with the same name.
            // Refer SO answer: http://stackoverflow.com/a/13068936/1560536
            if (!(tempFolder.delete() && tempFolder.mkdir())) {
                throw new MojoExecutionException(
                        "Cannot not create temp directory in path:" + tempFolder.getAbsolutePath());
            }
            tempAdviceFile = new File(tempFolder, ADVICE_FILENAME);
            if (!tempAdviceFile.createNewFile()) {
                throw new MojoExecutionException(
                        "Cannot create temp file '" + ADVICE_FILENAME + "' in path:" + tempFolder.getAbsolutePath()
                );
            }
            outputStream = new FileOutputStream(tempAdviceFile);
            writer = new OutputStreamWriter(outputStream, "UTF-8");
            IOUtils.write(createP2Instructions(), writer);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot create temp file '" + ADVICE_FILENAME + "' in the temp directory");
        } finally {
            IOUtils.closeQuietly(writer);
            IOUtils.closeQuietly(outputStream);
        }
        return tempAdviceFile;
    }

    private String createP2Instructions() {
        String artifactId = getArtifactId();
        String simpleId = getSimpleArtifactId();
        String srcFolder = String.format(
                "${installFolder}/../features/%s_${feature.version}/%s/", artifactId, DEPLOYMENT_FOLDER_NAME
        );
        String destFolder = String.format("${installFolder}/../../deployment/%s/", DEPLOYMENT_FOLDER_NAME);
        return "instructions.configure=\\\n" +
                String.format("org.eclipse.equinox.p2.touchpoint.natives.mkdir(path:%s);\\\n", destFolder) +
                String.format("org.eclipse.equinox.p2.touchpoint.natives.copy(source:%s,target:%s,overwrite:true);\\\n",
                              srcFolder + simpleId, destFolder + simpleId
                ) +
                "instructions.unconfigure=\\\n" +
                String.format("org.eclipse.equinox.p2.touchpoint.natives.remove(path:%s);\\\n", destFolder + simpleId);
    }

    private String[] getOsgiImports() {
        if (instructions == null) return null;
        Object importsObj = instructions.get(OSGI_IMPORT_PACKAGES);
        return ((importsObj != null) ? importsObj.toString().split(",") : null);
    }

    @Override
    public MavenProject getProject() {
        return project;
    }


    protected String getArtifactId() {
        return artifactId;
    }

    protected String getSimpleArtifactId() {
        int lastIndex = artifactId.lastIndexOf(".");
        if (lastIndex > -1) {
            return artifactId.substring(lastIndex + 1);
        }
        return artifactId;
    }

    protected Path getUUFTempDirectory() {
        return Paths.get(outputDirectoryPath + "/uuf-temp");
    }

    protected Path getUUFOsgiConfigOutDirectory() {
        return getUUFTempDirectory();
    }

    protected BuildPluginManager getPluginManager() {
        return this.pluginManager;
    }
}