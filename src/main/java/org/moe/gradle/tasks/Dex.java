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
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.SourceSet;
import org.moe.gradle.MoePlugin;
import org.moe.gradle.MoeSDK;
import org.moe.gradle.anns.IgnoreUnused;
import org.moe.gradle.anns.NotNull;
import org.moe.gradle.anns.Nullable;
import org.moe.gradle.utils.FileUtils;
import org.moe.gradle.utils.Mode;
import org.moe.gradle.utils.Require;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Dex extends AbstractBaseTask {

    private static final Logger LOG = Logging.getLogger(ProGuard.class);

    private static final String CONVENTION_DX_JAR = "dxJar";
    private static final String CONVENTION_INPUT_FILES = "inputFiles";
    private static final String CONVENTION_EXTRA_ARGS = "extraArgs";
    private static final String CONVENTION_DEST_DIR = "destDir";

    @Nullable
    private Object dxJar;

    @NotNull
    @InputFile
    public File getDxJar() {
        return getProject().file(getOrConvention(dxJar, CONVENTION_DX_JAR));
    }

    @IgnoreUnused
    public void setDxJar(@Nullable Object dxJar) {
        this.dxJar = dxJar;
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
    private List<Object> extraArgs;

    @Input
    @NotNull
    public List<Object> getExtraArgs() {
        return getOrConvention(extraArgs, CONVENTION_EXTRA_ARGS);
    }

    @IgnoreUnused
    public void setExtraArgs(@Nullable Collection<Object> extraArgs) {
        this.extraArgs = extraArgs == null ? null : new ArrayList<>(extraArgs);
    }

    @Nullable
    private Object destDir;

    @OutputDirectory
    @NotNull
    public File getDestDir() {
        return getProject().file(getOrConvention(destDir, CONVENTION_DEST_DIR));
    }

    @IgnoreUnused
    public void setDestDir(@Nullable Object destDir) {
        this.destDir = destDir;
    }

    @Internal
    @NotNull
    public Set<File> getDestJars() {
        return getProject().fileTree(getDestDir()).filter(it->it.isFile() && it.getName().endsWith(".jar")).getFiles();
    }

    @Override
    protected void run() {
        try {
            FileUtils.deleteFileOrFolder(getDestDir());
        } catch (IOException e) {
            throw new GradleException("an IOException occurred", e);
        }
        getDestDir().mkdirs();

        int index = 0;
        for (File f : getInputFiles()) {
            File out = new File(getDestDir(), "classes-" + index + ".jar");
            LOG.info("Dxing {} to {}...", f, out);

            javaexec(spec -> {
                spec.setMain("-jar");
                spec.args(getDxJar().getAbsolutePath());
                prepareArgumentsList(f, out).forEach(spec::args);
            });

            index++;
        }
    }

    @NotNull
    private List<String> prepareArgumentsList(File input, File output) {
        List<String> args = new ArrayList<>();

        // Set mode
        args.add("--dex");

        args.add("--verbose");
        args.add("--multi-dex");
        args.add("--core-library");

        // Set extra arguments
        args.addAll(getExtraArgs().stream().map(Object::toString).collect(Collectors.toList()));

        // Set output
        args.add("--output=" + output.getAbsolutePath());

        // Set input
        args.add(input.getAbsolutePath());
        return args;
    }

    private ClassValidate classValidateTaskDep;

    @NotNull
    @Internal
    public ClassValidate getClassValidateTaskDep() {
        return Require.nonNull(classValidateTaskDep);
    }

    protected final void setupMoeTask(@NotNull SourceSet sourceSet, final @NotNull Mode mode) {
        Require.nonNull(sourceSet);

        setSupportsRemoteBuild(false);

        final MoeSDK sdk = getMoeSDK();

        // Construct default output path
        final Path out = Paths.get(MoePlugin.MOE, sourceSet.getName(), "dex", mode.name);

        setDescription("Generates dex files (sourceset: " + sourceSet.getName() + ", mode: " + mode.name + ").");

        // Add dependencies
        final ClassValidate classValidate = getMoePlugin().getTaskBy(ClassValidate.class, sourceSet, mode);
        classValidateTaskDep = classValidate;
        dependsOn(classValidate);

        // Update convention mapping
        addConvention(CONVENTION_DX_JAR, sdk::getDxJar);
        addConvention(CONVENTION_INPUT_FILES, () -> {
            final ArrayList<Object> files = new ArrayList<>();
            files.add(classValidate.getClassesOutputDir());
            File rtOut = classValidate.getDesugarTaskDep().getRuntimeOutJar();
            if (rtOut.exists()) {
                files.add(rtOut);
            }
            return files;

        });
        addConvention(CONVENTION_EXTRA_ARGS, ArrayList::new);
        addConvention(CONVENTION_DEST_DIR, () -> resolvePathInBuildDir(out, "classes"));
        addConvention(CONVENTION_LOG_FILE, () -> resolvePathInBuildDir(out, "Dex.log"));
    }
}
