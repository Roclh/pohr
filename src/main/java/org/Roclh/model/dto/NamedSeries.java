package org.Roclh.model.dto;

import java.util.List;

public record NamedSeries(String label,
                          String cssClass,
                          boolean dashed,
                          List<Double> values) {
}
