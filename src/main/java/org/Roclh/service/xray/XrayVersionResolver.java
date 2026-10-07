package org.Roclh.service.xray;

import org.Roclh.service.version.GitHubLatestVersionResolver;
import org.springframework.stereotype.Component;

@Component
public class XrayVersionResolver extends GitHubLatestVersionResolver {
    @Override
    protected String latestApiUrl() {
        return "https://api.github.com/repos/XTLS/Xray-core/releases/latest";
    }

    @Override
    protected String serviceName() {
        return "Xray";
    }
}