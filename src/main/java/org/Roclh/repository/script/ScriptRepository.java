package org.Roclh.repository.script;

import org.Roclh.model.script.Script;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScriptRepository extends JpaRepository<Script, UUID> {

    Optional<Script> findByName(String name);

    boolean existsByName(String name);

    List<Script> findAllByOrderBySortOrderAscNameAsc();

    List<Script> findByEnabledTrueOrderBySortOrderAscNameAsc();

    List<Script> findByEntrypointTrueAndEnabledTrueOrderBySortOrderAscNameAsc();
}