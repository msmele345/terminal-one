package com.terminalone.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OptionPositionRepository extends JpaRepository<OptionPosition, Long> {

    List<OptionPosition> findAllByOrderByUnderlyingAscExpiryAsc();
}
