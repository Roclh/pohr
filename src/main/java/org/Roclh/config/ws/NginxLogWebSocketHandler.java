package org.Roclh.config.ws;

import org.springframework.stereotype.Component;

@Component
public class NginxLogWebSocketHandler extends AbstractLogWebSocketHandler {
    @Override
    protected String serviceName() { return "nginx"; }
}