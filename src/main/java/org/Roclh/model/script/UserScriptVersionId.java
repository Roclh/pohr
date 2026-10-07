package org.Roclh.model.script;

import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class UserScriptVersionId implements Serializable {
    private UUID userId;
    private UUID scriptId;
}