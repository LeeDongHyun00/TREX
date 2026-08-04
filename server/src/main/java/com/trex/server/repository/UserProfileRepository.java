package com.trex.server.repository;

import com.trex.server.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Integer> {

    boolean existsByUserId(Integer userId);

    Optional<UserProfile> findByUserId(Integer userId);
}
