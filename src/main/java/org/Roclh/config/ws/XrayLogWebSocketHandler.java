package org.Roclh.config.ws;

import org.springframework.stereotype.Component;

@Component
public class XrayLogWebSocketHandler extends AbstractLogWebSocketHandler {
    @Override
    protected String serviceName() { return "xray"; }
}