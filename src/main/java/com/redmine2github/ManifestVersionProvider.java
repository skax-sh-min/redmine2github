package com.redmine2github;

import picocli.CommandLine.IVersionProvider;

public class ManifestVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return new String[]{ version != null ? version : "(unknown)" };
    }
}
