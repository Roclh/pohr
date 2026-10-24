package org.Roclh.repository.traffic;

import org.Roclh.model.ClientTrafficState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientTrafficStateRepository extends JpaRepository<ClientTrafficState, Long> {

    List<ClientTrafficState> findByNodeId(String nodeId);
}
