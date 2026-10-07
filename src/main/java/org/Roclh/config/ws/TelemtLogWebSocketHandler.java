package org.Roclh.config.ws;

import org.springframework.stereotype.Component;

@Component
public class TelemtLogWebSocketHandler extends AbstractLogWebSocketHandler {
    @Override
    protected String serviceName() { return "telemt"; }
}