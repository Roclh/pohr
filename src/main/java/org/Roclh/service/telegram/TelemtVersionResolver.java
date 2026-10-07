package org.Roclh.service.telegram;

import org.Roclh.service.version.GitHubLatestVersionResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TelemtVersionResolver extends GitHubLatestVersionResolver {
    @Value("${pohr.telegram.install.latest-api}")
    private String latestApi;

    @Override
    protected String latestApiUrl() {
        return latestApi;
    }

    @Override
    protected String serviceName() {
        return "telemt";
    }
}
