/*
Copyright (C) 2016 Migeran

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.moe.gradle.tasks;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.SourceSet;
import org.moe.gradle.MoeExtension;
import org.moe.gradle.MoePlugin;
import org.moe.gradle.MoeSDK;
import org.moe.gradle.anns.IgnoreUnused;
import org.moe.gradle.anns.NotNull;
import org.moe.gradle.anns.Nullable;
import org.moe.gradle.utils.FileUtils;
import org.moe.gradle.utils.Mode;
import org.moe.gradle.utils.Require;
import org.moe.tools.classvalidator.proguard.ProguardCollector;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public class ProguardCollect extends AbstractBaseTask {

    private static final Logger LOG = Logging.getLogger(ProguardCollect.class);

    private static final String CONVENTION_OUT_CFG_FILE = "outCfgFile";
    private static final String CONVENTION_INPUT_FILES = "inputFiles";
    private static final String CONVENTION_CLASSPATH_FILES = "classpathFiles";


    @Nullable
    private Object outCfgFile;

    @OutputFile
    @NotNull
    public File getOutCfgFile() {
        return getProject().file(getOrConvention(this.outCfgFile, CONVENTION_OUT_CFG_FILE));
    }

    @IgnoreUnused
    public void setOutCfgFile(@Nullable Object outCfgFile) {
        this.outCfgFile = outCfgFile;
    }

    @Nullable
    private Set<Object> inputFiles;

    @InputFiles
    @NotNull
    public ConfigurableFileCollection getInputFiles() {
        return getProject().files(getOrConvention(inputFiles, CONVENTION_INPUT_FILES));
    }

    @IgnoreUnused
    public void setInputFiles(@Nullable Collection<Object> inputFiles) {
        this.inputFiles = inputFiles == null ? null : new HashSet<>(inputFiles);
    }

    @Nullable
    private Set<Object> classpathFiles;

    @InputFiles
    @NotNull
    public ConfigurableFileCollection getClasspathFiles() {
        return getProject().files(getOrConvention(classpathFiles, CONVENTION_CLASSPATH_FILES));
    }

    @IgnoreUnused
    public void setClasspathFiles(@Nullable Set<Object> classpathFiles) {
        this.classpathFiles = classpathFiles == null ? null : new HashSet<>(classpathFiles);
    }

    @Override
    protected void run() {
        try {
            FileUtils.deleteFileOrFolder(getOutCfgFile());
        } catch (IOException e) {
            throw new GradleException("an IOException occurred", e);
        }
        ProguardCollector.process(getInputFiles().getFiles(), getOutCfgFile().toPath(), getClasspathFiles().getFiles());
    }

    private ClassValidate classValidateTaskDep;

    @Nullable
    @IgnoreUnused
    @Internal
    public ClassValidate getClassValidateTaskDep() {
        return classValidateTaskDep;
    }

    protected final void setupMoeTask(final @NotNull SourceSet sourceSet, final @NotNull Mode mode) {
        Require.nonNull(sourceSet);

        setSupportsRemoteBuild(false);

        final Project project = getProject();
        final MoeExtension ext = getMoeExtension();
        final MoeSDK sdk = getMoeSDK();

        // Construct default output path
        final Path out = Paths.get(MoePlugin.MOE, sourceSet.getName(), "collect", mode.name);

        setDescription("Generate code specific proguard keep configs (sourceset: " + sourceSet.getName() + ", mode: " + mode.name + ").");

        // Add dependencies
        final ClassValidate classValidateTask = getMoePlugin().getTaskBy(ClassValidate.class, sourceSet, mode);
        classValidateTaskDep = classValidateTask;
        dependsOn(classValidateTask);

        addConvention(CONVENTION_OUT_CFG_FILE, () -> resolvePathInBuildDir(out, "proguard_collect.cfg"));
        addConvention(CONVENTION_INPUT_FILES, () -> new LinkedHashSet<Object>(classValidateTask.getOutputJars().getFiles()));
        addConvention(CONVENTION_CLASSPATH_FILES, () -> {
            HashSet<Object> hashSet = new LinkedHashSet<Object>(classValidateTask.getClasspathFiles().getFiles());
            hashSet.add(sdk.getJava8SupportJar());
            hashSet.add(sdk.getCoreJar());
            hashSet.add(ext.getPlatformJar());

            return hashSet;
        });
        addConvention(CONVENTION_LOG_FILE, () -> resolvePathInBuildDir(out, "ProguardCollect.log"));
    }
}
