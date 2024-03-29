package org.moe.gradle.options;

import org.moe.gradle.anns.IgnoreUnused;
import org.moe.gradle.anns.Nullable;

import java.io.File;
import java.util.List;

public class RemoteBuildOptions {

    @Nullable
    private List<File> resources;

    @Nullable
    private List<File> excludes;

    @Nullable
    public List<File> getResources() {
        return resources;
    }

    @IgnoreUnused
    public void setResources(@Nullable List<File> assets) {
        this.resources = assets;
    }

    @Nullable
    public List<File> getExcludes() {
        return excludes;
    }

    public void setExcludes(@Nullable List<File> excludes) {
        this.excludes = excludes;
    }
}
