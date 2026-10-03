package org.Roclh.repository;

import org.Roclh.model.EuNode;
import org.Roclh.model.NodeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EuNodeRepository extends JpaRepository<EuNode, UUID> {
    Optional<EuNode> findByName(String name);
    boolean existsByName(String name);
    List<EuNode> findByStatusIn(Collection<NodeStatus> statuses);
    List<EuNode> findByStatusInOrderByEnrolledAtDesc(Collection<NodeStatus> statuses);
}