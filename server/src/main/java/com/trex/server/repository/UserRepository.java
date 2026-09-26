package com.trex.server.repository;

import com.trex.server.entity.User;
import com.trex.server.exception.InvalidCredentialsException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByLoginId(String loginId);

    // 인증 필터를 통과한 loginId로 사용자를 조회하는 공통 진입점.
    default User getByLoginIdOrThrow(String loginId) {
        return findByLoginId(loginId)
                .orElseThrow(() -> new InvalidCredentialsException("존재하지 않는 사용자입니다"));
    }

    boolean existsByLoginId(String loginId);

    boolean existsByEmail(String email);
}
