package org.Roclh.repository.script;

import org.Roclh.model.script.UserScriptVersion;
import org.Roclh.model.script.UserScriptVersionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserScriptVersionRepository extends JpaRepository<UserScriptVersion, UserScriptVersionId> {

    List<UserScriptVersion> findByUserId(UUID userId);

    List<UserScriptVersion> findByScriptId(UUID scriptId);
}