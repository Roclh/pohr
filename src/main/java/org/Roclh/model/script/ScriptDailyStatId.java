package org.Roclh.model.script;

import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class ScriptDailyStatId implements Serializable {
    private String day;
    private UUID scriptId;
    private String scriptVersion;
}