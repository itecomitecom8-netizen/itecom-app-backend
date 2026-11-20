package com.elearning.repository;

import com.elearning.entity.VirtualClassInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VirtualClassInviteRepository extends JpaRepository<VirtualClassInvite, Long> {
    Optional<VirtualClassInvite> findByToken(String token);
}
