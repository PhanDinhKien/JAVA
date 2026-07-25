package com.microservice.userservice.repository;

import com.microservice.userservice.model.OrderStatistic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderStatisticRepository extends JpaRepository<OrderStatistic, UUID> {

    Optional<OrderStatistic> findByUserId(UUID userId);
}
