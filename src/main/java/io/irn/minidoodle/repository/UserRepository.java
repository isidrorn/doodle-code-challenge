package io.irn.minidoodle.repository;

import io.irn.minidoodle.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
